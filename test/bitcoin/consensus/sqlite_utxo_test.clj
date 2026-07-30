(ns bitcoin.consensus.sqlite-utxo-test
  (:require [bitcoin.consensus.assumeutxo :as assumeutxo]
            [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.sqlite-utxo :as sqlite]
            [bitcoin.consensus.utxo :as utxo]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def txid-a (vec (repeat 32 1)))
(def txid-b (vec (repeat 32 2)))
(def txid-c (vec (repeat 32 3)))
(def coin-a {:value 5000 :script-pubkey [0x51]
             :height 1 :coinbase? false})
(def coin-b {:value 3000 :script-pubkey [0x00 0x14 1 2 3]
             :height 2 :coinbase? true})

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
          (is (= {:integrity :ok :coin-count 1}
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
