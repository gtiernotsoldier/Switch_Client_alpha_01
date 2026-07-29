rootProject.name = "SwitchLite"

include(
    "core",
    "agent",
    "adapter:common",
    // Forge adapter (requires ForgeGradle — not available in CI):
    // "adapter:forge:v1_8_9"
    // Fabric adapter (requires Fabric Loom):
    // "adapter:fabric:v1_21"
)
