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

    // Include platform adapter jars if they were built locally.
    // CI does NOT build these (they need ForgeGradle / Fabric Loom),
    // so this is a no-op in CI and only takes effect in local dev builds.
    // Agent.java calls ForgeBootstrap.init() via reflection — the class
    // will be available in the classpath only if the jar was bundled here.
    val forgeAdapterDir = file("../adapter/forge/v1_8_9/build/libs")
    if (forgeAdapterDir.exists()) {
        from({
            forgeAdapterDir.listFiles { f -> f.name.endsWith(".jar") && !f.name.contains("sources") }
                ?.map { zipTree(it) } ?: emptyList()
        })
        logger.lifecycle("[agent jar] Bundled Forge 1.8.9 adapter")
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
