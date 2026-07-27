rootProject.name = "SwitchLite"

include(
    "core",
    "agent",
    "adapter:common"
    // MC-dependent modules (require ForgeGradle / Fabric Loom):
    // "adapter:forge:v1_8_9",
    // "adapter:fabric:v1_21"
)