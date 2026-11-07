dependencies {
    implementation(project(":contract"))
    implementation(project(":ipc"))
    implementation(project(":safety"))

    // Still needed until the 14 module construction sites are switched to the IPC
    // strategies; removing this line is the last step of the cut.
    implementation(project(":core"))
}
