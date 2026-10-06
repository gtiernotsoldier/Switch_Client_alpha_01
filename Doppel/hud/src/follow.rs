// follow.rs — A2: keep the overlay on the game window, not on the rect it was born with.
//
// The injector passes `--rect` exactly once, from `GetWindowRect`, while the game is still
// loading. Any later fullscreen switch, windowed resize or monitor move leaves the overlay
// drawing on that old rect — which is what the user sees in fullscreen as a HUD stuck in the
// corner of what used to be the screen.
//
// `--follow-pid <pid>` makes the overlay re-read the game window's rect itself
// (`EnumWindows` + `GetWindowThreadProcessId` + `GetClientRect` + `ClientToScreen`) on a slow
// poll and re-place its window when the rect changes. `--rect` still supplies the initial
// placement, and remains the whole story when no pid is given.
//
// The poll reads the **client** area, not the window rect. `GetWindowRect` answers the whole
// window — title bar, borders and all — and a HUD anchored to its top-right corner lands over
// Minecraft's own title bar instead of inside the game's frame. `GetClientRect` is the
// drawable rectangle and `ClientToScreen` puts its origin on the screen, so the overlay covers
// exactly what the game draws into. (`--rect` is the injector's own one-shot reading and is
// unchanged: it is a fallback, not the live path.)
//
// Note for the user, repeated in the usage text: under **exclusive** fullscreen no external
// overlay can ever be visible — Windows hands the display to the game and a layered window is
// simply not composited. Windowed or borderless fullscreen is required.
//
// The same pid and the same poll also answer the newer question "is the game the window in
// front?" — see `should_show` at the bottom. The overlay's window is shown only while it is,
// and hidden (not exited) the rest of the time, so nothing floats over a browser the user has
// alt-tabbed to.

use crate::hud::Rect;
use crate::win::*;

/// How often the game window is re-read, and how often the "is the game in front?"
/// gate below re-decides.
///
/// One second: a resolution switch or a drag ends well inside that, and the poll is one
/// `EnumWindows` pass over the desktop's top-level windows — tens of microseconds, once a
/// second, next to a 60 Hz render loop. The gate rides the same cadence for the same reason:
/// `GetForegroundWindow` is cheap, but the answer only changes when the user switches windows,
/// and a per-frame `ShowWindow` would be a flicker waiting to happen.
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
    /// The window's client area in screen coordinates, `None` when the two calls failed or
    /// the area was degenerate.
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

/// The client area's screen rect from the two calls that produce it: `ClientToScreen`'s answer
/// for the client's own (0, 0), and `GetClientRect`'s size.
///
/// Both halves are plain numbers here, so "the title bar is not part of the overlay's rect" is
/// a property this file can test without a window. An origin that would overflow the addition,
/// or an empty client area, is `None` — the caller keeps the last good rect rather than moving
/// the HUD to a wrapped coordinate.
pub fn client_rect(origin_x: i32, origin_y: i32, client_w: i32, client_h: i32) -> Option<Rect> {
    let right = origin_x.checked_add(client_w)?;
    let bottom = origin_y.checked_add(client_h)?;
    rect_from_corners(origin_x, origin_y, right, bottom)
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

    // The client area, not the window rect: `GetWindowRect` includes the title bar and the
    // borders, and a HUD anchored to it sits over the game's own title bar. The two Win32
    // calls are kept apart — the size from `GetClientRect`, the position from
    // `ClientToScreen` — so `client_rect` is the one place the pair becomes a screen rect.
    let mut client: RectWin = std::mem::zeroed();
    let mut origin = Point { x: 0, y: 0 };
    let rect = if GetClientRect(hwnd, &mut client) != 0 && ClientToScreen(hwnd, &mut origin) != 0 {
        client_rect(origin.x, origin.y, client.right, client.bottom)
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

/// The **client** rect of the game window owned by `pid`, or `None` when there is nothing
/// usable.
///
/// `None` means "no information", never "move the overlay to (0, 0)": the caller keeps the last
/// good rect, so a game that is briefly minimised (or between two windows during a fullscreen
/// switch) does not teleport the HUD.
///
/// The rect is in physical pixels because `win::enable_dpi_awareness` runs before any window is
/// created, so neither call hands back virtualised coordinates on a scaled display.
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

// ── is the game the window in front? ────────────────────────────────────────
//
// A1: the overlay used to be an always-on-top layered window over *every*
// application — alt-tab to a browser and the HUD stayed glued on top of it, which
// is neither useful nor stealthy. The gate below is the fix: the window is shown
// only while the game is the one in front, and hidden otherwise.
//
// The same `--follow-pid` that names the game for the rect names it here, and the
// same slow poll drives both. The *rule* is pure (`should_show`) so it is tested
// without a game; only the two Win32 reads that feed it need a desktop.

/// The two facts the visibility gate reads from the desktop, as plain values so the
/// rule over them can be tested (`should_show`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Foreground {
    /// `GetForegroundWindow()` is a window this pid owns — i.e. the game is the
    /// application in front.
    pub game: bool,
    /// That window is minimised. Only meaningful when `game` is set; a game that is
    /// both "in front" and minimised is still a game the user cannot see.
    pub minimised: bool,
}

/// Ask the desktop which window is in front and whose it is.
///
/// `GetForegroundWindow` reports NULL while the session has no foreground window at
/// all (a lock screen, a desktop switch). That is answered exactly like "not the
/// game": hiding is the direction that cannot be wrong, and the next poll a second
/// later puts the HUD back. A pid that owns no window simply never matches.
///
/// pid 0 is refused before Win32 is asked anything — `--follow-pid` already rejects
/// it at parse time, and no window can belong to it.
pub fn foreground_state(pid: u32) -> Foreground {
    let none = Foreground { game: false, minimised: false };
    if pid == 0 {
        return none;
    }
    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.is_null() {
            return none;
        }
        let mut owner: u32 = 0;
        GetWindowThreadProcessId(hwnd, &mut owner);
        if owner != pid {
            return none;
        }
        Foreground { game: true, minimised: IsIconic(hwnd) != 0 }
    }
}

/// Should the overlay's window be on screen, given what is known about the game?
///
/// Pure on purpose: this is the whole of the new behaviour, and the only way to
/// check it without a game, a second application and a human alt-tabbing between
/// them is to make it a function of three booleans.
///
///   * **No pid** — nothing says which window is the game, so there is nothing to
///     gate on and the overlay keeps the behaviour it had before this rule existed:
///     always shown. The injector always passes `--follow-pid`; a hand-run
///     `doppel-hud.exe --rect ...` is the case this branch is for.
///   * **A pid** — shown while a window of that process is the foreground window
///     and is not minimised, hidden otherwise. While hidden the *process* keeps
///     running on purpose (see main.rs): exiting would mean no HUD when the game
///     comes back, and "hidden" here means "no window on screen", not "not running".
pub fn should_show(has_pid: bool, game_in_front: bool, game_minimised: bool) -> bool {
    if !has_pid {
        return true;
    }
    game_in_front && !game_minimised
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
            window(3, "", true, false, None), // the client-area calls failed
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

    /// The conversion the follow poll actually uses: the client area, which is *not* the window
    /// rect. A HUD anchored to `GetWindowRect` sits over the game's title bar — the bug this
    /// conversion exists to fix — so the difference between the two is pinned here.
    #[test]
    fn the_client_area_excludes_the_title_bar_and_borders() {
        // A 1280x720 client area in a window at (100, 50) with a 30 px title bar and a 2 px
        // border: `GetWindowRect` would answer (100, 50)..(1384, 802), 30 px too high and
        // 4 px too wide.
        let window = rect_from_corners(100, 50, 1384, 802).expect("a window rect");
        let client = client_rect(102, 80, 1280, 720).expect("a client rect");
        assert_eq!(client, Rect::new(102, 80, 1280, 720));
        assert_eq!(client.y - window.y, 30, "the title bar sits above the client area");
        assert!(client.h < window.h && client.w < window.w);
        // The frame is at the top and the sides, not below: the bottom edges agree
        // apart from the bottom border.
        assert_eq!(client.bottom(), 800);
        assert_eq!(window.bottom() - client.bottom(), 2, "the bottom border");

        // A fullscreen borderless window has no frame at all: the two agree, which is why the
        // bug only shows up in windowed mode.
        assert_eq!(
            client_rect(0, 0, 1920, 1080),
            rect_from_corners(0, 0, 1920, 1080),
            "a borderless window's client area is the whole window"
        );

        // Negative origins (a monitor left of the primary one) stay negative.
        assert_eq!(client_rect(-1920, -100, 1920, 1080), Some(Rect::new(-1920, -100, 1920, 1080)));

        // Degenerate and overflowing answers are refused, never wrapped: a zero-sized overlay
        // is invisible and a wrapped origin would put the HUD on another screen.
        assert_eq!(client_rect(10, 10, 0, 500), None, "zero width");
        assert_eq!(client_rect(10, 10, 500, 0), None, "zero height");
        assert_eq!(client_rect(10, 10, -500, 100), None, "inverted");
        assert_eq!(client_rect(i32::MAX, 0, 10, 10), None, "overflow");
        assert_eq!(client_rect(0, i32::MAX, 10, 10), None, "overflow");
    }

    #[test]
    fn an_empty_candidate_list_is_none_not_a_default_rect() {
        // `None` has to mean "no information" so the caller keeps the last good rect.
        assert_eq!(choose_window(&[]), None);
    }

    /// The rule this change is about, and the one that needs a game, a browser and
    /// a human alt-tabbing between them to see for real: the overlay is on screen
    /// exactly while the game is the window in front.
    #[test]
    fn the_hud_is_shown_only_over_the_game() {
        // No pid: nothing identifies the game window, so there is nothing to gate
        // on and the overlay keeps the always-shown behaviour it had before.
        assert!(should_show(false, false, false), "no pid: shown");
        assert!(should_show(false, true, false), "no pid: shown whatever is in front");
        assert!(should_show(false, true, true), "no pid: even a minimised game is not ours to judge");
        assert!(should_show(false, false, true), "no pid: shown");

        // A pid: the game is the foreground window and is not minimised.
        assert!(should_show(true, true, false), "pid + game in front: shown");

        // Any other application in front: hidden, whatever its own minimised state.
        assert!(!should_show(true, false, false), "pid + another app in front: hidden");
        assert!(!should_show(true, false, true));

        // Minimised is hidden even when the pid's window reads as the foreground one:
        // a minimised window is not something the HUD can be usefully over.
        assert!(!should_show(true, true, true), "pid + minimised: hidden");
    }

    /// The gate's query needs no desktop for the one answer it can give by itself:
    /// pid 0 owns no window (`--follow-pid` refuses it at parse time), so it is
    /// refused before Win32 is asked anything.
    #[test]
    fn pid_zero_is_never_the_games_foreground_window() {
        assert_eq!(foreground_state(0), Foreground { game: false, minimised: false });
    }
}
