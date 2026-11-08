# Contributing

## Read first

1. Root `docs/ARCHITECTURE.md` — the code-truth map (which layer owns what; where a change belongs)
2. `Doppel/docs/CONSTITUTION.md` — the four principles and what they forbid
3. `Doppel/docs/IPC-PROTOCOL.md` — the wire contract; `Doppel/docs/PROTECTION.md` §9 — the process split and migration ledger

## The one rule that decides where your change goes

- **Judgement** (game-context predicates: is the target alive, is the GUI open, is the weapon a sword) → the adapter module's `.kt`
- **Algorithm / probability / time window / cross-tick state / randomness** → `core-rs/src/strategy/` (+ a new op, appended)
- **Platform side effects** (packets, keys, motion, rotations) → the adapter module, driven by the core's returned step
- **Wire format** → `core-rs/src/codec.rs` + the mirror in `ipc/.../Wire.kt` + the byte table in `IPC-PROTOCOL.md`, in the same commit

If you find yourself adding a `kotlin.random` call, a `System.nanoTime()` window, or a mutable cross-tick field to an adapter module: stop — it belongs in the core. That is the entire architecture.

## Workflow

```bash
# Rust side
cd Doppel/core-rs
cargo test                          # unit tests: strategies, wire, rng, panic containment
cargo run -- --selftest             # internal consistency checks
cargo run -- --emit-golden golden   # regenerate parity fixtures (TSV)

# JVM side
./gradlew build                     # contract, ipc, safety, core (oracle), agent, adapter
./gradlew :core:test                # parity suites replay core-rs/golden/ bit for bit
```

The parity suites are the gate: if you changed an algorithm on either side, regenerate the fixtures and make sure the other side still matches — a red `*ParityTest` means the port and the oracle disagree, not that the test is wrong.

## Changing an algorithm

1. Edit `core-rs/src/strategy/<name>.rs`
2. Edit the oracle twin (`core/src/main/kotlin/io/doppel/core/strategy/...`)
3. `cargo run -- --emit-golden golden`
4. `./gradlew :core:test` — all `*ParityTest` green
5. Add/extend the wiring test on the adapter side if the marshalling changed

## Adding a module

1. `adapter/common/module/<category>/X.kt` — judgement + marshalling + platform writes only
2. Register it in `AgentBridge.moduleEntries` (Fabric fallback) and the Forge bootstrap path
3. Algorithm (if any) goes to `core-rs/src/strategy/x.rs` + an **appended** op in `codec.rs` (op ids are never renumbered — the stub and the core upgrade independently)
4. Mirror the op in `ipc/.../Wire.kt` / `Strategies.kt`
5. Send a `Reset` frame on enable/disable (`Strategies.resetPlayer()` etc.) so the core forgets learned state
6. State the module's degradation direction (what to do when the core is unreachable) in the module comment — see `PROTECTION.md` §9.5.3

## Commit style

Conventional commits, scope = the module or layer:

```
refactor(block): AutoBlock / BlockHit random draws move to the core
refactor(state): KeepSprint / LagRange local state machines move to the core
```

Migration commits say what moved, which op carries it, whether a new RNG stream exists, and what evidence pins it (parity rows + E2E).

## Things that will get a PR rejected

- Any `kotlin.random` / clock-based decision in an adapter module
- Renumbering a wire op or a module id
- Packaging the Kotlin oracle (`io.doppel.core.*`) into agent.jar
- A module that holds state across ticks on the JVM side
- "Brutal" gameplay modules (see the constitution, Article 1)
- A parity fixture edited by hand instead of regenerated
