dependencies {
    // Algorithm implementations only. Everything here is a port of, or the
    // reference for, a function in core-rs — no values, no transport, no UI glue.
    implementation(project(":contract"))

    // The parity tests compare these implementations against the Rust core over a
    // real socket, so the harness (and therefore :ipc) is test-only.
    testImplementation(project(":ipc"))
}
