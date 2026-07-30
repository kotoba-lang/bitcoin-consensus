(ns bitcoin.consensus.utxo
  "Deterministic value-safe UTXO transitions. Script verification is injected
  and therefore cannot be accidentally skipped by callers."
  (:require [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.transaction :as transaction]))

(def coinbase-maturity 100)
(def initial-subsidy 5000000000)
(def halving-interval 210000)
(def max-block-sigop-cost 80000)

(defn block-subsidy
  ([height] (block-subsidy height halving-interval))
  ([height interval]
   (let [halvings (quot height interval)]
     (if (>= halvings 64)
       0
       (quot initial-subsidy
             (reduce * 1 (repeat halvings 2)))))))

(defn outpoint-key [txid-natural vout] [txid-natural vout])

(defn- spend-input
  [state transaction input-index input height verify-script]
  (let [key (outpoint-key (:txid-natural input) (:vout input))
        coin (get-in state [:coins key])]
    (when-not coin
      (codec/fail! :bitcoin.consensus/missing-input
                   "Transaction spends a missing or already-spent output."
                   {:input-index input-index :outpoint key}))
    (when (and (:coinbase? coin)
               (< (- height (:height coin)) coinbase-maturity))
      (codec/fail! :bitcoin.consensus/premature-coinbase-spend
                   "Transaction spends an immature coinbase output."
                   {:input-index input-index :coin-height (:height coin)
                    :height height}))
    (when-not (true? (verify-script transaction input-index coin))
      (codec/fail! :bitcoin.consensus/script-failed
                   "Input script verification failed."
                   {:input-index input-index}))
    [(update state :coins dissoc key) (:value coin)]))

(defn- provably-unspendable? [output]
  (= 0x6a (first (:script-pubkey output))))

(defn- add-outputs
  [state transaction height coinbase? allow-overwrite?]
  (reduce-kv
   (fn [result index output]
     (if (provably-unspendable? output)
       result
       (let [key (outpoint-key (:txid-natural transaction) index)]
       (when (and (not allow-overwrite?)
                  (contains? (:coins result) key))
         (codec/fail! :bitcoin.consensus/overwrite-unspent
                      "Transaction would overwrite an unspent output."
                      {:outpoint key}))
         (assoc-in result [:coins key]
                   (assoc output :height height :coinbase? coinbase?)))))
   state (vec (:outputs transaction))))

(defn- validate-sequence-locks!
  [state transaction height {:keys [sequence-locks? coin-mtp parent-mtp]}]
  (when sequence-locks?
    (let [prev-heights
          (mapv
           (fn [input]
             (or (get-in state
                         [:coins
                          (outpoint-key (:txid-natural input) (:vout input))
                          :height])
                 (codec/fail! :bitcoin.consensus/missing-input
                              "Transaction spends a missing output."
                              {:outpoint [(:txid-natural input)
                                          (:vout input)]})))
           (:inputs transaction))
          locks (transaction/calculate-sequence-locks
                 transaction prev-heights coin-mtp)]
      (when-not (transaction/sequence-locks-satisfied?
                 locks height parent-mtp)
        (codec/fail! :bitcoin.consensus/non-final-sequence
                     "Transaction violates BIP68 relative lock-time."
                     {:height height :parent-mtp parent-mtp
                      :locks locks})))))

(defn- input-coins [state transaction]
  (mapv
   (fn [input]
     (get-in state
             [:coins (outpoint-key (:txid-natural input) (:vout input))]))
   (:inputs transaction)))

(defn- add-sigop-cost!
  [total state transaction sigop-cost-fn]
  (let [next-total
        (+ total
           (if sigop-cost-fn
             (sigop-cost-fn transaction (input-coins state transaction))
             0))]
    (when (> next-total max-block-sigop-cost)
      (codec/fail! :bitcoin.consensus/too-many-sigops
                   "Block exceeds MAX_BLOCK_SIGOPS_COST."
                   {:cost next-total :limit max-block-sigop-cost}))
    next-total))

(defn apply-block
  "Apply a parsed block atomically. `verify-script` is mandatory and must
  return exactly true for every non-coinbase input."
  ([state block height verify-script]
   (apply-block state block height verify-script {}))
  ([state block height verify-script options]
   (when-not (ifn? verify-script)
     (codec/fail! :bitcoin.consensus/missing-script-verifier
                  "A script verifier is required." {}))
   (let [transactions (:transactions block)
        coinbase (first transactions)
        initial-sigops
        (add-sigop-cost! 0 state coinbase (:sigop-cost-fn options))
        [working fees _sigops]
        (reduce
         (fn [[working total-fees sigops] transaction]
           (validate-sequence-locks! working transaction height options)
           (let [transaction
                 (assoc transaction :prevout-coins
                        (input-coins working transaction))
                 sigops
                 (add-sigop-cost! sigops working transaction
                                  (:sigop-cost-fn options))
                 [spent input-value]
                 (reduce-kv
                  (fn [[current total] index input]
                    (let [[next-state value]
                          (spend-input current transaction index input
                                       height verify-script)]
                      [next-state (+ total value)]))
                  [working 0] (vec (:inputs transaction)))
                 output-value (transaction/output-value transaction)]
             (when (> output-value input-value)
               (codec/fail! :bitcoin.consensus/inputs-below-outputs
                            "Transaction creates value."
                            {:inputs input-value :outputs output-value}))
             [(add-outputs spent transaction height false
                           (:allow-bip30-overwrite? options))
              (+ total-fees (- input-value output-value))
              sigops]))
         [state 0 initial-sigops] (rest transactions))
        coinbase-value (transaction/output-value coinbase)
        allowed (+ (block-subsidy
                    height (or (:halving-interval options)
                               halving-interval))
                   fees)
        _ (when (> coinbase-value allowed)
            (codec/fail! :bitcoin.consensus/bad-coinbase-amount
                         "Coinbase exceeds subsidy plus fees."
                         {:value coinbase-value :allowed allowed}))]
     (-> working
         (add-outputs coinbase height true
                      (:allow-bip30-overwrite? options))
         (assoc :height height)))))

(def empty-state {:height -1 :coins {}})

(defn apply-block-with-undo
  "Apply a block and return {:state next-state :undo reversible-delta}."
  ([state block height verify-script]
   (apply-block-with-undo state block height verify-script {}))
  ([state block height verify-script options]
   (let [next-state (apply-block state block height verify-script options)
        before (:coins state)
        after (:coins next-state)
        spent (into {}
                    (keep (fn [[key coin]]
                            (when (not= coin (get after key ::missing))
                              [key coin])))
                    before)
        created (into #{}
                      (keep (fn [[key coin]]
                              (when (not= coin (get before key ::missing))
                                key)))
                      after)]
     {:state next-state
      :undo {:height (:height state) :spent spent :created created}})))

(defn disconnect-block
  "Reverse exactly one apply-block-with-undo transition."
  [state {:keys [height spent created]}]
  (-> state
      (update :coins #(apply dissoc % created))
      (update :coins merge spent)
      (assoc :height height)))
