// process.rs — Minecraft process detection
//
// Step 1: minimal ProcessInfo struct + stub. The real Toolhelp32/EnumWindows
// enumeration is migrated in a later step.

/// Information about a detected Minecraft process.
#[derive(Debug, Clone, Default)]
pub struct ProcessInfo {
    pub pid: u32,
    pub valid: bool,
    pub window_title: String,
    pub path: String,
}

/// Find the Minecraft (javaw.exe/java.exe with "minecraft" window) process.
/// Stub for step 1 — always returns invalid so main.rs flow can be tested
/// without a live game. Real implementation lands in the next step.
pub fn find_minecraft_process() -> ProcessInfo {
    ProcessInfo::default()
}
