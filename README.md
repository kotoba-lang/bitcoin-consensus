# bitcoin-consensus

Portable, deterministic Bitcoin full-block consensus kernel for kotoba-lang.

This repository is separate from the permanently read-only
[`org-bitcoin-p2p`](https://github.com/kotoba-lang/org-bitcoin-p2p) observer.

## Implemented

- bounded, canonical CompactSize and raw legacy/SegWit transaction decoding
- txid/wtxid, exact transaction and block weight, money-range and prevout checks
- transaction Merkle trees with CVE-2012-2459-style mutation detection
- BIP141 witness commitment validation
- block coinbase, duplicate transaction, and 4,000,000 weight checks
- contextual header difficulty, PoW, linkage, MTP, future-time, and chainwork
- transaction finality plus BIP34/BIP113/SegWit network activation heights
- atomic UTXO application, coinbase maturity, fees/subsidy, and undo records
- most-cumulative-work fork choice and tested multi-block reorganization
- mainnet genesis/block 1 fixtures and mined regtest fork conformance

## Security boundary

This is not yet a complete Bitcoin Core replacement. The mandatory
`verify-script` callback deliberately fails closed when absent, but the
in-process Script engine (legacy, P2SH, SegWit, Taproot/Schnorr), relative
locktime, sigop accounting, versionbits deployments, persistent chainstate,
assumevalid/assumeutxo, multi-peer block download, fuzzing, and broad
differential validation remain release blockers.

```bash
clojure -M:test
clojure -M:lint
clojure -M:coverage
```
