plugins {
    java
    `maven-publish`
}

group = "io.switchlite"
version = "0.1.0-alpha"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven("https://repo.maven.apache.org/maven2/")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":adapter:common"))
    implementation(project(":adapter:forge:v1_8_9"))

    // Kotlin stdlib — agent is a Java module but its deps are Kotlin.
    // Must be explicit because the java plugin doesn't resolve Kotlin transitive deps.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")

    // Javassist for bytecode manipulation
    implementation("org.javassist:javassist:3.29.2-GA")

    // JSON parsing (Jackson)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("org.slf4j:slf4j-simple:2.0.7")
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "io.switchlite.agent.Agent",
            "Agent-Class" to "io.switchlite.agent.Agent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
    archiveBaseName.set("switchlite-agent")

    // Bundle mappings into jar
    from("../mappings") {
        into("mappings")
        include("**/*.json")
    }

    // Fat jar: include all runtime dependencies (project + external)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })

    // Forge adapter is now included — pure reflection, no ForgeGradle needed at compile time.

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
