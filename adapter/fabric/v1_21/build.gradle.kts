plugins {
    kotlin("jvm") version "1.9.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":adapter:fabric:common"))
    
    // Fabric 1.21 dependencies (provided by Minecraft runtime)
    compileOnly("net.fabricmc:fabric-loader:0.16.0")
    compileOnly("minecraft": "com.mojang:minecraft:1.21")
    
    // Kotlin standard library
    implementation(kotlin("stdlib"))
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("SwitchLite-Fabric-1.21")
    archiveClassifier.set("")
    mergeServiceFiles()
}
