🥪 SwitchLite

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
│  · Load mapping library / cache files       │
│  · Inject Agent / deploy Fabric Mod         │
├─────────────────────────────────────────────┤
│          Java Agent (agent)                 │
│  · Class-loading-time bytecode modification │
│    (Javassist/ASM)                          │
│  · Provide MappingContext                   │
│    (cross-version class/method mapping)     │
│  · Cache serialization (avoid reflection    │
│    on every startup)                        │
├─────────────────────────────────────────────┤
│          Kotlin Core Library (core)         │
│  · Pure math / no Minecraft dependencies    │
│  · Strategy interfaces + implementations    │
│    (aim, click, velocity, keepsprint, tap)  │
│  · Algorithm implementations                │
│    (rotation, knockback, prediction, noise) │
│  · Condition engine (TriggerCondition)      │
│  · Data models (PlayerState, Vec3)          │
├─────────────────────────────────────────────┤
│          Version Adapter Layer (adapter)    │
│  · adapter/common (cross-version shared)    │
│  · forge/v1_8_9 (independent implementation)│
│  · fabric/v1_21 (version-difference mixins) │
│  · Each module = one .kt file               │
│  · Listen to Minecraft events → call core   │
│    strategies                               │
│  · Write decisions back to game             │
│    (motion, keypress, packet sending)       │
└─────────────────────────────────────────────┘
```

---

📦 Project Structure

```
SwitchLite/
├── LICENSE                       # GPLv3
├── README.md                     # Reference implementation guide
├── build.gradle.kts              # Gradle multi-module build
├── settings.gradle.kts
├── gradlew                       # Gradle wrapper
├── gradle/wrapper/
│   └── gradle-wrapper.properties
│
├── core/                         # Pure Kotlin logic — zero MC dependencies
│   └── src/main/kotlin/io/switchlite/core/
│       ├── algorithm/            # RotationCalculator, VectorOperations,
│       │                         #   GaussianNoise, NoiseGenerator, NoiseProvider
│       ├── condition/            # TriggerCondition, ConditionChecker
│       ├── decorator/            # NoiseDecorator (mandatory human-like noise)
│       ├── logging/              # CoreLogger, ReplayLogger (per-tick replay)
│       ├── model/                # PlayerState, TargetState, CombatContext,
│       │                         #   VelocityContext, PlatformCommand, SetbackEvent
│       ├── network/              # PacketScheduler (global packet scheduling)
│       ├── option/               # AimConfig, ClickMode, RandomRange, ProbabilityOption,
│       │                         #   TimingOptions, TriggerOptions
│       ├── safety/               # SafetyWrapper (auto-disable on repeated failures),
│       │                         #   SetbackTracker
│       ├── strategy/             # Strategy contracts + concrete implementations
│       │   ├── aim/              # AimStrategy, LegitAimStrategy,
│       │   │                     #   SelfAdaptiveAimStrategy, OvershootHelper
│       │   ├── click/            # ClickStrategy, ProbabilisticClickStrategy,
│       │   │                     #   CooldownClickStrategy, CritMode, OnItemUse,
│       │   │                     #   WeaponFilter
│       │   ├── velocity/         # VelocityStrategy, Legit/Delay/Click strategies
│       │   ├── keepsprint/       # KeepSprintStrategy, LegitKeepSprintStrategy
│       │   ├── tap/              # TapStateMachine
│       │   └── combat/           # CombatTrigger
│       └── util/                 # Vec2, Vec3, MathUtils
│   ├── src/main/java/io/switchlite/agent/
│   │   └── MappingContext.java   # Semantic key → MC member reflection resolver
│   └── src/test/kotlin/          # Unit tests: RotationCalculator, ConditionChecker,
│       └── ...                   #   TriggerOptions, MathUtils, Vec2, Vec3
│
├── adapter/
│   ├── common/                   # Cross-version shared adapter layer
│   │   └── src/main/kotlin/io/switchlite/adapter/common/
│   │       ├── api/              # EventBridge, IEventBridge, IStateExtractor,
│   │       │                     #   IMappingContext, KeyCode, KeyTranslator
│   │       ├── insight/          # BattleInsight (display-only assistance)
│   │       ├── module/           # Module base, Category, ModuleRegistry
│   │       │   ├── combat/       # 16 modules (see catalog below)
│   │       │   ├── movement/     # 6 modules
│   │       │   ├── player/       # 6 modules
│   │       │   ├── render/       # 5 modules (Fullbright, HUD, NoFOV, NoHurtCam, WebUI)
│   │       │   └── world/        # 1 module (FastPlace)
│   │       ├── option/           # ModuleOptions, OptionMeta, Delegates, ConfigManager
│   │       ├── render/           # GL11Bridge, FontRendererBridge, GLConstants,
│   │       │                     #   OverlayRenderer, RenderContext
│   │       ├── ui/               # RenderUtils, Theme
│   │       └── webui/            # WebUIServer, ConfigStore, LanHelper
│   │   └── src/main/resources/switchlite/webui/index.html   # Aurora panel
│   │
│   ├── forge/v1_8_9/             # Forge 1.8.9 translation layer
│   │   └── src/main/kotlin/io/switchlite/adapter/forge/v1_8_9/
│   │       ├── ForgeBootstrap.kt / ForgeEventBridge.kt / ForgeStateExtractor.kt
│   │       ├── ForgePacketInterceptor.kt
│   │       └── ForgeGL11Bridge.kt / ForgeFontRendererBridge.kt
│   │
│   └── fabric/v1_21/             # Fabric 1.21 translation layer
│       └── src/main/
│           ├── kotlin/io/switchlite/adapter/fabric/v1_21/
│           │   ├── FabricBootstrap.kt / FabricEventBridge.kt / FabricStateExtractor.kt
│           │   └── FabricVelocityInterceptor.kt
│           ├── java/.../mixin/ClientPlayNetworkHandlerMixin.java
│           └── resources/        # fabric.mod.json, switchlite.mixins.json
│
├── agent/                        # Java Agent (bytecode manipulation)
│   └── src/main/java/io/switchlite/agent/
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
│   ├── forge/v1_8_9.json         # Forge 1.8.9 (MCP names)
│   └── fabric/
│       ├── v1_20_1.json          # Fabric 1.20.1 baseline
│       └── deltas/               # Per-version delta mappings
│
├── injector/                     # C++/Rust dual-process injector
│   ├── CMakeLists.txt            # C++ payload build (payload.dll)
│   ├── Cargo.toml / build.rs     # Rust injector build
│   ├── src/                      # inject.cpp/h, main.cpp (+ Rust: inject.rs,
│   │                             #   main.rs, process.rs, resource.rs)
│   ├── src/payload/              # payload.cpp/h, attach_pipe.cpp/h, payload_log.h
│   └── resources/                # agent.jar, agent.rc
│
├── scripts/
│   └── generate_mappings.py      # Mapping JSON generator utility
│
└── docs/                         # Project documentation
    ├── ARCHITECTURE.md           # In-depth architecture walkthrough
    ├── CONSTITUTION.md           # The Sandwich Constitution (full text)
    └── CONTRIBUTING.md           # Contribution guidelines

.github/workflows/ci.yml          # CI: CMake payload.dll build pipeline
```

---

📦 Module Catalog (34 modules implemented)

All modules live in `adapter/common`, one `.kt` file each. Each module declares typed options via property delegates and can be toggled/configured from the WebUI panel.

| Category | Modules |
|---|---|
| Combat (16) | AimAssist, AutoBlock, AutoClicker, BlockHit, ClickAssist, DelayRemover, HitSelect, JumpReset, KeepSprint, Reach, STap, SprintReset, SuperKnockback, TriggerBot, Velocity, WTap |
| Movement (6) | NoJumpDelay, NoKeyboardFix, NoMouseFix, Sprint, Strafe, StrafeFix |
| Player (6) | AntiBot, AutoTool, BridgeAssist, Eagle, ParallaxStrike, Teams |
| Render (5) | Fullbright, HUD, NoFOV, NoHurtCam, WebUI |
| World (1) | FastPlace |

---

🧠 Core Strategy Implementations

The Core layer ships concrete strategy implementations — the ones the modules bind to:

| Domain | Implementations |
|---|---|
| Aim | `LegitAimStrategy` (soft pull to hitbox edge), `SelfAdaptiveAimStrategy` (distance/state adaptive), `OvershootHelper` (human overshoot modeling) |
| Click | `ProbabilisticClickStrategy` (gamma-distributed CPS), `CooldownClickStrategy` (1.9+ cooldown-aware), `CritMode`, `OnItemUse`, `WeaponFilter` |
| Velocity | `LegitVelocityStrategy` (random retention range + conditional triggers), `DelayVelocityStrategy`, `ClickVelocityStrategy` |
| KeepSprint | `KeepSprintStrategy`, `LegitKeepSprintStrategy` |
| Tap | `TapStateMachine` (STap/WTap timing state machine) |
| Combat | `CombatTrigger` (shared combat activation conditions) |

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
Injector	C++ / Rust	Process detection, version recognition, Agent injection / Fabric Mod deployment	Process enumeration, remote thread, version probing, payload DLL	
Agent	Java	Class-loading-time bytecode modification, providing cross-version mapping	Javassist / ASM, MappingContext, MappingLoader, cache serialization, RenderBridge	
Core	Kotlin	Pure math algorithms, strategy interfaces + implementations, condition engine, noise perturbation	AimStrategy, VelocityStrategy, TriggerCondition, NoiseProvider, SafetyWrapper	
Adapter	Kotlin	Version adaptation (1.8.9 Forge / 1.21 Fabric) + cross-version common modules	VelocityModule, AimModule, BattleInsight, WebUIServer, render bridges	

Core Principles:
- Algorithms in Core: All decision logic (knockback modification, rotation calculation, CPS generation) is fully decoupled from Minecraft and can be unit-tested.
- Logic in Modules: Each adapter layer module is a complete file that listens to events, extracts state, calls Core, and writes back to the game.
- Execution in Adapter: Specific packet sending, motion modification, and key simulation are completed by the adapter layer; Core is unaware.

---

️ Four Major Infrastructures (Cross-Cutting Concerns)

Infrastructure	Function	Implementation Location	Status	
Mapping Library + Cache	Cross-version class/method/field access, zero-reflection startup	Agent MappingLoader + JSON (forge/v1_8_9.json, fabric/v1_20_1.json + deltas)	✅ Implemented	
Condition Engine	Unified trigger rules (onlyGround, onLook, chance, delay, ticks)	Core TriggerCondition + ConditionChecker	✅ Implemented	
Noise Perturbation	Mandatory randomization (Gaussian/uniform distribution) on all strategy outputs	Core NoiseProvider + NoiseDecorator	✅ Implemented	
Global Packet Scheduler	Manages all network packet sending order, delay, and packet loss simulation	Core network/PacketScheduler	✅ Implemented	
Safety Wrapper	Auto-disable a module after repeated failures / suspicious patterns	Core safety/SafetyWrapper + SetbackTracker	✅ Implemented	
Replay Logging	Per-tick state, decision, and packet recording for post-kick review	Core logging/ReplayLogger	✅ Implemented	

These infrastructures are transparent to modules; module developers only need to declare configurations without repetitive implementation.

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
- **Per-install Bearer token** (16 random chars, persisted in `switchlite-config.json`) protecting every `/api/*` endpoint
- Endpoints: `GET /api/modules` (full enumeration incl. disabled modules), `POST /api/modules/{name}/toggle`, `POST /api/modules/{name}/keybind`, `POST /api/options`, `GET/POST /api/config` (export/import)
- **Config persistence**: every option change is written to disk and re-applied on next launch
- **Keybinds**: bindable per module from the panel (GLFW key codes, Esc to clear)
- Panel address + token are printed to the injector console and payload/agent logs only — deliberately **kept out of the in-game HUD** to prevent leaks via recording/streaming/screenshots

The in-game `HUD` module remains the single in-game overlay (module state cards); the ClickGUI has been removed in favor of this panel.

---

🎮 Combat Module Example (Velocity)

Core Layer:

```kotlin
interface VelocityStrategy {
    fun modifyVelocity(original: Vec3, player: PlayerState, target: TargetState?, config: VelocityConfig): Vec3
}
class DefaultVelocityStrategy : VelocityStrategy { /* pure math implementation */ }
```

Adapter Layer (1.8.9):

```kotlin
@EventTarget fun onPacket(event: PacketEvent) {
    val config = buildConfig()
    val modified = SandwichCore.velocityStrategy.modifyVelocity(original, playerState, targetState, config)
    applyModifiedMotion(modified)
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

Core-layer strategies are unit-tested without any Minecraft runtime:

- `RotationCalculatorTest` — rotation math
- `ConditionCheckerTest` — trigger condition evaluation
- `TriggerOptionsTest` — trigger option parsing
- `MathUtilsTest` / `Vec2Test` / `Vec3Test` — math utilities

---

📚 Documentation

- `docs/ARCHITECTURE.md` — in-depth architecture walkthrough
- `docs/CONSTITUTION.md` — the full Sandwich Constitution (four fundamental principles)
- `docs/CONTRIBUTING.md` — contribution guidelines
- `SwitchLite/README.md` — reference implementation guide (build & usage)

---

📋 Development Status

Alpha stage. Architecture complete, both platform translation layers implemented, cross-version shared module catalog filled in, WebUI panel operational.

✅ Completed
- Sandwich architecture structure
- Core algorithms (RotationCalculator, VectorOperations, NoiseProvider, GaussianNoise)
- Concrete strategy implementations (aim: Legit/SelfAdaptive; click: Probabilistic/Cooldown; velocity: Legit/Delay/Click; keepsprint; tap state machine)
- Data models (PlayerState, TargetState, CombatContext, VelocityContext, PlatformCommand, SetbackEvent)
- EventBridge singleton pattern + AgentBridge (Fabric fallback)
- Property delegates (float, int, boolean, enum, triggerOptions, probability) + OptionMeta typing
- **34 modules** across combat / movement / player / render / world categories
- Forge 1.8.9 platform layer (bootstrap, event bridge, state extractor, packet interceptor, GL/font bridges)
- Fabric 1.21 platform layer (bootstrap, event bridge, state extractor, velocity interceptor, packet mixin)
- MappingContext reflection system + MappingLoader cache + mapping JSON (forge 1.8.9, fabric 1.20.1 + deltas)
- **WebUI Aurora panel** (LAN access, bearer token auth, config persistence, keybinds, export/import)
- **In-game HUD** restored as the sole in-game overlay; ClickGUI removed in favor of WebUI
- Render abstraction (GL11Bridge, FontRendererBridge, OverlayRenderer, RenderContext)
- Safety infra (SafetyWrapper, SetbackTracker) + ReplayLogger + PacketScheduler
- Unit tests for core algorithms / conditions / options / math utilities
- CI pipeline (payload.dll build) + project docs (ARCHITECTURE / CONSTITUTION / CONTRIBUTING)

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

> "Algorithms in core, logic in modules, execution in adapter" — this is both a code organization principle and a design philosophy.

🔗 [Discord](https://discord.gg/Sq4rWn4JG) · [GitHub](https://github.com/gtiernotsoldier/Switch_Client_alpha_01)

Sandwich Core Team
