plugins {
    kotlin("jvm") version "1.9.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":core"))
    
    // Forge 1.8.9 dependencies (provided by Minecraft runtime)
    compileOnly("net.minecraftforge:forge:1.8.9-11.15.1.2318")
    
    // Kotlin standard library
    implementation(kotlin("stdlib"))
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("SwitchLite-1.8.9")
    archiveClassifier.set("")
    mergeServiceFiles()
}
