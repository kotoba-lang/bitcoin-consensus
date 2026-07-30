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
- headers-first synchronization that never activates missing block data
- Bitcoin Core-compatible `assumevalid` gates: assumed/best chain ancestry,
  minimum chainwork, and more than two weeks of proof-equivalent burial
- authenticated Core v2 AssumeUTXO streaming decode for mainnet, testnet3,
  testnet4, and signet, including exact `hash_serialized_3` commitments
- separate assumed and background-validated snapshot states
- checksummed atomic chainstate v2 persistence, v1 migration, and restart
  invariant validation
- bounded multi-peer block scheduling, response matching, timeout requeue, and
  misbehavior scoring
- official BIP143/BIP340/BIP341 vectors, mainnet genesis/block 1 fixtures,
  1,033 applicable Bitcoin Core v31.1 Script outcomes, and mined multi-block
  plus Core-generated AssumeUTXO regtest differential conformance

## Security boundary

The JVM chainstate now uses the in-process Script verifier by default; an
explicit callback remains available for differential testing. AssumeUTXO
snapshots are activated only after their network, independently validated base
header, coin count, and Core commitment match a pinned checkpoint; promotion
to `:validated` additionally requires a full background chainstate match.

This is still not a drop-in Bitcoin Core replacement. Of 1,222 upstream Script
vectors, 1,033 using implemented consensus/policy flags run in CI with exact
success/failure parity; 189 requiring unimplemented policy flags or Core's
generated Taproot placeholders are explicitly skipped. Full historical-chain
differential validation and a disk-backed UTXO backend sized for mainnet remain
required. Script, AssumeUTXO, and persistence adapters are currently JVM-only;
wire codecs, consensus values, BIP9, headers-first state, and scheduling remain
portable Clojure/ClojureScript values.

```bash
clojure -M:test
clojure -M:lint
clojure -M:coverage
./scripts/core_regtest_differential.sh # requires bitcoind + bitcoin-cli
./scripts/core_script_vectors.sh       # pinned Bitcoin Core v31.1 vectors
```
