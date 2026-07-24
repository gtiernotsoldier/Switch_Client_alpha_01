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
│          C++ Injector (injector)            │
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
│  · Strategy interfaces                      │
│    (AimStrategy, VelocityStrategy)          │
│  · Algorithm implementations                │
│    (rotation, knockback, prediction, noise) │
│  · Condition engine (TriggerCondition)      │
│  · Data models (PlayerState, Vec3)          │
├─────────────────────────────────────────────┤
│          Version Adapter Layer (adapter)    │
│  · forge/1.8.9 (independent implementation) │
│  · fabric/common (1.16~1.21 shared source)  │
│  · fabric/v1_xx (version difference adapters)│
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
├── core/                          # Pure logic, no MC dependencies
│   ├── algorithm/                 # RotationCalculator, VectorOperations, NoiseProvider
│   ├── condition/                 # ConditionChecker, TriggerOptions
│   ├── model/                     # PlayerState, TargetState, CombatContext
│   ── util/                       # Vec2, Vec3, MathUtils
├── adapter/
│   ├── common/                    # Shared module logic (single codebase)
│   │   ├── api/                   # EventBridge, IEventBridge, IStateExtractor
│   │   ├── module/                # Module base, Category, delegates
│   │   └── module/combat/         # AimAssist, AutoClicker, etc.
│   ├── forge/v1_8_9/             # Forge 1.8.9 translation layer
│   └── fabric/v1_21/             # Fabric 1.21 translation layer
├── agent/                         # MappingContext (Java reflection layer)
├── config/presets/                # Configuration presets
├── injector/resources/            # Mod metadata and resources
├── mappings/                      # Semantic key → MC member mapping
└── scripts/                       # Build and utility scripts
```

---

📦 Module Design Examples (Following the Constitution)

Module	Traditional Approach	Sandwich Approach	
AimAssist	Lock onto head/center, linear smoothing	Only softly pull back to hitbox edge when crosshair deviates, with jitter and reaction delay	
AutoClicker	Fixed CPS, uniform randomness	Distance-adaptive CPS, gamma distribution, only active when player attacks	
Velocity	Fixed retention rate 0%	Random range (20-60%), conditional triggering, simulating packet loss	
WTap	Fixed tick packet sending	Random ticks + probability + only triggers when moving forward	
Backtrack	Fixed delay	Dynamic delay following network distribution, random packet loss, coordinated with global scheduler	
Disabler	Single exploit abuse	Dynamic strategy library loading, multiple bypasses randomly switched, behavior legitimization	

---

🔧 Four-Layer Architecture in Detail

Layer	Language	Responsibility	Key Components	
Injector	C++	Process detection, version recognition, Agent injection / Fabric Mod deployment	Process enumeration, remote thread, version probing	
Agent	Java	Class-loading-time bytecode modification, providing cross-version mapping	Javassist / ASM, MappingContext, cache serialization	
Core	Kotlin	Pure math algorithms, strategy interfaces, condition engine, noise perturbation	AimStrategy, VelocityStrategy, TriggerCondition, NoiseProvider	
Adapter	Kotlin	Version adaptation (1.8.9 Forge / 1.20+ Fabric)	VelocityModule, AimModule, BattleInsight, etc.	

Core Principles:
- Algorithms in Core: All decision logic (knockback modification, rotation calculation, CPS generation) is fully decoupled from Minecraft and can be unit-tested.
- Logic in Modules: Each adapter layer module is a complete file that listens to events, extracts state, calls Core, and writes back to the game.
- Execution in Adapter: Specific packet sending, motion modification, and key simulation are completed by the adapter layer; Core is unaware.

---

️ Four Major Infrastructures (Cross-Cutting Concerns)

Infrastructure	Function	Implementation Location	
Mapping Library + Cache	Cross-version class/method/field access, zero-reflection startup	Agent + JSON + serialization	
Condition Engine	Unified trigger rules (onlyGround, onLook, chance, delay, ticks)	Core TriggerCondition + ConditionChecker	
Noise Perturbation	Mandatory randomization (Gaussian/uniform distribution) on all strategy outputs	Core NoiseProvider decorator	
Global Packet Scheduler	Manages all network packet sending order, delay, and packet loss simulation	Adapter PacketScheduler	

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

️ Assistance Module: BattleInsight

A pure display module that does not modify game behavior, providing:

- KB Win Rate: Real-time knockback distance comparison.
- HitSelect Timing: Indicates the 1-3 tick window after the opponent's attack.
- JumpReset Prompt: Suggests jumping when knocked back.
- Movement Prompt: Suggests W/A/S/D based on distance and terrain.

All prompts are based on real data collection (knockback packets, attack animations, player input) and cannot be detected by anti-cheat.

---

🌐 Cross-Version Strategy

- Forge 1.8.9: Independent adapter layer (MCP runtime names).
- Fabric 1.16  1.21:
  - `common/` shares 80% of source code (events, state extraction, strategy calls).
  - `v1_16/`, `v1_20/`, etc. submodules house version-difference adapters (API changes, package name mappings).

Mapping library + cache: C++ detects version → Agent loads corresponding JSON → adapter layer obtains classes/methods/fields through MappingContext, with no hardcoding.

---

📋 Development Status

This project is in alpha stage. Architecture is complete, both platform translation layers are implemented, and modules are being gradually filled in.

✅ Completed
- Sandwich architecture structure
- Core algorithms (RotationCalculator, VectorOperations, NoiseProvider)
- Data models (PlayerState, TargetState, CombatContext)
- EventBridge singleton pattern
- Property delegates (float, int, boolean, enum, triggerOptions, probability)
- Forge 1.8.9 platform layer
- Fabric 1.21 platform layer
- MappingContext reflection system

In Progress
- Module implementation (AimAssist, AutoClicker, Velocity, etc.)
- Packet interception (Fabric Mixin / Forge packet events)
- Target selection system
- Configuration UI

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
4. Contribute: PRs are welcome to improve strategy algorithms, add new version adapter layers, and optimize noise models. However, brutal modules (KillAura, Fly, etc.) are absolutely prohibited.

---

License

This project is licensed under GPLv3. Any derivative work must be open-sourced and retain the original copyright notice. We welcome community forks and secondary development, but please abide by the spirit of this "constitution" and maintain the healthy ecosystem of ghost clients.

---

🤝 Contact Us

> "Algorithms in core, logic in modules, execution in adapter" — this is both a code organization principle and a design philosophy.

🔗 [Discord](https://discord.gg/Sq4rWn4JG) · [GitHub](https://github.com/gtiernotsoldier/Switch_Client_alpha_01)

Sandwich Core Team