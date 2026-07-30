# Changelog

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
