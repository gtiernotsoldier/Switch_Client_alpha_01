rootProject.name = "Doppel"

include(
    "contract",
    "ipc",
    "safety",
    "core",
    "agent",
    "adapter:common",
    "adapter:forge:v1_8_9",
    // Fabric adapter (requires Fabric Loom):
    // "adapter:fabric:v1_21"
)
