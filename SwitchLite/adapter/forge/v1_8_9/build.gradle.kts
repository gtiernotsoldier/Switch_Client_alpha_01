// MC-dependent module — requires ForgeGradle toolchain.
// CI skips compilation: sourceSets redirected to empty dirs.
// Full build requires a proper Forge 1.8.9 development environment.

dependencies {
    implementation(project(":core"))
    implementation(project(":adapter:common"))
}

sourceSets {
    main {
        java.srcDirs = emptyList<File>()
        kotlin.srcDirs = emptyList<File>()
    }
}
