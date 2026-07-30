(ns bitcoin.consensus.sqlite-utxo
  "Transactional, ordered SQLite UTXO and undo storage.

  Validation runs against an immutable overlay on one SQL transaction. A
  caller either commits the complete block delta and undo journal or rolls the
  transaction back; partially connected blocks are never observable."
  (:require [bitcoin.consensus.assumeutxo :as assumeutxo]
            [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.utxo :as utxo]
            [clojure.string :as str]
            [kotobase.bitcoin.protocol :as header])
  (:import [java.sql Connection PreparedStatement ResultSet]
           [javax.sql DataSource]
           [org.sqlite SQLiteDataSource]))

(def schema-version 5)
(def maximum-pending-block-bytes 4000000)
(def maximum-pending-block-count 4096)
(def maximum-pending-total-bytes (* 4 1024 1024 1024))
(def ^:private deleted ::deleted)
(def ^:dynamic *fault-injector*
  "Optional process-local crash-test callback. Production callers should
  leave this nil; it cannot alter validation data or transaction ordering."
  nil)

(defn call-with-fault-injector!
  "Run `operation` with a callback invoked at named SQLite commit boundaries.

  This is intended for subprocess crash/fault tests. The callback is disabled
  by default and does not persist in, or get loaded from, consensus storage."
  [injector operation]
  (when-not (and (ifn? injector) (ifn? operation))
    (codec/fail! :bitcoin.consensus/fault-injector
                 "Fault injector and operation must be callable." {}))
  (binding [*fault-injector* injector]
    (operation)))

(defn- fault-point! [point]
  (when *fault-injector*
    (*fault-injector* point)))

(def ^:private schema
  ["CREATE TABLE IF NOT EXISTS consensus_meta (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    ) WITHOUT ROWID"
   "CREATE TABLE IF NOT EXISTS consensus_coins (
      txid BLOB NOT NULL CHECK(length(txid) = 32),
      vout INTEGER NOT NULL CHECK(vout BETWEEN 0 AND 4294967294),
      value INTEGER NOT NULL CHECK(value BETWEEN 0 AND 2100000000000000),
      script BLOB NOT NULL,
      height INTEGER NOT NULL CHECK(height >= 0),
      coinbase INTEGER NOT NULL CHECK(coinbase IN (0, 1)),
      PRIMARY KEY(txid, vout)
    ) WITHOUT ROWID"
   "CREATE TABLE IF NOT EXISTS consensus_undo_blocks (
      block_hash TEXT PRIMARY KEY,
      parent_hash TEXT,
      height INTEGER NOT NULL,
      previous_height INTEGER NOT NULL,
      committed_at INTEGER NOT NULL DEFAULT(unixepoch())
    ) WITHOUT ROWID"
   "CREATE TABLE IF NOT EXISTS consensus_undo (
      block_hash TEXT NOT NULL,
      sequence INTEGER NOT NULL,
      kind INTEGER NOT NULL CHECK(kind IN (0, 1)),
      txid BLOB NOT NULL CHECK(length(txid) = 32),
      vout INTEGER NOT NULL,
      value INTEGER,
      script BLOB,
      height INTEGER,
      coinbase INTEGER,
      PRIMARY KEY(block_hash, sequence),
      FOREIGN KEY(block_hash) REFERENCES consensus_undo_blocks(block_hash)
        ON DELETE CASCADE
    ) WITHOUT ROWID"
   "CREATE TABLE IF NOT EXISTS consensus_host_state (
      id INTEGER PRIMARY KEY CHECK(id = 1),
      bytes BLOB NOT NULL,
      updated_at INTEGER NOT NULL DEFAULT(unixepoch())
    )"
   "CREATE TABLE IF NOT EXISTS consensus_header_nodes (
      hash TEXT PRIMARY KEY,
      node BLOB NOT NULL CHECK(length(node) = 151)
    ) WITHOUT ROWID"
   "CREATE TABLE IF NOT EXISTS consensus_pending_blocks (
      hash TEXT PRIMARY KEY,
      raw BLOB NOT NULL CHECK(
        length(raw) BETWEEN 81 AND 4000000
      ),
      stored_at INTEGER NOT NULL DEFAULT(unixepoch()),
      FOREIGN KEY(hash) REFERENCES consensus_header_nodes(hash)
        ON DELETE CASCADE
    ) WITHOUT ROWID"])

(defn- fail! [type message data]
  (codec/fail! type message data))

(defn datasource ^SQLiteDataSource [path-or-url]
  (let [value (str path-or-url)]
    (doto (SQLiteDataSource.)
      (.setUrl (if (str/starts-with? value "jdbc:sqlite:")
                 value
                 (str "jdbc:sqlite:" value))))))

(defn- execute-statement! [^Connection connection sql]
  (with-open [statement (.createStatement connection)]
    (.execute statement sql)))

(defn- configure! [^Connection connection busy-timeout-ms]
  (execute-statement! connection "PRAGMA foreign_keys = ON")
  (execute-statement! connection "PRAGMA synchronous = FULL")
  (execute-statement! connection (str "PRAGMA busy_timeout = "
                                      (long busy-timeout-ms)))
  connection)

(defn- bind! [^PreparedStatement statement params]
  (doseq [[index value] (map-indexed vector params)]
    (cond
      (bytes? value) (.setBytes statement (inc index) value)
      (nil? value) (.setObject statement (inc index) nil)
      :else (.setObject statement (inc index) value)))
  statement)

(defn- execute! [^Connection connection sql params]
  (with-open [statement (bind! (.prepareStatement connection sql) params)]
    (.executeUpdate statement)))

(defn- first-row [^Connection connection sql params row-fn]
  (with-open [statement (bind! (.prepareStatement connection sql) params)
              result (.executeQuery statement)]
    (when (.next result)
      (row-fn result))))

(defn- txid-bytes [txid]
  (when-not (= 32 (count txid))
    (fail! :bitcoin.consensus/invalid-outpoint
           "UTXO transaction ID must contain 32 bytes."
           {:length (count txid)}))
  (byte-array (map unchecked-byte txid)))

(defn- bytes-vector [^bytes value]
  (loop [index 0
         result (transient [])]
    (if (= index (alength value))
      (persistent! result)
      (recur (unchecked-inc index)
             (conj! result
                    (bit-and 0xff (aget value index)))))))

(defn- read-coin [^ResultSet result offset]
  {:value (.getLong result offset)
   :script-pubkey (bytes-vector (.getBytes result (inc offset)))
   :height (.getLong result (+ offset 2))
   :coinbase? (not (zero? (.getInt result (+ offset 3))))})

(defn- sql-coin [^Connection connection [txid vout]]
  (first-row
   connection
   "SELECT value, script, height, coinbase
      FROM consensus_coins WHERE txid = ? AND vout = ?"
   [(txid-bytes txid) (long vout)]
   #(read-coin % 1)))

(defrecord SQLiteUTXO [^DataSource datasource busy-timeout-ms network])

(defrecord CoinOverlay [backend ^Connection connection base-count changes closed?]
  utxo/CoinStore
  (-coin-get [_ key]
    (when @closed?
      (fail! :bitcoin.consensus/closed-utxo-view
             "UTXO validation view is already closed." {}))
    (let [entry (find changes key)]
      (if entry
        (when-not (= deleted (val entry)) (val entry))
        (sql-coin connection key))))
  (-coin-contains? [this key] (some? (utxo/-coin-get this key)))
  (-coin-assoc [this key coin]
    (assoc this :changes (assoc changes key coin)))
  (-coin-dissoc [this key]
    (assoc this :changes (assoc changes key deleted)))
  (-coin-entries [_]
    (fail! :bitcoin.consensus/uncommitted-utxo-enumeration
           "An uncommitted UTXO overlay cannot be globally enumerated." {}))
  (-coin-count [_]
    (+ base-count
       (reduce-kv
        (fn [total key value]
          (let [existed? (some? (sql-coin connection key))]
            (+ total
               (cond
                 (and existed? (= deleted value)) -1
                 (and (not existed?) (not= deleted value)) 1
                 :else 0))))
        0 changes))))

(defn- connection [^SQLiteUTXO backend]
  (configure! (.getConnection ^DataSource (:datasource backend))
              (:busy-timeout-ms backend)))

(defn- meta-value [^Connection connection key]
  (first-row connection
             "SELECT value FROM consensus_meta WHERE key = ?"
             [key] #(.getString ^ResultSet % 1)))

(defn- put-meta! [^Connection connection key value]
  (execute! connection
            "INSERT INTO consensus_meta(key, value) VALUES (?, ?)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value"
            [key (str value)]))

(defn open
  "Open or initialize a network-bound UTXO database."
  [{:keys [path datasource network busy-timeout-ms]
    :or {busy-timeout-ms 5000}}]
  (when-not (keyword? network)
    (fail! :bitcoin.consensus/sqlite-network
           "SQLite UTXO storage requires a network keyword." {:network network}))
  (when-not (and (integer? busy-timeout-ms) (<= 0 busy-timeout-ms))
    (fail! :bitcoin.consensus/sqlite-configuration
           "SQLite busy timeout must be a non-negative integer."
           {:busy-timeout-ms busy-timeout-ms}))
  (let [source (or datasource (when path
                                (bitcoin.consensus.sqlite-utxo/datasource path)))]
    (when-not (instance? DataSource source)
      (fail! :bitcoin.consensus/sqlite-configuration
             "SQLite UTXO storage requires :path or :datasource." {}))
    (with-open [connection (configure! (.getConnection ^DataSource source)
                                       busy-timeout-ms)]
      (execute-statement! connection "PRAGMA journal_mode = WAL")
      (execute-statement! connection "PRAGMA synchronous = FULL")
      (doseq [statement schema] (execute-statement! connection statement))
      (let [stored-version (meta-value connection "schema_version")
            stored-network (meta-value connection "network")]
        (when (and stored-version
                   (not (contains? #{1 2 3 4 schema-version}
                                   (parse-long stored-version))))
          (fail! :bitcoin.consensus/sqlite-schema
                 "Unsupported SQLite UTXO schema."
                 {:expected schema-version :actual stored-version}))
        (when (= "1" stored-version)
          (execute-statement!
           connection "DROP INDEX IF EXISTS consensus_undo_outpoint"))
        (when (and stored-network (not= (name network) stored-network))
          (fail! :bitcoin.consensus/sqlite-network-mismatch
                 "SQLite UTXO database belongs to another network."
                 {:expected network :actual stored-network}))
        (put-meta! connection "schema_version" schema-version)
        (put-meta! connection "network" (name network))
        (when-not (meta-value connection "height")
          (put-meta! connection "height" -1)
          (put-meta! connection "coin_count" 0))))
    (->SQLiteUTXO source busy-timeout-ms network)))

(defn status [backend]
  (with-open [connection (connection backend)]
    {:network (:network backend)
     :height (parse-long (meta-value connection "height"))
     :tip (meta-value connection "tip")
     :coin-count (parse-long (meta-value connection "coin_count"))}))

(defn lookup [backend key]
  (with-open [connection (connection backend)]
    (sql-coin connection key)))

(defn begin
  "Begin an isolated validation transaction and return a CoinStore overlay."
  [backend]
  (let [connection (connection backend)]
    (try
      (.setAutoCommit connection false)
      (let [count (parse-long (or (meta-value connection "coin_count") "0"))]
        (->CoinOverlay backend connection count {} (atom false)))
      (catch Throwable error
        (.close connection)
        (throw error)))))

(defn rollback!
  [^CoinOverlay view]
  (when (compare-and-set! (:closed? view) false true)
    (try
      (.rollback ^Connection (:connection view))
      (finally (.close ^Connection (:connection view)))))
  nil)

(declare commit-block!)

(defn connect-block!
  "Validate one parsed block directly against the disk-backed UTXO view and
  atomically commit its delta and undo journal.

  Contextual header/deployment checks remain the chainstate caller's
  responsibility; this function owns the value, maturity, Script, sigop and
  transaction-level UTXO transition."
  ([backend block block-context verify-script]
   (connect-block! backend block block-context verify-script {}))
  ([backend block
    {:keys [block-hash parent-hash height previous-height]}
    verify-script options]
   (let [view (begin backend)]
     (try
       (let [{:keys [state undo]}
             (utxo/apply-block-with-undo
              {:height previous-height :coins view}
              block height verify-script options)]
         (commit-block!
          (:coins state)
          {:block-hash block-hash :parent-hash parent-hash
           :height height :previous-height previous-height :undo undo}))
       (catch Throwable error
         ;; commit-block! closes its view on either outcome; rollback! is
         ;; idempotent and handles validation failures before commit begins.
         (rollback! view)
         (throw error))))))

(defn- insert-coin! [connection [txid vout] coin]
  (execute!
   connection
   "INSERT INTO consensus_coins(txid, vout, value, script, height, coinbase)
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT(txid, vout) DO UPDATE SET
      value=excluded.value, script=excluded.script,
      height=excluded.height, coinbase=excluded.coinbase"
   [(txid-bytes txid) (long vout) (long (:value coin))
    (byte-array (map unchecked-byte (:script-pubkey coin)))
    (long (:height coin)) (if (:coinbase? coin) 1 0)]))

(defn- delete-coin! [connection [txid vout]]
  (execute! connection
            "DELETE FROM consensus_coins WHERE txid = ? AND vout = ?"
            [(txid-bytes txid) (long vout)]))

(defn- insert-undo!
  [connection {:keys [block-hash parent-hash height previous-height undo]}]
  (execute!
   connection
   "INSERT INTO consensus_undo_blocks
    (block_hash, parent_hash, height, previous_height)
    VALUES (?, ?, ?, ?)"
   [block-hash parent-hash height previous-height])
  (doseq [[sequence [kind key coin]]
          (map-indexed
           vector
           (concat
            (map (fn [[key coin]] [0 key coin]) (:spent undo))
            (map (fn [key] [1 key nil]) (:created undo))))]
    (let [[txid vout] key]
      (execute!
       connection
       "INSERT INTO consensus_undo
        (block_hash, sequence, kind, txid, vout, value, script,
         height, coinbase)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
       [block-hash sequence kind (txid-bytes txid) (long vout)
        (:value coin)
        (when coin
          (byte-array (map unchecked-byte (:script-pubkey coin))))
        (:height coin)
        (when coin (if (:coinbase? coin) 1 0))]))))

(defn- write-host-state! [connection bytes]
  (when-not (bytes? bytes)
    (fail! :bitcoin.consensus/sqlite-host-state
           "Atomic host state must be a byte array." {}))
  (execute!
   connection
   "INSERT INTO consensus_host_state(id, bytes) VALUES (1, ?)
    ON CONFLICT(id) DO UPDATE SET
      bytes=excluded.bytes, updated_at=unixepoch()"
   [bytes]))

(def ^:private taproot-state->code
  {nil 0 :defined 1 :started 2 :locked-in 3 :active 4 :failed 5})
(def ^:private code->taproot-state
  (into {} (map (fn [[state code]] [code state])) taproot-state->code))

(defn- node-flags [node]
  (cond-> 0
    (:active? node) (bit-or 1)
    (:header-valid? node) (bit-or 2)
    (:block-valid? node) (bit-or 4)
    (:scripts-checked? node) (bit-or 8)))

(defn- encode-header-node [node]
  (let [header-value (:header node)
        raw (:bytes header-value)
        chainwork (:chainwork node)
        taproot (get-in node [:deployments :taproot])
        taproot-code (get taproot-state->code taproot)]
    (when-not (and (string? (:hash node))
                   (= (:hash node) (:hash-hex header-value))
                   (= 80 (count raw))
                   (= 32 (count chainwork))
                   (nat-int? (:height node))
                   (<= (:height node) 0xffffffff)
                   (some? taproot-code))
      (fail! :bitcoin.consensus/sqlite-header
             "Normalized header node is malformed."
             {:hash (:hash node) :height (:height node)}))
    (byte-array
     (map
      unchecked-byte
      (concat
       [1]
       (header/uint-le->bytes (:height node) 4)
       (:hash header-value)
       raw chainwork
       [taproot-code (node-flags node)])))))

(defn- write-header-nodes! [^Connection connection nodes]
  (doseq [batch (partition-all 500 nodes)]
    (let [placeholders
          (str/join "," (repeat (count batch) "(?, ?)"))
          sql
          (str
           "INSERT INTO consensus_header_nodes(hash, node) VALUES "
           placeholders
           " ON CONFLICT(hash) DO UPDATE SET node=excluded.node")
          params
          (vec
           (mapcat
            (fn [node] [(:hash node) (encode-header-node node)])
            batch))]
      (execute! connection sql params))))

(defn- decode-stored-header [hash natural-hash raw]
  {:version (header/bytes->int32-le (subvec raw 0 4))
   :prev-block (subvec raw 4 36)
   :merkle-root (subvec raw 36 68)
   :timestamp (header/bytes->uint-le (subvec raw 68 72))
   :bits (header/bytes->uint-le (subvec raw 72 76))
   :nonce (header/bytes->uint-le (subvec raw 76 80))
   :hash natural-hash
   :hash-hex hash
   :bytes raw})

(defn- decode-header-node [bytes]
  (let [value (bytes-vector bytes)]
    (when-not (and (= 151 (count value)) (= 1 (first value)))
      (fail! :bitcoin.consensus/sqlite-header-format
             "Stored header node has an unsupported format."
             {:length (count value) :version (first value)}))
    (let [height (header/bytes->uint-le (subvec value 1 5))
          natural-hash (subvec value 5 37)
          hash (header/natural-hash->hex natural-hash)
          raw (subvec value 37 117)
          chainwork (subvec value 117 149)
          taproot-code (nth value 149)
          flags (nth value 150)
          taproot (get code->taproot-state taproot-code ::unknown)
          decoded (decode-stored-header hash natural-hash raw)
          parent-natural (:prev-block decoded)
          parent (when-not (every? zero? parent-natural)
                   (header/natural-hash->hex parent-natural))]
      (when (= ::unknown taproot)
        (fail! :bitcoin.consensus/sqlite-header-format
               "Stored header node has an unknown deployment state."
               {:taproot-code taproot-code :hash hash}))
      (when-not (zero? (bit-and flags 0xf0))
        (fail! :bitcoin.consensus/sqlite-header-format
               "Stored header node has unknown validity flags."
               {:flags flags :hash hash}))
      {:hash hash
       :parent parent
       :height height
       :header decoded
       :block nil
       :chainwork chainwork
       :undo nil
       :deployments {:taproot taproot}
       :active? (bit-test flags 0)
       :header-valid? (bit-test flags 1)
       :block-valid? (bit-test flags 2)
       :scripts-checked? (bit-test flags 3)})))

(defn- blob-header-nodes
  [backend]
  (with-open [^Connection connection (connection backend)
              ^PreparedStatement statement
              (.prepareStatement
               connection
               "SELECT node FROM consensus_header_nodes")
              ^ResultSet result (.executeQuery statement)]
    (loop [nodes (transient {})]
      (if (.next result)
        (let [node (decode-header-node (.getBytes result 1))]
          (recur (assoc! nodes (:hash node) node)))
        (persistent! nodes)))))

(defn header-nodes
  "Load normalized header/fork-choice nodes keyed by display-order hash."
  [backend]
  (blob-header-nodes backend))

(defn header-node
  "Load one normalized header node by display-order hash.

  This is the bounded lookup primitive used by lazy disk-backed chainstate
  hosts; unlike `header-nodes`, it never scans or materializes the index."
  [backend hash]
  (when-not (string? hash)
    (fail! :bitcoin.consensus/sqlite-header-hash
           "Normalized header lookup requires a display-order hash."
           {:hash hash}))
  (with-open [connection (connection backend)]
    (first-row
     connection
     "SELECT node FROM consensus_header_nodes WHERE hash = ?"
     [hash]
     #(decode-header-node (.getBytes ^ResultSet % 1)))))

(defn header-node-count
  "Return the normalized header row count without decoding header values."
  [backend]
  (with-open [connection (connection backend)]
    (first-row
     connection
     "SELECT count(*) FROM consensus_header_nodes"
     []
     #(.getLong ^ResultSet % 1))))

(defn header-integrity-check!
  "Recompute every normalized header hash, parent link, height, and chainwork.

  Normal startup deliberately trusts atomically committed normalized rows for
  speed. This explicit audit is the slower cryptographic corruption check.
  Verified compact metadata is staged in a connection-local temporary index,
  then parent relationships are checked by one SQL join. The source database
  is never modified and the JVM never retains a mainnet-sized graph."
  [backend]
  (with-open [^Connection connection (connection backend)]
    (execute-statement! connection "PRAGMA temp_store = FILE")
    (execute-statement!
     connection
     "CREATE TEMP TABLE consensus_header_audit (
        hash TEXT PRIMARY KEY,
        parent_hash TEXT,
        height INTEGER NOT NULL,
        chainwork BLOB NOT NULL CHECK(length(chainwork) = 32),
        bits INTEGER NOT NULL
      ) WITHOUT ROWID")
    (let [auto-commit (.getAutoCommit connection)
          work-cache (volatile! {})]
      (try
        (.setAutoCommit connection false)
        (let [row-count
              (with-open [^PreparedStatement scan
                          (.prepareStatement
                           connection
                           "SELECT hash, node FROM consensus_header_nodes")
                          ^PreparedStatement insert
                          (.prepareStatement
                           connection
                           "INSERT INTO consensus_header_audit
                              (hash, parent_hash, height, chainwork, bits)
                            VALUES (?, ?, ?, ?, ?)")
                          ^ResultSet result (.executeQuery scan)]
                (loop [count 0 pending 0]
                  (if-not (.next result)
                    (do
                      (when (pos? pending)
                        (.executeBatch insert)
                        (.clearBatch insert))
                      count)
                    (let [database-hash (.getString result 1)
                          node (decode-header-node (.getBytes result 2))
                          decoded
                          (header/decode-block-header
                           (get-in node [:header :bytes]))
                          stored-hash (:hash node)]
                      (when-not
                       (and (= database-hash stored-hash)
                            (= stored-hash (:hash-hex decoded))
                            (= (get-in node [:header :hash])
                               (:hash decoded)))
                        (fail!
                         :bitcoin.consensus/sqlite-header-hash
                         "Stored normalized header hash does not match its raw bytes."
                         {:stored stored-hash
                          :actual (:hash-hex decoded)}))
                      (.setString insert 1 stored-hash)
                      (.setString insert 2 (:parent node))
                      (.setLong insert 3 (:height node))
                      (.setBytes
                       insert 4
                       (byte-array
                        (map unchecked-byte (:chainwork node))))
                      (.setLong insert 5 (:bits decoded))
                      (.addBatch insert)
                      (let [pending (inc pending)]
                        (if (= 1000 pending)
                          (do (.executeBatch insert)
                              (.clearBatch insert)
                              (recur (inc count) 0))
                          (recur (inc count) pending)))))))
              audited-count
              (with-open [^PreparedStatement statement
                          (.prepareStatement
                           connection
                           "SELECT child.hash, child.parent_hash,
                                   child.height, child.chainwork, child.bits,
                                   parent.height, parent.chainwork
                              FROM consensus_header_audit AS child
                              LEFT JOIN consensus_header_audit AS parent
                                ON parent.hash = child.parent_hash")
                          ^ResultSet result (.executeQuery statement)]
                (loop [count 0]
                  (if-not (.next result)
                    count
                    (let [hash (.getString result 1)
                          parent (.getString result 2)
                          height (.getLong result 3)
                          chainwork (bytes-vector (.getBytes result 4))
                          bits (.getLong result 5)
                          parent-height (.getLong result 6)
                          missing-parent? (.wasNull result)
                          parent-chainwork
                          (some-> (.getBytes result 7) bytes-vector)
                          work
                          (or (get @work-cache bits)
                              (let [value (header/header-work bits)]
                                (vswap! work-cache assoc bits value)
                                value))
                          expected-height
                          (if parent (inc parent-height) 0)
                          expected-chainwork
                          (if parent
                            (when-not missing-parent?
                              (header/add-chainwork
                               parent-chainwork work))
                            work)]
                      (when (and parent missing-parent?)
                        (fail!
                         :bitcoin.consensus/sqlite-header-parent
                         "Stored normalized header has a missing parent."
                         {:hash hash :parent parent}))
                      (when-not (= expected-height height)
                        (fail!
                         :bitcoin.consensus/sqlite-header-height
                         "Stored normalized header height is inconsistent."
                         {:hash hash :expected expected-height
                          :actual height}))
                      (when-not (= expected-chainwork chainwork)
                        (fail!
                         :bitcoin.consensus/sqlite-header-chainwork
                         "Stored normalized header chainwork is inconsistent."
                         {:hash hash :height height}))
                      (recur (inc count))))))]
          (when-not (= row-count audited-count)
            (fail! :bitcoin.consensus/sqlite-header-audit
                   "Header audit row counts differ between passes."
                   {:headers row-count :audited audited-count}))
          (.commit connection)
          {:header-integrity :ok :header-nodes row-count})
        (catch Throwable error
          (.rollback connection)
          (throw error))
        (finally
          (.setAutoCommit connection auto-commit))))))

(defn host-state
  "Return the atomically committed host-state bytes, if present."
  [backend]
  (with-open [connection (connection backend)]
    (first-row connection
               "SELECT bytes FROM consensus_host_state WHERE id = 1"
               [] #(.getBytes ^ResultSet % 1))))

(defn undo
  "Read one durable active-chain undo journal."
  [backend block-hash]
  (with-open [connection (connection backend)]
    (let [previous-height
          (first-row
           connection
           "SELECT previous_height FROM consensus_undo_blocks
              WHERE block_hash = ?"
           [block-hash] #(.getLong ^ResultSet % 1))
          rows
          (with-open [statement
                      (bind!
                       (.prepareStatement
                        connection
                        "SELECT kind, txid, vout, value, script, height,
                                coinbase
                           FROM consensus_undo WHERE block_hash = ?
                           ORDER BY sequence")
                       [block-hash])
                      result (.executeQuery statement)]
            (loop [values []]
              (if (.next result)
                (recur
                 (conj
                  values
                  {:kind (.getInt result 1)
                   :key [(bytes-vector (.getBytes result 2))
                         (.getLong result 3)]
                   :coin
                   (when (zero? (.getInt result 1))
                     {:value (.getLong result 4)
                      :script-pubkey (bytes-vector (.getBytes result 5))
                      :height (.getLong result 6)
                      :coinbase? (not (zero? (.getInt result 7)))})}))
                values)))]
      (when (some? previous-height)
        {:height previous-height
         :spent (into {}
                      (keep #(when (zero? (:kind %))
                               [(:key %) (:coin %)]))
                      rows)
         :created (into #{}
                        (keep #(when (= 1 (:kind %)) (:key %)))
                        rows)}))))

(defn- apply-changes! [connection changes]
  (doseq [[key value] changes]
    (if (= deleted value)
      (delete-coin! connection key)
      (insert-coin! connection key value))))

(defn- pending-status* [connection]
  (first-row
   connection
   "SELECT count(*), COALESCE(sum(length(raw)), 0)
      FROM consensus_pending_blocks"
   []
   (fn [^ResultSet result]
     {:pending-blocks (.getLong result 1)
      :pending-bytes (.getLong result 2)})))

(defn pending-status
  "Return bounded side-branch staging utilization."
  [backend]
  (with-open [connection (connection backend)]
    (pending-status* connection)))

(defn pending-block
  "Load one staged raw block, or nil when it is not retained."
  [backend hash]
  (with-open [connection (connection backend)]
    (first-row
     connection
     "SELECT raw FROM consensus_pending_blocks WHERE hash = ?"
     [hash]
     #(.getBytes ^ResultSet % 1))))

(defn- validate-pending-options!
  [{:keys [store delete maximum-count maximum-bytes]
    :or {store {}
         delete []
         maximum-count maximum-pending-block-count
         maximum-bytes maximum-pending-total-bytes}}]
  (when-not (and (map? store)
                 (sequential? delete)
                 (<= (count store) maximum-pending-block-count)
                 (<= (count delete) maximum-pending-block-count)
                 (integer? maximum-count)
                 (<= 0 maximum-count maximum-pending-block-count)
                 (integer? maximum-bytes)
                 (<= 0 maximum-bytes maximum-pending-total-bytes))
    (fail! :bitcoin.consensus/pending-block-configuration
           "Pending block staging bounds are invalid."
           {:maximum-count maximum-count :maximum-bytes maximum-bytes
            :stores (count store) :deletes (count delete)}))
  (doseq [[hash raw] store]
    (when-not (and (string? hash)
                   (re-matches #"[0-9a-f]{64}" hash)
                   (bytes? raw)
                   (<= 81 (alength ^bytes raw)
                       maximum-pending-block-bytes))
      (fail! :bitcoin.consensus/pending-block
             "Staged block is malformed or exceeds consensus size bounds."
             {:hash hash
              :bytes (when (bytes? raw) (alength ^bytes raw))})))
  {:store store :delete (vec (distinct delete))
   :maximum-count maximum-count :maximum-bytes maximum-bytes})

(defn- apply-pending-blocks!
  [connection options]
  (let [{:keys [store delete maximum-count maximum-bytes]}
        (validate-pending-options! options)]
    (doseq [hash delete]
      (execute!
       connection
       "DELETE FROM consensus_pending_blocks WHERE hash = ?"
       [hash]))
    (doseq [[hash raw] store]
      (execute!
       connection
       "INSERT INTO consensus_pending_blocks(hash, raw)
          VALUES (?, ?)
          ON CONFLICT(hash) DO UPDATE SET
            raw = excluded.raw, stored_at = unixepoch()"
       [hash raw]))
    (let [{:keys [pending-blocks pending-bytes] :as status}
          (pending-status* connection)]
      (when (or (> pending-blocks maximum-count)
                (> pending-bytes maximum-bytes))
        (fail! :bitcoin.consensus/pending-block-limit
               "Pending side-branch staging exceeds its configured bounds."
               (assoc status
                      :maximum-count maximum-count
                      :maximum-bytes maximum-bytes)))
      status)))

(declare save-host-and-headers! save-host-headers-and-pending!)

(defn save-host-state!
  "Atomically update host metadata without changing the UTXO tip."
  [backend expected-tip expected-height bytes]
  (save-host-and-headers!
   backend expected-tip expected-height bytes []))

(defn save-host-and-headers!
  "Atomically update compact host metadata and changed normalized headers."
  [backend expected-tip expected-height bytes nodes]
  (save-host-headers-and-pending!
   backend expected-tip expected-height bytes nodes {}))

(defn save-host-headers-and-pending!
  "Atomically update host metadata, normalized headers, and bounded staged
  side-branch blocks. `:store` maps display hashes to raw byte arrays."
  [backend expected-tip expected-height bytes nodes pending-options]
  (with-open [connection (connection backend)]
    (let [auto-commit (.getAutoCommit connection)]
      (try
        (.setAutoCommit connection false)
        (let [actual-tip (meta-value connection "tip")
              actual-height (parse-long (meta-value connection "height"))]
          (when-not (and (= expected-tip actual-tip)
                         (= expected-height actual-height))
            (fail! :bitcoin.consensus/sqlite-stale-tip
                   "Refusing to save host state over another UTXO tip."
                   {:expected-tip expected-tip :actual-tip actual-tip
                    :expected-height expected-height
                    :actual-height actual-height}))
          (write-header-nodes! connection nodes)
          (apply-pending-blocks! connection pending-options)
          (write-host-state! connection bytes)
          (.commit connection)
          true)
        (catch Throwable error
          (.rollback connection)
          (throw error))
        (finally
          (.setAutoCommit connection auto-commit))))))

(defn import-snapshot!
  "Stream an authenticated Core v2 AssumeUTXO snapshot directly into SQLite.
  No Clojure map proportional to the UTXO set is created. Authentication
  failure rolls back every inserted coin.

  `:host-state-fn`, when present, receives the authenticated non-materialized
  state and returns host-state bytes committed in the same transaction."
  ([backend source header-at-height]
   (import-snapshot! backend source header-at-height {}))
  ([backend source header-at-height options]
   (with-open [connection (connection backend)]
     (let [auto-commit (.getAutoCommit connection)]
       (try
         (.setAutoCommit connection false)
         (let [current-count
               (first-row connection "SELECT count(*) FROM consensus_coins" []
                          #(.getLong ^ResultSet % 1))]
           (when-not (zero? current-count)
             (fail! :bitcoin.consensus/sqlite-snapshot-nonempty
                    "AssumeUTXO import requires an empty UTXO database."
                    {:coin-count current-count}))
           (let [host-state-fn (:host-state-fn options)
                 header-nodes (:header-nodes options)
                 header-nodes-fn (:header-nodes-fn options)
                 loaded
                 (assumeutxo/load-snapshot
                  source (:network backend) header-at-height
                  (-> options
                      (dissoc :host-state-fn :header-nodes :header-nodes-fn)
                      (assoc :materialize? false
                             :coin-consumer
                             #(insert-coin! connection %1 %2))))
                 {:keys [base-height base-blockhash coins-count]}
                 (:snapshot loaded)]
             (put-meta! connection "height" base-height)
             (put-meta! connection "tip" base-blockhash)
             (put-meta! connection "coin_count" coins-count)
             (when host-state-fn
               (write-host-state! connection (host-state-fn loaded)))
             (write-header-nodes!
              connection
              (if header-nodes-fn
                (header-nodes-fn loaded)
                header-nodes))
             (.commit connection)
             (:snapshot loaded)))
         (catch Throwable error
           (.rollback connection)
           (throw error))
         (finally
           (.setAutoCommit connection auto-commit)))))))

(defn commit-block!
  "Atomically commit a validated overlay, its reversible undo, and new tip.
  The database tip must still equal `parent-hash`/`previous-height`."
  [^CoinOverlay view
   {:keys [block-hash parent-hash height previous-height undo]}]
  (let [^Connection connection (:connection view)]
    (when @(:closed? view)
      (fail! :bitcoin.consensus/closed-utxo-view
             "UTXO validation view is already closed." {}))
    (try
      (let [actual-height (parse-long (meta-value connection "height"))
            actual-tip (meta-value connection "tip")]
        (when-not (= height (inc previous-height))
          (fail! :bitcoin.consensus/sqlite-height
                 "UTXO block height must immediately follow its parent."
                 {:height height :previous-height previous-height}))
        (when-not (and (= previous-height actual-height)
                       (= parent-hash actual-tip))
          (fail! :bitcoin.consensus/sqlite-stale-tip
                 "SQLite UTXO tip changed during validation."
                 {:expected-height previous-height :actual-height actual-height
                  :expected-tip parent-hash :actual-tip actual-tip}))
        (insert-undo!
         connection
         {:block-hash block-hash :parent-hash parent-hash
          :height height :previous-height previous-height :undo undo})
        (fault-point! :commit-block/after-undo)
        (let [next-count (utxo/coin-count view)]
          (apply-changes! connection (:changes view))
          (fault-point! :commit-block/after-coins)
          (put-meta! connection "height" height)
          (put-meta! connection "tip" block-hash)
          (put-meta! connection "coin_count" next-count)
          (fault-point! :commit-block/after-meta)
          (fault-point! :commit-block/before-commit)
          (.commit connection)
          (fault-point! :commit-block/after-commit)
          (reset! (:closed? view) true)
          {:height height :tip block-hash :coin-count next-count}))
      (catch Throwable error
        (.rollback connection)
        (reset! (:closed? view) true)
        (throw error))
      (finally
        (.close connection)))))

(defn commit-transition!
  "Atomically commit a most-work reorganization and checksummed host state.

  `detach` is ordered old tip toward the fork. `attach` is ordered fork child
  toward the new tip and each entry contains block/parent hashes, heights, and
  the freshly validated undo delta."
  [^CoinOverlay view
   {:keys [expected-tip expected-height new-tip new-height
           detach attach host-state-bytes header-nodes pending-delete]}]
  (let [^Connection connection (:connection view)]
    (when @(:closed? view)
      (fail! :bitcoin.consensus/closed-utxo-view
             "UTXO validation view is already closed." {}))
    (try
      (let [actual-tip (meta-value connection "tip")
            actual-height (parse-long (meta-value connection "height"))]
        (when-not (and (= expected-tip actual-tip)
                       (= expected-height actual-height))
          (fail! :bitcoin.consensus/sqlite-stale-tip
                 "SQLite UTXO tip changed during reorganization."
                 {:expected-tip expected-tip :actual-tip actual-tip
                  :expected-height expected-height
                  :actual-height actual-height}))
        (let [[fork-tip fork-height]
              (reduce
               (fn [[current-tip current-height] block-hash]
                 (when-not (= current-tip block-hash)
                   (fail! :bitcoin.consensus/sqlite-reorg-order
                          "Detached blocks are not ordered from the tip."
                          {:expected current-tip :actual block-hash}))
                 (let [row
                       (first-row
                        connection
                        "SELECT parent_hash, height, previous_height
                           FROM consensus_undo_blocks
                          WHERE block_hash = ?"
                        [block-hash]
                        (fn [^ResultSet result]
                          {:parent (.getString result 1)
                           :height (.getLong result 2)
                           :previous-height (.getLong result 3)}))]
                   (when-not (and row (= current-height (:height row)))
                     (fail! :bitcoin.consensus/missing-undo
                            "Detached block lacks matching durable undo."
                            {:hash block-hash :height current-height}))
                   (execute!
                    connection
                    "DELETE FROM consensus_undo_blocks WHERE block_hash = ?"
                    [block-hash])
                   [(:parent row) (:previous-height row)]))
               [actual-tip actual-height] detach)
              [result-tip result-height]
              (reduce
               (fn [[current-tip current-height] entry]
                 (when-not (and (= current-tip (:parent-hash entry))
                                (= (:height entry) (inc current-height))
                                (= (:previous-height entry) current-height))
                   (fail! :bitcoin.consensus/sqlite-reorg-order
                          "Attached blocks do not extend the reorganization fork."
                          {:tip current-tip :height current-height
                           :entry (dissoc entry :undo)}))
                 (insert-undo! connection entry)
                 [(:block-hash entry) (:height entry)])
               [fork-tip fork-height] attach)
              next-count (utxo/coin-count view)]
          (fault-point! :transition/after-undo)
          (when-not (and (= new-tip result-tip)
                         (= new-height result-height))
            (fail! :bitcoin.consensus/sqlite-reorg-tip
                   "Reorganization result differs from the selected tip."
                   {:expected-tip new-tip :actual-tip result-tip
                    :expected-height new-height
                    :actual-height result-height}))
          (apply-changes! connection (:changes view))
          (fault-point! :transition/after-coins)
          (put-meta! connection "height" new-height)
          (put-meta! connection "tip" new-tip)
          (put-meta! connection "coin_count" next-count)
          (fault-point! :transition/after-meta)
          (write-header-nodes! connection header-nodes)
          (fault-point! :transition/after-headers)
          (apply-pending-blocks!
           connection {:delete (or pending-delete [])})
          (fault-point! :transition/after-pending)
          (when host-state-bytes
            (write-host-state! connection host-state-bytes))
          (fault-point! :transition/after-host)
          (fault-point! :transition/before-commit)
          (.commit connection)
          (fault-point! :transition/after-commit)
          (reset! (:closed? view) true)
          {:height new-height :tip new-tip :coin-count next-count
           :detached (count detach) :attached (count attach)}))
      (catch Throwable error
        (.rollback connection)
        (reset! (:closed? view) true)
        (throw error))
      (finally
        (.close connection)))))

(defn disconnect-tip!
  "Atomically reverse the current tip using its durable undo journal."
  [backend expected-block-hash]
  (with-open [connection (connection backend)]
    (let [auto-commit (.getAutoCommit connection)]
      (try
        (.setAutoCommit connection false)
        (let [tip (meta-value connection "tip")
              block
              (first-row
               connection
               "SELECT parent_hash, previous_height
                  FROM consensus_undo_blocks WHERE block_hash = ?"
               [expected-block-hash]
               (fn [^ResultSet result]
                 {:parent (.getString result 1)
                  :height (.getLong result 2)}))]
          (when-not (= expected-block-hash tip)
            (fail! :bitcoin.consensus/sqlite-stale-tip
                   "Refusing to disconnect a non-tip block."
                   {:expected expected-block-hash :actual tip}))
          (when-not block
            (fail! :bitcoin.consensus/missing-undo
                   "SQLite UTXO tip has no durable undo journal."
                   {:hash expected-block-hash}))
          (with-open [statement
                      (bind!
                       (.prepareStatement
                        connection
                        "SELECT kind, txid, vout, value, script, height, coinbase
                           FROM consensus_undo WHERE block_hash = ?
                           ORDER BY sequence DESC")
                       [expected-block-hash])
                      result (.executeQuery statement)]
            (loop []
              (when (.next result)
                (let [kind (.getInt result 1)
                      key [(bytes-vector (.getBytes result 2))
                           (.getLong result 3)]]
                  (if (= kind 1)
                    (delete-coin! connection key)
                    (insert-coin!
                     connection key
                     {:value (.getLong result 4)
                      :script-pubkey (bytes-vector (.getBytes result 5))
                      :height (.getLong result 6)
                      :coinbase? (not (zero? (.getInt result 7)))})))
                (recur))))
          (execute! connection
                    "DELETE FROM consensus_undo_blocks WHERE block_hash = ?"
                    [expected-block-hash])
          (let [next-count
                (first-row connection "SELECT count(*) FROM consensus_coins" []
                           #(.getLong ^ResultSet % 1))]
            (put-meta! connection "height" (:height block))
            (if-let [parent (:parent block)]
              (put-meta! connection "tip" parent)
              (execute! connection
                        "DELETE FROM consensus_meta WHERE key = 'tip'" []))
            (put-meta! connection "coin_count" next-count)
            (.commit connection)
            {:height (:height block) :tip (:parent block)
             :coin-count next-count}))
        (catch Throwable error
          (.rollback connection)
          (throw error))
        (finally
          (.setAutoCommit connection auto-commit))))))

(defn integrity-check!
  "Audit SQLite pages, UTXO metadata, and normalized header cryptography."
  [backend]
  (with-open [connection (connection backend)]
    (let [integrity
          (first-row connection "PRAGMA integrity_check" []
                     #(.getString ^ResultSet % 1))
          actual
          (first-row connection "SELECT count(*) FROM consensus_coins" []
                     #(.getLong ^ResultSet % 1))
          claimed (parse-long (meta-value connection "coin_count"))]
      (when-not (= "ok" integrity)
        (fail! :bitcoin.consensus/sqlite-corrupt
               "SQLite integrity_check failed." {:result integrity}))
      (when-not (= actual claimed)
        (fail! :bitcoin.consensus/sqlite-count-mismatch
               "SQLite UTXO metadata count is inconsistent."
               {:expected claimed :actual actual}))
      (merge {:integrity :ok :coin-count actual}
             (header-integrity-check! backend)))))

(defn entries
  "Return a reducible ordered stream of committed UTXO entries. The stream is
  consumed inside this call so JDBC resources cannot escape."
  [backend reduce-fn initial]
  (with-open [connection (connection backend)
              statement (.prepareStatement
                         connection
                         "SELECT txid, vout, value, script, height, coinbase
                            FROM consensus_coins ORDER BY txid, vout")
              result (.executeQuery statement)]
    (loop [acc initial]
      (if (.next result)
        (let [entry
              [[(bytes-vector (.getBytes result 1)) (.getLong result 2)]
               (read-coin result 3)]
              next (reduce-fn acc entry)]
          (if (reduced? next) @next (recur next)))
        acc))))

(defn hash-serialized
  "Recompute Core HASH_SERIALIZED over the ordered disk UTXO cursor without
  materializing the set in JVM memory."
  [backend]
  (assumeutxo/hash-serialized-reduce
   (fn [reduce-fn initial]
     (entries backend reduce-fn initial))))
