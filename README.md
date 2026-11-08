🥪 Doppel

> A Minecraft Ghost Client built with the Sandwich Architecture — clean separation between core logic and platform-specific code.

[Discord](https://discord.gg/Sq4rWn4JG) · [GitHub](https://github.com/gtiernotsoldier/Switch_Client_alpha_01)

---

🥪 Sandwich Architecture · A Modern Ghost Client Design Manifesto

Why We Exist

Anti-cheat systems (Grim, Polar, Vulcan, Watchdog) are becoming smarter than ever. They no longer detect individual cheat signatures; instead, they identify non-human behavioral patterns through timing analysis, kinematic modeling, and machine learning. The traditional model of "feature stacking, fixed thresholds, and brute-force bypassing" has failed — those clients are getting banned en masse.

We need an entirely new design philosophy: not fighting signatures, but imitating humans; not pursuing extreme effects, but pursuing legitimate survival.

Thus, the Sandwich Architecture was born.

---

📜 Core Philosophy: A "Modern Constitution for Anti-Cheat Evasion"

We firmly believe that a ghost client capable of long-term survival must adhere to the following four fundamental principles:

1. ️ Safety First

- Legitimate by Default: All modules' default configurations must remain within human behavioral limits, never producing supernatural movement or input.
- Conditional Triggering: Modules only activate in specific tactical scenarios (e.g., `onlyGround`, `onlyMoveForward`, `onlyWhenTargetGoesBack`), avoiding permanent exposure.
- Soft Boundaries: All modifications should be "gentle, gradual, and with random perturbation." For example, AimAssist only softly pulls the crosshair to the edge of the hitbox, rather than locking onto the center.
- Removal of Brutal Modules: We absolutely do not include features that blatantly violate game physics, such as KillAura, Fly, Speed, or Jesus.

2. 🔍 Enhanced Debuggability

- Separation of Strategy and Execution: Core algorithms (Core) and the Minecraft adapter layer (Adapter) are fully decoupled, allowing Core to be unit-tested and simulated offline.
- Logging and Replay: The adapter layer can record per-tick state, decisions, and network packets, facilitating post-kick analysis and review.
- Hot-Loading of Strategies: All risk parameters (knockback retention rate, CPS range, trigger conditions) are defined via JSON configuration files, supporting runtime modification and cloud-based hot updates without recompilation.
- Unified Condition Engine: All modules share a `TriggerCondition` system, avoiding repetitive if-statements and enabling centralized debugging.

3. 🧠 Superior Strategy

- Dynamic Adaptation: Module behavior adjusts in real-time based on distance, player state, target actions, and server anti-cheat fingerprints. For example, AutoClicker's CPS varies with target distance, and Velocity only activates when the enemy retreats.
- Pluggable Strategies: The `AnticheatDetector` identifies the current anti-cheat type (Grim/Vulcan/Watchdog) and automatically loads the corresponding JSON strategy pack. Different anti-cheats have different "sweet spot parameters."
- Global Behavior Planning: All network packets are managed by a unified scheduler (`PacketScheduler`), simulating real network latency, packet loss, and out-of-order delivery to ensure timing consistency.
- Cloud Strategy Library: Strategy configuration files can be hosted in the cloud, with the client automatically pulling the latest version upon startup, enabling "hot update" evasion.

4. 🤝 Better Assistance

- The Player is the Protagonist: All modules act as "co-pilots," not replacing player decisions. AimAssist does not auto-select targets, AutoClicker only optimizes rhythm when the player holds left-click, and WTap only activates when the player moves forward.
- Reducing Repetitive Labor: Modules only handle mechanical, error-prone operations (such as auto-restocking, tool switching, and bridge sneak), allowing players to focus on tactics and aiming.
- Natural Interaction: Module outputs must carry "human imperfection" — random perturbation, reaction delay, overshoot, and jitter. Let the anti-cheat think, "This is a high-ping but skilled player."

---

🧱 Architecture Overview: Sandwich Layers

```
┌─────────────────────────────────────────────┐
│     C++/Rust Injector (injector)            │
│  · Process detection / version recognition  │
│  · Spawns & owns the core process; hands    │
│    its endpoint to the agent via config     │
│  · Inject Agent via payload.dll (JNI)       │
├─────────────────────────────────────────────┤
│          Java Agent (agent)                 │
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
│  · A separate OS process — a JVM dump       │
│    reveals no heuristic                     │
└─────────────────────────────────────────────┘
```

---

📦 Project Structure

```
Doppel/
├── LICENSE                       # GPLv3
├── README.md                     # Reference implementation guide
├── build.gradle.kts              # Gradle multi-module build
├── settings.gradle.kts
├── gradlew                       # Gradle wrapper
├── gradle/wrapper/
│   └── gradle-wrapper.properties
│
├── contract/                     # Pure data models & strategy interfaces
│                                 #   (PlayerState, TargetState, VelocityContext,
│                                 #    PlatformCommand, Vec3 …)
│
├── ipc/                          # JVM-side thin pipe to the core process
│   └── src/main/kotlin/io/doppel/ipc/
│       ├── CoreStub.kt           #   framing, seq correlation, 1 ms deadline
│       ├── CoreSupervisor.kt     #   pure connector in production (the injector
│       │                         #   owns the process; spawnDev is test-only)
│       ├── Wire.kt               #   wire enums mirrored from core-rs
│       └── Strategies.kt         #   the single factory the adapter asks
│
├── core-rs/                      # ★ The algorithm core — its own OS process
│                                 #   (Rust, zero third-party dependencies)
│   ├── src/strategy/             #   24 files: every strategy, state machine and
│   │                             #   RNG sampler (aim, click, velocity, tap,
│   │                             #   combat, keep_sprint, lag_range, eagle …)
│   ├── src/rng.rs                #   PCG32, bit-identical to DecisionRng.kt
│   ├── src/session.rs            #   20 RNG streams + per-module machines + op dispatch
│   ├── src/wire.rs / codec.rs    #   384-byte frames + append-only op table
│   ├── src/transport.rs          #   TCP loopback / UDS behind one Conn trait
│   └── golden/                   #   42 TSVs / 36k rows of bit-level parity fixtures
│
├── core/                         # Kotlin oracle — the reference twin of core-rs
│   └── src/                      #   Keeps the parity suites honest; never packaged
│                                 #   into agent.jar (CI guard: io/doppel/core
│                                 #   class count in the jar must be 0)
│
├── safety/                       # SetbackTracker & auto-disable wrappers
│
├── adapter/
│   ├── common/                   # Cross-version shared adapter layer
│   │   └── src/main/kotlin/io/doppel/adapter/common/
│   │       ├── api/              # EventBridge, IEventBridge, IStateExtractor,
│   │       │                     #   IMappingContext, KeyCode, KeyTranslator
│   │       ├── hud/              # HudStateWriter (v3 HUD state-file contract)
│   │       ├── insight/          # BattleInsight (display-only assistance)
│   │       ├── module/           # Module base, Category, ModuleRegistry
│   │       │   ├── combat/       # 21 modules (see catalog below)
│   │       │   ├── movement/     # 10 modules
│   │       │   ├── player/       # 7 modules
│   │       │   ├── render/       # 11 modules (+ KeystrokesLayout helper)
│   │       │   └── world/        # 1 module (FastPlace)
│   │       ├── option/           # ModuleOptions, OptionMeta, Delegates, ConfigManager
│   │       ├── render/           # GL11Bridge, FontRendererBridge, GLConstants,
│   │       │                     #   OverlayRenderer, RenderContext
│   │       ├── ui/               # RenderUtils, Theme
│   │       └── webui/            # WebUIServer, ConfigStore, LanHelper
│   │   └── src/main/resources/doppel/webui/index.html   # Aurora panel
│   │
│   ├── forge/v1_8_9/             # Forge 1.8.9 translation layer
│   │   └── src/main/kotlin/io/doppel/adapter/forge/v1_8_9/
│   │       ├── ForgeBootstrap.kt / ForgeEventBridge.kt / ForgeStateExtractor.kt
│   │       ├── ForgePacketInterceptor.kt
│   │       └── ForgeGL11Bridge.kt / ForgeFontRendererBridge.kt
│   │
│   └── fabric/v1_21/             # Fabric 1.21 translation layer
│       └── src/main/
│           ├── kotlin/io/doppel/adapter/fabric/v1_21/
│           │   ├── FabricBootstrap.kt / FabricEventBridge.kt / FabricStateExtractor.kt
│           │   └── FabricVelocityInterceptor.kt
│           ├── java/.../mixin/ClientPlayNetworkHandlerMixin.java
│           └── resources/        # fabric.mod.json, doppel.mixins.json
│
├── agent/                        # Java Agent (bytecode manipulation)
│   └── src/main/java/io/doppel/agent/
│       ├── Agent.java            # Instrumentation entry point
│       ├── Transformer.java      # Class-loading-time transforms
│       ├── MappingLoader.java    # Mapping JSON loader + cache
│       └── RenderBridge.java     # Reflection bridge to render APIs
│
├── config/                       # Hot-loadable JSON strategy configs
│   ├── default_aim.json          # AimAssist default (LEGIT mode, FOV, smoothness, noise)
│   ├── default_velocity.json     # Velocity default
│   ├── battle_insight.json       # BattleInsight display config
│   └── presets/                  # aggressive.json / invisible.json / seasoned.json
│
├── mappings/                     # Semantic key → MC member mapping (JSON)
│   ├── forge/v1_8_9.json         # Forge 1.8.9 (SRG + MCP names)
│   ├── fabric/                   # v1_20_1.json baseline + per-version deltas
│   └── verify/                   # Mapping verification fixtures
│
├── injector/                     # Rust injector + C++ payload — OWNS the core
│   ├── CMakeLists.txt            #   process lifecycle (resource.rs::launch_core
│   ├── Cargo.toml / build.rs     #   spawns doppel-core BEFORE injection; the
│   ├── src/                      #   endpoint travels in doppel-config.properties)
│   │                             #   Rust: flow / panel / window / ui / inject /
│   │                             #   process / resource; C++: main / inject / payload
│   └── resources/                #   agent.jar, agent.rc
│
├── hud/                          # External overlay process (Rust) — the offline
│                                 #   indicator lives here, not in the JVM
├── scripts/                      # generate_mappings.py, verify_mappings.mjs,
│                                 #   ipc_latency_bench.c, HUD mockups
├── tickets/                      # Open P0 tickets (injection, classloader)
│
└── docs/                         # Deep documentation (the two architecture maps
    │                             #   live in the repo root's own docs/)
    ├── IPC-PROTOCOL.md           # Wire v1 byte tables + runbook
    ├── PROTECTION.md             # Hardening roadmap (§9: the process split)
    ├── CONSTITUTION.md           # The Sandwich Constitution
    ├── CONTRIBUTING.md           # Contribution guidelines
    ├── CLIENT-AUTHORITY-AUDIT.md # Which advantages are truly undetectable
    └── hud-contract-v3.md        # HUD state-file contract v3

../docs/ARCHITECTURE.md           # Architecture map, Chinese (repo root)
../docs/ARCHITECTURE.en.md        # English mirror

.github/workflows/ci.yml          # CI: CMake payload.dll build pipeline
```

---

📦 Module Catalog (50 modules implemented)

All modules live in `adapter/common`, one `.kt` file each. Each module declares typed options via property delegates and can be toggled/configured from the WebUI panel. Combat/movement decision modules carry **no algorithms of their own** — every probability, time window and state machine lives in the core process (`core-rs`).

| Category | Modules |
|---|---|
| Combat (21) | ADTap, AimAssist, AutoBlock, AutoClicker, Backtrack, BlockHit, ClickAssist, DelayRemover, HitSelect, JumpReset, KeepSprint, KnockbackDelay, KnockbackDisplace, LagRange, Reach, SprintReset, STap, SuperKnockback, TriggerBot, Velocity, WTap |
| Movement (10) | InstantStop, InvMove, NoJumpDelay, NoKeyboardFix, NoMouseFix, NullMove, Sprint, Strafe, StrafeAssist, StrafeFix |
| Player (7) | AntiBot, AutoTool, BridgeAssist, Eagle, FakeLag, TargetFilter, Teams |
| Render (11) | Freelook, Fullbright, HUD, JumpStatus, JumpTiming, Keystrokes, KnockbackDisplay, NoHurtCam, Speedometer, VelocityDisplay, WebUI |
| World (1) | FastPlace |

---

🧠 Core Strategy Implementations

Since the P6 split the strategy implementations the modules bind to live in **core-rs** (Rust, its own OS process); the Kotlin `core/` tree remains only as the bit-exact oracle for the parity suites. Modules ask through `Strategies` (ipc) and never call an algorithm in-VM:

| Domain | core-rs implementation | Wire ops |
|---|---|---|
| Aim | `strategy/aim.rs` (LEGIT / NORMAL / LINEAR / SELF_ADAPTIVE) + `geom.rs` | structured frames |
| Click | `strategy/click.rs` (probabilistic + cooldown crit FSM) | structured frames |
| Velocity / KnockbackDelay | `strategy/velocity.rs` (retention, sprint brace, delay queue) | `VEL_BRACE`, `VEL_DELAY_ENQUEUE/PUMP` |
| Movement | `strategy/movement.rs` (SOCD / strafe / sliding) | `MOV_*` ops 0–2 |
| Tap family | `strategy/tap.rs` + `tap_sample.rs` / `adtap_sample.rs` (own RNG streams) | `TAP_*` ops 0–9 |
| Trigger engine | `strategy/combat.rs` (trigger / arbiter / predictor / range / decide) | `CBT_*` ops 0–6 |
| Block family | `autoblock_sample.rs`, `blockhit_sample.rs` (chance & timing draws, own streams) | `CBT_AUTOBLOCK_CHANCE`, `CBT_BLOCKHIT_SAMPLE` |
| Reach | `reach.rs` + `reach_sample.rs` | `REACH_INTERSECT`, `PLAYER_REACH_SAMPLE` |
| Player family | `eagle.rs`, `eagle_place.rs`, `backtrack.rs`, `knockback_displace.rs`, `click_assist.rs`, `hit_select.rs`, `sprint_reset.rs`, `jump_reset.rs`, `super_knockback.rs`, `keep_sprint.rs`, `lag_range.rs` | `PLAYER_*` ops 0–13 |

Every port is pinned by a bit-level golden replay (500+ rows per module) plus a real-socket E2E; the full evidence table is `Doppel/docs/IPC-PROTOCOL.md` §9.

---

📦 Module Design Examples (Following the Constitution)

Module	Traditional Approach	Sandwich Approach	
AimAssist	Lock onto head/center, linear smoothing	Only softly pull back to hitbox edge when crosshair deviates, with jitter and reaction delay	
AutoClicker	Fixed CPS, uniform randomness	Distance-adaptive CPS, gamma distribution, only active when player attacks	
Velocity	Fixed retention rate 0%	Random range (20-60%), conditional triggering, simulating packet loss	
WTap	Fixed tick packet sending	Random ticks + probability + only triggers when moving forward	
Backtrack	Fixed delay	Dynamic delay following network distribution, random packet loss, coordinated with global scheduler	
Disabler	Single exploit abuse	Dynamic strategy library loading, multiple bypasses randomly switched, behavior legitimization	
Reach	Fixed extension	Small bounded extension, active only in specific combat states, randomized	
AutoBlock	Hold-block forever	Timed block windows synchronized with attack rhythm and opponent swing	
TriggerBot	Instant attack on crosshair	Latency-masked attack timing with human reaction delay	
Strafe	Perfect movement lock	Assists only when the player is already strafing; never auto-controls direction	

---

🔧 Four-Layer Architecture in Detail

Layer	Language	Responsibility	Key Components	
Injector	C++ / Rust	Process detection, version recognition, Agent injection — and it spawns & owns the core process	Process enumeration, remote thread, version probing, payload DLL, resource.rs::launch_core	
Agent	Java	Orchestration only — one hook (Display.update), mappings, tick/key threads	ASM, MappingContext, MappingLoader, RenderBridge	
Core process	Rust	**All algorithms, state machines and randomness** — deterministic, replayable, golden-parity pinned	24 strategy files, 20 PCG32 streams, session state, wire/codec/transport (core-rs/)	
IPC stub	Kotlin	Thin pipe: framing, seq correlation, 1 ms deadline, per-op degradation	CoreStub, CoreSupervisor, Wire, Strategies (ipc/)	
Adapter	Kotlin	Version adaptation (1.8.9 Forge / 1.21 Fabric) + judgement & platform side effects	50 modules, EventBridge, WebUIServer, render bridges	

Core Principles:
- Algorithms in the core **process**: All decision logic (knockback modification, rotation calculation, CPS generation, every probability, window and cross-tick memory) lives in core-rs — out of process, unit-testable, and replayable bit for bit. A JVM dump reveals no heuristic.
- Logic in Modules: Each adapter module is a complete file that samples game-context facts, marshals them across the wire, and materializes the returned instruction.
- Execution in Adapter: Specific packet sending, motion modification, and key simulation are completed by the adapter layer; the core never knows what Minecraft is.

---

️ Four Major Infrastructures (Cross-Cutting Concerns)

Infrastructure	Function	Implementation Location	Status	
Mapping Library + Cache	Cross-version class/method/field access, zero-reflection startup	Agent MappingLoader + JSON (forge/v1_8_9.json, fabric/v1_20_1.json + deltas)	✅ Implemented	
Condition Engine	Unified trigger rules (onlyGround, onLook, chance, delay, ticks)	core-rs `condition.rs` (full port) + Kotlin oracle twin	✅ Implemented	
Noise Perturbation	Mandatory randomization (Gaussian/uniform distribution) on all strategy outputs	core-rs RNG: 20 independent PCG32 streams on the decision path	✅ Implemented	
Global Packet Scheduler	Manages all network packet sending order, delay, and packet loss simulation	Core network/PacketScheduler	✅ Implemented	
Safety Wrapper	Auto-disable a module after repeated failures / suspicious patterns	Core safety/SafetyWrapper + SetbackTracker	✅ Implemented	
Replay Logging	Per-tick state, decision, and packet recording for post-kick review	Core logging/ReplayLogger	✅ Implemented	

These infrastructures are transparent to modules; module developers only need to declare configurations without repetitive implementation.

> Since the P6 split, anything on the decision path that this table calls "Core" is the **core-rs process**; the Kotlin `core/` tree is the parity oracle and ships nowhere.

---

⚡ Key Components

EventBridge (Singleton)
The central nervous system. Modules call directly:

```kotlin
EventBridge.setPlayerRotation(Vec2(yaw, pitch))
EventBridge.applyMotion(Vec3(x, y, z))
EventBridge.onVelocityPacket(ctx)
```

The platform registers as the handler at startup — modules never know which platform they are running on.

MappingContext
A Java reflection-based mapping layer that resolves Minecraft classes/methods/fields through semantic keys. Zero hardcoded MC class names in code.

```kotlin
MappingContext.getFieldValue(player, "forge:entity_posX")
MappingContext.invokeMethod(world, "fabric:world_getEntityByID", entityId)
```

Platform Commands
Velocity processing returns sealed commands:
- `ModifyMotion` — replace motion values
- `CancelPacket` — completely drop the packet
- `ClickBurst` — send rapid attack packets
- `Pass` — let original values through

Render Abstraction (GL11Bridge)
A version-agnostic rendering layer so the HUD/overlay stack never depends on a specific MC version:
- `GL11Bridge` — GL 1.1 immediate-mode texture/image calls (Forge 1.8.9 LWJGL2 implementation via reflection)
- `FontRendererBridge` — font rendering abstraction (vanilla pixel font drop-in)
- `OverlayRenderer` + `RenderContext` — frame-scoped render context; the in-game HUD is the only overlay
- `Theme` / `RenderUtils` — shared Aurora visual tokens and drawing utilities

Config System
- `ModuleOptions` + `OptionMeta` — typed module options (BOOLEAN / FLOAT / INT / PROBABILITY / CHOICES / STRING) with property delegates
- `ConfigManager` — hot-loadable JSON configs in `config/`
- `KeyCode` / `KeyTranslator` — cross-platform key handling for module keybinds

---

🌐 WebUI Control Panel (Aurora)

Configuration and module control moved from an in-game GUI to a **browser-based panel** shipped with the client — a zero-render-thread-cost design:

- `WebUIServer` (adapter:common, JDK built-in `com.sun.net.httpserver`) — dedicated daemon thread pool, **never touches the MC render thread**; idle cost is zero
- Serves the **Aurora** panel (`index.html`) on port `4173`, bound to `0.0.0.0` for LAN access
- **Per-install Bearer token** (16 random chars, persisted in `doppel-config.json`) protecting every `/api/*` endpoint
- Endpoints: `GET /api/modules` (full enumeration incl. disabled modules), `POST /api/modules/{name}/toggle`, `POST /api/modules/{name}/keybind`, `POST /api/options`, `GET/POST /api/config` (export/import)
- **Config persistence**: every option change is written to disk and re-applied on next launch
- **Keybinds**: bindable per module from the panel (GLFW key codes, Esc to clear)
- Panel address + token are printed to the injector console and payload/agent logs only — deliberately **kept out of the in-game HUD** to prevent leaks via recording/streaming/screenshots

The in-game `HUD` module remains the single in-game overlay (module state cards); the ClickGUI has been removed in favor of this panel.

---

🎮 Combat Module Example (Velocity)

Core process (Rust, `core-rs/src/strategy/velocity.rs`) — out-of-process, stateful per session:

```rust
pub fn decide(ctx: &VelocityContext, cfg: &VelocityConfig, rng: &mut Pcg32) -> VelocityResult
// gate chain + retention draw; the RNG stream lives in the session, so a
// golden replay with the same seed reproduces every decision bit for bit
```

Adapter layer (1.8.9) — judgement, marshalling, materialization; no algorithm in sight:

```kotlin
fun onVelocityPacket(ctx: VelocityContext) {
    val outcome = Strategies.velocity(ctx, buildConfig())   // → CoreStub → wire → core-rs
    when (val cmd = outcome.result) {                       // 1 ms deadline inside
        is VelocityResult.Modify    -> EventBridge.pendingMotion(cmd.motion)
        is VelocityResult.Cancel    -> dropPacket(ctx.packetHandle)
        is VelocityResult.ClickBurst -> sendBurst(cmd)
        else -> {}                                          // Pass: vanilla applies it
    }
}
```

Supported modes: Legit (random range + conditional triggering), Delay (packet delay), Click (auto-clicking).
Conditions: `onlyMove`, `onlyMoveForward`, `onlyWhenTargetGoesBack`, `onlyGround`, `onLook`, `disabledInAir`.
Randomization: horizontal/vertical retention rate range, trigger probability, delay ticks.

---

🤝 Assistance Module: BattleInsight

A pure display module that does not modify game behavior, providing:

- KB Win Rate: Real-time knockback distance comparison.
- HitSelect Timing: Indicates the 1-3 tick window after the opponent's attack.
- JumpReset Prompt: Suggests jumping when knocked back.
- Movement Prompt: Suggests W/A/S/D based on distance and terrain.

All prompts are based on real data collection (knockback packets, attack animations, player input) and cannot be detected by anti-cheat.

---

🌐 Cross-Version Strategy

- Forge 1.8.9: Independent adapter layer (`adapter/forge/v1_8_9`, MCP runtime names, mapping: `mappings/forge/v1_8_9.json`).
- Fabric 1.21: `adapter/fabric/v1_21` with `ClientPlayNetworkHandlerMixin` for packet interception.
- `adapter/common`: shared module codebase (modules, API bridges, render bridges, WebUI) used by every version.
- Mapping library + cache: C++/Rust injector detects version → Agent loads the corresponding JSON → adapter layer resolves classes/methods/fields through `MappingContext`, with zero hardcoded names. `scripts/generate_mappings.py` regenerates mapping JSON.

---

🧪 Testing

The core is tested without any Minecraft runtime, at three levels:

- **Rust unit tests** (`cargo test`) — every strategy, the wire codecs, the RNG, panic containment
- **Golden parity suites** (25 JVM suites, `core/src/test/.../ipc/`) — the Kotlin oracle replays the TSV fixtures emitted by `cargo run -- --emit-golden golden`; every decision and every state field must match bit for bit (500+ rows per module)
- **Real-socket E2E** (`CoreStubE2ETest`) — full framing → TCP loopback → core → decode round trips, tick for tick

Wiring tests on the adapter side assert that modules ask the core with the right ops and own no local randomness or cross-tick state.

---

📚 Documentation

- `docs/ARCHITECTURE.md` — architecture map, the code-truth lookup table (Chinese; `ARCHITECTURE.en.md` is the English mirror)
- `Doppel/docs/IPC-PROTOCOL.md` — wire v1: byte tables, ops, timing, degradation
- `Doppel/docs/PROTECTION.md` — hardening roadmap; §9 documents the process split
- `Doppel/docs/CONSTITUTION.md` — the Sandwich Constitution (four fundamental principles)
- `Doppel/docs/CLIENT-AUTHORITY-AUDIT.md` — which client-authority advantages are genuinely undetectable
- `Doppel/docs/CONTRIBUTING.md` — contribution guidelines
- `Doppel/README.md` — reference implementation guide (build & usage)

---

📋 Development Status

Alpha stage. Architecture complete, both platform translation layers implemented, cross-version shared module catalog filled in, WebUI panel operational.

✅ Completed
- Sandwich architecture structure
- **P6 process split (2026-11)**: all algorithms live in `core-rs`, a separate Rust process; the JVM keeps judgement + marshalling + side effects only, and the injector owns the core's lifecycle
- **All randomness migrated (2026-11-08)**: 20 independent PCG32 streams in the core; the 50 adapter modules contain zero `kotlin.random` calls
- **All state machines migrated (2026-11-08)**: KeepSprint / LagRange joined SprintReset, JumpReset, SuperKnockback, Eagle (AutoPlace), Backtrack, HitSelect, ClickAssist, KnockbackDisplace, Reach sampling and the Tap / Block families in the core (`PLAYER_*` ops 0–13)
- **Golden parity system**: 42 TSV fixtures / 36k rows, 25 JVM parity suites + a real-socket E2E per module
- Data models (PlayerState, TargetState, CombatContext, VelocityContext, PlatformCommand, SetbackEvent)
- EventBridge singleton pattern + AgentBridge (Fabric fallback)
- Property delegates (float, int, boolean, enum, triggerOptions, probability) + OptionMeta typing
- **50 modules** across combat (21) / movement (10) / player (7) / render (11) / world (1)
- Forge 1.8.9 platform layer (bootstrap, event bridge, state extractor, packet interceptor, GL/font bridges)
- Fabric 1.21 platform layer (bootstrap, event bridge, state extractor, velocity interceptor, packet mixin)
- MappingContext reflection system + MappingLoader cache + mapping JSON (forge 1.8.9, fabric 1.20.1 + deltas)
- **WebUI Aurora panel** (LAN access, bearer token auth, config persistence, keybinds, export/import)
- **In-game HUD** restored as the sole in-game overlay; ClickGUI removed in favor of WebUI
- Render abstraction (GL11Bridge, FontRendererBridge, OverlayRenderer, RenderContext)
- Safety infra (SafetyWrapper, SetbackTracker)
- CI pipeline (payload.dll build) + project docs (ARCHITECTURE / IPC-PROTOCOL / PROTECTION / CONSTITUTION / CONTRIBUTING)

In Progress
- More module refinements and balance tuning
- Target selection system hardening
- Cloud strategy library delivery (hot-update pipeline)
- Additional version adapters (Fabric 1.16–1.20)

---

🌍 Future Outlook

Anti-cheat will only get stronger, but we will not "break" them. Our goal is: to make Sandwich Architecture client behavior statistically indistinguishable from real human players.

We believe that assistance ≠ cheating. As long as we respect game rules, respect other players, and respect the anti-cheat baseline, ghost clients can exist long-term as a "training tool" and "assistive peripheral."

Welcome to join us in defining the next generation of ghost clients.

---

🔧 Development and Contribution Guide

1. Clone the repository: `git clone https://github.com/gtiernotsoldier/Switch_Client_alpha_01.git`
2. Build: Use Gradle to build the core, agent, and adapter modules separately.
3. Debug: Unit-test Core layer strategies; integration test in local Forge/Fabric environments.
4. Contribute: Read `docs/CONTRIBUTING.md` and `docs/CONSTITUTION.md` first. PRs are welcome to improve strategy algorithms, add new version adapter layers, and optimize noise models. However, brutal modules (KillAura, Fly, etc.) are absolutely prohibited.

---

License

This project is licensed under GPLv3. Any derivative work must be open-sourced and retain the original copyright notice. We welcome community forks and secondary development, but please abide by the spirit of this "constitution" and maintain the healthy ecosystem of ghost clients.

---

🤝 Contact Us

> "Algorithms in core, logic in modules, execution in adapter" — and since 2026-11 the core is a *process*, not a library: nothing a JVM dump reveals is a heuristic.

🔗 [Discord](https://discord.gg/Sq4rWn4JG) · [GitHub](https://github.com/gtiernotsoldier/Switch_Client_alpha_01)

Sandwich Core Team
