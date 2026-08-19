// process.rs — Minecraft process detection
//
// Ported from C++ process.cpp: Toolhelp32 snapshot to find javaw.exe/java.exe,
// then EnumWindows to find a visible window whose title contains "minecraft",
// then OpenProcess + EnumProcessModulesEx + GetModuleFileNameExA for the exe
// path. Raw FFI to kernel32/psapi/user32 — zero deps.

use std::ffi::c_void;
use std::ptr;

use crate::process::ProcessInfo;

// ── Win32 types ──

#[repr(C)]
struct PROCESSENTRY32W {
    dw_size: u32,
    cnt_usage: u32,
    th32_process_id: u32,
    th32_default_heap_id: usize,
    th32_module_id: u32,
    cnt_threads: u32,
    th32_parent_process_id: u32,
    pc_pri_class_base: i32,
    dw_flags: u32,
    sz_exe_file: [u16; 260],
}

const TH32CS_SNAPPROCESS: u32 = 0x00000002;
const INVALID_HANDLE_VALUE: isize = -1;
const PROCESS_QUERY_INFORMATION: u32 = 0x0400;
const PROCESS_VM_READ: u32 = 0x0010;
const MAX_PATH: usize = 260;

// ── kernel32 / psapi / user32 FFI ──

#[link(name = "kernel32")]
extern "system" {
    fn CreateToolhelp32Snapshot(dw_flags: u32, th32_process_id: u32) -> *mut c_void;
    fn Process32FirstW(h_snapshot: *mut c_void, lppe: *mut PROCESSENTRY32W) -> i32;
    fn Process32NextW(h_snapshot: *mut c_void, lppe: *mut PROCESSENTRY32W) -> i32;
    fn OpenProcess(
        dw_desired_access: u32,
        b_inherit_handle: i32,
        dw_process_id: u32,
    ) -> *mut c_void;
    fn CloseHandle(h_object: *mut c_void) -> i32;
    fn GetLastError() -> u32;
}

#[link(name = "psapi")]
extern "system" {
    fn EnumProcessModulesEx(
        h_process: *mut c_void,
        lph_module: *mut *mut c_void,
        cb: u32,
        lpcb_needed: *mut u32,
        dw_filter_flag: u32,
    ) -> i32;
    fn GetModuleFileNameExW(
        h_process: *mut c_void,
        h_module: *mut c_void,
        lp_filename: *mut u16,
        n_size: u32,
    ) -> u32;
}

#[link(name = "user32")]
extern "system" {
    fn EnumWindows(lp_enum_func: Option<unsafe extern "system" fn(*mut c_void, isize) -> i32>, l_param: isize) -> i32;
    fn GetWindowThreadProcessId(h_wnd: *mut c_void, lpdw_process_id: *mut u32) -> u32;
    fn IsWindowVisible(h_wnd: *mut c_void) -> i32;
    fn GetWindowTextW(h_wnd: *mut c_void, lp_string: *mut u16, n_max_count: i32) -> i32;
}

// ── Window title matching via EnumWindows ──

/// Search a visible top-level window owned by [target_pid] whose title
/// (lowercased) contains "minecraft". Returns the window title if found.
unsafe extern "system" fn find_minecraft_window(h_wnd: *mut c_void, l_param: isize) -> i32 {
    // Recover the shared search state
    let state = l_param as *mut WindowSearch;
    if state.is_null() {
        return 0;
    }
    let st = &mut *state;

    let mut window_pid: u32 = 0;
    GetWindowThreadProcessId(h_wnd, &mut window_pid);
    if window_pid != st.target_pid {
        return 1; // continue
    }
    if IsWindowVisible(h_wnd) == 0 {
        return 1;
    }

    let mut title_buf = [0u16; 256];
    let len = GetWindowTextW(h_wnd, title_buf.as_mut_ptr(), 256);
    if len <= 0 {
        return 1;
    }
    let title = String::from_utf16_lossy(&title_buf[..len as usize]);
    if title.to_lowercase().contains("minecraft") {
        st.found_title = title;
        st.found = true;
        return 0; // stop
    }
    1 // continue
}

struct WindowSearch {
    target_pid: u32,
    found: bool,
    found_title: String,
}

pub fn find_minecraft_process() -> ProcessInfo {
    let mut result = ProcessInfo::default();

    unsafe {
        let h_snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if h_snapshot as isize == INVALID_HANDLE_VALUE || h_snapshot.is_null() {
            return result;
        }

        let mut pe: PROCESSENTRY32W = std::mem::zeroed();
        pe.dw_size = std::mem::size_of::<PROCESSENTRY32W>() as u32;

        if Process32FirstW(h_snapshot, &mut pe) != 0 {
            loop {
                // sz_exe_file is UTF-16; compare lowercased to javaw.exe / java.exe
                let exe = String::from_utf16_lossy(&pe.sz_exe_file);
                let exe_lower = exe.to_lowercase();
                if exe_lower == "javaw.exe" || exe_lower == "java.exe" {
                    // Search windows of this PID for a "minecraft" title
                    let mut search = WindowSearch {
                        target_pid: pe.th32_process_id,
                        found: false,
                        found_title: String::new(),
                    };
                    EnumWindows(
                        Some(find_minecraft_window),
                        &mut search as *mut WindowSearch as isize,
                    );

                    if search.found {
                        result.pid = pe.th32_process_id;
                        result.valid = true;
                        result.window_title = search.found_title;

                        // Get exe path
                        let h_proc = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, 0, pe.th32_process_id);
                        if !h_proc.is_null() {
                            let mut h_mods: [*mut c_void; 1024] = [ptr::null_mut(); 1024];
                            let mut cb_needed: u32 = 0;
                            if EnumProcessModulesEx(
                                h_proc,
                                h_mods.as_mut_ptr(),
                                (h_mods.len() * std::mem::size_of::<*mut c_void>()) as u32,
                                &mut cb_needed,
                                0x03, // LIST_MODULES_32BIT | LIST_MODULES_64BIT
                            ) != 0
                            {
                                let mut mod_name = [0u16; MAX_PATH];
                                let got = GetModuleFileNameExW(h_proc, h_mods[0], mod_name.as_mut_ptr(), MAX_PATH as u32);
                                if got > 0 {
                                    result.path = String::from_utf16_lossy(&mod_name[..got as usize]);
                                }
                            }
                            CloseHandle(h_proc);
                        }
                        break;
                    }
                }

                if Process32NextW(h_snapshot, &mut pe) == 0 {
                    break;
                }
            }
        }
        CloseHandle(h_snapshot);
    }

    result
}
