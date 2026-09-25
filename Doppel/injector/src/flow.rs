// flow.rs — the injection pipeline, with no opinion about who is watching.
//
// Sandwich Architecture — Layer 1 (injector). This is the single copy of the
// four-step flow. It used to live inside main.rs, hard-wired to the console
// UI; now it takes a `Reporter` so the same code drives the terminal timeline
// and the local web panel without diverging.
//
//   1. Linking with Minecraft — find the target javaw process + window
//   2. Preparing runtime     — detect version/platform, extract agent.jar
//   3. Injecting             — payload.dll performs the in-process JNI attach
//   4. Handoff               — wait for the WebUI panel broadcast, report it
//
// The C++ payload.dll stays C++ (it must run inside javaw.exe and talk JNI /
// the Windows Attach pipe); everything around it is Rust.

use std::time::Instant;

use crate::process::{self, ProcessInfo};
use crate::resource;
use crate::{inject, version};

pub const STEPS: [&str; 4] = [
    "Linking with Minecraft",
    "Preparing runtime",
    "Injecting",
    "Handoff",
];

/// How the flow talks to whoever is watching it.
///
/// Implementations must be cheap and non-blocking: `run()` calls these from
/// the injection thread while the terminal spinner and the HTTP server keep
/// running their own workers.
pub trait Reporter: Send + Sync {
    /// A step started spinning.
    fn step_begin(&self, idx: usize);
    /// Sub-status of the running step (e.g. "mc 1.8.9").
    fn step_note(&self, note: &str);
    /// Finished successfully; `secs` is its wall-clock duration.
    fn step_ok(&self, idx: usize, secs: f32);
    /// Finished with a failure; `reason` is shown to the user.
    fn step_fail(&self, idx: usize, reason: &str);
    /// Verbose-only detail line.
    fn detail(&self, msg: &str);
    /// Always-visible warning.
    fn warn(&self, msg: &str);
    /// Always-visible actionable hint.
    fn hint(&self, msg: &str);
}

/// What a successful run hands back to the caller.
#[derive(Debug, Clone, Default)]
pub struct Outcome {
    pub pid: u32,
    pub version: String,
    pub platform: String,
    /// WebUI panel URL broadcast by the agent, when it arrived in time.
    pub panel_url: Option<String>,
    pub panel_token: String,
}

/// Why a run stopped. `reason()` is what a human should read.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FlowError {
    NoProcess,
    BadVersion,
    NoAgent,
    InjectFailed,
}

impl FlowError {
    pub fn reason(self) -> &'static str {
        match self {
            FlowError::NoProcess => "no running Minecraft window found",
            FlowError::BadVersion => "could not identify the Minecraft version",
            FlowError::NoAgent => "embedded agent.jar missing from this exe",
            FlowError::InjectFailed => "the JVM rejected the payload",
        }
    }

    pub fn hint(self) -> &'static str {
        match self {
            FlowError::NoProcess => "launch Minecraft first, then pick its process",
            FlowError::BadVersion => "unsupported launcher layout — see %TEMP%\\doppel-payload.log",
            FlowError::NoAgent => "rebuild via CI — the fat jar step must run before cargo build",
            FlowError::InjectFailed => "check %TEMP%\\doppel-payload.log and doppel-agent.log",
        }
    }

    /// Which step index failed (used to mark the timeline red).
    pub fn step_index(self) -> usize {
        match self {
            FlowError::NoProcess => 0,
            FlowError::BadVersion | FlowError::NoAgent => 1,
            FlowError::InjectFailed => 2,
        }
    }
}

/// Run the whole pipeline against `target_pid`.
///
/// `None` means "auto-pick": the first java process that looks like Minecraft.
/// `Some(pid)` injects exactly that process, which is what the web panel does
/// — the user chose it from the list, so we don't second-guess the choice.
pub fn run(target_pid: Option<u32>, rep: &dyn Reporter) -> Result<Outcome, FlowError> {
    // ── Step 1 · Linking with Minecraft ──
    let t0 = Instant::now();
    rep.step_begin(0);
    let mc = match target_pid {
        Some(pid) => process::find_process_by_pid(pid),
        None => process::find_minecraft_process(),
    };
    if !mc.valid {
        rep.step_fail(0, FlowError::NoProcess.reason());
        rep.hint(FlowError::NoProcess.hint());
        return Err(FlowError::NoProcess);
    }
    rep.detail(&format!("PID {} · window \"{}\"", mc.pid, mc.window_title));
    rep.step_ok(0, t0.elapsed().as_secs_f32());

    // ── Step 2 · Preparing runtime ──
    let t1 = Instant::now();
    rep.step_begin(1);
    let info = version::parse_minecraft_version(&mc.path, &mc.window_title);
    if !info.valid {
        rep.step_fail(1, FlowError::BadVersion.reason());
        rep.hint(FlowError::BadVersion.hint());
        return Err(FlowError::BadVersion);
    }
    rep.detail(&format!("version {} · platform {}", info.version, info.platform));
    rep.step_note(&format!("mc {}", info.version));

    let agent_path = resource::get_embedded_agent_path();
    if agent_path.is_empty() {
        rep.step_fail(1, FlowError::NoAgent.reason());
        rep.hint(FlowError::NoAgent.hint());
        return Err(FlowError::NoAgent);
    }
    rep.detail(&format!("agent ready · {}", agent_path));
    rep.step_ok(1, t1.elapsed().as_secs_f32());

    // ── Step 3 · Injecting ──
    let t2 = Instant::now();
    rep.step_begin(2);
    rep.step_note(&format!("{} · {}", info.platform, info.version));
    if !inject::inject_java_agent(&mc, &agent_path, &info.platform, &info.version) {
        rep.step_fail(2, FlowError::InjectFailed.reason());
        rep.hint(FlowError::InjectFailed.hint());
        return Err(FlowError::InjectFailed);
    }
    rep.step_ok(2, t2.elapsed().as_secs_f32());

    // ── Step 4 · Handoff ──
    let t3 = Instant::now();
    rep.step_begin(3);
    rep.step_note("waiting for panel");
    let (panel_url, panel_token) = wait_panel_broadcast(6);
    if panel_url.is_some() {
        rep.detail("panel broadcast captured");
    } else {
        rep.warn("panel not broadcast yet — it keeps starting in the background");
        rep.hint("the address will appear in %TEMP%\\doppel-payload.log");
    }
    rep.step_ok(3, t3.elapsed().as_secs_f32());

    Ok(Outcome {
        pid: mc.pid,
        version: info.version,
        platform: info.platform,
        panel_url,
        panel_token,
    })
}

/// Poll %TEMP%\doppel-payload.log for the WebUI broadcast line:
///   "[WebUI] Panel: <url...>  Token: <token>"
/// Returns (first url, token). Empty strings when not broadcast within budget.
pub fn wait_panel_broadcast(max_secs: u64) -> (Option<String>, String) {
    let log_path = format!("{}doppel-payload.log", resource::temp_dir());
    let deadline = Instant::now() + std::time::Duration::from_secs(max_secs);
    while Instant::now() < deadline {
        if let Ok(log) = std::fs::read_to_string(&log_path) {
            if let Some(line) = log.lines().rev().find(|l| l.contains("[WebUI] Panel:")) {
                let rest = match line.find("[WebUI] Panel:") {
                    Some(i) => line[i + "[WebUI] Panel:".len()..].trim(),
                    None => "",
                };
                let (urls_part, token) = match rest.find("Token:") {
                    Some(i) => (rest[..i].trim(), rest[i + "Token:".len()..].trim()),
                    None => (rest, ""),
                };
                let url = urls_part
                    .split_whitespace()
                    .find(|s| s.starts_with("http"))
                    .unwrap_or("")
                    .to_string();
                let url_opt = if url.is_empty() { None } else { Some(url) };
                return (url_opt, token.to_string());
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(400));
    }
    (None, String::new())
}

/// The candidate list the web panel shows. Re-scanned on every call so a
/// Minecraft that is still booting shows up without restarting the injector.
pub fn candidates() -> Vec<ProcessInfo> {
    process::list_java_processes()
}
