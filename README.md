# SwitchLite

> A Minecraft cheat client built with the **Sandwich Architecture** — clean separation between core logic and platform-specific code.

## What is SwitchLite?

SwitchLite is a modular Minecraft client that supports multiple platforms (Forge 1.8.9, Fabric 1.21) through a single shared codebase. The core module logic is written **once** and runs on any platform via thin translation layers.

## Architecture: Sandwich Pattern

```
─────────────────────────────────────────────────┐
│                  Platform Layer                   │
│   adapter/forge/v1_8_9  |  adapter/fabric/v1_21  │
│   (translation only — zero business logic)        │
├─────────────────────────────────────────────────┤
│                  Common Layer                     │
│              adapter/common/                      │
│   Module base class, EventBridge, Delegates       │
│   (single copy of module logic)                   │
├─────────────────────────────────────────────────┤
│                   Core Layer                      │
│                  core/                            │
│   Algorithms, Models, Conditions, Utilities       │
│   (zero Minecraft dependencies)                   │
└─────────────────────────────────────────────────┘
```

### Design Principles (The Constitution)

1. **Core is pure** — `core/` has zero Minecraft class imports. It's plain Kotlin data and algorithms.
2. **One copy of logic** — Module behavior lives in `adapter/common/`, never duplicated per platform.
3. **Platform layers translate only** — `adapter/forge/` and `adapter/fabric/` do nothing but map platform events to the common interface. No business logic allowed.
4. **MappingContext over hardcoding** — All Minecraft field/method access goes through semantic keys (`"forge:entity_posX"`, `"fabric:player_rotationYaw"`), never direct references.
5. **EventBridge is the contract** — Modules call `EventBridge.xxx()` directly. Platforms register themselves as handlers. Modules never know which platform they're running on.

## Project Structure

```
SwitchLite/
├── core/                          # Pure logic, no MC deps
│   ├── algorithm/                 # RotationCalculator, VectorOperations, NoiseProvider
│   ├── condition/                 # ConditionChecker, TriggerOptions
│   ├── model/                     # PlayerState, TargetState, CombatContext
│   └── util/                      # Vec2, Vec3, MathUtils
├── adapter/
│   ├── common/                    # Shared module logic
│   │   ├── api/                   # EventBridge, IEventBridge, IStateExtractor
│   │   ├── module/                # Module base, Category, delegates
│   │   └── module/combat/         # AimAssist, AutoClicker, etc.
│   ├── forge/v1_8_9/             # Forge 1.8.9 translation layer
│   ── fabric/v1_21/             # Fabric 1.21 translation layer
├── agent/                         # MappingContext (Java reflection layer)
├── config/presets/                # Configuration presets
├── injector/resources/            # Mod metadata and resources
── mappings/                      # Semantic key → MC member mappings
└── scripts/                       # Build and utility scripts
```

## Key Components

### EventBridge (Singleton)
The central nervous system. Modules call it directly:
```kotlin
EventBridge.setPlayerRotation(Vec2(yaw, pitch))
EventBridge.applyMotion(Vec3(x, y, z))
EventBridge.onVelocityPacket(ctx)
```
Platforms register themselves as handlers during bootstrap — modules never know the difference.

### MappingContext
Java-based reflection layer that resolves Minecraft classes/methods/fields via semantic keys. No hardcoded MC class names anywhere in the codebase.
```kotlin
MappingContext.getFieldValue(player, "forge:entity_posX")
MappingContext.invokeMethod(world, "fabric:world_getEntityByID", entityId)
```

### State Extractors
Each platform provides a `StateExtractor` that converts MC objects into pure data snapshots (`PlayerState`, `TargetState`, `CombatContext`).

### Platform Commands
Velocity processing returns sealed commands:
- `ModifyMotion` — replace motion values
- `CancelPacket` — drop the packet entirely
- `ClickBurst` — send rapid attack packets
- `Pass` — let original values through

## Supported Platforms

| Platform | Version | Status |
|----------|---------|--------|
| Forge | 1.8.9 | Translation layer complete |
| Fabric | 1.21 | Translation layer complete |

## Development Status

This project is in **alpha** stage. The architecture is complete and both platform translation layers are implemented. Individual modules are being filled in progressively.

### Completed
- [x] Sandwich architecture structure
- [x] Core algorithms (RotationCalculator, VectorOperations, NoiseProvider)
- [x] Data models (PlayerState, TargetState, CombatContext)
- [x] EventBridge singleton pattern
- [x] Property delegates (float, int, boolean, enum, triggerOptions, probability)
- [x] Forge 1.8.9 platform layer
- [x] Fabric 1.21 platform layer
- [x] MappingContext reflection system

### In Progress
- [ ] Module implementations (AimAssist, AutoClicker, Velocity, etc.)
- [ ] Packet interception (Mixin for Fabric, packet events for Forge)
- [ ] Target selection system
- [ ] Configuration UI

## License

This project is for educational and research purposes only.
