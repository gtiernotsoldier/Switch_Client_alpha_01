// inject.rs — DLL injection core (ported from C++ inject.cpp)
//
// Architecture: Rust-led injector performs the injection; the C++ payload.dll
// (kept in payload/) does the in-process JNI attach work. This module only
// does what the old inject.cpp did: write config, extract payload.dll,
// CreateRemoteThread(LoadLibraryA), wait for the payload's done event.

use std::ffi::c_void;
use std::ffi::CString;
use std::ptr;

use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, WAIT_OBJECT_0, WAIT_TIMEOUT};
use windows_sys::Win32::System::LibraryLoader::{GetModuleHandleA, GetProcAddress};
use windows_sys::Win32::System::Memory::{
    VirtualAllocEx, VirtualFreeEx, WriteProcessMemory, MEM_COMMIT, MEM_RELEASE, MEM_RESERVE,
    PAGE_READWRITE,
};
use windows_sys::Win32::System::Threading::{
    CreateEventA, CreateRemoteThread, OpenProcess, WaitForSingleObject, LPTHREAD_START_ROUTINE,
    PROCESS_CREATE_THREAD, PROCESS_QUERY_INFORMATION, PROCESS_VM_OPERATION, PROCESS_VM_READ,
    PROCESS_VM_WRITE,
};

use crate::process::ProcessInfo;

type ThreadStart = unsafe extern "system" fn(*mut c_void) -> u32;

/// Inject payload.dll into the target process and wait for it to finish
/// attaching the Java agent. Mirrors injectJavaAgent() from the C++ injector.
pub fn inject_java_agent(
    proc: &ProcessInfo,
    agent_path: &str,
    platform: &str,
    version: &str,
) -> bool {
    println!("[Inject] Injecting via DLL + JNI into PID {}...", proc.pid);

    // 1. Write config file next to agent.jar
    let config_dir = match agent_path.rfind(['\\', '/']) {
        Some(pos) => &agent_path[..pos],
        None => "",
    };
    let config_path = format!("{}\\switchlite-config.properties", config_dir);
    let cfg = format!(
        "switchlite.platform={}\nswitchlite.version={}\n",
        platform, version
    );
    if std::fs::write(&config_path, cfg).is_ok() {
        println!("[Inject] Config written: {}", config_path);
    } else {
        eprintln!("[Inject] Failed to write config: {}", config_path);
    }

    // 2. Extract payload.dll from embedded resource.
    let dll_path = crate::resource::get_embedded_payload_path();
    if dll_path.is_empty() {
        eprintln!("[Inject] Failed to extract payload.dll");
        return false;
    }

    // 3. Open target process
    let desired = PROCESS_CREATE_THREAD
        | PROCESS_QUERY_INFORMATION
        | PROCESS_VM_OPERATION
        | PROCESS_VM_WRITE
        | PROCESS_VM_READ;
    let h_process = unsafe { OpenProcess(desired, 0, proc.pid) };
    if h_process == 0 {
        eprintln!(
            "[Inject] Cannot open process (error: {})",
            unsafe { GetLastError() }
        );
        return false;
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
        eprintln!(
            "[Inject] VirtualAllocEx failed (error: {})",
            unsafe { GetLastError() }
        );
        unsafe { CloseHandle(h_process) };
        return false;
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
        eprintln!(
            "[Inject] WriteProcessMemory failed (error: {})",
            unsafe { GetLastError() }
        );
        unsafe {
            VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
            CloseHandle(h_process);
        }
        return false;
    }

    // 6. Find LoadLibraryA in target
    let h_kernel32 = unsafe { GetModuleHandleA("kernel32.dll\0".as_ptr() as *const i8) };
    let p_load_library = unsafe { GetProcAddress(h_kernel32, "LoadLibraryA\0".as_ptr() as *const i8) };
    if p_load_library.is_null() {
        eprintln!("[Inject] GetProcAddress(LoadLibraryA) failed");
        unsafe {
            VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
            CloseHandle(h_process);
        }
        return false;
    }

    // 7. Create remote thread to load the DLL
    let start_routine: LPTHREAD_START_ROUTINE =
        Some(std::mem::transmute::<_, ThreadStart>(p_load_library));
    let h_thread = unsafe {
        CreateRemoteThread(
            h_process,
            ptr::null(),
            0,
            start_routine,
            p_remote_mem,
            0,
            ptr::null_mut(),
        )
    };
    if h_thread == 0 {
        eprintln!(
            "[Inject] CreateRemoteThread failed (error: {})",
            unsafe { GetLastError() }
        );
        unsafe {
            VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
            CloseHandle(h_process);
        }
        return false;
    }

    // 8. Named event for payload completion (PID-scoped to avoid collisions)
    let event_name = format!("SwitchLitePayloadDone_{}", proc.pid);
    let event_cstr = CString::new(event_name.clone()).unwrap_or_default();
    let h_done_event = unsafe { CreateEventA(ptr::null(), 1, 0, event_cstr.as_ptr()) };
    if h_done_event.is_null() {
        eprintln!("[Inject] Failed to create done event");
    } else {
        println!("[Inject] Created done event: {}", event_name);
    }

    // 9. Wait for LoadLibraryA thread (DLL load)
    unsafe { WaitForSingleObject(h_thread, 10_000) };
    unsafe { CloseHandle(h_thread) };

    // 10. Wait for payload to signal real completion
    if !h_done_event.is_null() {
        println!("[Inject] Waiting for payload to complete (up to 15s)...");
        let wait = unsafe { WaitForSingleObject(h_done_event, 15_000) };
        match wait {
            WAIT_OBJECT_0 => {
                println!("[Inject] [+] Payload signaled completion (Agent loaded)")
            }
            WAIT_TIMEOUT => eprintln!(
                "[Inject] [!] Payload timed out after 15s — Agent may not have loaded"
            ),
            other => eprintln!("[Inject] [!] Wait error: {}", other),
        }
        unsafe { CloseHandle(h_done_event) };
    } else {
        println!("[Inject] [!] No done event, waiting 5s as fallback...");
        std::thread::sleep(std::time::Duration::from_secs(5));
    }

    // 11. Cleanup
    unsafe {
        VirtualFreeEx(h_process, p_remote_mem, 0, MEM_RELEASE);
        CloseHandle(h_process);
    }

    println!("[+] Java Agent injected successfully via DLL");
    true
}
