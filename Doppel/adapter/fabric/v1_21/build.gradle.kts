// Doppel Fabric adapter — build file. NOT wired into the build by default.
//
// Why: Fabric Loom downloads a full Minecraft client + mappings at configuration time,
// which the CI (JDK 8, no Loom) cannot do, so settings.gradle.kts keeps this module
// commented out. To bring the Fabric adapter into a build:
//
//   1. Uncomment `include("adapter:fabric:v1_21")` in settings.gradle.kts
//   2. Align the `minecraftVersion` / `yarnMappings` / `loaderVersion` placeholders below
//      with the mapping target the rest of the adapter speaks — mappings/fabric/v1_21_11.json
//      (MC 1.21.x line). The Kotlin adapter code accesses MC through those semantic mapping
//      keys, but it also names real Minecraft classes directly (the packet decision objects
//      the agent's ASM hook calls into) and MUST compile against a matching Minecraft jar.
//   3. `./gradlew :adapter:fabric:v1_21:build` (needs network for the Minecraft download)
//
// Note: this module previously needed the Mixin annotation processor for its two Sponge
// mixins; the mixins (and doppel.mixins.json) are gone — packet interception moved into
// the agent's ASM layer (Transformer → PacketBridge → the decision objects here), so no
// annotation processor is required any more. What still keeps this module out of CI is
// only Loom's configuration-time Minecraft download. None of it is exercised by CI yet;
// everything here was written to compile under Loom once wired: FabricBootstrap,
// FabricPacketVault, FabricVelocityInterceptor, FabricEventBridge, FabricStateExtractor.

plugins {
    // Align the Loom version with your Gradle; 1.6+ supports MC 1.21.
    id("fabric-loom") version "1.6-SNAPSHOT"
}

// Versions to align with mappings/fabric/v1_21_11.json before the first build.
val minecraftVersion: String = (project.findProperty("doppelMinecraft") as String?) ?: "1.21.11"
val yarnMappings: String = (project.findProperty("doppelYarn") as String?) ?: "1.21.11+build.1"
val loaderVersion: String = (project.findProperty("doppelLoader") as String?) ?: "0.16.9"

java {
    // Java 21 — the MC 1.21.x class files this module compiles against require it.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")
    mappings("net.fabricmc:yarn:${yarnMappings}:v2")
    modImplementation("net.fabricmc:fabric-loader:${loaderVersion}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:*")

    // The shared module layer (Module/EventBridge/ConfigManager/...).
    // Compiled at Java 8 — that is fine on Java 21 and is what keeps the two platforms
    // able to share every module implementation.
    implementation(project(":adapter:common"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
    }
}
