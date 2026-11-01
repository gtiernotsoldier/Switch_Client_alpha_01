// log.rs — the overlay's own breadcrumbs, so "it started but nothing appeared"
// can be answered from a file instead of from the absence of a window.
//
// One append-only text file, `%TEMP%\doppel-hud.log`, next to the state file (the
// injector already points at it from `injector/src/resource.rs`). The startup line
// carries the local time, the pid, the rect the window covers, the resolved state
// file and whether it was readable; the exit line carries why the process stopped
// and how many frames it pushed to the compositor. Between them they separate the
// three ways "no overlay on screen" can happen:
//
//   * no line at all            — the exe never ran (see the injector's launch log)
//   * a start line, no frames   — it ran, but the state file never appeared
//   * frames but nothing seen   — it drew; the rect line says where it drew
//
// Nothing here may break the overlay: the file is opened best-effort, every write
// ignores its result, and a log over `MAX_BYTES` is truncated on the next start so
// it cannot grow without bound. Timestamps come from `GetLocalTime` (win.rs),
// because `std::time` speaks UTC only and a third-party date crate is not allowed.

use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

#[cfg(windows)]
use crate::win::{GetLocalTime, SystemTimeW};

/// Truncate on start above this: ~70 KB is roughly a thousand start/exit pairs,
/// far more than the two lines a run of this process produces.
pub const MAX_BYTES: u64 = 64 * 1024;

/// An open, best-effort log. `file` is `None` when the path could not be opened
/// (no temp directory, a locked file): every method then quietly does nothing,
/// which is the whole contract — logging must never fail the overlay.
pub struct Log {
    file: Option<File>,
}

impl Log {
    /// Open `%TEMP%\doppel-hud.log` for appending.
    pub fn open() -> Log {
        Log::open_at(&default_path())
    }

    /// Append one line, stamped with the local time and the pid so two overlays
    /// (or two days) cannot be confused for one another.
    pub fn line(&mut self, message: &str) {
        let Some(file) = self.file.as_mut() else {
            return;
        };
        let _ = writeln!(file, "{} [pid {}] {}", local_timestamp(), std::process::id(), message);
    }

    /// Open `path` for appending, first truncating a log that outgrew the cap. A
    /// failure to open is not an error: the overlay runs exactly the same without
    /// a log.
    pub fn open_at(path: &Path) -> Log {
        let existing = fs::metadata(path).map(|meta| meta.len()).ok();
        let over = existing.filter(|len| *len > MAX_BYTES);
        let file = open(path, over.is_some()).ok();
        let mut log = Log { file };
        if let Some(bytes) = over {
            log.line(&format!("log truncated on start (was {bytes} bytes, cap {MAX_BYTES})"));
        }
        log
    }
}

/// Append, or create-and-truncate. Separate from `open_at` so the "is it too big"
/// decision is testable without a real cap-sized file in the caller.
fn open(path: &Path, truncate: bool) -> std::io::Result<File> {
    let mut options = OpenOptions::new();
    options.create(true).write(true);
    if truncate {
        options.truncate(true);
    } else {
        options.append(true);
    }
    options.open(path)
}

/// `%TEMP%\doppel-hud.log` — the same directory `HudStateWriter` publishes the
/// state file into, so one `%TEMP%` listing shows both halves of the hand-off.
pub fn default_path() -> PathBuf {
    std::env::temp_dir().join("doppel-hud.log")
}

/// `YYYY-MM-DD HH:MM:SS.mmm` in the machine's local time zone. All zeros if the
/// system will not say — a line with a useless timestamp still beats no line.
#[cfg(windows)]
pub fn local_timestamp() -> String {
    let mut time: SystemTimeW = unsafe { std::mem::zeroed() };
    unsafe {
        GetLocalTime(&mut time);
    }
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}",
        time.year, time.month, time.day, time.hour, time.minute, time.second, time.milliseconds
    )
}

/// Off Windows there is no `GetLocalTime`: the review build logs UTC with the
/// same 23-character format (the year is the only coarse field).
#[cfg(not(windows))]
pub fn local_timestamp() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(d) => {
            let secs = d.as_secs();
            let (hh, mm, ss) = ((secs / 3600) % 24, (secs / 60) % 60, secs % 60);
            format!(
                "{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}",
                1970 + (secs / 31_556_952) as u64,
                1,
                1,
                hh,
                mm,
                ss,
                d.subsec_millis()
            )
        }
        Err(_) => "0000-00-00 00:00:00.000".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The cap is enforced on start, not on every line: a log over 64 KB is
    /// truncated once, then appended to normally.
    #[test]
    fn an_oversized_log_is_truncated_on_start() {
        let path = std::env::temp_dir().join("doppel-hud-log-test-cap.log");
        let _ = fs::remove_file(&path);

        fs::write(&path, vec![b'x'; MAX_BYTES as usize + 1]).unwrap();
        let mut log = Log::open_at(&path);
        log.line("first line of the new run");
        let text = fs::read_to_string(&path).unwrap();
        assert!(text.len() < 256, "the old contents must be gone, got {} bytes", text.len());
        assert!(text.contains("log truncated on start"), "the cut is reported: {text}");
        assert!(text.contains("first line of the new run"));

        // Under the cap, the previous run's lines survive: the log is a history.
        let mut log = Log::open_at(&path);
        log.line("second run");
        let text = fs::read_to_string(&path).unwrap();
        assert!(text.contains("first line of the new run") && text.contains("second run"));

        let _ = fs::remove_file(&path);
    }

    /// A path that cannot be opened leaves a `Log` that silently absorbs lines —
    /// the overlay keeps running with no log rather than failing to start.
    #[test]
    fn an_unopenable_path_never_fails_and_stamps_local_time() {
        // A missing parent directory: `create(true)` does not make one.
        let nowhere = std::env::temp_dir().join("doppel-hud-log-no-such-dir").join("x.log");
        let mut log = Log::open_at(&nowhere);
        log.line("this line goes nowhere");

        // `YYYY-MM-DD HH:MM:SS.mmm`, e.g. 2026-10-06 14:43:23.123.
        let stamp = local_timestamp();
        assert_eq!(stamp.len(), 23, "unexpected timestamp: {stamp}");
        assert_eq!(&stamp[4..5], "-", "unexpected timestamp: {stamp}");
        assert_eq!(&stamp[10..11], " ", "unexpected timestamp: {stamp}");
        assert_eq!(&stamp[13..14], ":", "unexpected timestamp: {stamp}");
        assert!(stamp.chars().next().unwrap().is_ascii_digit());
    }
}
