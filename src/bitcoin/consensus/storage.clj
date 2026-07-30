(ns bitcoin.consensus.storage
  "Checksummed, atomic JVM persistence for pure chainstate values."
  (:require [bitcoin.consensus.codec :as codec]
            [clojure.edn :as edn])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.channels FileChannel)
           (java.nio.file AtomicMoveNotSupportedException Files LinkOption
                          OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.security MessageDigest)))

(def format-version 2)

(defn- sha256-hex [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- state-payload [state]
  {:format format-version
   :network (:network state)
   :consensus (:consensus state)
   :active-tip (:active-tip state)
   :best-header (:best-header state)
   :utxo (:utxo state)
   :nodes (:nodes state)})

(defn encode
  "Encode chainstate as checksum-newline-EDN bytes."
  [state]
  (let [body (.getBytes (pr-str (state-payload state))
                        StandardCharsets/UTF_8)
        checksum (.getBytes (str (sha256-hex body) "\n")
                            StandardCharsets/US_ASCII)
        result (byte-array (+ (alength checksum) (alength body)))]
    (System/arraycopy checksum 0 result 0 (alength checksum))
    (System/arraycopy body 0 result (alength checksum) (alength body))
    result))

(defn- active-path [state]
  (loop [hash (:active-tip state) result #{}]
    (if (nil? hash)
      result
      (let [node (get-in state [:nodes hash])]
        (when-not node
          (codec/fail! :bitcoin.consensus/corrupt-chainstate
                       "Active chain references a missing node."
                       {:hash hash}))
        (recur (:parent node) (conj result hash))))))

(defn validate!
  "Validate structural invariants that must survive a process restart."
  [state expected-network]
  (when-not (contains? #{1 format-version} (:format state))
    (codec/fail! :bitcoin.consensus/unsupported-chainstate-format
                 "Unsupported chainstate snapshot format."
                 {:format (:format state)}))
  (when-not (= expected-network (:network state))
    (codec/fail! :bitcoin.consensus/chainstate-network-mismatch
                 "Chainstate belongs to a different Bitcoin network."
                 {:expected expected-network :actual (:network state)}))
  (let [state (cond-> state
                (= 1 (:format state))
                (assoc :best-header (:active-tip state)))
        path (active-path state)
        tip (get-in state [:nodes (:active-tip state)])
        best-header (get-in state [:nodes (:best-header state)])]
    (when-not best-header
      (codec/fail! :bitcoin.consensus/corrupt-chainstate
                   "Best header references a missing node."
                   {:best-header (:best-header state)}))
    (when (pos? (compare (:chainwork tip) (:chainwork best-header)))
      (codec/fail! :bitcoin.consensus/corrupt-chainstate
                   "Best header has less work than the active tip."
                   {:active-tip (:active-tip state)
                    :best-header (:best-header state)}))
    (when-not (= (:height tip) (get-in state [:utxo :height]))
      (codec/fail! :bitcoin.consensus/corrupt-chainstate
                   "UTXO height differs from the active tip height."
                   {:tip-height (:height tip)
                    :utxo-height (get-in state [:utxo :height])}))
    (doseq [[hash node] (:nodes state)]
      (when-not (= hash (:hash node))
        (codec/fail! :bitcoin.consensus/corrupt-chainstate
                     "Node map key differs from its block hash."
                     {:key hash :hash (:hash node)}))
      (when-let [parent-hash (:parent node)]
        (let [parent (get-in state [:nodes parent-hash])]
          (when-not (and parent
                         (= (inc (:height parent)) (:height node))
                         (= (get-in parent [:header :hash])
                            (get-in node [:header :prev-block])))
            (codec/fail! :bitcoin.consensus/corrupt-chainstate
                         "Node parent linkage or height is inconsistent."
                         {:hash hash :parent parent-hash}))))
      (when-not (= (contains? path hash) (true? (:active? node)))
        (codec/fail! :bitcoin.consensus/corrupt-chainstate
                     "Active flags do not match the active-tip path."
                     {:hash hash :active? (:active? node)})))
    (dissoc state :format)))

(defn decode
  [bytes expected-network]
  (let [text (String. ^bytes bytes StandardCharsets/UTF_8)
        newline (.indexOf text "\n")]
    (when-not (= 64 newline)
      (codec/fail! :bitcoin.consensus/corrupt-chainstate
                   "Snapshot checksum header is malformed." {}))
    (let [claimed (subs text 0 newline)
          body (subs text (inc newline))
          body-bytes (.getBytes body StandardCharsets/UTF_8)]
      (when-not (= claimed (sha256-hex body-bytes))
        (codec/fail! :bitcoin.consensus/chainstate-checksum-mismatch
                     "Snapshot checksum does not match its contents." {}))
      (binding [*read-eval* false]
        (validate! (edn/read-string body) expected-network)))))

(defn save!
  "Atomically replace `path` with a durable snapshot."
  [path state]
  (let [target (.toAbsolutePath (Path/of (str path) (make-array String 0)))
        parent (.getParent target)
        _ (Files/createDirectories parent
                                   (make-array java.nio.file.attribute.FileAttribute
                                               0))
        temporary (Files/createTempFile parent ".chainstate-" ".tmp"
                                        (make-array
                                         java.nio.file.attribute.FileAttribute
                                         0))
        bytes (encode state)]
    (try
      (Files/write temporary bytes
                   (into-array OpenOption
                               [StandardOpenOption/WRITE
                                StandardOpenOption/TRUNCATE_EXISTING]))
      (with-open [channel
                  (FileChannel/open temporary
                                    (into-array OpenOption
                                                [StandardOpenOption/WRITE]))]
        (.force channel true))
      (try
        (Files/move temporary target
                    (into-array
                     java.nio.file.CopyOption
                     [StandardCopyOption/ATOMIC_MOVE
                      StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move temporary target
                      (into-array
                       java.nio.file.CopyOption
                       [StandardCopyOption/REPLACE_EXISTING]))))
      target
      (finally
        (Files/deleteIfExists temporary)))))

(defn load!
  [path expected-network]
  (let [target (Path/of (str path) (make-array String 0))]
    (when-not (Files/exists target (make-array LinkOption 0))
      (codec/fail! :bitcoin.consensus/chainstate-not-found
                   "Chainstate snapshot does not exist."
                   {:path (str path)}))
    (decode (Files/readAllBytes target) expected-network)))
