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

genesis_hash="$(bitcoin-cli -regtest -datadir="$consensus_datadir" getblockhash 0)"
genesis_raw="$(bitcoin-cli -regtest -datadir="$consensus_datadir" getblock "$genesis_hash" 0)"

result="$(
  GENESIS_RAW="$genesis_raw" clojure -M -e '
    (require (quote bitcoin.consensus.block))
    (let [hex (System/getenv "GENESIS_RAW")
          bytes (mapv #(Integer/parseInt (apply str %) 16)
                      (partition 2 hex))
          parsed (bitcoin.consensus.block/parse bytes)]
      (println (get-in parsed [:header :hash-hex])
               (:size parsed)
               (:weight parsed)))'
)"

expected="$genesis_hash 285 1140"
if [[ "$result" != "$expected" ]]; then
  echo "Core/kernel mismatch: expected '$expected', got '$result'" >&2
  exit 1
fi

echo "Core/kernel regtest genesis match: $result"
