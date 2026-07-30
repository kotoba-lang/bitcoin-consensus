(ns bitcoin.consensus.sqlite-utxo-test
  (:require [bitcoin.consensus.assumeutxo :as assumeutxo]
            [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.sqlite-utxo :as sqlite]
            [bitcoin.consensus.utxo :as utxo]
            [clojure.test :refer [deftest is testing]]
            [kotobase.bitcoin.protocol :as header])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def txid-a (vec (repeat 32 1)))
(def txid-b (vec (repeat 32 2)))
(def txid-c (vec (repeat 32 3)))
(def coin-a {:value 5000 :script-pubkey [0x51]
             :height 1 :coinbase? false})
(def coin-b {:value 3000 :script-pubkey [0x00 0x14 1 2 3]
             :height 2 :coinbase? true})

(defn- soak-txid [height]
  (vec (concat (codec/uint-le height 4) (repeat 28 0))))

(defn- soak-coin [height]
  {:value (+ 1000 height)
   :script-pubkey [0x51]
   :height height
   :coinbase? false})

(def genesis-like-block
  {:transactions
   [{:txid-natural txid-a
     :inputs [{:txid-natural (vec (repeat 32 0))
               :vout 0xffffffff :script-sig [1 1] :sequence 0xffffffff}]
     :outputs [{:value 5000 :script-pubkey [0x51]}]}]})

(defn- core-varint [value]
  (loop [value value result []]
    (let [byte (bit-or (bit-and value 0x7f)
                       (if (seq result) 0x80 0))]
      (if (<= value 0x7f)
        (vec (cons byte result))
        (recur (dec (quot value 128)) (cons byte result))))))

(defn- compressed-amount [amount]
  (if (zero? amount)
    0
    (loop [amount amount exponent 0]
      (if (and (zero? (mod amount 10)) (< exponent 9))
        (recur (quot amount 10) (inc exponent))
        (if (< exponent 9)
          (let [digit (mod amount 10)]
            (+ 1 (* 10 (+ (* 9 (quot amount 10)) digit -1)) exponent))
          (+ 1 (* 10 (dec amount)) 9))))))

(defn- snapshot-bytes [base-hash [[txid vout] coin]]
  (byte-array
   (map unchecked-byte
        (concat
         assumeutxo/snapshot-magic
         (codec/uint-le assumeutxo/snapshot-version 2)
         (assumeutxo/network-magic :regtest)
         (reverse
          (mapv #(Integer/parseInt (apply str %) 16)
                (partition 2 base-hash)))
         (codec/uint-le 1 8)
         txid [1] (codec/compact-size vout)
         (core-varint (+ (* 2 (:height coin))
                         (if (:coinbase? coin) 1 0)))
         (core-varint (compressed-amount (:value coin)))
         (core-varint (+ 6 (count (:script-pubkey coin))))
         (:script-pubkey coin)))))

(defn- temp-database []
  (let [directory
        (Files/createTempDirectory
         "bitcoin-consensus-utxo-"
         (make-array FileAttribute 0))]
    {:directory directory
     :path (.resolve ^Path directory "chainstate.sqlite")}))

(defn- delete-database! [{:keys [^Path directory ^Path path]}]
  (doseq [suffix ["-shm" "-wal" ""]]
    (Files/deleteIfExists
     (Path/of (str path suffix) (make-array String 0))))
  (Files/deleteIfExists directory))

(defn- with-database [run!]
  (let [{:keys [path] :as temporary} (temp-database)]
    (try
      (run! path)
      (finally (delete-database! temporary)))))

(defn- crash-process! [path fault pending-hash]
  (let [java
        (str (Path/of (System/getProperty "java.home")
                      (into-array String ["bin" "java"])))
        builder
        (doto
         (ProcessBuilder.
          (into-array
           String
           [java "-cp" (System/getProperty "java.class.path")
            "clojure.main" "-m"
            "bitcoin.consensus.sqlite-crash-worker"
            (str path) (subs (str fault) 1) pending-hash]))
          (.redirectErrorStream true))
        process (.start builder)
        finished? (.waitFor process 20 TimeUnit/SECONDS)]
    (when-not finished?
      (.destroyForcibly process)
      (.waitFor process 5 TimeUnit/SECONDS))
    {:finished? finished?
     :exit (when finished? (.exitValue process))
     :output (slurp (.getInputStream process))}))

(defn- initialize-crash-database! [path]
  (let [backend (sqlite/open {:path path :network :regtest})
        decoded
        (header/decode-block-header
         (header/hex->bytes header/regtest-genesis-header-hex))
        hash (:hash-hex decoded)
        node
        {:hash hash :parent nil :height 0
         :header decoded :block nil
         :chainwork (header/header-work (:bits decoded))
         :undo nil :deployments {:taproot :active}
         :active? true :header-valid? true
         :block-valid? true :scripts-checked? true}
        raw (byte-array (repeat 81 (unchecked-byte 1)))]
    (sqlite/save-host-headers-and-pending!
     backend nil -1 (.getBytes "old-host") [node]
     {:store {hash raw} :maximum-count 1 :maximum-bytes 81})
    (sqlite/commit-block!
     (-> (sqlite/begin backend)
         (utxo/-coin-assoc [txid-a 0] coin-a))
     {:block-hash "old" :parent-hash nil
      :height 0 :previous-height -1
      :undo {:height -1 :spent {} :created #{[txid-a 0]}}})
    hash))

(deftest block-commit-reopen-and-durable-disconnect
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            first-view (sqlite/begin backend)
            first-view (utxo/-coin-assoc first-view [txid-a 0] coin-a)]
        (is (= {:height 0 :tip "block-0" :coin-count 1}
               (sqlite/commit-block!
                first-view
                {:block-hash "block-0" :parent-hash nil
                 :height 0 :previous-height -1
                 :undo {:spent {} :created #{[txid-a 0]}}})))
        (let [reopened (sqlite/open {:path path :network :regtest})
              second-view (sqlite/begin reopened)
              second-view (-> second-view
                              (utxo/-coin-dissoc [txid-a 0])
                              (utxo/-coin-assoc [txid-b 7] coin-b))]
          (is (nil? (utxo/-coin-get second-view [txid-a 0])))
          (is (= {:height 1 :tip "block-1" :coin-count 1}
                 (sqlite/commit-block!
                  second-view
                  {:block-hash "block-1" :parent-hash "block-0"
                   :height 1 :previous-height 0
                   :undo {:spent {[txid-a 0] coin-a}
                          :created #{[txid-b 7]}}})))
          (is (nil? (sqlite/lookup reopened [txid-a 0])))
          (is (= coin-b (sqlite/lookup reopened [txid-b 7])))
          (is (= {:integrity :ok :coin-count 1
                  :header-integrity :ok :header-nodes 0}
                 (sqlite/integrity-check! reopened)))
          (is (= {:height 0 :tip "block-0" :coin-count 1}
                 (sqlite/disconnect-tip! reopened "block-1")))
          (is (= coin-a (sqlite/lookup reopened [txid-a 0])))
          (is (nil? (sqlite/lookup reopened [txid-b 7]))))))))

(deftest rollback-and-network-binding-fail-closed
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :mainnet})
            view (-> (sqlite/begin backend)
                     (utxo/-coin-assoc [txid-a 0] coin-a))]
        (sqlite/rollback! view)
        (is (= {:network :mainnet :height -1 :tip nil :coin-count 0}
               (sqlite/status backend)))
        (is (nil? (sqlite/lookup backend [txid-a 0])))
        (testing "an existing database cannot be reopened for another chain"
          (is (= :bitcoin.consensus/sqlite-network-mismatch
                 (:type
                  (ex-data
                   (try
                     (sqlite/open {:path path :network :testnet})
                     (catch clojure.lang.ExceptionInfo error error)))))))))))

(deftest normalized-headers-upsert-and-reopen
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            decoded
            (header/decode-block-header
             (header/hex->bytes header/regtest-genesis-header-hex))
            node
            {:hash (:hash-hex decoded) :parent nil :height 0
             :header decoded :block nil
             :chainwork (header/header-work (:bits decoded))
             :undo nil :deployments {:taproot :active}
             :active? true :header-valid? true
             :block-valid? true :scripts-checked? true}
            bytes (.getBytes "compact-host-state")]
        (sqlite/save-host-and-headers! backend nil -1 bytes [node])
        (is (= {(:hash node) node} (sqlite/header-nodes backend)))
        (is (= node (sqlite/header-node backend (:hash node))))
        (is (nil? (sqlite/header-node backend (apply str (repeat 64 "0")))))
        (is (= 1 (sqlite/header-node-count backend)))
        (is (= (seq bytes) (seq (sqlite/host-state backend))))
        (let [updated (assoc node :active? false :block-valid? false)]
          (sqlite/save-host-and-headers! backend nil -1 bytes [updated])
          (is (= false
                 (get-in (sqlite/header-nodes backend)
                         [(:hash node) :active?])))
          (is (= false
                 (get-in (sqlite/header-nodes backend)
                         [(:hash node) :block-valid?]))))))))

(deftest normalized-header-integrity-recomputes-raw-hashes
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            decoded
            (header/decode-block-header
             (header/hex->bytes header/regtest-genesis-header-hex))
            node
            {:hash (:hash-hex decoded) :parent nil :height 0
             :header decoded :block nil
             :chainwork (header/header-work (:bits decoded))
             :undo nil :deployments {:taproot :active}
             :active? true :header-valid? true
             :block-valid? true :scripts-checked? true}]
        (sqlite/save-host-and-headers!
         backend nil -1 (.getBytes "host") [node])
        (is (= {:header-integrity :ok :header-nodes 1}
               (sqlite/header-integrity-check! backend)))
        (with-open [connection (.getConnection (sqlite/datasource path))
                    select (.prepareStatement
                            connection
                            "SELECT node FROM consensus_header_nodes")
                    result (.executeQuery select)]
          (is (.next result))
          (let [damaged (aclone (.getBytes result 1))]
            (aset-byte damaged 5
                       (unchecked-byte
                        (bit-xor 1 (bit-and 0xff (aget damaged 5)))))
            (with-open [update (.prepareStatement
                               connection
                               "UPDATE consensus_header_nodes SET node = ?")]
              (.setBytes update 1 damaged)
              (is (= 1 (.executeUpdate update))))))
        (is (= :bitcoin.consensus/sqlite-header-hash
               (:type
                (ex-data
                 (try
                   (sqlite/header-integrity-check! backend)
                   (catch clojure.lang.ExceptionInfo error error))))))))))

(deftest pending-side-block-staging-is-atomic-and-bounded
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            decoded
            (header/decode-block-header
             (header/hex->bytes header/regtest-genesis-header-hex))
            hash (:hash-hex decoded)
            node
            {:hash hash :parent nil :height 0
             :header decoded :block nil
             :chainwork (header/header-work (:bits decoded))
             :undo nil :deployments {:taproot :active}
             :active? true :header-valid? true
             :block-valid? true :scripts-checked? true}
            raw (byte-array (repeat 81 (unchecked-byte 1)))
            first-host (.getBytes "first")
            rejected-host (.getBytes "rejected")]
        (sqlite/save-host-headers-and-pending!
         backend nil -1 first-host [node]
         {:store {hash raw} :maximum-count 1 :maximum-bytes 81})
        (is (= {:pending-blocks 1 :pending-bytes 81}
               (sqlite/pending-status backend)))
        (is (= (seq raw) (seq (sqlite/pending-block backend hash))))
        (is (= :bitcoin.consensus/pending-block-limit
               (:type
                (ex-data
                 (try
                   (sqlite/save-host-headers-and-pending!
                    backend nil -1 rejected-host []
                    {:maximum-count 0 :maximum-bytes 0})
                   (catch clojure.lang.ExceptionInfo error error))))))
        (is (= (seq first-host) (seq (sqlite/host-state backend))))
        (sqlite/save-host-headers-and-pending!
         backend nil -1 first-host [] {:delete [hash]})
        (is (= {:pending-blocks 0 :pending-bytes 0}
               (sqlite/pending-status backend)))
        (is (nil? (sqlite/pending-block backend hash)))))))

(deftest stale-parent-cannot-commit
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            view (sqlite/begin backend)]
        (is (= :bitcoin.consensus/sqlite-stale-tip
               (:type
                (ex-data
                 (try
                   (sqlite/commit-block!
                    view
                    {:block-hash "wrong" :parent-hash "not-the-tip"
                     :height 1 :previous-height 0
                     :undo {:spent {} :created #{}}})
                   (catch clojure.lang.ExceptionInfo error error))))))
        (is (= -1 (:height (sqlite/status backend))))))))

(deftest overwrite-undo-restores-the-prior-bip30-coin
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :mainnet})
            first-view (-> (sqlite/begin backend)
                           (utxo/-coin-assoc [txid-a 0] coin-a))]
        (sqlite/commit-block!
         first-view
         {:block-hash "before-bip30" :parent-hash nil
          :height 0 :previous-height -1
          :undo {:spent {} :created #{[txid-a 0]}}})
        (let [overwrite (-> (sqlite/begin backend)
                            (utxo/-coin-assoc [txid-a 0] coin-b))]
          (sqlite/commit-block!
           overwrite
           {:block-hash "bip30-repeat" :parent-hash "before-bip30"
            :height 1 :previous-height 0
            :undo {:spent {[txid-a 0] coin-a}
                   :created #{[txid-a 0]}}})
          (is (= coin-b (sqlite/lookup backend [txid-a 0])))
          (sqlite/disconnect-tip! backend "bip30-repeat")
          (is (= coin-a (sqlite/lookup backend [txid-a 0]))))))))

(deftest most-work-reorganization-and-host-state-commit-together
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            genesis-view (-> (sqlite/begin backend)
                             (utxo/-coin-assoc [txid-a 0] coin-a))]
        (sqlite/commit-block!
         genesis-view
         {:block-hash "genesis" :parent-hash nil
          :height 0 :previous-height -1
          :undo {:height -1 :spent {} :created #{[txid-a 0]}}})
        (let [old-view (-> (sqlite/begin backend)
                           (utxo/-coin-dissoc [txid-a 0])
                           (utxo/-coin-assoc [txid-b 0] coin-b))
              old-undo {:height 0 :spent {[txid-a 0] coin-a}
                        :created #{[txid-b 0]}}]
          (sqlite/commit-block!
           old-view
           {:block-hash "old" :parent-hash "genesis"
            :height 1 :previous-height 0 :undo old-undo})
          (let [view (sqlite/begin backend)
                detached
                (utxo/disconnect-block {:height 1 :coins view} old-undo)
                alternate-block
                {:transactions
                 [{:txid-natural txid-c
                   :inputs [{:txid-natural (vec (repeat 32 0))
                             :vout 0xffffffff :script-sig [1 1]
                             :sequence 0xffffffff}]
                   :outputs [{:value 1000 :script-pubkey [0x51]}]}]}
                {next-state :state alternate-undo :undo}
                (utxo/apply-block-with-undo
                 detached alternate-block 1 (constantly true)
                 {:halving-interval 150})]
            (is (= {:height 1 :tip "alternate" :coin-count 2
                    :detached 1 :attached 1}
                   (sqlite/commit-transition!
                    (:coins next-state)
                    {:expected-tip "old" :expected-height 1
                     :new-tip "alternate" :new-height 1
                     :detach ["old"]
                     :attach
                     [{:block-hash "alternate" :parent-hash "genesis"
                       :height 1 :previous-height 0
                       :undo alternate-undo}]
                     :host-state-bytes (byte-array [1 2 3])})))
            (is (= coin-a (sqlite/lookup backend [txid-a 0])))
            (is (nil? (sqlite/lookup backend [txid-b 0])))
            (is (= 1000 (:value (sqlite/lookup backend [txid-c 0]))))
            (is (nil? (sqlite/undo backend "old")))
            (is (= 0 (:height (sqlite/undo backend "alternate"))))
            (is (= [1 2 3] (vec (sqlite/host-state backend))))))))))

(deftest hard-process-crashes-never-publish-partial-consensus-transitions
  (doseq [fault
          [:transition/after-undo
           :transition/after-coins
           :transition/after-meta
           :transition/after-headers
           :transition/after-pending
           :transition/after-host
           :transition/before-commit
           :transition/after-commit]]
    (testing (str fault)
      (with-database
        (fn [path]
          (let [pending-hash (initialize-crash-database! path)
                process (crash-process! path fault pending-hash)
                backend (sqlite/open {:path path :network :regtest})
                committed? (= fault :transition/after-commit)]
            (is (:finished? process) (:output process))
            (is (= 91 (:exit process)) (:output process))
            (is (= (if committed?
                     {:height 1 :tip "next" :coin-count 1}
                     {:height 0 :tip "old" :coin-count 1})
                   (select-keys (sqlite/status backend)
                                [:height :tip :coin-count])))
            (is (= (if committed? coin-b coin-a)
                   (sqlite/lookup
                    backend [(if committed? txid-b txid-a) 0])))
            (is (nil? (sqlite/lookup
                       backend [(if committed? txid-a txid-b) 0])))
            (is (some? (sqlite/undo backend "old")))
            (is (= committed? (some? (sqlite/undo backend "next"))))
            (is (= (if committed?
                     {:pending-blocks 0 :pending-bytes 0}
                     {:pending-blocks 1 :pending-bytes 81})
                   (sqlite/pending-status backend)))
            (is (= (seq (.getBytes (if committed? "new-host" "old-host")))
                   (seq (sqlite/host-state backend))))
            (is (= :ok (:integrity (sqlite/integrity-check! backend))))))))))

(deftest hard-process-crashes-never-publish-partial-linear-blocks
  (doseq [fault
          [:commit-block/after-undo
           :commit-block/after-coins
           :commit-block/after-meta
           :commit-block/before-commit
           :commit-block/after-commit]]
    (testing (str fault)
      (with-database
        (fn [path]
          (let [_ (sqlite/open {:path path :network :regtest})
                process (crash-process! path fault "-")
                backend (sqlite/open {:path path :network :regtest})
                committed? (= fault :commit-block/after-commit)]
            (is (:finished? process) (:output process))
            (is (= 91 (:exit process)) (:output process))
            (is (= (if committed?
                     {:height 0 :tip "old" :coin-count 1}
                     {:height -1 :tip nil :coin-count 0})
                   (select-keys (sqlite/status backend)
                                [:height :tip :coin-count])))
            (is (= committed?
                   (= coin-a (sqlite/lookup backend [txid-a 0]))))
            (is (= committed? (some? (sqlite/undo backend "old"))))
            (is (= :ok (:integrity (sqlite/integrity-check! backend))))))))))

(deftest repeated-restart-and-undo-soak-remains-exactly-reversible
  (with-database
    (fn [path]
      (let [block-count 256]
        (loop [height 0
               backend (sqlite/open {:path path :network :regtest})]
          (when (< height block-count)
            (let [key [(soak-txid height) 0]
                  previous-key
                  (when (pos? height) [(soak-txid (dec height)) 0])
                  view
                  (cond-> (sqlite/begin backend)
                    previous-key (utxo/-coin-dissoc previous-key)
                    true (utxo/-coin-assoc key (soak-coin height)))]
              (sqlite/commit-block!
               view
               {:block-hash (str "soak-" height)
                :parent-hash (when (pos? height)
                               (str "soak-" (dec height)))
                :height height :previous-height (dec height)
                :undo
                {:height (dec height)
                 :spent
                 (if previous-key
                   {previous-key (soak-coin (dec height))}
                   {})
                 :created #{key}}})
              (when (zero? (mod (inc height) 32))
                (let [reopened
                      (sqlite/open {:path path :network :regtest})]
                  (is (= {:height height
                          :tip (str "soak-" height)
                          :coin-count 1}
                         (select-keys
                          (sqlite/status reopened)
                          [:height :tip :coin-count])))
                  (is (= :ok
                         (:integrity
                          (sqlite/integrity-check! reopened))))))
              (recur (inc height)
                     (if (zero? (mod (inc height) 32))
                       (sqlite/open {:path path :network :regtest})
                       backend)))))
        (loop [height (dec block-count)
               backend (sqlite/open {:path path :network :regtest})]
          (when-not (neg? height)
            (sqlite/disconnect-tip! backend (str "soak-" height))
            (when (zero? (mod (- block-count height) 32))
              (let [reopened
                    (sqlite/open {:path path :network :regtest})]
                (is (= (dec height) (:height (sqlite/status reopened))))
                (is (= :ok
                       (:integrity (sqlite/integrity-check! reopened))))))
            (recur (dec height)
                   (if (zero? (mod (- block-count height) 32))
                     (sqlite/open {:path path :network :regtest})
                     backend))))
        (let [reopened (sqlite/open {:path path :network :regtest})]
          (is (= {:height -1 :tip nil :coin-count 0}
                 (select-keys (sqlite/status reopened)
                              [:height :tip :coin-count])))
          (is (= :ok (:integrity (sqlite/integrity-check! reopened)))))))))

(deftest consensus-transition-validates-directly-on-disk-overlay
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})]
        (is (= {:height 0 :tip "genesis" :coin-count 1}
               (sqlite/connect-block!
                backend genesis-like-block
                {:block-hash "genesis" :parent-hash nil
                 :height 0 :previous-height -1}
                (constantly true)
                {:halving-interval 150})))
        (is (= {:value 5000 :script-pubkey [0x51]
                :height 0 :coinbase? true}
               (sqlite/lookup backend [txid-a 0])))
        (is (= {:height -1 :tip nil :coin-count 0}
               (sqlite/disconnect-tip! backend "genesis")))))))

(deftest authenticated-snapshot-streams-into-sqlite
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            base-hash (apply str (repeat 64 "a"))
            entry [[txid-a 0] coin-a]
            commitment (assumeutxo/hash-serialized {[txid-a 0] coin-a})
            options
            {:checkpoints
             {2 {:blockhash base-hash
                 :hash-serialized commitment
                 :chain-tx-count 3}}}
            snapshot (snapshot-bytes base-hash entry)
            damaged (aclone snapshot)]
        (aset-byte damaged (dec (alength damaged))
                   (unchecked-byte
                    (bit-xor 1 (bit-and 0xff
                                        (aget damaged
                                              (dec (alength damaged)))))))
        (is (= :bitcoin.consensus/snapshot-commitment
               (:type
                (ex-data
                 (try
                   (sqlite/import-snapshot!
                    backend damaged #(when (= % 2) base-hash) options)
                   (catch clojure.lang.ExceptionInfo error error))))))
        (is (= 0 (:coin-count (sqlite/status backend)))
            "a failed commitment rolls back all streamed rows")
        (is (= :assumed
               (:status
                (sqlite/import-snapshot!
                 backend snapshot
                 #(when (= % 2) base-hash) options))))
        (is (= coin-a (sqlite/lookup backend [txid-a 0])))
        (is (= {:network :regtest :height 2 :tip base-hash :coin-count 1}
               (sqlite/status backend)))))))

(deftest snapshot-and-host-state-share-one-commit
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            base-hash (apply str (repeat 64 "4"))
            entry [[txid-a 0] coin-a]
            commitment (assumeutxo/hash-serialized {[txid-a 0] coin-a})
            snapshot (snapshot-bytes base-hash entry)
            host-bytes (.getBytes "atomic-host-state")
            options
            {:checkpoints
             {2 {:blockhash base-hash
                 :hash-serialized commitment
                 :chain-tx-count 3}}
             :host-state-fn
             (fn [loaded]
               (is (nil? (get-in loaded [:utxo :coins])))
               host-bytes)}]
        (sqlite/import-snapshot!
         backend snapshot #(when (= % 2) base-hash) options)
        (is (= (seq host-bytes) (seq (sqlite/host-state backend))))
        (is (= base-hash (:tip (sqlite/status backend))))
        (is (= coin-a (sqlite/lookup backend [txid-a 0])))))))

(deftest disk-hash-serialized-matches-in-memory-ordering
  (with-database
    (fn [path]
      (let [backend (sqlite/open {:path path :network :regtest})
            txid-b (vec (reverse (range 32)))
            coin-b {:value 2000 :script-pubkey [0 20 1 2 3 4 5 6 7 8 9
                                                10 11 12 13 14 15 16 17
                                                18 19 20]
                    :height 0 :coinbase? false}
            coins {[txid-b 3] coin-b [txid-a 0] coin-a}
            base-hash (apply str (repeat 64 "5"))
            snapshot
            (snapshot-bytes
             base-hash
             ;; The fixture serializer accepts one entry, so use the overlay
             ;; commit path to exercise multiple SQL cursor rows.
             [[txid-a 0] coin-a])
            commitment
            (assumeutxo/hash-serialized {[txid-a 0] coin-a})]
        (sqlite/import-snapshot!
         backend snapshot (constantly base-hash)
         {:checkpoints
          {2 {:blockhash base-hash
              :hash-serialized commitment :chain-tx-count 3}}})
        (let [view (sqlite/begin backend)
              updated (utxo/coin-assoc view [txid-b 3] coin-b)]
          (sqlite/commit-transition!
           updated
           {:expected-tip base-hash :expected-height 2
            :new-tip "next" :new-height 3 :detach []
            :attach [{:block-hash "next" :parent-hash base-hash
                      :height 3 :previous-height 2
                      :undo {:height 2 :spent {}
                             :created #{[txid-b 3]}}}]}))
        (is (= (assumeutxo/hash-serialized coins)
               (sqlite/hash-serialized backend)))))))
