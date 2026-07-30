# Changelog

## 0.1.0 — 2026-07-30

- Add bounded raw legacy and SegWit transaction and block parsing.
- Enforce context-free transaction, Merkle, weight, coinbase, witness
  commitment, money-range, and duplicate checks.
- Add contextual PoW, difficulty, BIP34, finality, BIP113, and SegWit
  activation validation for mainnet, testnet3, and regtest.
- Add atomic UTXO transitions and undo-driven most-work chain reorganization.
- Validate mainnet genesis/block 1 and mined competing regtest forks.
