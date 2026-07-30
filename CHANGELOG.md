# Changelog

## 0.10.0 — 2026-07-30

- Add bounded normalized-header point lookup and row-count primitives so
  disk-backed hosts can use lazy cached indexes without decoding the complete
  mainnet header graph during every process restart.

## 0.9.0 — 2026-07-30

- Add schema-v4 normalized, fixed-length header-node storage and atomically
  commit changed header rows with compact host metadata, UTXO transitions,
  snapshot activation, and reorganization state.
- Add legacy monolithic-host migration support for node hosts and expose a
  full cryptographic header audit that recomputes raw hashes, parent links,
  heights, and exact cumulative work.
- Accelerate mainnet-scale restart decoding with byte-array loops, direct hash
  conversion, transient map construction, and allocation-free parent lookup.

## 0.8.1 — 2026-07-30

- Cache exact header work by compact target within each validated batch,
  avoiding repeated 256-bit division across long difficulty epochs.
- Upgrade to `org-bitcoin-p2p` v0.5.0 for JDK-accelerated JVM SHA-256d.

## 0.8.0 — 2026-07-30

- Add atomic chronological `accept-headers` batch validation. It evaluates
  contextual PoW, expected difficulty, linkage, median time, and future time
  once over a shared 2,017-header window, then indexes the batch without
  rebuilding that window for every member.
- Reject duplicate or already indexed members before applying a batch.

## 0.7.4 — 2026-07-30

- Consume `org-bitcoin-p2p` v0.4.1 so real peers' full-range uint64 nonces
  decode without JVM overflow and `net_addr` ports use network byte order.

## 0.7.3 — 2026-07-30

- Match Bitcoin Core genesis semantics: validate and index genesis, but never
  insert its coinbase transaction into the UTXO set.
- Make background HASH_SERIALIZED validation compatible with real
  Core-generated snapshots instead of only self-generated fixtures.
- Require the live Core regtest differential to match the independently
  validated full-chain UTXO commitment against Core's dumped snapshot.

## 0.7.2 — 2026-07-30

- Recompute Bitcoin Core HASH_SERIALIZED directly from the ordered SQLite UTXO
  cursor in constant JVM memory.
- Validate AssumeUTXO background evidence from an independently persisted
  height, tip, and UTXO commitment without materializing the coin set.

## 0.7.1 — 2026-07-30

- Persist AssumeUTXO trust/status metadata in checksummed chainstate so a
  restart cannot silently forget pending background validation.
- Commit checksummed host/fork state in the same SQLite transaction as an
  authenticated constant-memory snapshot import.

## 0.7.0 — 2026-07-30

- Add Bitcoin Core v31.1 testnet4 consensus parameters, BIP94 header
  retargeting, genesis validation, and pinned AssumeUTXO/assumevalid anchors.
- Add default signet parameters and full BIP325 block-challenge verification
  through the in-process Script VM, verified against a live Core signet block.
- Upgrade the SQLite schema with atomic checksummed host-state persistence,
  durable undo lookup, schema-v1 migration, and all-or-nothing multi-block
  most-work reorganization commits.
- Allow disk-backed chainstate hosts to resolve pruned in-memory undo from the
  authenticated SQLite journal.

## 0.6.1 — 2026-07-30

- Activate witness and Taproot Script flags only at their buried or
  versionbits-derived deployment state instead of applying post-soft-fork
  semantics to historical blocks.

## 0.6.0 — 2026-07-30

- Match all 1,222 Bitcoin Core v31.1 Script vector outcomes with no skipped
  fixtures, including generated Taproot trees and policy flag behavior.
- Add an ordered, network-bound SQLite UTXO backend with WAL/FULL durability,
  immutable validation overlays, atomic coin + undo commits, durable
  disconnect, stale-tip protection, and integrity verification.
- Build undo from only the outpoints touched by a block instead of scanning the
  complete UTXO set.
- Stream authenticated Core v2 AssumeUTXO snapshots directly into SQLite
  without materializing the coin set, rolling back all rows on commitment
  failure.
- Add a resumable Core historical-range differential harness with pruned-data,
  checkpoint-height, block hash/size/weight, and final-tip guards.

## 0.5.2 — 2026-07-30

- Parameterize the live Core differential for long regtest histories.
- Atomically persist and reload the kernel chainstate at configurable intervals
  during differential validation to prove restart-safe continuation.
- Wait for the temporary Core daemon to exit before cleanup.

## 0.5.1 — 2026-07-30

- Activate an authenticated AssumeUTXO state only when its base is on the
  best-work header chain and strictly exceeds the current active chainwork.
- Preserve the original chainstate boundary for independent background
  validation by keeping activation a pure, explicit transition.

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
