dependencies {
    implementation(project(":core"))
    implementation(project(":adapter:common"))

    // Netty — required by ForgePacketInterceptor (compile-time only, provided by MC at runtime)
    compileOnly("io.netty:netty-all:4.0.23.Final")

    // No Forge/MC/LWJGL compile dependencies — all access via reflection at runtime
}
