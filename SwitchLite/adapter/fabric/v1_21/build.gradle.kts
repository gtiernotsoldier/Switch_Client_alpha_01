// MC-dependent module — requires Fabric Loom toolchain.
// CI skips compilation: sourceSets redirected to empty dirs.
// Full build requires a proper Fabric 1.21 development environment.

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
