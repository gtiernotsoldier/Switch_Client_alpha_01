# Architecture

The code-truth architecture map lives in the repository root:

- **`docs/ARCHITECTURE.md`** (repo root) — the Chinese lookup table: layers, threads/processes, startup chain, wire protocol summary, the per-module table of *where judgement / algorithms / materialization reside*, the RNG stream inventory, key files and pitfalls.
- **`docs/ARCHITECTURE.en.md`** (repo root) — the English mirror of the same map.

One-line summary (2026-11, after `af1c7ca`):

```
javaw.exe  (agent hook → adapter modules: judgement + marshalling + platform writes)
    │  384-byte fixed frames, TCP loopback, 1 ms deadline, per-op degradation
    ▼
doppel-core.exe  (Rust, zero deps: all strategies, state machines and RNG —
                  deterministic, replayable, pinned by golden parity)
```

Deep dives that live beside this file:

- `IPC-PROTOCOL.md` — wire v1 byte tables, op tables, timing, degradation, runbook
- `PROTECTION.md` — hardening roadmap; §9 documents the process split and the migration ledger
- `CLIENT-AUTHORITY-AUDIT.md` — which client-authority advantages are genuinely undetectable
- `hud-contract-v3.md` — the HUD state-file contract

Maintainer's rule: whenever a module migrates into the core, a wire op is appended, or a thread/process boundary moves, update the root map in the same commit.
