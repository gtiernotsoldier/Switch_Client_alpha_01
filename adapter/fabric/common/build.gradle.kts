plugins {
    kotlin("jvm") version "1.9.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":core"))
    
    // Fabric common dependencies (provided by Minecraft runtime)
    compileOnly("net.fabricmc:fabric-loader:0.15.0")
    
    // Kotlin standard library
    implementation(kotlin("stdlib"))
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("SwitchLite-Fabric-Common")
    archiveClassifier.set("")
    mergeServiceFiles()
}
