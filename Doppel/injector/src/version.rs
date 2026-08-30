// version.rs — Minecraft version / platform detection
//
// Ported from C++ version.cpp. Pure std::fs + string logic (no Windows API —
// directory traversal via std::fs is simpler and safer than FindFirstFileA).
//
// Logic:
//   1. Parse version from window title ("Minecraft 1.8.9" -> "1.8.9")
//   2. Locate the .minecraft directory (walk up from exe path, %APPDATA%,
//      launcher dirs like PCL/HMCL)
//   3. Detect platform from filesystem (mods dir forge/fabric files,
//      versions dir names)
//   4. Fallback: read version from versions/<dir>/<dir>.json

/// Detected Minecraft version + platform info.
#[derive(Debug, Clone, Default)]
pub struct VersionInfo {
    pub valid: bool,
    pub version: String,
    pub platform: String,
    pub mc_dir: String,
}

/// Identify the Minecraft version and platform.
pub fn parse_minecraft_version(mc_path: &str, window_title: &str) -> VersionInfo {
    let mut result = VersionInfo::default();

    // 1. Locate .minecraft directory FIRST — the filesystem version is authoritative.
    let mc_dir = find_minecraft_dir(mc_path);
    result.mc_dir = mc_dir.clone();

    // 2. Try the versions/ directory as the authoritative version source. It reflects the
    //    REAL installed version, unlike window titles that third-party launchers
    //    (FPSMaster etc.) can spoof/truncate (e.g. "Minecraft 1.8" for an 1.8.9 install).
    let dir_version = if mc_dir.is_empty() {
        String::new()
    } else {
        version_from_versions_dir(&mc_dir)
    };

    // 3. Detect platform from filesystem
    result.platform = if mc_dir.is_empty() {
        "Unknown".to_string()
    } else {
        detect_platform(&mc_dir)
    };

    // 4. Resolve version: directory source wins; window title is only a fallback.
    let raw = if !dir_version.is_empty() {
        dir_version
    } else {
        parse_version_from_title(window_title)
    };
    result.version = normalize_version(&raw);

    // 5. Validation
    result.valid = !result.version.is_empty();
    result
}

/// Normalize any detected version token down to a canonical Minecraft version.
///
/// Third-party launchers (FPSMaster, custom title spoofers) frequently report a truncated or
/// branded token where an 1.8.9 install is involved — e.g. a window title "Minecraft 1.8",
/// or a version-id like "FPSMaster-Edge"/"Forge-1.8.9". Since every Forge 1.8-family runtime
/// we target uses the 1.8.9 SRG layout, we collapse all 1.8.x tokens to "1.8.9" so mapping
/// resources always resolve.
fn normalize_version(raw: &str) -> String {
    let s = raw.trim();
    if s.is_empty() {
        return String::new();
    }
    // Pull out the first dotted numeric core, e.g. "1.8", "1.8.9", "1.12.2".
    let numeric = {
        let mut out = String::new();
        let mut started = false;
        for c in s.chars() {
            let is_num = c.is_ascii_digit() || c == '.';
            if is_num {
                out.push(c);
                started = true;
            } else if started {
                break; // stop at the first non-numeric, non-dot char after the numeric core
            }
        }
        out.trim_end_matches('.').to_string()
    };

    if numeric.is_empty() {
        return String::new();
    }

    // Split into major / minor / patch.
    let parts: Vec<&str> = numeric.split('.').collect();
    let major = parts.get(0).map(|s| s.to_string()).unwrap_or_default();
    let minor = parts.get(1).map(|s| s.to_string()).unwrap_or_default();

    // Collapse the whole 1.8 family (1.8, 1.8.1..1.8.9) to 1.8.9 — same SRG layout.
    if major == "1" && minor == "8" {
        return "1.8.9".to_string();
    }
    // Otherwise return the numeric core as-is (e.g. 1.21.1).
    numeric
}

// ── Window title version parser ──

/// Extract Minecraft version from window title, e.g. "Minecraft 1.8.9" -> "1.8.9".
fn parse_version_from_title(title: &str) -> String {
    let lower = title.to_lowercase();
    let mc_pos = match lower.find("minecraft") {
        Some(p) => p,
        None => return String::new(),
    };
    let mut pos = mc_pos + 9; // skip "minecraft"

    // Skip whitespace/asterisks/dashes
    let chars: Vec<char> = title.chars().collect();
    while pos < chars.len() && (chars[pos] == ' ' || chars[pos] == '*' || chars[pos] == '-') {
        pos += 1;
    }

    // Read version: digits and dots only
    let mut version = String::new();
    while pos < chars.len() && (chars[pos].is_ascii_digit() || chars[pos] == '.') {
        version.push(chars[pos]);
        pos += 1;
    }

    // Must have at least "X.Y" format
    if version.len() < 3 || !version.contains('.') {
        return String::new();
    }
    version
}

// ── .minecraft directory discovery ──

fn path_join(a: &str, b: &str) -> String {
    if a.is_empty() {
        return b.to_string();
    }
    let last = a.chars().last().unwrap_or('\0');
    if last == '/' || last == '\\' {
        format!("{}{}", a, b)
    } else {
        format!("{}\\{}", a, b)
    }
}

fn dir_exists(path: &str) -> bool {
    std::path::Path::new(path).is_dir()
}

/// Find .minecraft directory by walking up from exe path.
fn find_minecraft_dir(exe_path: &str) -> String {
    // 1. Walk up from exe path (existing logic)
    let mut current = exe_path.to_string();
    for _ in 0..10 {
        let pos = current.rfind(['/', '\\']);
        let pos = match pos {
            Some(p) => p,
            None => break,
        };
        current.truncate(pos);
        let mc_dir = path_join(&current, ".minecraft");
        if dir_exists(&mc_dir) {
            return mc_dir;
        }
        let name_start = current.rfind(['/', '\\']);
        let dir_name = match name_start {
            Some(ns) => current[ns + 1..].to_string(),
            None => current.clone(),
        };
        if dir_name == ".minecraft" {
            return current;
        }
    }

    // 2. Fallback: %APPDATA%/.minecraft
    let appdata = get_default_minecraft_dir();
    if dir_exists(&appdata) {
        return appdata;
    }

    // 3. Same-drive root .minecraft (PCL2 often stores game in D:\\.minecraft etc.)
    if exe_path.len() >= 2 && exe_path.as_bytes()[1] == b':' {
        let drive_root = format!("{}:\\", &exe_path[..1]);
        let drive_mc = path_join(&drive_root, ".minecraft");
        if dir_exists(&drive_mc) {
            return drive_mc;
        }
    }

    // 4. Walk up further looking for PCL2/HMCL launcher folders
    let mut current = exe_path.to_string();
    for _ in 0..15 {
        let pos = current.rfind(['/', '\\']);
        let pos = match pos {
            Some(p) => p,
            None => break,
        };
        current.truncate(pos);
        let name_start = current.rfind(['/', '\\']);
        let name = match name_start {
            Some(ns) => current[ns + 1..].to_string(),
            None => current.clone(),
        };
        let lower = name.to_lowercase();
        if lower.contains("pcl") || lower.contains("hmcl") || lower.contains("launcher") {
            let sibling = path_join(&current, ".minecraft");
            if dir_exists(&sibling) {
                return sibling;
            }
            let parent_pos = current.rfind(['/', '\\']);
            if let Some(pp) = parent_pos {
                let parent_mc = path_join(&current[..pp], ".minecraft");
                if dir_exists(&parent_mc) {
                    return parent_mc;
                }
            }
        }
    }

    String::new()
}

fn get_default_minecraft_dir() -> String {
    #[cfg(windows)]
    {
        if let Ok(appdata) = std::env::var("APPDATA") {
            return path_join(&appdata, ".minecraft");
        }
    }
    #[cfg(not(windows))]
    {
        if let Ok(home) = std::env::var("HOME") {
            return path_join(&home, ".minecraft");
        }
    }
    String::new()
}

// ── Platform detection ──

fn list_dir_names(path: &str) -> Vec<String> {
    let mut result = Vec::new();
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            if entry.path().is_dir() {
                if let Some(name) = entry.file_name().to_str() {
                    result.push(name.to_string());
                }
            }
        }
    }
    result
}

fn detect_platform(mc_dir: &str) -> String {
    // Check mods folder for platform indicators
    let mut mods_dir = path_join(mc_dir, "mods");
    if !dir_exists(&mods_dir) {
        // Also check versions/<version>/mods
        let versions_dir = path_join(mc_dir, "versions");
        for v in list_dir_names(&versions_dir) {
            let vmods = path_join(&path_join(&versions_dir, &v), "mods");
            if dir_exists(&vmods) {
                mods_dir = vmods;
                break;
            }
        }
    }

    if dir_exists(&mods_dir) {
        for f in list_dir_names(&mods_dir) {
            let lower = f.to_lowercase();
            if lower.contains("forge") {
                return "Forge".to_string();
            }
        }
        for f in list_dir_names(&mods_dir) {
            let lower = f.to_lowercase();
            if lower.contains("fabric-api") || lower.contains("fabric-loader") {
                return "Fabric".to_string();
            }
        }
    }

    // Check versions directory for Forge version folders (e.g. "1.8.9-forge-...")
    let versions_dir = path_join(mc_dir, "versions");
    for v in list_dir_names(&versions_dir) {
        let lower = v.to_lowercase();
        if lower.contains("forge") {
            return "Forge".to_string();
        }
        if lower.contains("fabric") {
            return "Fabric".to_string();
        }
    }

    "Vanilla".to_string()
}

// ── versions/*.json fallback ──

/// Quick JSON string value extractor — looks for "key":"value" or "key": "value".
fn json_get(json: &str, key: &str) -> String {
    let search = format!("\"{}\"", key);
    let pos = match json.find(&search) {
        Some(p) => p,
        None => return String::new(),
    };
    let colon = match json[pos + search.len()..].find(':') {
        Some(p) => pos + search.len() + p,
        None => return String::new(),
    };
    // skip whitespace
    let mut i = colon + 1;
    let bytes = json.as_bytes();
    while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\t' || bytes[i] == b'\n') {
        i += 1;
    }
    if i >= bytes.len() || bytes[i] != b'"' {
        return String::new();
    }
    i += 1;
    let start = i;
    while i < bytes.len() && bytes[i] != b'"' {
        i += 1;
    }
    if i >= bytes.len() {
        return String::new();
    }
    json[start..i].to_string()
}

fn version_from_versions_dir(mc_dir: &str) -> String {
    let versions_dir = path_join(mc_dir, "versions");
    let mut fallback = String::new();
    for dir_name in list_dir_names(&versions_dir) {
        let json_path = path_join(&path_join(&versions_dir, &dir_name), &format!("{}.json", dir_name));
        if let Ok(content) = std::fs::read_to_string(&json_path) {
            let id = json_get(&content, "id");
            if !id.is_empty() {
                if !id.contains("forge") && !id.contains("fabric") {
                    return id;
                }
                if fallback.is_empty() {
                    fallback = id;
                }
            }
        }
        if fallback.is_empty() && !dir_name.is_empty() {
            fallback = dir_name.clone();
        }
    }
    fallback
}
