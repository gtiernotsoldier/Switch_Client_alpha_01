// version.rs — Minecraft version / platform detection
//
// Step 1: minimal VersionInfo struct + stub. The window-title / filesystem
// detection is migrated in a later step.

/// Detected Minecraft version + platform info.
#[derive(Debug, Clone, Default)]
pub struct VersionInfo {
    pub valid: bool,
    pub version: String,
    pub platform: String,
    pub mc_dir: String,
}

/// Identify the Minecraft version and platform.
/// Stub for step 1 — returns a fixed Forge/1.8.9 default so the injection
/// flow can be exercised before real detection lands.
pub fn parse_minecraft_version(_mc_path: &str, _window_title: &str) -> VersionInfo {
    VersionInfo {
        valid: true,
        version: "1.8.9".to_string(),
        platform: "Forge".to_string(),
        mc_dir: String::new(),
    }
}
