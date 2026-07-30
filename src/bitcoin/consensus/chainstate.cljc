(ns bitcoin.consensus.chainstate
  "Pure most-work block tree with atomic UTXO reorganization."
  (:require [bitcoin.consensus.block :as block]
            [bitcoin.consensus.codec :as codec]
            #?(:clj [bitcoin.consensus.script :as script])
            [bitcoin.consensus.transaction :as transaction]
            [bitcoin.consensus.utxo :as utxo]
            [bitcoin.consensus.versionbits :as versionbits]
            [kotobase.bitcoin.protocol :as header]))

(def consensus-parameters
  {:mainnet {:bip34-height 227931
             :bip65-height 388381
             :bip66-height 363725
             :csv-height 419328
             :bip113-height 419328
             :segwit-height 481824
             :taproot-height 709632
             :taproot-deployment
             {:bit 2 :start-time 1619222400 :timeout 1628640000
              :min-activation-height 709632 :threshold 1815 :period 2016}
             :halving-interval 210000
             :script-flag-exceptions
             {"00000000000002dc756eebf4f49723ed8d30cc28a5f108eb94b1ba88ac4f9c22"
              #{}
              "0000000000000000000f14c35b2d841e986ab5441de8c585d5ffe55ea1e395ad"
              #{:p2sh :witness}}}
   :testnet {:bip34-height 21111
             :bip65-height 581885
             :bip66-height 330776
             :csv-height 770112
             :bip113-height 770112
             :segwit-height 834624
             ;; Taproot activation is versionbits-derived on testnet.
             :taproot-height nil
             :taproot-deployment
             {:bit 2 :start-time 1619222400 :timeout 1628640000
              :min-activation-height 0 :threshold 1512 :period 2016}
             :halving-interval 210000
             :script-flag-exceptions
             {"00000000dd30457c001f4095d208cc1296b0eed002427aa599874af7a432b105"
              #{}}}
   :regtest {:bip34-height 1
             :bip65-height 1
             :bip66-height 1
             :csv-height 1
             :bip113-height 1
             :segwit-height 0
             :taproot-height 0
             :taproot-deployment {:always-active? true}
             :halving-interval 150
             :script-flag-exceptions {}}})

(def bip30-repeat-blocks
  #{"00000000000a4d0a398161ffc163c503763b1f4360639393e0e4c8e300e0caec"
    "00000000000743f190a18c5577a3c2d2a1f610ae9601ac046a38084ccb7cd721"})

(defn script-flags
  "Bitcoin Core block-consensus Script flags for a known deployment state."
  [{:keys [bip65-height bip66-height csv-height segwit-height
           script-flag-exceptions]}
   height block-hash]
  (or (get script-flag-exceptions block-hash)
      (cond-> #{:p2sh :witness :taproot}
        (>= height bip66-height) (conj :dersig)
        (>= height bip65-height) (conj :cltv)
        (>= height csv-height) (conj :csv)
        (>= height segwit-height) (conj :null-dummy))))

#?(:clj
   (defn- verifier-for
     [state height block-hash override]
     (or override
         (let [flags (script-flags (:consensus state) height block-hash)]
           (fn [transaction input-index coin]
             (script/verify-input transaction input-index coin flags)))))
   :cljs
   (defn- verifier-for
     [_state _height _block-hash override]
     (or override
         (codec/fail! :bitcoin.consensus/missing-script-verifier
                      "The built-in Script VM is currently JVM-only." {}))))

#?(:clj
   (defn- sigop-counter
     [parameters height block-hash]
     (let [flags (script-flags parameters height block-hash)]
       (fn [transaction coins]
         (script/transaction-sigop-cost transaction coins flags))))
   :cljs
   (defn- sigop-counter [_parameters _height _block-hash] nil))

(defn- parent-hash [parsed-block]
  (header/natural-hash->hex (get-in parsed-block [:header :prev-block])))

(defn initialize
  "Create chainstate from the network's actual genesis block."
  ([network genesis-block]
   (initialize network genesis-block nil))
  ([network genesis-block verify-script]
   (let [expected (header/genesis-header network)
        actual (:header genesis-block)]
    (when-not (= (:hash expected) (:hash actual))
      (codec/fail! :bitcoin.consensus/wrong-genesis
                   "Genesis block does not match the configured network."
                   {:network network :actual (:hash-hex actual)}))
    (let [base-state {:network network
                      :consensus (get consensus-parameters network)}
          taproot-active?
          (true? (get-in base-state
                         [:consensus :taproot-deployment :always-active?]))
          verifier (verifier-for base-state 0 (:hash-hex actual)
                                 verify-script)
          {utxo-state :state undo :undo}
          (utxo/apply-block-with-undo
           utxo/empty-state genesis-block 0 verifier
           {:sigop-cost-fn
            (sigop-counter (:consensus base-state) 0 (:hash-hex actual))
            :halving-interval
            (get-in base-state [:consensus :halving-interval])})
          hash (:hash-hex actual)
          chainwork (header/header-work (:bits actual))]
      {:network network
       :consensus (:consensus base-state)
       :active-tip hash
       :utxo utxo-state
       :nodes {hash {:hash hash :parent nil :height 0
                     :header actual :block genesis-block
                     :chainwork chainwork :undo undo
                     :deployments
                     {:taproot (if taproot-active? :active :defined)}
                     :active? true :block-valid? true}}}))))

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
  (cond
    (zero? height) [0x00]
    (<= 1 height 16) [(+ 0x50 height)]
    :else
    (let [number
          (loop [value height result []]
            (if (zero? value)
              result
              (recur (quot value 256) (conj result (mod value 256)))))
          number (if (not (zero? (bit-and 0x80 (peek number))))
                   (conj number 0)
                   number)]
      (into [(count number)] number))))

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
                   {:height height :expected expected
                    :actual-prefix
                    (vec (take (max 8 (count expected)) actual))}))))

(defn- median-time-past [state parent]
  (let [timestamps
        (sort
         (map #(get-in % [:header :timestamp])
              (ancestor-nodes state parent 11)))]
    (nth timestamps (quot (count timestamps) 2))))

(defn- ancestor-at-height [state tip height]
  (loop [hash tip]
    (let [node (get-in state [:nodes hash])]
      (cond
        (nil? node) nil
        (= height (:height node)) node
        (< (:height node) height) nil
        :else (recur (:parent node))))))

(defn- median-time-past-at-height [state height]
  (let [node (ancestor-at-height state (:active-tip state) height)]
    (when-not node
      (codec/fail! :bitcoin.consensus/missing-locktime-ancestor
                   "BIP68 coin ancestor is unavailable."
                   {:height height}))
    (median-time-past state (:hash node))))

(defn- next-taproot-state [state parent-node height]
  (let [deployment (get-in state [:consensus :taproot-deployment])
        parent-state (get-in parent-node [:deployments :taproot] :defined)]
    (cond
      (:always-active? deployment) :active
      (nil? (:period deployment)) parent-state
      :else
      (let [period (:period deployment)
            signal-count
            (if (zero? (mod height period))
              (count
               (filter
                #(versionbits/signals?
                  (get-in % [:header :version]) (:bit deployment))
                (ancestor-nodes state (:hash parent-node) period)))
              0)]
        (versionbits/next-state
         deployment height parent-state
         (median-time-past state (:hash parent-node))
         signal-count)))))

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
             height (:height node)
             verifier (verifier-for current-state height hash verify-script)
             csv-active? (>= height
                             (get-in current-state
                                     [:consensus :csv-height]))
             parent-mtp (median-time-past current-state
                                          (:active-tip current-state))
             {next-utxo :state undo :undo}
             (utxo/apply-block-with-undo
              (:utxo current-state) (:block node) height verifier
              {:sequence-locks? csv-active?
               :allow-bip30-overwrite?
               (contains? bip30-repeat-blocks hash)
               :halving-interval
               (get-in current-state [:consensus :halving-interval])
               :parent-mtp parent-mtp
               :sigop-cost-fn
               (sigop-counter (:consensus current-state) height hash)
               :coin-mtp
               #(median-time-past-at-height current-state %)})]
         (-> current-state
             (assoc :utxo next-utxo :active-tip hash)
             (assoc-in [:nodes hash :undo] undo)
             (assoc-in [:nodes hash :active?] true)
             (assoc-in [:nodes hash :block-valid?] true))))
     (assoc detached :active-tip fork) attach)))

(defn accept-block
  "Validate and add a parsed block, activating it atomically only when its
  cumulative work exceeds the current active tip."
  ([state parsed-block now]
   (accept-block state parsed-block now nil))
  ([state parsed-block now verify-script]
   (let [hash (get-in parsed-block [:header :hash-hex])]
    (if (contains? (:nodes state) hash)
      state
      (let [parent-node (validate-header! state parsed-block now)
            _ (validate-block-context! state parsed-block parent-node)
            node {:hash hash :parent (:hash parent-node)
                  :height (inc (:height parent-node))
                  :header (:header parsed-block) :block parsed-block
                  :deployments
                  {:taproot
                   (next-taproot-state
                    state parent-node (inc (:height parent-node)))}
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
          added))))))

(defn active-height [state]
  (get-in state [:nodes (:active-tip state) :height]))
