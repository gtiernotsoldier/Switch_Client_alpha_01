// resource.rs — embedded resource extraction (agent.jar, payload.dll)
//
// Step 1: stub returning placeholder paths. Real PE-resource extraction
// (FindResource/LoadResource) is migrated in a later step.

/// Extract the embedded agent.jar and return its temp path.
pub fn get_embedded_agent_path() -> String {
    // TODO(step N): FindResource/SizeofResource/LockResource from the PE
    // resources (RCDATA 101), write to %TEMP%\switchlite-agent.jar.
    String::new()
}

/// Extract the embedded payload.dll and return its temp path.
pub fn get_embedded_payload_path() -> String {
    // TODO(step N): FindResource/SizeofResource/LockResource from the PE
    // resources (RCDATA 102), write to %TEMP%\switchlite-payload.dll.
    //
    // Step-1 fallback: use a file next to the executable so the flow is
    // testable before resource extraction lands.
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_default();
    let sidecar = exe_dir.join("payload.dll");
    if sidecar.exists() {
        return sidecar.to_string_lossy().into_owned();
    }
    String::new()
}
