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
}

tasks.register<Jar>("shadowJar") {
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    with(tasks.jar.get())
    archiveBaseName.set("switchlite-agent-shadow")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
