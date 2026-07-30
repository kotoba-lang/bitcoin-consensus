# Changelog

## 0.5.0 — 2026-07-30

- Add headers-first validation without activating headers that lack block data.
- Match Bitcoin Core v31.1 `assumevalid` ancestry, minimum-chainwork, and
  proof-equivalent two-week burial gates while skipping Script checks only.
- Decode and authenticate Core v2 AssumeUTXO snapshots with bounded canonical
  parsing, compressed Coin/script support, exact `hash_serialized_3`, pinned
  checkpoints, and independent background-chainstate promotion.
- Persist `best-header` in chainstate format v2 while migrating v1 snapshots.
- Add a live differential against Core-generated regtest UTXO snapshots.
- Run all 1,033 currently applicable v31.1 `script_tests.json` outcomes in CI
  from a SHA-256-pinned source; explicitly account for 189 unsupported vectors.
- Fix strict signature/public-key encoding failure semantics, historical hybrid
  public keys, CHECKMULTISIG evaluation order, and witness-disabled handling
  found by the upstream vectors.

## 0.4.2 — 2026-07-30

- Validate a real SegWit wallet spend in the 103-block Core differential.
- Decode signed transaction versions and unsigned 32-bit wire fields correctly.

## 0.4.1 — 2026-07-30

- Match BIP34 OP_1 through OP_16 coinbase-height encoding on small heights.

## 0.4.0 — 2026-07-30

- Add the in-process legacy, P2SH, SegWit v0, Taproot, BIP341/342, CLTV, CSV,
  CHECKMULTISIG, and sigop-counting consensus Script engine.

## 0.3.0 — 2026-07-30

- Add parsed block serialization for P2P and differential tooling.
- Add deterministic adversarial decoder fuzz cases and exhaustively mutate
  every byte of the mainnet genesis fixture.
- Decode unsigned 64-bit wire integers without JVM `long` overflow and keep
  serialized byte values compatible with the hashing backend.
- Add a live Bitcoin Core v31 regtest differential harness.

## 0.2.0 — 2026-07-30

- Add checksummed, atomic JVM chainstate persistence.
- Reject snapshot corruption, network mismatch, bad parent linkage, inconsistent
  active flags, and UTXO/tip height mismatch during restart.
- Add bounded multi-peer block scheduling with deduplication, response matching,
  timeout/disconnect requeue, and peer misbehavior scoring.

## 0.1.0 — 2026-07-30

- Add bounded raw legacy and SegWit transaction and block parsing.
- Enforce context-free transaction, Merkle, weight, coinbase, witness
  commitment, money-range, and duplicate checks.
- Add contextual PoW, difficulty, BIP34, finality, BIP113, and SegWit
  activation validation for mainnet, testnet3, and regtest.
- Add atomic UTXO transitions and undo-driven most-work chain reorganization.
- Validate mainnet genesis/block 1 and mined competing regtest forks.
