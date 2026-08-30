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
