dependencies {
    implementation(project(":core"))
    implementation(project(":adapter:common"))
    // No Forge/MC/LWJGL compile dependencies — all access via reflection at runtime
}
