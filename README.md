🥪 SwitchLite

A Minecraft Ghost Client built with the Sandwich Architecture — clean separation between core logic and platform-specific code.

A Minecraft ghost client based on the Sandwich Architecture — fully decoupled core logic and platform code.

Discord:https://discord.gg/Sq4rWn4JG · GitHub:https://github.com/gtiernotsoldier/Switch_Client_alpha_01

🥪 Sandwich Architecture · A Design Manifesto for Modern Ghost Clients

Why We Exist

Anti-cheat systems (Grim, Polar, Vulcan, Watchdog) are becoming smarter than ever. They no longer merely detect individual cheat signatures—they identify non-human behavior patterns through temporal analysis, kinematic modeling, and machine learning. The traditional paradigm of "stacking features, fixed thresholds, brute-force bypass" has failed—those clients are getting banned en masse.

We need a completely new design philosophy: not fighting signatures, but mimicking humans; not chasing maximum effectiveness, but pursuing legitimate survival.

Thus, the Sandwich Architecture was born.

📜 Core Philosophy: A "Constitution" for Modern Anti-Cheat Resistance

We firmly believe that a long-surviving ghost client must adhere to four fundamental principles:

1. 🛡️ Safety First

Legitimate by default: All module defaults must stay within human behavioral limits—no supernatural movement or input.

Conditional triggering: Modules activate only in specific tactical scenarios (e.g., onlyGround, onlyMoveForward, onlyWhenTargetGoesBack) to avoid constant exposure.

Soft boundaries: All modifications must be "gentle, gradual, and randomly perturbed." For example, AimAssist only nudges the crosshair toward the hitbox edge rather than locking onto the center.

No brute-force modules: We categorically exclude blatantly physics-violating features like KillAura, Fly, Speed, and Jesus.

2. 🔍 Debuggability

Separation of strategy and execution: Core algorithms (Core) are fully decoupled from the Minecraft adaptation layer (Adapter), enabling unit testing and offline simulation of Core.

Logging and replay: The adaptation layer records per-tick state, decisions, and network packets for post-ban analysis.

Hot-reloadable strategies: All risk parameters (knockback retention rate, CPS range, trigger conditions) are defined via JSON configs, supporting runtime modification and cloud hot-swapping without recompilation.

Unified condition engine: All modules share a TriggerCondition system, eliminating duplicate if-checks and centralizing debugging.

3. 🧠 Strategy

Dynamic adaptation: Module behavior adjusts in real time based on distance, player state, target actions, and server anti-cheat fingerprints. For instance, AutoClicker CPS scales with target distance; Velocity only triggers when the enemy backs away.

Pluggable strategies: AnticheatDetector identifies the current anti-cheat type (Grim/Vulcan/Watchdog) and auto-loads the corresponding JSON strategy pack. Different anti-cheats have different "sweet spots."

Global behavior planning: All network packets are managed by a unified scheduler (PacketScheduler), simulating real network latency, packet loss, and reordering to ensure temporal consistency.

Cloud strategy library: Strategy configs can be hosted in the cloud and pulled automatically on client startup, enabling "hot-update" bypasses.

4. 🤝 Assistance

Player remains the protagonist: All modules act as "co-pilots," never replacing player decisions. AimAssist doesn't auto-select targets; AutoClicker only optimizes rhythm while the player holds left-click; WTap only triggers while the player moves forward.

Reducing repetitive labor: Modules handle mechanical, error-prone tasks (auto-refill, tool switching, bridge sneaking), freeing players to focus on tactics and aim.

Natural interaction: Module output must carry "human imperfection"—random jitter, reaction delays, overshoot, noise. Make the anti-cheat think "this is a high-ping but skilled player."

🧱 Architecture Overview: The Sandwich Layers

┌─────────────────────────────────────────────┐ │ C++ Injector (injector) │ │ · Process detection / version ID │ │ · Load mapping libs / cache files │ │ · Inject Agent / deploy Fabric Mod │ ├─────────────────────────────────────────────┤ │ Java Agent (agent) │ │ · Bytecode modification at class load │ │ (Javassist/ASM) │ │ · Provides MappingContext (cross-version │ │ class/method mappings) │ │ · Cache serialization (avoid runtime refl.)│ ├─────────────────────────────────────────────┤ │ Kotlin Core Library (core) │ │ · Pure math / zero MC dependency │ │ · Strategy interfaces (AimStrategy, │ │ VelocityStrategy) │ │ · Algorithm implementations (rotation, │ │ knockback, prediction, noise) │ │ · Condition engine (TriggerCondition) │ │ · Data models (PlayerState, Vec3) │ ├─────────────────────────────────────────────┤ │ Version Adapter Layer (adapter) │ │ · forge/1.8.9 (independent impl) │ │ · fabric/common (shared src 1.16–1.21) │ │ · fabric/v1_xx (version diff adapters) │ │ · One module = one .kt file │ │ · Listen to MC events → call core strategy │ │ · Write decisions back to game (motion, │ │ inputs, packets) │ └─────────────────────────────────────────────┘ 

📦 Project Structure

SwitchLite/ ├── core/ # Pure logic, no MC dependency │ ├── algorithm/ # RotationCalculator, VectorOperations, NoiseProvider │ ├── condition/ # ConditionChecker, TriggerOptions │ ├── model/ # PlayerState, TargetState, CombatContext │ └── util/ # Vec2, Vec3, MathUtils ├── adapter/ │ ├── common/ # Shared module logic (single source) │ │ ├── api/ # EventBridge, IEventBridge, IStateExtractor │ │ ├── module/ # Module base, Category, delegates │ │ └── module/combat/ # AimAssist, AutoClicker, etc. │ ├── forge/v1_8_9/ # Forge 1.8.9 translation layer │ └── fabric/v1_21/ # Fabric 1.21 translation layer ├── agent/ # MappingContext (Java reflection layer) ├── config/presets/ # Config presets ├── injector/resources/ # Mod metadata and resources ├── mappings/ # Semantic key → MC member mappings └── scripts/ # Build and utility scripts 

📦 Module Design Examples (Constitutional Compliance)

ModuleTraditional ApproachSandwich ApproachAimAssistLock onto head/center, linear smoothingOnly nudge crosshair toward hitbox edge when off-target, with jitter and reaction delayAutoClickerFixed CPS, uniform randomDistance-adaptive CPS, gamma distribution, only triggers while player attacksVelocityFixed retention rate 0%Randomized range (20–60%), conditional triggering, simulates packet lossWTapFixed tick packet timingRandom tick + probability + only triggers while moving forwardBacktrackFixed delayDynamic delay following network distribution, random packet loss, coordinated with global schedulerDisablerSingle exploitDynamically loaded strategy library, randomized switching between bypasses, behavior legalization 

🔧 Four-Layer Architecture Explained

LayerLanguageResponsibilityKey ComponentsInjectorC++Process detection, version identification, Agent injection / Fabric Mod deploymentProcess enumeration, remote threading, version probingAgentJavaBytecode modification at class load, cross-version mappingJavassist / ASM, MappingContext, cache serializationCoreKotlinPure math algorithms, strategy interfaces, condition engine, noise perturbationAimStrategy, VelocityStrategy, TriggerCondition, NoiseProviderAdapterKotlinVersion adaptation (1.8.9 Forge / 1.20+ Fabric)VelocityModule, AimModule, BattleInsight, etc. 

Core Principles:

Algorithms belong in Core: All decision logic (knockback modification, rotation calculation, CPS generation) is fully decoupled from Minecraft and unit-testable.

Logic belongs in Modules: Each adapter-layer module is a single file that listens to events, extracts state, calls Core, and writes back to the game.

Execution happens in Adapter: Specific packet sending, motion modification, input simulation, etc., are handled by the adapter layer—Core remains unaware.

🛠️ Four Infrastructure Pillars (Cross-Cutting Concerns)

InfrastructurePurposeImplementation LocationMapping Library + CacheCross-version class/method/field access, zero-reflection startupAgent + JSON + serializationCondition EngineUnified trigger rules (onlyGround, onLook, chance, delay, ticks)Core TriggerCondition + ConditionCheckerNoise PerturbationForces randomization on all strategy outputs (Gaussian/uniform)Core NoiseProvider decoratorGlobal Packet SchedulerManages packet send order, delay, and simulated lossAdapter PacketScheduler 

These infrastructures are transparent to modules—module developers simply declare configurations without reimplementing them.

⚡ Key Components

EventBridge (Singleton)

The central nervous system. Modules call directly:

EventBridge.setPlayerRotation(Vec2(yaw, pitch)) EventBridge.applyMotion(Vec3(x, y, z)) EventBridge.onVelocityPacket(ctx) 

Platforms register themselves as handlers at startup—modules never know which platform they're running on.

MappingContext

A Java reflection-based mapping layer that resolves Minecraft classes/methods/fields via semantic keys. Zero hardcoded MC class names in code.

MappingContext.getFieldValue(player, "forge:entity_posX") MappingContext.invokeMethod(world, "fabric:world_getEntityByID", entityId) 

Platform Commands

Velocity handling returns sealed commands:

ModifyMotion — Replace motion values

CancelPacket — Drop packet entirely

ClickBurst — Send rapid attack packets

Pass — Let original value pass through

🎮 Combat Module Example (Velocity)

Core Layer:

interface VelocityStrategy { fun modifyVelocity( original: Vec3, player: PlayerState, target: TargetState?, config: VelocityConfig ): Vec3 } class DefaultVelocityStrategy : VelocityStrategy { /* Pure math implementation */ } 

Adapter Layer (1.8.9):

@EventTarget fun onPacket(event: PacketEvent) { val config = buildConfig() val modified = SandwichCore.velocityStrategy.modifyVelocity( original, playerState, targetState, config ) applyModifiedMotion(modified) } 

Supported modes: Legit (randomized range + conditional trigger), Delay (packet delay), Click (auto click).

Conditions: onlyMove, onlyMoveForward, onlyWhenTargetGoesBack, onlyGround, onLook, disabledInAir.

Randomization: Horizontal/vertical retention rate ranges, trigger probability, delay ticks.

🛡️ Assist Module: BattleInsight

A purely visual module that does not modify game behavior, providing:

KB Win Rate: Real-time knockback distance comparison.

HitSelect Timing: Hints for the 1–3 tick window after opponent attacks.

JumpReset Hint: Suggests jumping when knocked back.

Movement Hint: Suggests W/A/S/D based on distance and terrain.

All hints are based on real data collection (knockback packets, attack animations, player input)—undetectable by anti-cheats.

🌐 Cross-Version Strategy

Forge 1.8.9: Independent adapter layer (MCP runtime names).

Fabric 1.16 ~ 1.21:

common/ shares ~80% of source code (events, state extraction, strategy calls).

Sub-modules like v1_16/, v1_20/ house version-specific adapters (API changes, package name mappings).

Mapping library + cache: C++ detects version → Agent loads corresponding JSON → Adapter accesses classes/methods/fields via MappingContext, zero hardcoding.

📋 Development Status

This project is in alpha. The architecture is complete, both platform translation layers are implemented, and modules are being filled in progressively.

✅ Completed

[x] Sandwich Architecture structure

[x] Core algorithms (RotationCalculator, VectorOperations, NoiseProvider)

[x] Data models (PlayerState, TargetState, CombatContext)

[x] EventBridge singleton pattern

[x] Property delegates (float, int, boolean, enum, triggerOptions, probability)

[x] Forge 1.8.9 platform layer

[x] Fabric 1.21 platform layer

[x] MappingContext reflection system

🚧 In Progress

[ ] Module implementations (AimAssist, AutoClicker, Velocity, etc.)

[ ] Packet interception (Fabric Mixin / Forge packet events)

[ ] Target selection system

[ ] Configuration UI

🌍 Future Vision

Anti-cheats will only get stronger, but we won't try to "break" them. Our goal is: to make Sandwich-architecture clients statistically indistinguishable from real human players.

We believe assistance ≠ cheating. As long as we respect game rules, other players, and anti-cheat boundaries, ghost clients can exist long-term as "training tools" and "assistive peripherals."

Join us in defining the next generation of ghost clients.

🔧 Development & Contribution Guide

Clone: git clone https://github.com/gtiernotsoldier/Switch_Client_alpha_01.git

Build: Use Gradle to build core, agent, and adapter modules separately.

Debug: Unit test Core-layer strategies; integration test in local Forge/Fabric environments.

Contribute: PRs improving strategy algorithms, adding version adapters, or optimizing noise models are welcome. Brute-force modules (KillAura, Fly, etc.) are strictly prohibited.

📄 License

This project is licensed under GPLv3. Any derivative work must be open-sourced and retain original copyright notices. Community forks and derivative projects are welcome, but please uphold the spirit of this "Constitution" and maintain the ecological health of ghost clients.

🤝 Contact

"Algorithms in core, logic in modules, execution in adapter" — this is both a code organization principle and a design philosophy.

🔗 Discord:https://discord.gg/Sq4rWn4JG · GitHub:https://github.com/gtiernotsoldier/Switch_Client_alpha_01

Sandwich Core Team 
