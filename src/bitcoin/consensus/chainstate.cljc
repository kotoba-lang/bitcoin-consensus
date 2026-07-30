(ns bitcoin.consensus.chainstate
  "Pure most-work block tree with atomic UTXO reorganization."
  (:require [bitcoin.consensus.block :as block]
            [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.transaction :as transaction]
            [bitcoin.consensus.utxo :as utxo]
            [kotobase.bitcoin.protocol :as header]))

(def consensus-parameters
  {:mainnet {:bip34-height 227931
             :bip113-height 419328
             :segwit-height 481824}
   :testnet {:bip34-height 21111
             :bip113-height 770112
             :segwit-height 834624}
   :regtest {:bip34-height 500
             :bip113-height 432
             :segwit-height 0}})

(defn- parent-hash [parsed-block]
  (header/natural-hash->hex (get-in parsed-block [:header :prev-block])))

(defn initialize
  "Create chainstate from the network's actual genesis block."
  [network genesis-block verify-script]
  (let [expected (header/genesis-header network)
        actual (:header genesis-block)]
    (when-not (= (:hash expected) (:hash actual))
      (codec/fail! :bitcoin.consensus/wrong-genesis
                   "Genesis block does not match the configured network."
                   {:network network :actual (:hash-hex actual)}))
    (let [{utxo-state :state undo :undo}
          (utxo/apply-block-with-undo
           utxo/empty-state genesis-block 0 verify-script)
          hash (:hash-hex actual)
          chainwork (header/header-work (:bits actual))]
      {:network network
       :consensus (get consensus-parameters network)
       :active-tip hash
       :utxo utxo-state
       :nodes {hash {:hash hash :parent nil :height 0
                     :header actual :block genesis-block
                     :chainwork chainwork :undo undo
                     :active? true :block-valid? true}}})))

(defn- ancestor-nodes [state hash limit]
  (loop [hash hash remaining limit newest []]
    (if (or (nil? hash) (zero? remaining))
      (vec (reverse newest))
      (let [node (get-in state [:nodes hash])]
        (recur (:parent node) (dec remaining) (conj newest node))))))

(defn- validate-header! [state parsed-block now]
  (let [parent (parent-hash parsed-block)
        parent-node (get-in state [:nodes parent])]
    (when-not parent-node
      (codec/fail! :bitcoin.consensus/unknown-parent
                   "Block parent is unknown."
                   {:parent parent}))
    (let [context (ancestor-nodes state parent 2017)
          headers (conj (mapv :header context) (:header parsed-block))
          result
          (header/validate-header-consensus
           headers
           {:network (:network state)
            :start-height (:height (first context))
            :validate-from-index (count context)
            :now now})]
      (when-not (:valid? result)
        (codec/fail! :bitcoin.consensus/invalid-header
                     "Block header failed contextual consensus."
                     {:errors (:errors result)}))
      parent-node)))

(defn coinbase-height-prefix
  "Return the minimally encoded BIP34 script prefix for a block height."
  [height]
  (let [number
        (loop [value height result []]
          (if (zero? value)
            result
            (recur (quot value 256) (conj result (mod value 256)))))
        number (cond
                 (empty? number) []
                 (not (zero? (bit-and 0x80 (peek number))))
                 (conj number 0)
                 :else number)]
    (into [(count number)] number)))

(defn- starts-with? [value prefix]
  (= prefix (vec (take (count prefix) value))))

(defn validate-coinbase-height!
  [parsed-block height]
  (let [actual (get-in parsed-block
                       [:transactions 0 :inputs 0 :script-sig])
        expected (coinbase-height-prefix height)]
    (when-not (starts-with? actual expected)
      (codec/fail! :bitcoin.consensus/bad-coinbase-height
                   "Coinbase scriptSig does not begin with the block height."
                   {:height height :expected expected}))))

(defn- median-time-past [state parent]
  (let [timestamps
        (sort
         (map #(get-in % [:header :timestamp])
              (ancestor-nodes state parent 11)))]
    (nth timestamps (quot (count timestamps) 2))))

(defn- validate-block-context! [state parsed-block parent-node]
  (let [height (inc (:height parent-node))
        {:keys [bip34-height bip113-height segwit-height]}
        (:consensus state)
        block-time
        (if (>= height bip113-height)
          (median-time-past state (:hash parent-node))
          (get-in parsed-block [:header :timestamp]))]
    (when (>= height bip34-height)
      (validate-coinbase-height! parsed-block height))
    (doseq [value (:transactions parsed-block)]
      (when-not (transaction/final? value height block-time)
        (codec/fail! :bitcoin.consensus/non-final-transaction
                     "Block contains a non-final transaction."
                     {:height height :locktime (:locktime value)})))
    (when (>= height segwit-height)
      (block/validate-witness-commitment! (:transactions parsed-block)))))

(defn- common-ancestor [state left right]
  (loop [left left right right]
    (let [left-node (get-in state [:nodes left])
          right-node (get-in state [:nodes right])]
      (cond
        (> (:height left-node) (:height right-node))
        (recur (:parent left-node) right)

        (< (:height left-node) (:height right-node))
        (recur left (:parent right-node))

        (= left right) left
        :else (recur (:parent left-node) (:parent right-node))))))

(defn- path-to-ancestor [state tip ancestor]
  (loop [hash tip result []]
    (if (= hash ancestor)
      result
      (recur (get-in state [:nodes hash :parent]) (conj result hash)))))

(defn- activate-tip [state candidate verify-script]
  (let [current (:active-tip state)
        fork (common-ancestor state current candidate)
        detach (path-to-ancestor state current fork)
        attach (reverse (path-to-ancestor state candidate fork))
        detached
        (reduce
         (fn [current-state hash]
           (let [undo (get-in current-state [:nodes hash :undo])]
             (when-not undo
               (codec/fail! :bitcoin.consensus/missing-undo
                            "Active block is missing undo data."
                            {:hash hash}))
             (-> current-state
                 (update :utxo utxo/disconnect-block undo)
                 (assoc-in [:nodes hash :active?] false))))
         state detach)]
    (reduce
     (fn [current-state hash]
       (let [node (get-in current-state [:nodes hash])
             {next-utxo :state undo :undo}
             (utxo/apply-block-with-undo
              (:utxo current-state) (:block node) (:height node)
              verify-script)]
         (-> current-state
             (assoc :utxo next-utxo :active-tip hash)
             (assoc-in [:nodes hash :undo] undo)
             (assoc-in [:nodes hash :active?] true)
             (assoc-in [:nodes hash :block-valid?] true))))
     (assoc detached :active-tip fork) attach)))

(defn accept-block
  "Validate and add a parsed block, activating it atomically only when its
  cumulative work exceeds the current active tip."
  [state parsed-block now verify-script]
  (let [hash (get-in parsed-block [:header :hash-hex])]
    (if (contains? (:nodes state) hash)
      state
      (let [parent-node (validate-header! state parsed-block now)
            _ (validate-block-context! state parsed-block parent-node)
            node {:hash hash :parent (:hash parent-node)
                  :height (inc (:height parent-node))
                  :header (:header parsed-block) :block parsed-block
                  :chainwork
                  (header/add-chainwork
                   (:chainwork parent-node)
                   (header/header-work
                    (get-in parsed-block [:header :bits])))
                  :active? false :block-valid? false}
            added (assoc-in state [:nodes hash] node)
            active-work (get-in state [:nodes (:active-tip state)
                                       :chainwork])]
        (if (header/better-chain? (:chainwork node) active-work)
          (activate-tip added hash verify-script)
          added)))))

(defn active-height [state]
  (get-in state [:nodes (:active-tip state) :height]))
