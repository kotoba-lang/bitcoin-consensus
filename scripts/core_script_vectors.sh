#!/usr/bin/env bash
set -euo pipefail

core_tag="v31.1"
vector_sha256="bc23cb1dfa760d50042f534da23cbbe4b6fbb03d7def0b64f8de049453d6ead5"
vector_file="$(mktemp "${TMPDIR:-/tmp}/bitcoin-core-script-tests.XXXXXX")"
cleanup() {
  rm -f "$vector_file"
}
trap cleanup EXIT

curl -fsSL \
  "https://raw.githubusercontent.com/bitcoin/bitcoin/${core_tag}/src/test/data/script_tests.json" \
  -o "$vector_file"

actual_sha256="$(shasum -a 256 "$vector_file" | awk '{print $1}')"
if [[ "$actual_sha256" != "$vector_sha256" ]]; then
  echo "Bitcoin Core vector checksum mismatch." >&2
  exit 1
fi

result="$(clojure -M:core-vectors "$vector_file")"
expected="{:vectors 1222, :passed 1033, :skipped 189, :failed 0}"
if [[ "$result" != "$expected" ]]; then
  echo "Bitcoin Core Script vector coverage changed: $result" >&2
  exit 1
fi

echo "Bitcoin Core ${core_tag} Script outcomes conform: $result"
