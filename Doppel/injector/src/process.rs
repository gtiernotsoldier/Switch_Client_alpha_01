// process.rs — Minecraft process detection
//
// Ported from C++ process.cpp: Toolhelp32 snapshot to find javaw.exe/java.exe,
// then EnumWindows to find a visible window whose title contains "minecraft",
// then OpenProcess + EnumProcessModulesEx + GetModuleFileNameExA for the exe
// path. Raw FFI to kernel32/psapi/user32 — zero deps.

use std::ffi::c_void;
use std::ptr;

/// Information about a detected Minecraft process.
#[derive(Debug, Clone, Default)]
pub struct ProcessInfo {
    pub pid: u32,
    pub valid: bool,
    pub window_title: String,
    pub path: String,
}

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
    fn ReadProcessMemory(
        h_process: *mut c_void,
        lp_base_address: *const c_void,
        lp_buffer: *mut c_void,
        n_size: usize,
        lp_number_of_bytes_read: *mut usize,
    ) -> i32;
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
/// Tolerates missing title — a visible javaw window is treated as a hit even
/// if the title doesn't literally contain "minecraft" (the launcher may have
/// changed the title, or the game is fullscreen).
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
    if len > 0 {
        let title = String::from_utf16_lossy(&title_buf[..len as usize]);
        if title.to_lowercase().contains("minecraft") {
            st.found_title = title;
            st.found = true;
            return 0; // stop — clear minecraft title
        }
        // Any visible window on a javaw process counts as a hit too, but keep
        // scanning in case a better "minecraft"-titled window exists.
        if !st.found {
            st.found_title = title;
            st.found = true;
        }
    } else if !st.found {
        // Visible window with no title — still a hit (fullscreen MC).
        st.found_title = String::new();
        st.found = true;
    }
    1 // continue scanning
}

struct WindowSearch {
    target_pid: u32,
    found: bool,
    found_title: String,
}

/// Read the command line of a process (NtQueryInformationProcess -> PEB ->
/// RTL_USER_PROCESS_PARAMETERS). Returns empty on any failure.
/// Used to confirm a javaw.exe process is actually Minecraft (cmdline
/// contains ".minecraft" or the minecraft main class) — more reliable than
/// window-title matching.
fn read_process_command_line(pid: u32) -> String {
    unsafe {
        // OpenProcess with query info
        let h = OpenProcess(PROCESS_QUERY_INFORMATION, 0, pid);
        if h.is_null() {
            return String::new();
        }
        // NtQueryInformationProcess is in ntdll; declare it here.
        extern "system" {
            fn NtQueryInformationProcess(
                process_handle: *mut c_void,
                process_information_class: u32,
                process_information: *mut c_void,
                process_information_length: u32,
                return_length: *mut u32,
            ) -> i32;
        }
        // PROCESS_BASIC_INFORMATION (class 0)
        #[repr(C)]
        struct PBI {
            exit_status: *mut c_void,
            peb_base_address: *mut c_void,
            affinity_mask: usize,
            base_priority: i32,
            unique_process_id: usize,
            inherited_from_unique_process_id: usize,
        }
        let mut pbi: PBI = std::mem::zeroed();
        let mut ret_len: u32 = 0;
        let status = NtQueryInformationProcess(
            h,
            0,
            &mut pbi as *mut PBI as *mut c_void,
            std::mem::size_of::<PBI>() as u32,
            &mut ret_len,
        );
        CloseHandle(h);
        if status != 0 || pbi.peb_base_address.is_null() {
            return String::new();
        }

        // Read PEB -> ProcessParameters (offset 0x20 on x64, 0x10 on x86)
        // CommandLine is UNICODE_STRING at offset 0x70 (x64) / 0x40 (x86).
        // This is brittle; on failure we fall back to empty (title matching
        // already accepted the process).
        #[cfg(target_pointer_width = "64")]
        const PARAMS_OFFSET: usize = 0x20;
        #[cfg(target_pointer_width = "32")]
        const PARAMS_OFFSET: usize = 0x10;

        let h2 = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, 0, pid);
        if h2.is_null() {
            return String::new();
        }

        // Read the PEB pointer value
        let mut peb_ptr_bytes = [0u8; 8];
        let mut read: usize = 0;
        let ok = ReadProcessMemory(
            h2,
            pbi.peb_base_address,
            peb_ptr_bytes.as_mut_ptr() as *mut c_void,
            8,
            &mut read,
        );
        if ok == 0 {
            CloseHandle(h2);
            return String::new();
        }
        let peb_addr = usize::from_le_bytes(peb_ptr_bytes);

        // PEB + PARAMS_OFFSET -> ProcessParameters pointer
        let mut params_bytes = [0u8; 8];
        let ok = ReadProcessMemory(
            h2,
            (peb_addr + PARAMS_OFFSET) as *mut c_void,
            params_bytes.as_mut_ptr() as *mut c_void,
            8,
            &mut read,
        );
        if ok == 0 {
            CloseHandle(h2);
            return String::new();
        }
        let params_addr = usize::from_le_bytes(params_bytes);

        // ProcessParameters + 0x70 -> UNICODE_STRING { Length, MaxLen, Buffer }
        #[cfg(target_pointer_width = "64")]
        const CMDLINE_OFFSET: usize = 0x70;
        #[cfg(target_pointer_width = "32")]
        const CMDLINE_OFFSET: usize = 0x40;

        let mut us_bytes = [0u8; 16];
        let ok = ReadProcessMemory(
            h2,
            (params_addr + CMDLINE_OFFSET) as *mut c_void,
            us_bytes.as_mut_ptr() as *mut c_void,
            16,
            &mut read,
        );
        CloseHandle(h2);
        if ok == 0 {
            return String::new();
        }
        let length = u16::from_le_bytes([us_bytes[0], us_bytes[1]]) as usize;
        let buffer_addr = usize::from_le_bytes(us_bytes[8..16].try_into().unwrap_or([0u8; 8]));
        if length == 0 || buffer_addr == 0 {
            return String::new();
        }

        // Read the command line chars
        let h3 = OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, 0, pid);
        if h3.is_null() {
            return String::new();
        }
        let mut buf = vec![0u8; length];
        let mut read: usize = 0;
        let ok = ReadProcessMemory(
            h3,
            buffer_addr as *mut c_void,
            buf.as_mut_ptr() as *mut c_void,
            length,
            &mut read,
        );
        CloseHandle(h3);
        if ok == 0 {
            return String::new();
        }
        let units: Vec<u16> = buf
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes([c[0], c[1]]))
            .collect();
        String::from_utf16_lossy(&units)
    }
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
                // IMPORTANT: szExeFile is a C string (UTF-16, NUL-terminated).
                // Truncate at the first NUL — from_utf16_lossy on the whole
                // array pads with trailing NULs and the name match fails.
                let exe_raw = &pe.sz_exe_file;
                let len = exe_raw.iter().position(|&c| c == 0).unwrap_or(exe_raw.len());
                let exe = String::from_utf16_lossy(&exe_raw[..len]);
                let exe_lower = exe.to_lowercase();
                if exe_lower == "javaw.exe" || exe_lower == "java.exe" {
                    // Confirm it's Minecraft via command line (".minecraft" or
                    // net.minecraft.client.main.Main). Falls back to window
                    // title scan if cmdline can't be read.
                    let cmdline = read_process_command_line(pe.th32_process_id);
                    let cmd_lower = cmdline.to_lowercase();
                    let is_mc_cmdline =
                        cmd_lower.contains(".minecraft") || cmd_lower.contains("net.minecraft");
                    let mut search = WindowSearch {
                        target_pid: pe.th32_process_id,
                        found: false,
                        found_title: String::new(),
                    };
                    EnumWindows(
                        Some(find_minecraft_window),
                        &mut search as *mut WindowSearch as isize,
                    );
                    let has_window = search.found;

                    if is_mc_cmdline || has_window {
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
