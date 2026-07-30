# bitcoin-consensus

Portable, deterministic Bitcoin full-block consensus kernel for kotoba-lang.

This repository is separate from the permanently read-only
[`org-bitcoin-p2p`](https://github.com/kotoba-lang/org-bitcoin-p2p) observer.

## Implemented

- bounded, canonical CompactSize and raw legacy/SegWit transaction decoding
- unsigned 64-bit amount decoding without host integer overflow
- txid/wtxid, exact transaction and block weight, money-range and prevout checks
- transaction Merkle trees with CVE-2012-2459-style mutation detection
- BIP141 witness commitment validation
- block coinbase, duplicate transaction, and 4,000,000 weight checks
- contextual header difficulty, PoW, linkage, MTP, future-time, and chainwork
- legacy, P2SH, SegWit v0, and Taproot key/script-path Script verification
- legacy, BIP143, and BIP341/342 sighash plus ECDSA/BIP340 Schnorr verification
- historical lax-DER/BIP66, CLTV, CSV/BIP68, BIP147, and BIP30 exceptions
- BIP9 versionbits state transitions and network-specific buried deployments
- legacy/P2SH/witness sigop accounting with the 80,000 block-cost limit
- transaction finality plus BIP34/BIP113 and relative lock-time validation
- atomic UTXO application, coinbase maturity, per-network subsidy, fees, and undo
- provably unspendable output pruning and signed transaction-version handling
- most-cumulative-work fork choice and tested multi-block reorganization
- checksummed atomic chainstate persistence with restart invariant validation
- bounded multi-peer block scheduling, response matching, timeout requeue, and
  misbehavior scoring
- official BIP143/BIP340/BIP341 vectors, mainnet genesis/block 1 fixtures, and
  mined multi-block Bitcoin Core regtest differential conformance

## Security boundary

The JVM chainstate now uses the in-process Script verifier by default; an
explicit callback remains available for differential testing. This is still not
a drop-in Bitcoin Core replacement: exhaustive upstream `script_tests.json`
conformance and full historical-chain differential validation remain required.
`assumevalid`/`assumeutxo` are synchronization optimizations and are not yet
implemented. The Script and persistence adapters are currently JVM-only; the
wire codecs, consensus values, BIP9 state machine, and sync scheduler remain
portable Clojure/ClojureScript values.

```bash
clojure -M:test
clojure -M:lint
clojure -M:coverage
./scripts/core_regtest_differential.sh # requires bitcoind + bitcoin-cli
```
