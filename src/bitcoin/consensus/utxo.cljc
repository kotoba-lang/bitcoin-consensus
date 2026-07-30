(ns bitcoin.consensus.utxo
  "Deterministic value-safe UTXO transitions. Script verification is injected
  and therefore cannot be accidentally skipped by callers."
  (:require [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.transaction :as transaction]))

(def coinbase-maturity 100)
(def initial-subsidy 5000000000)
(def halving-interval 210000)

(defn block-subsidy [height]
  (let [halvings (quot height halving-interval)]
    (if (>= halvings 64)
      0
      (quot initial-subsidy
            (reduce * 1 (repeat halvings 2))))))

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

(defn- add-outputs [state transaction height coinbase?]
  (reduce-kv
   (fn [result index output]
     (let [key (outpoint-key (:txid-natural transaction) index)]
       (when (contains? (:coins result) key)
         (codec/fail! :bitcoin.consensus/overwrite-unspent
                      "Transaction would overwrite an unspent output."
                      {:outpoint key}))
       (assoc-in result [:coins key]
                 (assoc output :height height :coinbase? coinbase?))))
   state (vec (:outputs transaction))))

(defn apply-block
  "Apply a parsed block atomically. `verify-script` is mandatory and must
  return exactly true for every non-coinbase input."
  [state block height verify-script]
  (when-not (ifn? verify-script)
    (codec/fail! :bitcoin.consensus/missing-script-verifier
                 "A script verifier is required." {}))
  (let [transactions (:transactions block)
        coinbase (first transactions)
        [working fees]
        (reduce
         (fn [[working total-fees] transaction]
           (let [[spent input-value]
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
             [(add-outputs spent transaction height false)
              (+ total-fees (- input-value output-value))]))
         [state 0] (rest transactions))
        coinbase-value (transaction/output-value coinbase)
        allowed (+ (block-subsidy height) fees)
        _ (when (> coinbase-value allowed)
            (codec/fail! :bitcoin.consensus/bad-coinbase-amount
                         "Coinbase exceeds subsidy plus fees."
                         {:value coinbase-value :allowed allowed}))]
    (-> working
        (add-outputs coinbase height true)
        (assoc :height height))))

(def empty-state {:height -1 :coins {}})

(defn apply-block-with-undo
  "Apply a block and return {:state next-state :undo reversible-delta}."
  [state block height verify-script]
  (let [next-state (apply-block state block height verify-script)
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
     :undo {:height (:height state) :spent spent :created created}}))

(defn disconnect-block
  "Reverse exactly one apply-block-with-undo transition."
  [state {:keys [height spent created]}]
  (-> state
      (update :coins #(apply dissoc % created))
      (update :coins merge spent)
      (assoc :height height)))
