// resource.rs — embedded resource extraction (agent.jar, payload.dll)
//
// Ported from C++ inject.cpp: FindResourceA / LoadResource / SizeofResource /
// LockResource read the RCDATA resources embedded in the EXE itself, then the
// bytes are written to %TEMP%. Raw FFI to kernel32 — zero deps.

use std::ffi::c_void;
use std::ptr;

// Resource IDs (must match the .rc file)
const AGENT_JAR_RCDATA: u16 = 101;
const PAYLOAD_DLL_RCDATA: u16 = 102;
const HUD_EXE_RCDATA: u16 = 103;
const RT_RCDATA: *const u16 = 10u16 as *const u16; // MAKEINTRESOURCE(10)

// ── kernel32 resource FFI ──

#[link(name = "kernel32")]
extern "system" {
    fn FindResourceW(
        h_module: *mut c_void,
        lp_name: *const u16,
        lp_type: *const u16,
    ) -> *mut c_void;
    fn LoadResource(h_module: *mut c_void, h_res_info: *mut c_void) -> *mut c_void;
    fn SizeofResource(h_module: *mut c_void, h_res_info: *mut c_void) -> u32;
    fn LockResource(h_res_data: *mut c_void) -> *mut c_void;
    fn GetLastError() -> u32;
}

/// MAKEINTRESOURCE-style helper: resource ID as a pointer-encoded u16.
fn make_int_resource(id: u16) -> *const u16 {
    id as *const u16
}

/// Extract an embedded RCDATA resource [res_id] and write it to [out_path].
/// Returns true on success.
fn extract_resource(res_id: u16, out_path: &str) -> bool {
    unsafe {
        let h_res = FindResourceW(
            ptr::null_mut(), // current module (the exe)
            make_int_resource(res_id),
            RT_RCDATA,
        );
        if h_res.is_null() {
            eprintln!(
                "[Resource] FindResource failed for id {} (error: {})",
                res_id,
                GetLastError()
            );
            return false;
        }

        let h_mem = LoadResource(ptr::null_mut(), h_res);
        if h_mem.is_null() {
            eprintln!("[Resource] LoadResource failed for id {}", res_id);
            return false;
        }

        let size = SizeofResource(ptr::null_mut(), h_res) as usize;
        if size == 0 {
            eprintln!("[Resource] SizeofResource returned 0 for id {}", res_id);
            return false;
        }

        let data = LockResource(h_mem);
        if data.is_null() {
            eprintln!("[Resource] LockResource failed for id {}", res_id);
            return false;
        }

        // Copy bytes out of the resource (Rust slice from raw pointer)
        let bytes = std::slice::from_raw_parts(data as *const u8, size);
        match std::fs::write(out_path, bytes) {
            Ok(_) => {
                println!("[Resource] Extracted {} ({} bytes)", out_path, size);
                true
            }
            Err(e) => {
                eprintln!("[Resource] Failed to write {}: {}", out_path, e);
                false
            }
        }
    }
}

/// Temp dir path (%TEMP%) with trailing backslash.
pub fn temp_dir() -> String {
    #[cfg(windows)]
    {
        if let Ok(tmp) = std::env::var("TEMP") {
            if !tmp.is_empty() {
                let last = tmp.chars().last().unwrap_or('\0');
                if last == '\\' || last == '/' {
                    return tmp;
                }
                return format!("{}\\", tmp);
            }
        }
        if let Ok(tmp) = std::env::var("TMP") {
            if !tmp.is_empty() {
                return format!("{}\\", tmp.trim_end_matches(['\\', '/']));
            }
        }
    }
    String::new()
}

/// Extract the embedded agent.jar and return its temp path.
pub fn get_embedded_agent_path() -> String {
    let dir = temp_dir();
    if dir.is_empty() {
        return String::new();
    }
    let path = format!("{}doppel-agent.jar", dir);
    if extract_resource(AGENT_JAR_RCDATA, &path) {
        path
    } else {
        String::new()
    }
}

/// Extract the embedded payload.dll and return its temp path.
pub fn get_embedded_payload_path() -> String {
    let dir = temp_dir();
    if dir.is_empty() {
        return String::new();
    }
    let path = format!("{}doppel-payload.dll", dir);
    if extract_resource(PAYLOAD_DLL_RCDATA, &path) {
        path
    } else {
        String::new()
    }
}

/// Extract the embedded external HUD overlay and return its temp path.
pub fn get_embedded_hud_path() -> String {
    let dir = temp_dir();
    if dir.is_empty() {
        return String::new();
    }
    let path = format!("{}doppel-hud.exe", dir);
    if extract_resource(HUD_EXE_RCDATA, &path) {
        path
    } else {
        String::new()
    }
}

/// Start the external HUD overlay once the game has published its first state block.
///
/// Waiting for the file is the cleanest readiness signal available: the JVM creates it on its very
/// first publish (`HudStateWriter`), so the overlay never starts into the long "the game is still
/// loading" window — and if the game never publishes, no overlay is started at all. The wait runs on
/// a detached thread, so the injection flow itself is never delayed by it.
pub fn launch_hud_when_ready() {
    std::thread::spawn(|| {
        let exe = get_embedded_hud_path();
        if exe.is_empty() {
            return;
        }
        let state = format!("{}doppel-hud.state", temp_dir());
        let state_path = std::path::Path::new(&state);
        // Up to ~30 s: Forge + OptiFine + world load, and the JVM only publishes once rendering runs.
        for _ in 0..300 {
            if state_path.exists() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        if !state_path.exists() {
            return;
        }
        // Best-effort single instance: the name is fixed for now (de-characterisation is a later
        // pass); ERROR_ALREADY_EXISTS simply means an overlay is already running for this session.
        if !claim_overlay_slot() {
            return;
        }
        let _ = std::process::Command::new(&exe).spawn();
    });
}

/// Named-mutex claim, so a second injection does not stack a second overlay.
fn claim_overlay_slot() -> bool {
    use std::ffi::c_void;
    #[link(name = "kernel32")]
    extern "system" {
        fn CreateMutexW(attrs: *mut c_void, initial_owner: i32, name: *const u16) -> *mut c_void;
        fn GetLastError() -> u32;
    }
    const ERROR_ALREADY_EXISTS: u32 = 183;
    // "DoppelHudOverlay" as UTF-16, NUL terminated.
    let name: Vec<u16> = "DoppelHudOverlay\0".encode_utf16().collect();
    unsafe {
        let handle = CreateMutexW(std::ptr::null_mut(), 1, name.as_ptr());
        if handle.is_null() {
            return true; // cannot tell: prefer showing the overlay over silently not showing it
        }
        GetLastError() != ERROR_ALREADY_EXISTS
    }
}
