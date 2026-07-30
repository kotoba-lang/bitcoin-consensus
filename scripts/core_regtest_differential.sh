#!/usr/bin/env bash
set -euo pipefail

if ! command -v bitcoind >/dev/null || ! command -v bitcoin-cli >/dev/null; then
  echo "Bitcoin Core is required for differential verification." >&2
  exit 77
fi

consensus_datadir="$(mktemp -d "${TMPDIR:-/tmp}/bitcoin-consensus-core.XXXXXX")"
cleanup() {
  bitcoin-cli -regtest -datadir="$consensus_datadir" stop >/dev/null 2>&1 || true
  rm -rf "$consensus_datadir"
}
trap cleanup EXIT

bitcoind -regtest -datadir="$consensus_datadir" -daemonwait -server=1 \
  -listen=0 -fallbackfee=0.00001 >/dev/null

bitcoin-cli -regtest -datadir="$consensus_datadir" createwallet differential \
  >/dev/null
mining_address="$(
  bitcoin-cli -regtest -datadir="$consensus_datadir" getnewaddress
)"
bitcoin-cli -regtest -datadir="$consensus_datadir" \
  generatetoaddress 12 "$mining_address" >/dev/null

result="$(
  {
    for height in $(seq 0 12); do
      block_hash="$(
        bitcoin-cli -regtest -datadir="$consensus_datadir" getblockhash "$height"
      )"
      block_json="$(
        bitcoin-cli -regtest -datadir="$consensus_datadir" \
          getblock "$block_hash" 1
      )"
      block_raw="$(
        bitcoin-cli -regtest -datadir="$consensus_datadir" \
          getblock "$block_hash" 0
      )"
      block_size="$(jq -r .size <<<"$block_json")"
      block_weight="$(jq -r .weight <<<"$block_json")"
      printf '%s|%s|%s|%s\n' \
        "$block_hash" "$block_size" "$block_weight" "$block_raw"
    done
  } | clojure -M -e '
    (require (quote bitcoin.consensus.block)
             (quote bitcoin.consensus.chainstate)
             (quote clojure.string))
    (let [lines (line-seq (java.io.BufferedReader. *in*))
          chainstate (volatile! nil)]
      (doseq [[index line] (map-indexed vector lines)]
        (let [[expected-hash expected-size expected-weight hex]
              (clojure.string/split line #"\|")
              bytes (mapv #(Integer/parseInt (apply str %) 16)
                          (partition 2 hex))
              parsed (bitcoin.consensus.block/parse bytes)
              actual [(get-in parsed [:header :hash-hex])
                      (:size parsed) (:weight parsed)]
              expected [expected-hash
                        (parse-long expected-size)
                        (parse-long expected-weight)]]
          (when-not (= expected actual)
            (throw
             (ex-info "Bitcoin Core differential mismatch"
                      {:height index :expected expected :actual actual})))
          (vreset!
           chainstate
           (if (zero? index)
             (bitcoin.consensus.chainstate/initialize :regtest parsed)
             (bitcoin.consensus.chainstate/accept-block
              @chainstate parsed 2000000000)))))
      (println
       (str "verified=" (count lines)
            " active-height="
            (bitcoin.consensus.chainstate/active-height @chainstate))))'
)"

if [[ "$result" != "verified=13 active-height=12" ]]; then
  echo "Core/kernel differential did not verify every fixture: '$result'" >&2
  exit 1
fi

echo "Core/kernel regtest differential match: $result"
