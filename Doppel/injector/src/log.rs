// log.rs — the injector's own log file.
//
// The injector used to leave no trace of its own: the only copy of a failure
// lived in the console window and (briefly) in the browser tab, and both were
// gone by the time anyone looked. Every panel event and step transition now
// lands in %TEMP%\doppel-injector.log so a failed run can be read afterwards.

use std::io::Write;

/// HH:MM:SS from the wall clock (UTC — close enough for a short-lived log).
fn hms() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let day = secs % 86_400;
    format!("{:02}:{:02}:{:02}", day / 3600, (day % 3600) / 60, day % 60)
}

/// Append one line. Best-effort by design: a log that cannot be written must
/// never break an injection.
pub fn append(line: &str) {
    let path = format!("{}doppel-injector.log", crate::resource::temp_dir());
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(f, "[{}] {}", hms(), line);
    }
}
