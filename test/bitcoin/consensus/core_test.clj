(ns bitcoin.consensus.core-test
  (:require [bitcoin.consensus.block :as block]
            [bitcoin.consensus.chainstate :as chainstate]
            [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.transaction :as transaction]
            [bitcoin.consensus.utxo :as utxo]
            [clojure.test :refer [deftest is]]
            [kotobase.bitcoin.protocol :as header]
            [sha256d.core :as sha256d]))

(def genesis-block-hex
  "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c0101000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000")

(def block-one-hex
  "010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e362990101000000010000000000000000000000000000000000000000000000000000000000000000ffffffff0704ffff001d0104ffffffff0100f2052a0100000043410496b538e853519c726a2c91e61ec11600ae1390813a627c66fb8be7947be63c52da7589379515d4e0a604f8141781e62294721166bf621e73a82cbf2342c858eeac00000000")

(def regtest-genesis-block-hex
  (str header/regtest-genesis-header-hex (subs genesis-block-hex 160)))

(defn hex->bytes [value]
  (mapv #(Integer/parseInt (apply str %) 16) (partition 2 value)))

(defn error-type [function]
  (:type
   (ex-data
    (try
      (function)
      (catch clojure.lang.ExceptionInfo exception exception)))))

(defn regtest-coinbase [height branch]
  (transaction/parse
   (transaction/serialize
    {:version 1
     :inputs [{:txid-natural (vec (repeat 32 0))
               :vout 0xffffffff
               :script-sig [height branch]
               :sequence 0xffffffff}]
     :outputs [{:value (utxo/block-subsidy height)
                :script-pubkey [81]}]
     :witnesses nil
     :locktime 0
     :segwit? false})))

(defn mine-regtest-block [parent height branch]
  (let [coinbase (regtest-coinbase height branch)
        template {:version 1
                  :prev-block (get-in parent [:header :hash])
                  :merkle-root (:txid-natural coinbase)
                  :timestamp (inc (get-in parent [:header :timestamp]))
                  :bits 0x207fffff}]
    (loop [nonce 0]
      (let [header-bytes (header/encode-block-header
                          (assoc template :nonce nonce))
            decoded (header/decode-block-header header-bytes)]
        (if (header/hash-meets-target? (:hash decoded) (:bits decoded))
          (block/parse
           (vec (concat header-bytes [1] (:raw coinbase))))
          (recur (inc nonce)))))))

(deftest parses-and-validates-the-real-genesis-block
  (let [parsed (block/parse (hex->bytes genesis-block-hex))
        coinbase (first (:transactions parsed))]
    (is (= 1 (:transaction-count parsed)))
    (is (= 285 (:size parsed)))
    (is (= 1140 (:weight parsed)))
    (is (transaction/coinbase? coinbase))
    (is (= 5000000000 (transaction/output-value coinbase)))
    (is (= (:merkle-root (:header parsed)) (:txid-natural coinbase)))
    (is (= 0 (:locktime coinbase)))))

(deftest canonical-compact-size-fails-closed
  (is (= [252 1] (codec/read-compact-size [252] 0)))
  (is (= :bitcoin.consensus/noncanonical-compact-size
         (error-type #(codec/read-compact-size [0xfd 1 0] 0))))
  (doseq [value [253 65535 65536 4294967295 4294967296]]
    (is (= value
           (first
            (codec/read-compact-size (codec/compact-size value) 0)))))
  (is (= :bitcoin.consensus/truncated
         (error-type #(codec/read-uint-le [1] 0 2))))
  (is (= :bitcoin.consensus/resource-limit
         (error-type #(codec/read-var-bytes [2 1 2] 0 1 "test")))))

(deftest segwit-transaction-round-trips-and-has-distinct-identifiers
  (let [value {:version 2
               :inputs [{:txid-natural (vec (repeat 32 1))
                         :vout 3 :script-sig [] :sequence 0xfffffffe}]
               :outputs [{:value 999 :script-pubkey [0 20
                                                      1 2 3 4 5 6 7 8 9 10
                                                      11 12 13 14 15 16 17 18
                                                      19 20]}]
               :witnesses [[[48 1 2] [2 3 4]]]
               :locktime 42 :segwit? true}
        raw (transaction/serialize value)
        parsed (transaction/parse raw)]
    (is (:segwit? parsed))
    (is (= (:witnesses value) (:witnesses parsed)))
    (is (= raw (transaction/serialize parsed)))
    (is (not= (:txid-natural parsed) (:wtxid-natural parsed)))
    (is (< (:base-size parsed) (:total-size parsed)))
    (is (= (+ (* 3 (:base-size parsed)) (:total-size parsed))
           (:weight parsed)))))

(deftest malformed-transactions-fail-closed
  (let [base {:version 1
              :inputs [{:txid-natural (vec (repeat 32 1))
                        :vout 0 :script-sig [] :sequence 0xffffffff}]
              :outputs [{:value 1 :script-pubkey [81]}]
              :witnesses [[]] :locktime 0 :segwit? true}
        superfluous (transaction/serialize base)
        unknown-flag (assoc superfluous 5 2)
        ordinary (transaction/serialize (assoc base :segwit? false))]
    (is (= :bitcoin.consensus/superfluous-witness
           (error-type #(transaction/parse superfluous))))
    (is (= :bitcoin.consensus/unknown-witness-flag
           (error-type #(transaction/parse unknown-flag))))
    (is (= :bitcoin.consensus/trailing-data
           (error-type #(transaction/parse (conj ordinary 0)))))
    (is (= :bitcoin.consensus/amount-out-of-range
           (error-type
            #(transaction/parse
              (transaction/serialize
               (assoc-in (assoc base :segwit? false)
                         [:outputs 0 :value]
                         (inc transaction/max-money)))))))))

(deftest context-free-transaction-and-finality-rules-fail-closed
  (let [input {:txid-natural (vec (repeat 32 3))
               :vout 0 :script-sig [] :sequence 0}
        base {:inputs [input]
              :outputs [{:value 1 :script-pubkey [81]}]
              :locktime 101}]
    (is (= :bitcoin.consensus/amount-out-of-range
           (error-type
            #(transaction/validate-context-free!
              (assoc base :outputs
                     [{:value transaction/max-money :script-pubkey [81]}
                      {:value 1 :script-pubkey [81]}])))))
    (is (= :bitcoin.consensus/duplicate-input
           (error-type
            #(transaction/validate-context-free!
              (assoc base :inputs [input input])))))
    (is (= :bitcoin.consensus/null-prevout
           (error-type
            #(transaction/validate-context-free!
              (assoc base :inputs
                     [(assoc input :txid-natural (vec (repeat 32 0))
                             :vout 0xffffffff)
                      input])))))
    (is (false? (transaction/final? base 100 2000000000)))
    (is (true? (transaction/final? base 102 2000000000)))
    (is (true? (transaction/final?
                (assoc-in base [:inputs 0 :sequence]
                          transaction/final-sequence)
                100 2000000000)))))

(deftest merkle-mutation-is-distinguished-from-odd-padding
  (let [a (vec (repeat 32 1))
        b (vec (repeat 32 2))]
    (is (false? (:mutated? (block/merkle-root [a b a]))))
    (is (true? (:mutated? (block/merkle-root [a a]))))))

(deftest block-parser-rejects-a-header-merkle-mismatch
  (let [raw (hex->bytes genesis-block-hex)
        changed-header (update raw 36 bit-xor 1)]
    (is (= :bitcoin.consensus/bad-merkle-root
           (error-type #(block/parse changed-header))))
    (is (= :bitcoin.consensus/trailing-data
           (error-type #(block/parse (conj raw 0)))))))

(deftest bip141-witness-commitment-is-validated
  (let [reserved (vec (repeat 32 0))
        commitment
        (vec
         (sha256d/sha256d-bytes
          (vec (concat (vec (repeat 32 0)) reserved))))
        coinbase
        (transaction/parse
         (transaction/serialize
          {:version 1
           :inputs [{:txid-natural (vec (repeat 32 0))
                     :vout 0xffffffff :script-sig [1 1]
                     :sequence 0xffffffff}]
           :outputs [{:value 5000000000
                      :script-pubkey
                      (vec (concat block/witness-commitment-prefix
                                   commitment))}]
           :witnesses [[reserved]] :locktime 0 :segwit? true}))]
    (is (= 0 (:index (block/validate-witness-commitment! [coinbase]))))
    (is (= :bitcoin.consensus/bad-witness-commitment
           (error-type
            #(block/validate-witness-commitment!
              [(update-in coinbase [:outputs 0 :script-pubkey 6]
                          bit-xor 1)]))))
    (is (= :bitcoin.consensus/bad-witness-reserved-value
           (error-type
            #(block/validate-witness-commitment!
              [(assoc-in coinbase [:witnesses 0] [[0]])]))))
    (is (= :bitcoin.consensus/missing-witness-commitment
           (error-type
            #(block/validate-witness-commitment!
              [(assoc-in coinbase [:outputs 0 :script-pubkey] [81])]))))))

(deftest bip34-height-prefix-is-minimally-script-number-encoded
  (is (= [1 1] (chainstate/coinbase-height-prefix 1)))
  (is (= [2 128 0] (chainstate/coinbase-height-prefix 128)))
  (is (= [2 244 1] (chainstate/coinbase-height-prefix 500))))

(deftest utxo-transition-enforces-value-and-coinbase-maturity
  (let [source-id (vec (repeat 32 7))
        spend-id (vec (repeat 32 8))
        coinbase-id (vec (repeat 32 9))
        state {:height 100
               :coins {[source-id 0]
                       {:value 1000 :script-pubkey [81]
                        :height 0 :coinbase? false}}}
        spend {:txid-natural spend-id
               :inputs [{:txid-natural source-id :vout 0}]
               :outputs [{:value 900 :script-pubkey [81]}]}
        coinbase {:txid-natural coinbase-id
                  :inputs [{:txid-natural (vec (repeat 32 0))
                            :vout 0xffffffff}]
                  :outputs [{:value (+ (utxo/block-subsidy 101) 100)
                             :script-pubkey [81]}]}
        next-state
        (utxo/apply-block state {:transactions [coinbase spend]}
                          101 (constantly true))]
    (is (nil? (get-in next-state [:coins [source-id 0]])))
    (is (= 900 (get-in next-state [:coins [spend-id 0] :value])))
    (is (true? (get-in next-state [:coins [coinbase-id 0] :coinbase?])))))

(deftest utxo-transition-fails-closed-on-invalid-state-changes
  (let [source-id (vec (repeat 32 3))
        spend-id (vec (repeat 32 4))
        coinbase-id (vec (repeat 32 5))
        base-state
        {:height 10
         :coins {[source-id 0]
                 {:value 1000 :script-pubkey [81]
                  :height 10 :coinbase? true}}}
        spend {:txid-natural spend-id
               :inputs [{:txid-natural source-id :vout 0}]
               :outputs [{:value 900 :script-pubkey [81]}]}
        coinbase {:txid-natural coinbase-id
                  :inputs [{:txid-natural (vec (repeat 32 0))
                            :vout 0xffffffff}]
                  :outputs [{:value (utxo/block-subsidy 11)
                             :script-pubkey [81]}]}
        parsed-block {:transactions [coinbase spend]}]
    (is (= :bitcoin.consensus/missing-script-verifier
           (error-type #(utxo/apply-block base-state parsed-block 11 nil))))
    (is (= :bitcoin.consensus/premature-coinbase-spend
           (error-type
            #(utxo/apply-block base-state parsed-block 11
                               (constantly true)))))
    (is (= :bitcoin.consensus/script-failed
           (error-type
            #(utxo/apply-block
              (assoc-in base-state [:coins [source-id 0] :coinbase?] false)
              parsed-block 11 (constantly false)))))
    (is (= :bitcoin.consensus/missing-input
           (error-type
            #(utxo/apply-block
              (assoc base-state :coins {})
              parsed-block 11 (constantly true)))))
    (is (= :bitcoin.consensus/inputs-below-outputs
           (error-type
            #(utxo/apply-block
              (-> base-state
                  (assoc-in [:coins [source-id 0] :coinbase?] false))
              (assoc-in parsed-block
                        [:transactions 1 :outputs 0 :value] 1001)
              11 (constantly true)))))
    (is (= 2500000000 (utxo/block-subsidy 210000)))
    (is (zero? (utxo/block-subsidy (* 64 210000))))))

(deftest undo-restores-the-exact-previous-utxo-state
  (let [genesis (block/parse (hex->bytes genesis-block-hex))
        transition
        (utxo/apply-block-with-undo
         utxo/empty-state genesis 0 (constantly true))]
    (is (= utxo/empty-state
           (utxo/disconnect-block (:state transition) (:undo transition))))))

(deftest chainstate-connects-real-block-one-by-most-work
  (let [genesis (block/parse (hex->bytes genesis-block-hex))
        block-one (block/parse (hex->bytes block-one-hex))
        initial (chainstate/initialize :mainnet genesis (constantly true))
        connected
        (chainstate/accept-block initial block-one 2000000000
                                 (constantly true))]
    (is (= 0 (chainstate/active-height initial)))
    (is (= 1 (chainstate/active-height connected)))
    (is (= (get-in block-one [:header :hash-hex])
           (:active-tip connected)))
    (is (true? (get-in connected
                       [:nodes (:active-tip connected) :block-valid?])))
    (is (= 2 (count (get-in connected [:utxo :coins]))))))

(deftest chainstate-atomically-reorganizes-to-the-most-work-regtest-fork
  (let [genesis (block/parse (hex->bytes regtest-genesis-block-hex))
        a1 (mine-regtest-block genesis 1 10)
        a2 (mine-regtest-block a1 2 10)
        b1 (mine-regtest-block genesis 1 20)
        b2 (mine-regtest-block b1 2 20)
        b3 (mine-regtest-block b2 3 20)
        initial (chainstate/initialize :regtest genesis (constantly true))
        a-state (-> initial
                    (chainstate/accept-block a1 2000000000
                                             (constantly true))
                    (chainstate/accept-block a2 2000000000
                                             (constantly true)))
        side-state (-> a-state
                       (chainstate/accept-block b1 2000000000
                                                (constantly true))
                       (chainstate/accept-block b2 2000000000
                                                (constantly true)))
        reorganized
        (chainstate/accept-block side-state b3 2000000000
                                 (constantly true))]
    (is (= 2 (chainstate/active-height a-state)))
    (is (= (:active-tip a-state) (:active-tip side-state)))
    (is (false? (get-in side-state
                        [:nodes (get-in b2 [:header :hash-hex])
                         :block-valid?])))
    (is (= 3 (chainstate/active-height reorganized)))
    (is (= (get-in b3 [:header :hash-hex]) (:active-tip reorganized)))
    (is (= 4 (count (get-in reorganized [:utxo :coins]))))
    (is (nil? (get-in reorganized
                      [:utxo :coins
                       [(get-in a1 [:transactions 0 :txid-natural]) 0]])))
    (is (= (utxo/block-subsidy 3)
           (get-in reorganized
                   [:utxo :coins
                    [(get-in b3 [:transactions 0 :txid-natural]) 0]
                    :value])))
    (is (false? (get-in reorganized
                        [:nodes (get-in a2 [:header :hash-hex]) :active?])))
    (is (true? (get-in reorganized
                       [:nodes (get-in b2 [:header :hash-hex]) :active?])))))
