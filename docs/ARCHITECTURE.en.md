# Doppel Architecture Map (for Future Maintainers)

> This document is a **lookup table for the actual code**, not a pitch. It tells future maintainers: which files to touch when changing a module, which chain to follow, and where data comes from and goes.
> For the philosophy, see the root `README.md`; this document is only about **code reality** (aligned 2026-11, after `af1c7ca`).

## 0. One Line: Two Processes, the Will Lives Outside

```
javaw.exe                                doppel-core.exe (standalone algorithm process)
├─ agent.jar injected by the injector    ├─ all algorithms (24 strategy/*.rs files)
├─ adapter modules: judgement+marshal    ├─ all cross-tick state machines (KeepSprint/LagRange/…)
│  + platform writes                     ├─ all randomness (20 independent PCG32 streams)
└─ zero algorithms, zero kotlin.random,  └─ the "player personality": replayable, diffable,
   zero decision fields                     testable in isolation
          └──────── 384-byte fixed frames over TCP loopback ────────┘
```

Process ownership: **the core process belongs to the injector** (spawned before injection; the endpoint travels in `doppel-config.properties`). The game JVM is a *pure connector* — `CoreSupervisor` never spawns, supervises or kills the process in the production path. Byte tables and the runbook live in `Doppel/docs/IPC-PROTOCOL.md`.

## 1. Layers and Module Directories (Current 2026 Code Reality)

```
Doppel/
├── injector/            # Rust/C++ DLL injector: process detection, version id, payload injection
│                        #   + resource.rs::launch_core — spawns the core before injecting
│                        #     (--port-file is the readiness signal)
├── agent/               # Java 8 agent: orchestration only. ASM hooks exactly one point: Display.update()
├── core-rs/             # ★ the algorithm process (Rust, zero third-party deps)
│   ├── src/strategy/    #   24 files: every strategy, state machine and RNG sampler
│   ├── src/rng.rs       #   PCG32 (bit-for-bit identical to the JVM's DecisionRng; golden vectors pin both)
│   ├── src/session.rs   #   session state: 20 RNG streams + per-module machines + op dispatch
│   ├── src/wire.rs      #   frame format (384B fixed) + Module/Kind/Action enums
│   ├── src/codec.rs     #   the op table (append-only) + codecs
│   ├── src/transport.rs #   TCP loopback / UDS behind one Conn trait
│   └── golden/          #   42 TSVs, 36k+ rows of bit-level parity fixtures
├── ipc/                 # JVM-side pipe: CoreStub / CoreSupervisor / Wire / Strategies
├── contract/            # pure data models & strategy interfaces (PlayerState, VelocityResult…)
├── core/                # the Kotlin oracle: reference twin of core-rs (parity tests only; NOT in agent.jar)
├── safety/              # safety wrappers (SetbackTracker, …)
├── adapter/
│   ├── common/          # cross-version shared: 50 modules + EventBridge hub + WebUI
│   │   ├── api/         #   EventBridge (hub), IEventBridge, IStateExtractor
│   │   ├── module/      #   Module base + 50 modules (combat 21 / movement 10 / player 7 / render 11 / world 1)
│   │   ├── render/      #   OverlayRenderer, RenderContext
│   │   └── webui/       #   WebUI config server (0.0.0.0:4173, bearer token)
│   ├── forge/v1_8_9/    # Forge 1.8.9 landing layer (ForgeBootstrap / ForgeEventBridge / ForgeStateExtractor / ForgePacketInterceptor)
│   └── fabric/v1_21/    # Fabric 1.21 landing layer (symmetric)
├── mappings/forge/v1_8_9.json   # semantic key → SRG/MCP name mapping
├── hud/                 # external overlay (Rust process; the offline indicator lives here per PROTECTION §9.4, not in the JVM)
├── tickets/             # P0 tickets (injection false-success, ClassLoader signature)
└── docs/                # IPC-PROTOCOL / PROTECTION / CONSTITUTION / CONTRIBUTING / CLIENT-AUTHORITY-AUDIT / hud-contract-v3
```

Gradle modules (`settings.gradle.kts`): `contract` → `ipc` → `safety` → `core` (oracle) → `agent` → `adapter:common` → `adapter:forge:v1_8_9` (Fabric needs Loom; commented out by default). core-rs is a separate cargo project.

## 2. Threads and Processes (Prerequisite for Understanding All Timing)

| Thread/Process | Who runs it | Responsibility |
|---|---|---|
| **JVM main thread (MC render)** | `Display.update()` hook → `RenderBridge.onFrame` → `ForgeBootstrap.render()` | Writes real keys, modifies motion, materializes synthesized state; KeepSprint is the only frame-driven op (idle frames short-circuit locally, never cross the wire) |
| **JVM background 20Hz** | `Agent.java` → `ForgeBootstrap.tick()` | **Module asking** (read PlayerState → frame → wire → apply the returned instruction to EventBridge) |
| **JVM Netty thread** | `ForgePacketInterceptor.channelRead()` | Intercepts `S12/S27` → `EventBridge.onVelocityPacket` (self) + `notifyEntityVelocity` (other entities) |
| **core-rs process** | one thread per stub: `read → session.handle → write` | All algorithms, state machines and RNG advancement; idles blocked in read, ~zero CPU |

**Iron rule 1**: decisions are *asked for* on the background/render threads, materialization is *written* only on the main thread (see `ForgeEventBridge.applySyntheticInput`).
**Iron rule 2**: judgement lives in the module, **the will lives in the core**. The module reports facts ("attacking / sprinting / distance 3.2"); the core answers what to do. Probability, timing, windows and memory never live on the JVM.

## 3. Startup Chain (How the Client Comes Up)

```
Rust injector
  1. launch_core(): extract embedded doppel-core.exe → --listen tcp:127.0.0.1:0 --port-file …
     → core binds, writes the port file (readiness) → endpoint goes into doppel-config.properties
  2. find javaw window → version detection → payload.dll (JNI) → agentmain("jni-attach")
  3. Agent reads the endpoint → CoreSupervisor.connect() → Hello/HelloAck (token + seed derivation)
  4. ASM retransform Display.update() → RenderBridge.onFrame() every frame → ForgeBootstrap.render()
```

`agent/Transformer.java` hooks only `Display.update()`. Attack-method injection (KeepSprint's old bytecode plan) is deprecated; see the KeepSprint module comments.

> **Pitfall**: `ForgePacketInterceptor` uses `player.sendQueue` (`field_71174_a`) for the Netty handler, **not** `mc_netHandler` (`field_71453_ak` is a wrong mapping, reads null). Find the Netty channel by **field type** `io.netty.channel.Channel`. Pipeline injection must run via `channel.eventLoop().execute {}`.

## 4. Mapping-Driven (Key to Cross-Version)

- **Zero hardcoded MC class/field names** (except ~31 `Class.forName` leftovers).
- Every access goes through `MappingContext.getFieldValue(obj, "forge:xxx")` / `invokeMethod(...)`.
- Semantic keys live in `mappings/forge/v1_8_9.json`: `"class" + "field"/"method"` (SRG names) + `mcp` fallback.
- `joined.srg` only maps **obf→SRG** and does not tell which MCP name an SRG corresponds to; verify new mappings with FDP/LB Mixin `@Shadow` when necessary.

## 5. Data Flow: One Decision's Full Life (Velocity as Example)

1. **Intercept**: Netty thread receives S12 → checks `entityId == playerId` → `ForgeEventBridge.onVelocityPacket(ctx)`.
2. **Ask**: the Velocity module frames the snapshot (232B: f64-raw-bits position/motion, flags, config) → `Strategies.velocity()` → `CoreStub` across the wire.
3. **Decide**: core-rs `session.rs` dispatches by Module → `strategy/velocity.rs` runs the RNG stream + gate chain → Decision frame back.
4. **Materialize**: `PlatformCommand` (`ModifyMotion`/`CancelPacket`/`ClickBurst`/`Pass`) — `ModifyMotion` → `pendingMotion` applied on main thread; `CancelPacket` drops on the Netty thread; `ClickBurst` sends directly.
5. **Degrade**: 1 ms deadline miss → `Pass(original motion)` (**not** a replay of last tick's command); core death → reconnect next tick, this tick degrades.

Measured latency p50 6.70 µs / p99 12.50 µs (loopback) — 80× headroom against the deadline.

## 6. EventBridge: The Hub (Both Decoupling Point and the Only "God Object" Warning Area)

**It is correct** — a hub decoupling 50 modules from the platform is an architectural decision, not a mistake. What it needs is **boundary discipline**, not splitting into files.

### Admission Discipline (written at the top of the code for future followers)
> **EventBridge holds exactly two kinds of things:**
> 1. **Cross-thread/cross-module shared state** (`@Volatile`: synthetic inputs, physical keys, motion, knockback data, `crosshairTarget`, sprint claims).
> 2. **Platform callback decoupling** (`registerXHandler`: modules never touch MC directly).
> **Module-internal logic is forbidden here** — it belongs in that module's `.kt`; **cross-tick decision state is even more forbidden** — it belongs in core-rs.

### Sections (7 in current code)
1. Lifecycle & Tick → 2. Combat: Attack & Knockback → 3. Input: Keys & Synthetic → 4. Motion & Actions → 5. Target & Entity → 6. Render & Display → 7. Player Assist.

## 7. Module Lookup Table (Where Judgement / Algorithm / Materialization Reside)

**The algorithm column is entirely in core-rs** (op ids live in `core-rs/src/codec.rs`, `pub mod op`, append-only, never renumbered):

| Module | core-rs algorithm (op) | What stays in the module (judgement + materialization) |
|---|---|---|
| Velocity | `strategy/velocity.rs` (incl. KnockbackDelay: `VEL_DELAY_ENQUEUE/PUMP`) | S12 interception marshalling, `pendingMotion` |
| AimAssist | `strategy/aim.rs` + `geom.rs` | mouse delta sampling, rotation write |
| AutoClicker | `strategy/click.rs` | left-button sampling, attack marshalling |
| Movement family (Strafe/InstantStop…) | `strategy/movement.rs` (`MOV_SOCD/STRAFE/SLIDING`) | key-state judgement, synthetic input |
| WTap / STap | `strategy/tap.rs` + `tap_sample.rs` (`TAP_WTAP_SAMPLE`=7 / `TAP_STAP_SAMPLE`=8, own streams) | forward judgement, W/S key writes |
| ADTap | `adtap_sample.rs` (`TAP_ADTAP_SAMPLE`=9, own `rng_adtap`) | A/D judgement, key writes |
| TriggerBot | `combat.rs` (`CBT_TRIGGER/ARBITER/PREDICT/RANGE/DECIDE`) | gate-condition marshalling, attack |
| AutoBlock | `autoblock_sample.rs` (`CBT_AUTOBLOCK_CHANCE`=5, own stream) | sword/threat judgement, right-click & C08 |
| BlockHit | `blockhit_sample.rs` (`CBT_BLOCKHIT_SAMPLE`=6, own stream) | swing judgement, attack rhythm |
| Reach | `reach.rs` + `reach_sample.rs` (`REACH_INTERSECT` / `PLAYER_REACH_SAMPLE`=3) | ray marshalling, `objectMouseOver` write |
| Backtrack | `backtrack.rs` (`PLAYER_BACKTRACK_TICK`=1) | clock marshalling, packet-hold switch |
| KnockbackDisplace | `knockback_displace.rs` (`PLAYER_KBD_ATTACK`=2) | attack-moment sampling, yaw write |
| ClickAssist | `click_assist.rs` (`PLAYER_CLICK_ASSIST`=4; 13 predicates packed in flags) | predicate sampling, extra clicks |
| HitSelect | `hit_select.rs` (`PLAYER_HIT_TICK`=5 / `PLAYER_HIT_GATE`=6) | crit conditions, click gate |
| Eagle | `eagle.rs` (`PLAYER_EAGLE_TICK`=0); Eagle AutoPlace → `eagle_place.rs` (`PLAYER_EAGLE_PLACE`=7, own stream) | edge/pitch judgement, sneak & placement |
| SprintReset | `sprint_reset.rs` (`PLAYER_SPRINT_RESET`=8, own stream) | gate/range/hurt judgement, C0B send |
| JumpReset | `jump_reset.rs` (`PLAYER_JUMP_KNOCK`=9 / `PLAYER_JUMP_PUMP`=10, own stream) | internal conditions, frontal angle, jump queue |
| SuperKnockback | `super_knockback.rs` (`PLAYER_SUPER_KNOCKBACK`=11, own stream; 3-phase machine) | trigger/range judgement, C0B + sprint claim |
| **KeepSprint** | `keep_sprint.rs` (`PLAYER_KEEPSPRINT`=12, **first frame-driven op**, no RNG) | attack composite, forward arbitration, claimSprint + C0B |
| **LagRange** | `lag_range.rs` (`PLAYER_LAGRANGE`=13, no RNG, deterministic hold machine) | GUI/distance/sword judgement, single `packetLagActive` write |
| KnockbackDelay | the delay queue in `velocity.rs` (`VEL_DELAY_ENQUEUE/PUMP`) | packet marshalling, delay pump |
| HUD family (11 render modules + JumpTiming/KnockbackDisplay) | none (display-only, reads EventBridge) | local window timing/measuring (display semantics, not decisions) |
| TargetFilter / AntiBot / Teams | none (pure local filter predicates) | player/mob filtering → `isViableTarget` |

**General pattern**: a module = judgement (game-context predicates) + marshalling (framing) + materialization (translating the core's step into platform side effects). **Algorithms, probabilities, time windows, cross-tick state and RNG live exclusively in core-rs.** All 50 adapter modules have zero `kotlin.random` calls (achieved 2026-11-08).

### RNG Stream Inventory (20, in `session.rs`)

velocity / aim / click / tap (side selection) / wtap / stap / adtap / combat (trigger engine) / autoblock / blockhit / player / backtrack / kbd / reach / click_assist / hit_select / eagle_place / sprint_reset / jump_reset / super_knockback. KeepSprint and LagRange have no stream (deterministic). **Stream discipline**: one module's draws must never shift another module's replay.

### Who Owns State (One-Second Test)

Ask "does this field live across ticks?" — if yes, it belongs in the core. On module toggle the adapter sends a `Reset` frame (config kept, learned state forgotten) — skipping it does not fail loudly; it just lets the next enable inherit stale state.

## 8. Key File List (Most Frequently Touched When Fixing Bugs)

| What to change | Which file |
|---|---|
| Add a new module | `adapter/common/module/<category>/X.kt` + `AgentBridge.moduleEntries` (Fabric fallback) / `ForgeBootstrap` registration; the algorithm goes into `core-rs/src/strategy/` + a **new appended** op |
| Change an algorithm/probability/window | `core-rs/src/strategy/<name>.rs` + the twin `core/src/main/.../<Name>Logic.kt` (oracle) + **regenerate golden** |
| Change knockback/S12 behaviour | `ForgePacketInterceptor` + `EventBridge.onVelocityPacket` + `core-rs/src/strategy/velocity.rs` |
| Change ops / frame format | `core-rs/src/codec.rs` (op table) + `ipc/.../Wire.kt` (mirror) + `Doppel/docs/IPC-PROTOCOL.md` (byte table) |
| Change key materialization | `ForgeEventBridge.registerListeners()` + `applySyntheticInput()` |
| Change HUD display | `OverlayRenderer.render()` + `module/render/*.kt` (except the offline indicator — it lives in the external `hud/` process) |
| Change process lifecycle | `injector/src/resource.rs` (core spawn) + `ipc/.../CoreSupervisor.kt` (connector) |
| Add a mapping key | `mappings/forge/v1_8_9.json` (verify with SRG + FDP/LB Mixin) |
| Regenerate parity fixtures | `cd Doppel/core-rs && cargo run -- --emit-golden golden` (JVM ParityTests replay them directly) |

## 9. Known Pitfalls (Don't Step on These)

- The `mc_netHandler` mapping is broken (returns null) — use `player.sendQueue`.
- Netty pipeline operations must run on the event-loop thread; find the channel field by type.
- Background threads **must not** directly modify `mc.thePlayer.motion` / call entity methods — go through synthetic input; materialization happens on the main thread.
- **Op ids are append-only**: the stub and the core are two independently upgradable binaries; renumbering makes an old stub silently compute a different function.
- **f32 values in fixtures travel as raw bits**: values like `last_dist` are f32 on the JVM and must be bit-dumped into the TSV, never round-tripped through f64 decimals (same lesson as velocity's f64 rule).
- **core/ (Kotlin) is the oracle, not the product**: it is not packaged into agent.jar; changing a core-rs algorithm requires updating the oracle and regenerating golden, or the ParityTest goes red.
- Module re-enable **must** send `Reset` (`Strategies.resetPlayer()` etc. in `onEnable`/`onDisable`), or stale core state leaks into the new session.
- Every synchronous decision has a 1 ms deadline; on timeout, do the right thing for that op's degradation direction (table in `Doppel/docs/PROTECTION.md` §9.5.3), never replay last tick.
- Fabric and Forge each have their own `EventBridge.registerX` materialization; both platforms must implement a new API for it to be complete.

---

*Maintenance note: this file is a mapping table to the actual code; every time you refactor EventBridge/ForgeEventBridge/the injection chain/migrate a module into the core, update the corresponding sections here — don't let it go stale.*
