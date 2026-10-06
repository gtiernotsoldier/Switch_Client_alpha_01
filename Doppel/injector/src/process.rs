// process.rs — Java process enumeration + Minecraft detection
//
// Ported from C++ process.cpp: Toolhelp32 snapshot to find javaw.exe/java.exe,
// then EnumWindows to find a visible window whose title contains "minecraft",
// then OpenProcess + EnumProcessModulesEx + GetModuleFileNameExA for the exe
// path. Raw FFI to kernel32/psapi/user32 — zero deps.
//
// Two entry points:
//   list_java_processes()     — every javaw/java process, for the web picker
//   find_minecraft_process()  — the first one that looks like Minecraft
//
// The injector no longer guesses by itself: the panel lists these and the user
// picks the PID. Auto-pick stays as the headless/`--pid`-less fallback.

use std::ffi::c_void;
use std::ptr;

/// Information about a detected Java process.
#[derive(Debug, Clone, Default)]
pub struct ProcessInfo {
    pub pid: u32,
    pub valid: bool,
    pub window_title: String,
    pub path: String,
    /// Executable file name, lowercased ("javaw.exe" / "java.exe").
    pub exe: String,
    /// Full command line, when the PEB read succeeds. Empty otherwise.
    pub cmdline: String,
    /// True when the cmdline or the window title says this is Minecraft.
    pub is_minecraft: bool,
    /// True when a doppel payload module is already loaded in this process.
    pub injected: bool,
}

impl ProcessInfo {
    /// A short, panel-friendly rendering of the command line: the version id
    /// or main class, without the -D flags and classpath noise.
    pub fn cmdline_hint(&self) -> String {
        // Slice the *lowercased* copy, never `self.cmdline`: to_lowercase() can
        // change a string's byte length (`İ` -> `i̇`), so an index found in one
        // is not guaranteed to be a char boundary in the other, and slicing
        // there would panic.
        let lower = self.cmdline.to_lowercase();
        // Forge/OptiFine installs put the interesting token in --version.
        for flag in ["--version ", "--gamedir "] {
            if let Some(i) = lower.find(flag) {
                let rest = &lower[i + flag.len()..];
                let token = rest
                    .trim_start_matches('"')
                    .split(['"', ' '])
                    .next()
                    .unwrap_or("");
                if !token.is_empty() {
                    return token.to_string();
                }
            }
        }
        if let Some(i) = lower.find("net.minecraft") {
            return lower[i..].split_whitespace().next().unwrap_or("").to_string();
        }
        if let Some(i) = lower.find(".minecraft") {
            return lower[i..].split_whitespace().next().unwrap_or("").to_string();
        }
        String::new()
    }
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
    fn GetModuleBaseNameW(
        h_process: *mut c_void,
        h_module: *mut c_void,
        lp_base_name: *mut u16,
        n_size: u32,
    ) -> u32;
}

#[link(name = "user32")]
extern "system" {
    fn EnumWindows(lp_enum_func: Option<unsafe extern "system" fn(*mut c_void, isize) -> i32>, l_param: isize) -> i32;
    fn GetWindowThreadProcessId(h_wnd: *mut c_void, lpdw_process_id: *mut u32) -> u32;
    fn IsWindowVisible(h_wnd: *mut c_void) -> i32;
    fn GetWindowTextW(h_wnd: *mut c_void, lp_string: *mut u16, n_max_count: i32) -> i32;
    fn GetWindowRect(h_wnd: *mut c_void, rect: *mut Rect) -> i32;
    fn IsIconic(h_wnd: *mut c_void) -> i32;
}

/// Win32 `RECT`: four LONGs, left/top/right/bottom — note the *corners*, not
/// origin+size, which is why the conversion below subtracts rather than copies.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct Rect {
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
}

// ── the game window's rect → the overlay's `--rect` ──────────────────────────

/// The rectangle the game window occupies on the virtual desktop, in physical
/// pixels: origin plus size, which is the form `doppel-hud --rect` takes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WindowRect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

impl WindowRect {
    /// Build one from a Win32 `RECT`'s corners.
    ///
    /// Pure on purpose: this is the arithmetic that decides where the overlay
    /// lands, so it is tested without a window. `None` means "there is no usable
    /// rect here" — a minimised or zero-sized window — which the caller turns into
    /// "spawn the overlay with no `--rect`" rather than a degenerate overlay.
    ///
    /// Note what is *not* done: the result is never clamped or intersected with a
    /// monitor. On a multi-monitor desktop the game's rect routinely starts at a
    /// negative x (a monitor to the left of the primary one) or has a negative y
    /// (one above it), and the overlay must cover the game, not the primary
    /// monitor.
    pub fn from_corners(left: i32, top: i32, right: i32, bottom: i32) -> Option<WindowRect> {
        let w = right.checked_sub(left)?;
        let h = bottom.checked_sub(top)?;
        // A zero or negative extent is not a rect: dragging a window can produce
        // it, and `--rect` refuses it anyway.
        if w <= 0 || h <= 0 {
            return None;
        }
        Some(WindowRect { x: left, y: top, w, h })
    }

    /// The overlay's `--rect x y w h`. Same numbers, as the strings the CLI wants.
    pub fn rect_args(self) -> [String; 5] {
        [
            "--rect".to_string(),
            self.x.to_string(),
            self.y.to_string(),
            self.w.to_string(),
            self.h.to_string(),
        ]
    }

    /// `1280x720 at (0, 0)` — the launch log's one-line form.
    pub fn label(self) -> String {
        format!("{}x{} at ({}, {})", self.w, self.h, self.x, self.y)
    }
}

// ── Window rect lookup via EnumWindows ──

/// Find the visible top-level window owned by `pid` and return its screen rect.
///
/// This is the same window `find_minecraft_window` already picks out for the
/// process list — the search below records the `HWND` alongside the title, which
/// is the one thing the panel's version threw away. The overlay is then spawned
/// onto the game's own rect so it follows the game on any monitor and at any
/// window size, instead of covering the primary monitor.
///
/// `None` when the process has no visible top-level window, when the window is
/// minimised, or when its rect is degenerate; the caller then starts the overlay
/// with no `--rect` at all (the primary monitor).
pub fn find_game_window_rect(pid: u32) -> Option<WindowRect> {
    find_game_window_rect_raw(pid)
}

/// The window lookup plus the one thing the rect alone cannot say: whether its
/// numbers are in the coordinate space the overlay draws in.
///
/// The overlay calls `SetProcessDPIAware`, so the rect it is given must be in
/// physical pixels. This process is a plain DPI-unaware GUI app — making it aware
/// process-wide would re-scale the launcher window, which is not this change's
/// business — so on a scaled display `GetWindowRect` answers in *virtualised*
/// coordinates: a 150 % display reports a 1920x1080 game as 1280x720, and the
/// overlay would land on the wrong part of the screen. `physical` reports whether
/// the per-thread fix below worked; the launch log records it either way.
pub struct GameWindow {
    pub pid: u32,
    pub rect: Option<WindowRect>,
    /// True when the rect is known to be in physical pixels.
    pub physical: bool,
}

/// Look up the game window and, best effort, in physical pixels.
pub fn find_game_window(pid: u32) -> GameWindow {
    // Per-thread, so the launcher window on the main thread keeps its own scaling.
    let physical = use_physical_pixels_on_this_thread();
    GameWindow { pid, rect: find_game_window_rect(pid), physical }
}

/// `SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)`.
///
/// Resolved through `GetProcAddress` rather than declared as an import: the call
/// only exists from Windows 10 1703, and a static import of it would stop the
/// injector from *loading* on anything older. Returns false when the API is not
/// there, which is the signal that the rect may be virtualised.
fn use_physical_pixels_on_this_thread() -> bool {
    /// `DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2` is the pseudo-handle `(HANDLE)-4`.
    const PER_MONITOR_AWARE_V2: *mut c_void = -4isize as *mut c_void;

    type SetThreadDpiAwarenessContext = unsafe extern "system" fn(*mut c_void) -> *mut c_void;

    #[link(name = "kernel32")]
    extern "system" {
        fn LoadLibraryW(name: *const u16) -> *mut c_void;
        fn GetProcAddress(module: *mut c_void, name: *const u8) -> *mut c_void;
    }

    unsafe {
        let module: Vec<u16> = "user32.dll\0".encode_utf16().collect();
        let handle = LoadLibraryW(module.as_ptr());
        if handle.is_null() {
            return false;
        }
        let symbol = GetProcAddress(handle, b"SetThreadDpiAwarenessContext\0".as_ptr());
        if symbol.is_null() {
            return false;
        }
        let set_context: SetThreadDpiAwarenessContext = std::mem::transmute(symbol);
        // The return is the *previous* context, and NULL means the call failed —
        // an unaware process reports UNAWARE, which is not NULL.
        !set_context(PER_MONITOR_AWARE_V2).is_null()
    }
}

fn find_game_window_rect_raw(pid: u32) -> Option<WindowRect> {
    if pid == 0 {
        return None;
    }
    let mut search = WindowSearch {
        target_pid: pid,
        found: false,
        titled: false,
        found_title: String::new(),
        hwnd: ptr::null_mut(),
    };
    unsafe {
        EnumWindows(Some(find_minecraft_window), &mut search as *mut WindowSearch as isize);
    }
    let hwnd = search.hwnd;
    if hwnd.is_null() {
        return None;
    }
    unsafe {
        // A minimised window reports the classic (-32000, -32000) icon position,
        // which would put the overlay in the corner of the virtual desktop.
        if IsIconic(hwnd) != 0 {
            return None;
        }
        let mut rect = Rect::default();
        if GetWindowRect(hwnd, &mut rect) == 0 {
            return None;
        }
        WindowRect::from_corners(rect.left, rect.top, rect.right, rect.bottom)
    }
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
            st.titled = true;
            st.hwnd = h_wnd;
            return 0; // stop — clear minecraft title
        }
        // Any visible window on a javaw process counts as a hit too, but keep
        // scanning in case a better "minecraft"-titled window exists.
        if !st.found {
            st.found_title = title;
            st.found = true;
            st.hwnd = h_wnd;
        }
    } else if !st.found {
        // Visible window with no title — still a hit (fullscreen MC).
        st.found_title = String::new();
        st.found = true;
        st.hwnd = h_wnd;
    }
    1 // continue scanning
}

struct WindowSearch {
    target_pid: u32,
    found: bool,
    /// True when the hit came from a title that literally says minecraft.
    titled: bool,
    found_title: String,
    /// The `HWND` behind `found_title`. Only `find_game_window_rect` uses it —
    /// the panel listing keeps to the title — but the enum callback is where the
    /// handle is cheapest to capture, so it is recorded for both.
    hwnd: *mut c_void,
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

/// Fill in `path` (image path) and `injected` (does a doppel module live in
/// this process?) by walking the target's loaded modules.
fn probe_modules(pid: u32, info: &mut ProcessInfo) {
    unsafe {
        let h_proc = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, 0, pid);
        if h_proc.is_null() {
            return;
        }
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
            let count = (cb_needed as usize / std::mem::size_of::<*mut c_void>()).min(h_mods.len());
            if count > 0 {
                let mut mod_name = [0u16; MAX_PATH];
                let got = GetModuleFileNameExW(h_proc, h_mods[0], mod_name.as_mut_ptr(), MAX_PATH as u32);
                if got > 0 {
                    info.path = String::from_utf16_lossy(&mod_name[..got as usize]);
                }
            }
            // Any module whose base name mentions doppel means we are already in.
            for m in h_mods.iter().take(count) {
                let mut base = [0u16; MAX_PATH];
                let got = GetModuleBaseNameW(h_proc, *m, base.as_mut_ptr(), MAX_PATH as u32);
                if got > 0 {
                    let name = String::from_utf16_lossy(&base[..got as usize]).to_lowercase();
                    if name.contains("doppel") {
                        info.injected = true;
                    }
                }
            }
        }
        CloseHandle(h_proc);
    }
}

/// Every javaw.exe / java.exe process on the machine, most useful first
/// (Minecraft candidates, then the rest). This is what the web panel lists.
pub fn list_java_processes() -> Vec<ProcessInfo> {
    let mut out: Vec<ProcessInfo> = Vec::new();

    unsafe {
        let h_snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if h_snapshot as isize == INVALID_HANDLE_VALUE || h_snapshot.is_null() {
            return out;
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
                    let pid = pe.th32_process_id;
                    let mut info = ProcessInfo {
                        pid,
                        valid: true,
                        exe: exe_lower.clone(),
                        ..Default::default()
                    };

                    // Command line is the reliable Minecraft signal.
                    let cmdline = read_process_command_line(pid);
                    let cmd_lower = cmdline.to_lowercase();
                    let is_mc_cmdline =
                        cmd_lower.contains(".minecraft") || cmd_lower.contains("net.minecraft");
                    info.cmdline = cmdline;

                    // Window title is the fallback signal (and the nicer label).
                    let mut search = WindowSearch {
                        target_pid: pid,
                        found: false,
                        titled: false,
                        found_title: String::new(),
                        // The panel only needs the title; the same callback is
                        // what `find_game_window` reads the handle out of.
                        hwnd: ptr::null_mut(),
                    };
                    EnumWindows(
                        Some(find_minecraft_window),
                        &mut search as *mut WindowSearch as isize,
                    );
                    info.window_title = search.found_title;
                    info.is_minecraft = is_mc_cmdline || search.titled;

                    probe_modules(pid, &mut info);
                    out.push(info);
                }

                if Process32NextW(h_snapshot, &mut pe) == 0 {
                    break;
                }
            }
        }
        CloseHandle(h_snapshot);
    }

    // Minecraft first, then by pid so the list is stable between polls.
    out.sort_by(|a, b| {
        b.is_minecraft
            .cmp(&a.is_minecraft)
            .then(a.pid.cmp(&b.pid))
    });
    out
}

/// Auto-pick: the first process that looks like Minecraft.
pub fn find_minecraft_process() -> ProcessInfo {
    list_java_processes()
        .into_iter()
        .find(|p| p.is_minecraft)
        .unwrap_or_default()
}

/// Look up one specific PID from the java process list. Used when the user
/// picked a row in the panel — `valid` stays false if that PID is gone.
pub fn find_process_by_pid(pid: u32) -> ProcessInfo {
    let mut found = list_java_processes()
        .into_iter()
        .find(|p| p.pid == pid)
        .unwrap_or_default();
    if found.pid == 0 {
        return ProcessInfo::default();
    }
    // A hand-picked process is a valid target even if our heuristics didn't
    // label it as Minecraft — the user knows what they clicked.
    found.valid = true;
    found
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `GetWindowRect` answers with corners; `--rect` wants origin plus size. The
    /// subtraction is the whole conversion, and getting it wrong (passing
    /// `right`/`bottom` as the width/height) would make the overlay enormous.
    #[test]
    fn a_window_rect_becomes_an_origin_and_a_size() {
        let rect = WindowRect::from_corners(100, 50, 1380, 770).unwrap();
        assert_eq!((rect.x, rect.y, rect.w, rect.h), (100, 50, 1280, 720));
        assert_eq!(rect.label(), "1280x720 at (100, 50)");
        assert_eq!(
            rect.rect_args(),
            ["--rect", "100", "50", "1280", "720"]
        );
    }

    /// The rect is handed over exactly as the game reports it, including the
    /// negative origins that are normal the moment a second monitor is involved.
    /// The overlay must cover the *game*, so nothing here may clamp or intersect
    /// with the primary monitor.
    #[test]
    fn the_game_rect_is_never_clamped_to_the_primary_monitor() {
        // A monitor to the left of the primary one.
        let left = WindowRect::from_corners(-1920, 120, -320, 1020).unwrap();
        assert_eq!((left.x, left.y, left.w, left.h), (-1920, 120, 1600, 900));
        assert_eq!(left.rect_args()[1], "-1920");

        // A monitor above it.
        let above = WindowRect::from_corners(0, -1080, 1920, 0).unwrap();
        assert_eq!((above.x, above.y, above.w, above.h), (0, -1080, 1920, 1080));

        // A windowed game smaller than the monitor stays smaller.
        let small = WindowRect::from_corners(300, 200, 1580, 920).unwrap();
        assert_eq!((small.w, small.h), (1280, 720));
    }

    /// A degenerate window — minimised, zero-sized, or a drag in progress — has
    /// no usable rect, and the caller turns that into "no `--rect`" (the primary
    /// monitor) rather than an overlay nobody can see.
    #[test]
    fn an_empty_window_rect_is_not_a_rect() {
        assert_eq!(WindowRect::from_corners(0, 0, 0, 720), None);
        assert_eq!(WindowRect::from_corners(0, 0, 1280, 0), None);
        assert_eq!(WindowRect::from_corners(500, 500, 100, 100), None);
        assert_eq!(WindowRect::from_corners(-32000, -32000, -32000, -32000), None);
        // One pixel is still a window.
        assert!(WindowRect::from_corners(9, 9, 10, 10).is_some());
    }

    /// The subtraction is checked, not wrapping: a pathological window that
    /// reports `right < left` across the i32 range must not turn into a
    /// 4-billion-pixel-wide overlay.
    #[test]
    fn an_overflowing_corner_pair_is_refused_rather_than_wrapped() {
        assert_eq!(WindowRect::from_corners(i32::MIN, 0, i32::MAX, 100), None);
        assert_eq!(WindowRect::from_corners(0, i32::MIN, 100, i32::MAX), None);
    }

    /// A pid of 0 means "no process was chosen"; the lookup must not enumerate
    /// windows on behalf of nothing.
    #[test]
    fn a_zero_pid_has_no_window() {
        assert_eq!(find_game_window_rect(0), None);
        let found = find_game_window(0);
        assert_eq!(found.pid, 0);
        assert!(found.rect.is_none());
    }
}
