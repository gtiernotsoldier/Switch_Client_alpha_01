# 🥪 SwitchLite - Sandwich Architecture Reference Implementation

A modern, cross-version Minecraft ghost client framework designed for high debuggability and assistance-first gameplay.

## Architecture Overview

```
┌─────────────────────────────────────────────┐
│          C++ Injector (injector)            │
│  · Process detection / Version identification│
│  · Load mapping library / Cache files       │
│  · Inject Agent / Deploy Fabric Mod         │
├─────────────────────────────────────────────┤
│          Java Agent (agent)                 │
│  · Runtime bytecode modification            │
│  · MappingContext (cross-version resolver)  │
│  ✓ Cache serialization                      │
├─────────────────────────────────────────────┤
│          Kotlin Core (core)                 │
│  · Pure math / Zero Minecraft dependencies  │
│  · Strategy interfaces & implementations    │
│  · Condition engine & Noise providers       │
├─────────────────────────────────────────────┤
│          Version Adapter (adapter)          │
│  · forge/v1_8_9 (MCP mappings)              │
│  · fabric/common (1.16~1.21 shared)         │
│  · fabric/v1_xx (version-specific)          │
└─────────────────────────────────────────────┘
```

## Core Principles (Constitution)

1. **Safety First** - All modules default to human-limit behavior
2. **Debuggability** - Strategy/execution separation with unit tests
3. **Strategy** - Dynamic adaptation with cloud-config support
4. **Assistance** - Player is the protagonist, modules are co-pilots

## Project Structure

- `injector/` - C++ process injector with manual mapping
- `agent/` - Java Agent for bytecode manipulation
- `core/` - Pure Kotlin algorithms (no game dependencies)
- `adapter/` - Version-specific game integration
- `mappings/` - JSON mapping files for cross-version compatibility
- `config/` - Hot-loadable strategy configurations

## Implemented Modules

### Combat
- **AimAssist**: Legit mode (box-edge correction), FOV limits, smooth interpolation, noise injection
- **Velocity**: Hit reduction with conditional triggers
- **SprintReset**: Optimized sprint resetting for maximum KB

## Building

```bash
# Build Core (pure Kotlin)
./gradlew :core:build

# Build Agent
./gradlew :agent:shadowJar

# Build Forge 1.8.9 Adapter
./gradlew :adapter:forge:v1_8_9:build

# Build Fabric 1.21 Adapter
./gradlew :adapter:fabric:v1_21:build
```

## Usage

### Mode A: Forge Mod (1.8.9)
Place `SwitchLite-1.8.9.jar` in `.minecraft/mods/`

### Mode B: Agent Injection
```bash
java -javaagent:SwitchLite-Agent.jar -jar minecraft.jar
```

## Configuration

All modules support hot-loading via JSON configs in `config/`:

```json
{
  "mode": "LEGIT",
  "fov": { "horizontal": 120, "vertical": 60 },
  "smoothness": 0.85,
  "noiseIntensity": 0.05
}
```

## Safety Features

- **Configuration Fingerprinting**: Cached decision functions for performance
- **Safety Wrapper**: Auto-disable on repeated failures
- **Noise Decorator**: Human-like behavior injection
- **Packet Scheduler**: Realistic network simulation

## License

GPLv3 - Open source, community-driven development

## Contributing

1. Fork the repository
2. Create a feature branch
3. Ensure all unit tests pass
4. Submit a PR with clear description

---

**Sandwich Architecture**: "Algorithms in core, logic in modules, execution in adapter"
