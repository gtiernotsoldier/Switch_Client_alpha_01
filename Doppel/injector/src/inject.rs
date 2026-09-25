// inject.rs — DLL injection core (ported from C++ inject.cpp)
//
// Architecture: Rust-led injector performs the injection; the C++ payload.dll
// (kept in payload/) does the in-process JNI attach work.
//
// Uses raw FFI to kernel32 (zero dependencies) — same calls as the old C++
// inject.cpp: OpenProcess, VirtualAllocEx, WriteProcessMemory,
// CreateRemoteThread(LoadLibraryA), CreateEventA, WaitForSingleObject.

use std::ffi::c_void;
use std::ptr;

use crate::process::ProcessInfo;
use crate::ui;

// ── kernel32 FFI declarations ──

#[link(name = "kernel32")]
extern "system" {
    fn OpenProcess(dw_desired_access: u32, b_inherit_handle: i32, dw_process_id: u32)
        -> *mut c_void;
    fn VirtualAllocEx(
        h_process: *mut c_void,
        lp_address: *const c_void,
        dw_size: usize,
        fl_allocation_type: u32,
        fl_protect: u32,
    ) -> *mut c_void;
    fn VirtualFreeEx(
        h_process: *mut c_void,
        lp_address: *mut c_void,
        dw_size: usize,
        dw_free_type: u32,
    ) -> i32;
    fn WriteProcessMemory(
        h_process: *mut c_void,
        lp_base_address: *mut c_void,
        lp_buffer: *const c_void,
        n_size: usize,
        lp_number_of_bytes_written: *mut usize,
    ) -> i32;
    fn GetModuleHandleA(lp_module_name: *const u8) -> *mut c_void;
    fn GetProcAddress(h_module: *mut c_void, lp_proc_name: *const u8) -> *mut c_void;
    fn CreateRemoteThread(
        h_process: *mut c_void,
        lp_thread_attributes: *const c_void,
        dw_stack_size: usize,
        lp_start_address: *mut c_void,
        lp_parameter: *mut c_void,
        dw_creation_flags: u32,
        lp_thread_id: *mut u32,
    ) -> *mut c_void;
    fn CreateEventA(
        lp_event_attributes: *const c_void,
        b_manual_reset: i32,
        b_initial_state: i32,
        lp_name: *const u8,
    ) -> *mut c_void;
    fn WaitForSingleObject(h_handle: *mut c_void, dw_milliseconds: u32) -> u32;
    fn CloseHandle(h_object: *mut c_void) -> i32;
    fn GetLastError() -> u32;
}

// Constants (kernel32 / winnt)
const PROCESS_CREATE_THREAD: u32 = 0x0002;
const PROCESS_QUERY_INFORMATION: u32 = 0x0400;
const PROCESS_VM_OPERATION: u32 = 0x0008;
const PROCESS_VM_WRITE: u32 = 0x0020;
const PROCESS_VM_READ: u32 = 0x0010;
const MEM_COMMIT: u32 = 0x1000;
const MEM_RESERVE: u32 = 0x2000;
const MEM_RELEASE: u32 = 0x8000;
const PAGE_READWRITE: u32 = 0x04;
const WAIT_OBJECT_0: u32 = 0x00000000;
const WAIT_TIMEOUT: u32 = 0x00000102;
/// CreateEventA sets this when the named object already existed — for our done
/// event that means a previous payload is still resident in the target.
const ERROR_ALREADY_EXISTS: u32 = 183;

/// Why an injection attempt stopped. The string is shown to the user as-is,
/// so it must say what actually went wrong — never a bare "failed".
pub type InjectResult = Result<(), String>;

/// Inject payload.dll into the target process and wait for it to finish
/// attaching the Java agent. Mirrors injectJavaAgent() from the C++ injector.
///
/// IMPORTANT: this returns Ok ONLY when the payload confirmed it loaded. The
/// old version returned true whenever the WinAPI calls did not error, so a
/// payload that never ran (the DLL already resident in the target — the
/// loader only bumps its refcount and never re-enters DllMain) was reported
/// as a success. That made the injector lie to the user. Every early exit
/// below carries a reason.
pub fn inject_java_agent(
    proc: &ProcessInfo,
    agent_path: &str,
    platform: &str,
    version: &str,
) -> InjectResult {
    ui::detail(&format!("DLL + JNI attach into PID {}", proc.pid));

    // 1. Write config file next to agent.jar
    let config_dir = match agent_path.rfind(['\\', '/']) {
        Some(pos) => &agent_path[..pos],
        None => "",
    };
    let config_path = format!("{}\\doppel-config.properties", config_dir);
    let cfg = format!(
        "doppel.platform={}\ndoppel.version={}\n",
        platform, version
    );
    if std::fs::write(&config_path, cfg).is_ok() {
        ui::detail(&format!("config written · {}", config_path));
    } else {
        ui::warn(&format!("config write failed · {}", config_path));
    }

    // 2. Extract payload.dll from embedded resource.
    let dll_path = crate::resource::get_embedded_payload_path();
    if dll_path.is_empty() {
        return Err("payload.dll could not be extracted from this exe".to_string());
    }

    // 3. Open target process
    let desired = PROCESS_CREATE_THREAD
        | PROCESS_QUERY_INFORMATION
        | PROCESS_VM_OPERATION
        | PROCESS_VM_WRITE
        | PROCESS_VM_READ;
    let h_process = unsafe { OpenProcess(desired, 0, proc.pid) };
    if h_process.is_null() {
        let err = unsafe { GetLastError() };
        // 5 = access denied: the game is elevated, or an anti-cheat/AV is
        // guarding the process. 87 = the pid is already gone.
        let hint = match err {
            5 => " (access denied — try running the injector as administrator)",
            87 => " (the process exited)",
            _ => "",
        };
        return Err(format!(
            "cannot open PID {} for injection — WinAPI error {}{}",
            proc.pid, err, hint
        ));
    }

    // 4. Allocate memory in target for DLL path
    let path_bytes = dll_path.as_bytes();
    let path_size = path_bytes.len() + 1;
    let p_remote_mem = unsafe {
        VirtualAllocEx(
            h_process,
            ptr::null(),
            path_size,
            MEM_COMMIT | MEM_RESERVE,
            PAGE_READWRITE,
        )
    };
    if p_remote_mem.is_null() {
        let err = unsafe { GetLastError() };
        unsafe { CloseHandle(h_process) };
        return Err(format!("VirtualAllocEx failed — WinAPI error {}", err));
    }

    // 5. Write DLL path into target
    let mut written: usize = 0;
    let ok = unsafe {
        WriteProcessMemory(
            h_process,
            p_remote_mem,
            path_bytes.as_ptr() as *const c_void,
            path_size,
            &mut written,
        )
    };
    if ok == 0 {
        let err = unsafe { GetLastError() };
        unsafe {
            VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
            CloseHandle(h_process);
        }
        return Err(format!("WriteProcessMemory failed — WinAPI error {}", err));
    }

    // 6. Find LoadLibraryA in target
    let h_kernel32 = unsafe { GetModuleHandleA(b"kernel32.dll\0".as_ptr()) };
    if h_kernel32.is_null() {
        unsafe {
            VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
            CloseHandle(h_process);
        }
        return Err("GetModuleHandleA(kernel32.dll) failed".to_string());
    }
    let p_load_library = unsafe { GetProcAddress(h_kernel32, b"LoadLibraryA\0".as_ptr()) };
    if p_load_library.is_null() {
        unsafe {
            VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
            CloseHandle(h_process);
        }
        return Err("GetProcAddress(LoadLibraryA) failed".to_string());
    }

    // Snapshot the payload log length BEFORE anything can be appended: the log
    // is opened append-only, so a completion line from an earlier injection is
    // still in the file and would otherwise be mistaken for this run's.
    let payload_log = format!(
        "{}\\doppel-payload.log",
        std::env::var("TEMP").unwrap_or_default()
    );
    let log_offset = std::fs::metadata(&payload_log)
        .map(|m| m.len() as usize)
        .unwrap_or(0);

    // 7. Create the named done event BEFORE spawning the remote thread, so the
    //    payload's OpenEventA always finds it (avoids a race where the payload
    //    thread runs before the event exists -> OpenEventA fails -> signalDone
    //    silently skipped -> injector waits the full timeout).
    let event_name = format!("DoppelPayloadDone_{}\0", proc.pid);
    let h_done_event = unsafe { CreateEventA(ptr::null(), 1, 0, event_name.as_ptr()) };
    if h_done_event.is_null() {
        ui::warn("could not create the completion event — falling back to a timed wait");
    } else {
        // payload.cpp opens this event with OpenEventA and NEVER closes it (the
        // handle lives in a static and the DLL never unloads), so after one
        // successful injection the object stays alive AND SIGNALED inside the
        // target process. CreateEventA would hand back that stale object —
        // bInitialState is ignored for an object that already exists — and
        // WaitForSingleObject would return immediately, so the injector would
        // report "agent loaded" for an injection that never happened.
        //
        // ERROR_ALREADY_EXISTS is therefore positive proof that a previous
        // payload is still resident in this process. Do not inject twice: a
        // second agent means a second WebUI, a second HUD and doubled packet
        // hooks. Stop here, before the remote thread is created.
        let existed = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
        if existed {
            unsafe {
                CloseHandle(h_done_event);
                VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
                CloseHandle(h_process);
            }
            return Err(format!(
                "PID {} already has Doppel loaded — nothing was injected; \
restart Minecraft and inject the fresh process",
                proc.pid
            ));
        }
        ui::detail(&format!("done event · DoppelPayloadDone_{}", proc.pid));
    }

    // 8. Create remote thread to load the DLL
    // LPTHREAD_START_ROUTINE = fn(LPVOID) -> DWORD; LoadLibraryA has signature
    // fn(LPCSTR) -> HMODULE — both are one-pointer-arg functions, so the raw
    // address is compatible.
    let h_thread = unsafe {
        CreateRemoteThread(
            h_process,
            ptr::null(),
            0,
            p_load_library,
            p_remote_mem,
            0,
            ptr::null_mut(),
        )
    };
    if h_thread.is_null() {
        let err = unsafe { GetLastError() };
        unsafe {
            VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
            CloseHandle(h_process);
        }
        return Err(format!(
            "CreateRemoteThread failed — WinAPI error {} (an anti-cheat or AV may be blocking it)",
            err
        ));
    }

    // 9. Wait for LoadLibraryA thread (DLL load)
    unsafe { WaitForSingleObject(h_thread, 10_000) };
    unsafe { CloseHandle(h_thread) };

    // 10. Wait for the payload to confirm it loaded. Poll in short slices and
    //     also watch the payload log for "bootstrap() completed" so we finish
    //     as soon as the agent is really in, instead of burning the timeout.
    //
    //     Only text appended AFTER we started counts: the log is append-only,
    //     so an earlier run's success line must not be read as ours.
    let mut confirmed = false;
    if !h_done_event.is_null() {
        ui::detail("waiting for the payload to finish attaching (up to 8s)");
        for _ in 0..16 {
            // Event signaled?
            let wait = unsafe { WaitForSingleObject(h_done_event, 500) };
            if wait == WAIT_OBJECT_0 {
                ui::detail("payload signaled completion — agent loaded");
                confirmed = true;
                break;
            }
            // Or payload log shows completion?
            if let Ok(log) = std::fs::read_to_string(&payload_log) {
                let fresh = if log.len() >= log_offset {
                    &log[log_offset..]
                } else {
                    &log[..]
                };
                if fresh.contains("Agent.bootstrap() completed successfully") {
                    ui::detail("payload log shows agent loaded");
                    confirmed = true;
                    break;
                }
                if fresh.contains("FATAL")
                    || fresh.contains("JVM not found")
                    || fresh.contains("AttachCurrentThread failed")
                {
                    unsafe {
                        CloseHandle(h_done_event);
                        VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
                        CloseHandle(h_process);
                    }
                    return Err(format!("the payload reported an error — see {}", payload_log));
                }
            }
        }
        unsafe { CloseHandle(h_done_event) };
    } else {
        // Without an event we cannot tell, so give the payload a short window
        // and then judge by the log.
        ui::detail("no completion event — waiting 3s as fallback");
        std::thread::sleep(std::time::Duration::from_secs(3));
        if let Ok(log) = std::fs::read_to_string(&payload_log) {
            let fresh = if log.len() >= log_offset {
                &log[log_offset..]
            } else {
                &log[..]
            };
            confirmed = fresh.contains("Agent.bootstrap() completed successfully");
        }
    }

    // 11. Cleanup
    unsafe {
        VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
        CloseHandle(h_process);
    }

    if !confirmed {
        // The remote thread ran, but the payload never reported in. Most often
        // the loader found the DLL already resident and therefore never called
        // DllMain at all. Saying "success" here is what made the injector lie.
        return Err(format!(
            "the payload never confirmed a load — nothing was injected. \
Check {} (if the DLL was already resident in that process, restart Minecraft first)",
            payload_log
        ));
    }

    Ok(())
}
