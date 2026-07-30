(ns bitcoin.consensus.sighash
  "Legacy, BIP143, and BIP341 signature digest algorithms."
  (:require [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.transaction :as transaction]
            [btc-crypto.signature :as signature]
            [sha256d.core :as sha256d]))

(def zero-hash (vec (repeat 32 0)))
(def one-hash (into [1] (repeat 31 0)))
(def uint64-max 0xffffffffffffffffN)

(defn- digest [bytes]
  (vec (sha256d/sha256d-bytes (vec bytes))))

(defn- single-digest [bytes]
  (vec (sha256d/sha256-bytes (vec bytes))))

(defn- tagged-digest [tag bytes]
  (let [tag-hash
        (single-digest
         #?(:clj (seq (.getBytes ^String tag "UTF-8"))
            :cljs (map #(.charCodeAt tag %) (range (count tag)))))]
    (single-digest (concat tag-hash tag-hash bytes))))

(defn- outpoint-bytes [{:keys [txid-natural vout]}]
  (vec (concat txid-natural (codec/uint-le vout 4))))

(defn- sequence-bytes [input]
  (codec/uint-le (:sequence input) 4))

(defn- output-bytes [output]
  (transaction/serialize-output output))

(defn legacy
  "Pre-SegWit SignatureHash. `script-code` must already have OP_CODESEPARATOR
  removed according to the executing script's rules."
  [value input-index script-code hash-type]
  (let [base-type (bit-and hash-type 0x1f)
        anyone-can-pay?
        (not (zero? (bit-and hash-type signature/sighash-anyonecanpay)))]
    (if (and (= base-type signature/sighash-single)
             (>= input-index (count (:outputs value))))
      one-hash
      (let [inputs
            (mapv
             (fn [index input]
               (assoc input :script-sig
                      (if (= index input-index) (vec script-code) [])))
             (range) (:inputs value))
            inputs
            (if (contains? #{signature/sighash-none
                             signature/sighash-single}
                           base-type)
              (mapv (fn [index input]
                      (if (= index input-index)
                        input
                        (assoc input :sequence 0)))
                    (range) inputs)
              inputs)
            inputs (if anyone-can-pay?
                     [(nth inputs input-index)]
                     inputs)
            outputs
            (case base-type
              2 []
              3 (into
                 (vec
                  (repeat input-index
                          {:value uint64-max :script-pubkey []}))
                 [(nth (:outputs value) input-index)])
              (:outputs value))
            serialized
            (transaction/serialize
             (assoc value :inputs inputs :outputs outputs
                    :witnesses nil :segwit? false))]
        (digest (concat serialized (codec/uint-le hash-type 4)))))))

(defn bip143
  "BIP143 witness-v0 SignatureHash for one input and its spent amount."
  [value input-index script-code amount hash-type]
  (let [base-type (bit-and hash-type 0x1f)
        anyone-can-pay?
        (not (zero? (bit-and hash-type signature/sighash-anyonecanpay)))
        inputs (:inputs value)
        outputs (:outputs value)
        hash-prevouts
        (if anyone-can-pay?
          zero-hash
          (digest (mapcat outpoint-bytes inputs)))
        hash-sequence
        (if (or anyone-can-pay?
                (contains? #{signature/sighash-single
                             signature/sighash-none}
                           base-type))
          zero-hash
          (digest (mapcat sequence-bytes inputs)))
        hash-outputs
        (cond
          (not (contains? #{signature/sighash-single
                            signature/sighash-none}
                          base-type))
          (digest (mapcat output-bytes outputs))

          (and (= base-type signature/sighash-single)
               (< input-index (count outputs)))
          (digest (output-bytes (nth outputs input-index)))

          :else zero-hash)
        input (nth inputs input-index)
        preimage
        (concat
         (codec/uint-le (:version value) 4)
         hash-prevouts hash-sequence
         (outpoint-bytes input)
         (codec/compact-size (count script-code)) script-code
         (codec/uint-le amount 8)
         (sequence-bytes input)
         hash-outputs
         (codec/uint-le (:locktime value) 4)
         (codec/uint-le hash-type 4))]
    (digest preimage)))

(def valid-taproot-hash-types
  #{0 1 2 3 0x81 0x82 0x83})

(defn taproot
  "BIP341/342 signature hash. `prevout-coins` must align with every input.
  Supplying :tapleaf-hash selects the tapscript extension (ext_flag=1)."
  [value input-index prevout-coins hash-type
   {:keys [annex tapleaf-hash code-separator-position]
    :or {code-separator-position 0xffffffff}}]
  (when-not (contains? valid-taproot-hash-types hash-type)
    (codec/fail! :bitcoin.consensus/taproot-hash-type
                 "Taproot signature uses an invalid hash type."
                 {:hash-type hash-type}))
  (when-not (= (count (:inputs value)) (count prevout-coins))
    (codec/fail! :bitcoin.consensus/missing-prevout-data
                 "Taproot sighash requires every input prevout."
                 {:inputs (count (:inputs value))
                  :prevouts (count prevout-coins)}))
  (let [base-type (bit-and hash-type 3)
        base-type (if (zero? base-type) signature/sighash-all base-type)
        anyone-can-pay?
        (not (zero? (bit-and hash-type signature/sighash-anyonecanpay)))
        inputs (:inputs value)
        outputs (:outputs value)
        input (nth inputs input-index)
        coin (nth prevout-coins input-index)
        _ (when-not coin
            (codec/fail! :bitcoin.consensus/missing-prevout-data
                         "Taproot input prevout is unavailable."
                         {:input-index input-index}))
        common-input-hashes
        (when-not anyone-can-pay?
          (concat
           (single-digest (mapcat outpoint-bytes inputs))
           (single-digest
            (mapcat #(codec/uint-le (:value %) 8) prevout-coins))
           (single-digest
            (mapcat
             #(concat
               (codec/compact-size (count (:script-pubkey %)))
               (:script-pubkey %))
             prevout-coins))
           (single-digest (mapcat sequence-bytes inputs))))
        output-hash
        (when (= base-type signature/sighash-all)
          (single-digest (mapcat output-bytes outputs)))
        spend-type (+ (if tapleaf-hash 2 0) (if annex 1 0))
        selected-input
        (if anyone-can-pay?
          (concat
           (outpoint-bytes input)
           (codec/uint-le (:value coin) 8)
           (codec/compact-size (count (:script-pubkey coin)))
           (:script-pubkey coin)
           (sequence-bytes input))
          (codec/uint-le input-index 4))
        annex-hash
        (when annex
          (single-digest
           (concat (codec/compact-size (count annex)) annex)))
        single-output-hash
        (when (= base-type signature/sighash-single)
          (when (>= input-index (count outputs))
            (codec/fail! :bitcoin.consensus/taproot-single-without-output
                         "Taproot SIGHASH_SINGLE input has no output."
                         {:input-index input-index}))
          (single-digest (output-bytes (nth outputs input-index))))
        extension
        (when tapleaf-hash
          (concat tapleaf-hash [0]
                  (codec/uint-le code-separator-position 4)))
        message
        (concat
         [hash-type]
         (codec/uint-le (:version value) 4)
         (codec/uint-le (:locktime value) 4)
         common-input-hashes
         output-hash
         [spend-type]
         selected-input
         annex-hash
         single-output-hash
         extension)]
    (tagged-digest "TapSighash" (cons 0 message))))

(defn taproot-keypath
  "BIP341 key-path signature hash."
  [value input-index prevout-coins hash-type annex]
  (taproot value input-index prevout-coins hash-type {:annex annex}))
