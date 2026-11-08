# 🥪 Doppel - Sandwich Architecture Reference Implementation

A modern, cross-version Minecraft ghost client framework designed for high debuggability and assistance-first gameplay.

> Code-truth lookup tables live in the repo root `docs/ARCHITECTURE.md` (Chinese) / `ARCHITECTURE.en.md`; wire byte tables in `Doppel/docs/IPC-PROTOCOL.md`; hardening rationale in `Doppel/docs/PROTECTION.md`. This file is the short orientation.

## Architecture Overview (2026-11 reality)

Two processes. The will lives outside the game:

```
┌─────────────────────────────────────────────┐
│   Rust injector + C++ payload (injector)    │
│  · Process detection / version recognition  │
│  · Spawns & owns the core process; hands    │
│    its endpoint to the agent via config     │
│  · Injects the agent via payload.dll (JNI)  │
├─────────────────────────────────────────────┤
│          Java 8 agent (agent)               │
│  · One ASM hook: Display.update()           │
│  · MappingContext (cross-version resolver)  │
│  · Orchestration only — zero algorithms     │
├─────────────────────────────────────────────┤
│          Version Adapter (adapter)          │
│  · 50 modules, one .kt file each            │
│  · Judgement + marshalling + platform       │
│    side effects only                        │
│  · forge/v1_8_9 · fabric/v1_21              │
├─────────────────────────────────────────────┤
│   Thin IPC stub (ipc) + models (contract)   │
│  · 384-byte fixed frames over TCP loopback  │
│  · 1 ms deadline, per-op degradation        │
├─────────────────────────────────────────────┤
│  ★ Algorithm Core Process (core-rs, Rust)   │
│  · All strategies & cross-tick state        │
│    machines — deterministic & replayable    │
│  · All randomness: 20 independent PCG32     │
│    streams, pinned by golden parity         │
│  · Kotlin core/ = the bit-exact oracle for  │
│    the parity suites (never shipped)        │
└─────────────────────────────────────────────┘
```

## Core Principles (Constitution)

1. **Safety First** - All modules default to human-limit behavior
2. **Debuggability** - Strategy/execution separation; the whole personality is replayable offline
3. **Strategy** - Dynamic adaptation with cloud-config support
4. **Assistance** - Player is the protagonist, modules are co-pilots

See `Doppel/docs/CONSTITUTION.md` for the full text.

## Project Structure

- `core-rs/` - the algorithm core: its own OS process (Rust, zero deps), all strategies / state machines / RNG
- `ipc/` - JVM-side thin pipe (`CoreStub`, `CoreSupervisor`, `Wire`, `Strategies`)
- `contract/` - pure data models & strategy interfaces
- `core/` - the Kotlin oracle twin of core-rs (parity tests only; never packaged into agent.jar)
- `safety/` - SetbackTracker & auto-disable wrappers
- `agent/` - Java 8 agent: orchestration, mappings, the single render hook
- `adapter/` - 50 modules (`common/`) + `forge/v1_8_9` + `fabric/v1_21`
- `injector/` - Rust injector + C++ payload; owns the core process lifecycle
- `hud/` - external overlay process (the offline indicator lives here, not in the JVM)
- `mappings/` - JSON mapping files for cross-version compatibility
- `config/` - hot-loadable strategy configurations

## Implemented Modules

50 modules across combat (21) / movement (10) / player (7) / render (11) / world (1). Highlights:

- **Combat family**: Velocity, AimAssist, AutoClicker, TriggerBot, Reach, HitSelect, KeepSprint, LagRange, Backtrack, SprintReset / JumpReset / SuperKnockback, WTap / STap / ADTap, AutoBlock / BlockHit, KnockbackDelay / KnockbackDisplace…
- Every probability, time window and state machine lives in `core-rs`; the modules carry **no algorithms** and no `kotlin.random` (zero occurrences across the adapter, 2026-11-08).

Full catalog: root `README.md` § Module Catalog.

## Building

```bash
# JVM side (Gradle): contract, ipc, safety, core (oracle), agent, adapter
./gradlew build

# Algorithm core (Rust)
cd core-rs
cargo build --release
cargo test                       # unit tests: strategies, wire, rng, panic containment
cargo run -- --selftest          # internal consistency checks
cargo run -- --emit-golden golden  # regenerate the parity fixtures the JVM suites replay
./target/release/doppel-core --bench  # loopback round-trip latency benchmark

# Parity suites on the JVM side replay core-rs/golden/ bit for bit
./gradlew :core:test
```

## Usage

**Production (Windows)**: run the injector. It spawns `doppel-core` (endpoint via `doppel-config.properties`), finds the javaw process, injects `agent.jar` via `payload.dll`, and opens the WebUI panel.

**Development fallback**: if no endpoint was published and `-Ddoppel.core.path` names the binary, `CoreSupervisor.spawnDev()` starts the core locally — test-only; a release build never sets it.

The old "place a jar in `.minecraft/mods`" mode is gone: this client is injection-first, and the algorithm process is owned by the injector, not the game JVM.

## Configuration

In-game HUD stays minimal; module control lives in the **WebUI panel** (`adapter/common/webui`, port 4173, per-install bearer token). Strategy defaults are hot-loadable JSON in `config/`:

```json
{
  "mode": "LEGIT",
  "fov": { "horizontal": 120, "vertical": 60 },
  "smoothness": 0.85,
  "noiseIntensity": 0.05
}
```

## Safety Features

- **Golden parity gate**: same seed → same decisions, diff == 0 (500+ rows per module, real-socket E2E)
- **Deterministic RNG**: 20 PCG32 streams in the core, bit-identical to the JVM twin
- **Safety Wrapper**: auto-disable on repeated failures / setbacks
- **Degradation by design**: every op declares its safe direction on a missed deadline or a dead core

## License

GPLv3 - Open source, community-driven development

## Contributing

1. Fork the repository
2. Create a feature branch
3. `cargo test` + `./gradlew build` — parity suites must stay green
4. Submit a PR with clear description

Guidelines: `Doppel/docs/CONTRIBUTING.md`

---

**Sandwich Architecture**: "Algorithms in core, logic in modules, execution in adapter" — and since 2026-11 the core is a *process*, not a library.
