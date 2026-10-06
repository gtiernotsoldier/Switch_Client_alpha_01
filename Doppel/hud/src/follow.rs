// follow.rs — A2: keep the overlay on the game window, not on the rect it was born with.
//
// The injector passes `--rect` exactly once, from `GetWindowRect`, while the game is still
// loading. Any later fullscreen switch, windowed resize or monitor move leaves the overlay
// drawing on that old rect — which is what the user sees in fullscreen as a HUD stuck in the
// corner of what used to be the screen.
//
// `--follow-pid <pid>` makes the overlay re-read the game window's rect itself
// (`EnumWindows` + `GetWindowThreadProcessId` + `GetWindowRect`) on a slow poll and re-place its
// window when the rect changes. `--rect` still supplies the initial placement, and remains the
// whole story when no pid is given.
//
// Note for the user, repeated in the usage text: under **exclusive** fullscreen no external
// overlay can ever be visible — Windows hands the display to the game and a layered window is
// simply not composited. Windowed or borderless fullscreen is required.

use crate::hud::Rect;
use crate::win::*;

/// How often the game window is re-read.
///
/// One second: a resolution switch or a drag ends well inside that, and the poll is one
/// `EnumWindows` pass over the desktop's top-level windows — tens of microseconds, once a
/// second, next to a 60 Hz render loop.
pub const POLL_MS: u64 = 1_000;

/// One window the search looked at.
///
/// Split out from the enumeration so the *choice* is a pure function over plain values and can
/// be tested with no desktop at all (see the tests below).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    /// `HWND` as a plain integer — the enum callback's own identity for the window.
    pub hwnd: usize,
    /// The window title, already lossily decoded. Used only for the "minecraft" preference.
    pub title: String,
    pub visible: bool,
    /// Minimised: its rect is the (-32000, -32000) icon position and must not be used.
    pub iconic: bool,
    /// `GetWindowRect`'s answer, `None` when the call failed or the rect was degenerate.
    pub rect: Option<Rect>,
}

/// Turn a Win32 RECT's corners into the overlay's origin+size form.
///
/// The one place that conversion happens, so a caller cannot mix the two conventions up. A
/// zero or negative extent is `None` rather than a rect: dragging a window produces that
/// transiently, and a zero-sized overlay is invisible.
pub fn rect_from_corners(left: i32, top: i32, right: i32, bottom: i32) -> Option<Rect> {
    let w = right.checked_sub(left)?;
    let h = bottom.checked_sub(top)?;
    if w <= 0 || h <= 0 {
        return None;
    }
    Some(Rect::new(left, top, w, h))
}

/// Pick the window the overlay should cover.
///
/// The injector's own search (`process::find_minecraft_window`) prefers a window whose title
/// mentions Minecraft and otherwise falls back to the first visible window of the process — a
/// fullscreen Java window routinely has no title at all. This mirrors that preference, with one
/// difference in the fallback: the **largest** window wins rather than the first, because a JVM
/// process can own small helper windows (a splash screen, an error dialog) that first-wins
/// would pick.
pub fn choose_window(candidates: &[Candidate]) -> Option<Rect> {
    let mut titled: Option<Rect> = None;
    let mut largest: Option<(i64, Rect)> = None;

    for candidate in candidates {
        if !candidate.visible || candidate.iconic {
            continue;
        }
        let Some(rect) = candidate.rect else { continue };
        if rect.is_empty() {
            continue;
        }
        let area = rect.w as i64 * rect.h as i64;
        if largest.map(|(best, _)| area > best).unwrap_or(true) {
            largest = Some((area, rect));
        }
        if titled.is_none() && candidate.title.to_lowercase().contains("minecraft") {
            titled = Some(rect);
        }
    }

    titled.or_else(|| largest.map(|(_, rect)| rect))
}

/// What one `EnumWindows` pass accumulates.
struct Search {
    pid: u32,
    candidates: Vec<Candidate>,
}

/// Collect every top-level window owned by the followed pid. Non-zero keeps the enumeration
/// going — stopping early would make the result depend on Z-order, which changes constantly.
unsafe extern "system" fn collect_window(hwnd: Hwnd, param: Lparam) -> i32 {
    if param == 0 {
        return 1;
    }
    let search = &mut *(param as *mut Search);

    let mut pid: u32 = 0;
    GetWindowThreadProcessId(hwnd, &mut pid);
    if pid != search.pid {
        return 1;
    }

    let mut title_buf = [0u16; 256];
    let len = GetWindowTextW(hwnd, title_buf.as_mut_ptr(), title_buf.len() as i32);
    let title = if len > 0 {
        String::from_utf16_lossy(&title_buf[..len as usize])
    } else {
        String::new()
    };

    let mut raw: RectWin = std::mem::zeroed();
    let rect = if GetWindowRect(hwnd, &mut raw) != 0 {
        rect_from_corners(raw.left, raw.top, raw.right, raw.bottom)
    } else {
        None
    };

    search.candidates.push(Candidate {
        hwnd: hwnd as usize,
        title,
        visible: IsWindowVisible(hwnd) != 0,
        iconic: IsIconic(hwnd) != 0,
        rect,
    });
    1
}

/// The rect of the game window owned by `pid`, or `None` when there is nothing usable.
///
/// `None` means "no information", never "move the overlay to (0, 0)": the caller keeps the last
/// good rect, so a game that is briefly minimised (or between two windows during a fullscreen
/// switch) does not teleport the HUD.
///
/// The rect is in physical pixels because `win::enable_dpi_awareness` runs before any window is
/// created, so `GetWindowRect` does not hand back virtualised coordinates on a scaled display.
pub fn game_window_rect(pid: u32) -> Option<Rect> {
    if pid == 0 {
        return None;
    }
    let mut search = Search { pid, candidates: Vec::new() };
    unsafe {
        EnumWindows(Some(collect_window), &mut search as *mut Search as Lparam);
    }
    choose_window(&search.candidates)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn window(hwnd: usize, title: &str, visible: bool, iconic: bool, rect: Option<Rect>) -> Candidate {
        Candidate { hwnd, title: title.to_string(), visible, iconic, rect }
    }

    #[test]
    fn a_titled_minecraft_window_wins_over_a_larger_untitled_one() {
        // The preference has to beat the area rule, or a big borderless window with no title
        // and a small titled one would be read in the opposite order to the injector's.
        let candidates = vec![
            window(1, "", true, false, Some(Rect::new(0, 0, 1920, 1080))),
            window(2, "Minecraft 1.8.9", true, false, Some(Rect::new(10, 10, 800, 600))),
        ];
        assert_eq!(choose_window(&candidates), Some(Rect::new(10, 10, 800, 600)));
    }

    #[test]
    fn an_untitled_fullscreen_window_is_still_found() {
        // This is the actual fullscreen case: the Java window has no title at all.
        let candidates = vec![window(1, "", true, false, Some(Rect::new(0, 0, 2560, 1440)))];
        assert_eq!(choose_window(&candidates), Some(Rect::new(0, 0, 2560, 1440)));
    }

    #[test]
    fn the_largest_untitled_window_wins() {
        // A splash screen or an error dialog must not become the overlay's rect.
        let candidates = vec![
            window(1, "", true, false, Some(Rect::new(0, 0, 320, 200))),
            window(2, "", true, false, Some(Rect::new(0, 0, 1280, 720))),
            window(3, "", true, false, Some(Rect::new(0, 0, 400, 300))),
        ];
        assert_eq!(choose_window(&candidates), Some(Rect::new(0, 0, 1280, 720)));
    }

    #[test]
    fn hidden_minimised_and_degenerate_windows_are_skipped() {
        let candidates = vec![
            window(1, "Minecraft", false, false, Some(Rect::new(0, 0, 1920, 1080))), // hidden
            window(2, "Minecraft", true, true, Some(Rect::new(-32000, -32000, 160, 30))), // minimised
            window(3, "", true, false, None), // GetWindowRect failed
            window(4, "", true, false, Some(Rect::new(0, 0, 0, 0))), // degenerate
        ];
        assert_eq!(choose_window(&candidates), None);
    }

    #[test]
    fn corner_conversion_rejects_non_rects() {
        assert_eq!(rect_from_corners(100, 50, 1380, 770), Some(Rect::new(100, 50, 1280, 720)));
        // Negative origins are legitimate: a monitor left of / above the primary one.
        assert_eq!(rect_from_corners(-1920, -100, 0, 980), Some(Rect::new(-1920, -100, 1920, 1080)));
        assert_eq!(rect_from_corners(10, 10, 10, 500), None, "zero width");
        assert_eq!(rect_from_corners(10, 10, 500, 10), None, "zero height");
        assert_eq!(rect_from_corners(500, 500, 10, 10), None, "inverted");
    }

    #[test]
    fn an_empty_candidate_list_is_none_not_a_default_rect() {
        // `None` has to mean "no information" so the caller keeps the last good rect.
        assert_eq!(choose_window(&[]), None);
    }
}
