plugins {
    kotlin("jvm") version "1.9.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    // No Minecraft dependencies - pure Kotlin/Java
    implementation(kotlin("stdlib"))
    
    // Testing
    testImplementation(kotlin("test"))
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("SwitchLite-Core")
    archiveClassifier.set("")
    mergeServiceFiles()
}
