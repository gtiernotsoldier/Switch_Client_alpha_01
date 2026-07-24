plugins {
    kotlin("jvm") version "1.9.20" apply false
    java
}

allprojects {
    group = "io.switchlite"
    version = "0.1.0-alpha"

    repositories {
        mavenCentral()
        maven("https://repo.maven.apache.org/maven2/")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        jvmToolchain(8)
    }

    dependencies {
        implementation(kotlin("stdlib"))
        
        // Coroutines
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        
        // JSON parsing (Jackson)
        implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
        implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
        implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
        
        // Logging
        implementation("org.slf4j:slf4j-api:2.0.7")
        implementation("org.slf4j:slf4j-simple:2.0.7")
        
        // Testing
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
        testImplementation("io.mockk:mockk:1.13.7")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
