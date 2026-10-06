# doppel-hud — out-of-process HUD overlay

A standalone Win32 process that puts a translucent card on top of the Minecraft
client. It is **not** part of the JVM: nothing is injected, no game class is
touched, and the overlay cannot crash the game by drawing.

This is the **D0** skeleton of the overlay plan: a real layered window with a
hand-rasterised rounded card. The data pipeline that will fill that card does not
exist yet.

## Status

| Phase | Scope | State |
| ----- | ----- | ----- |
| **D0** | Skeleton, transparent click-through window, rounded card + accent bar, argument parsing | **done** |
| D1 | Bitmap font baked into the binary, text rendering into the same buffer | not started |
| D2 | Shared-memory transport between the agent and this process | not started |
| D5 | JVM-side `drawHudCard` annotation on the render path | not started |
| D6 | De-characterised naming across the whole pipeline | partially — see below |

## Hard prerequisite: windowed / borderless fullscreen only

**The overlay does not appear under exclusive fullscreen.** A layered window is
composited by the desktop compositor (DWM); a game in exclusive fullscreen owns
the display output and bypasses that compositor, so nothing layered is ever drawn
on top of it. This is a property of the platform, not a bug that will be fixed
later.

Run Minecraft in **windowed** or **borderless fullscreen** mode. Note also that
the overlay is forbidden from taking focus at all (see below), so it can never
pull the game out of exclusive fullscreen by itself.

## Building

```sh
cargo build --release
```

Zero third-party dependencies: user32, gdi32 and kernel32 are called through raw
`extern "system"` FFI, exactly like `injector/src/window.rs`. The rasteriser in
`src/draw.rs` is pure Rust with no Win32 in it.

## Running

```sh
target/release/doppel-hud.exe [options]
```

| Option | Meaning | Default |
| ------ | ------- | ------- |
| `--x <i32>` | screen x of the card's top-left corner | `40` |
| `--y <i32>` | screen y of the card's top-left corner | `40` |
| `--w <u32>` | card width in physical pixels | `360` |
| `--h <u32>` | card height in physical pixels | `200` |
| `--hide` | print one line, create no window, exit 0 | — |
| `--help` | print usage, exit 0 | — |

`--hide` is the automation hook: it proves the executable starts, parses its
arguments and resolves its imports without touching the desktop, which is what a
CI smoke check needs from a release binary.

The console window is intentional — it is where `--hide` and argument errors
print. Stop the overlay from that console with Ctrl+C, from Task Manager, or with
`taskkill /IM doppel-hud.exe` (without `/F`: a plain `taskkill` posts `WM_CLOSE`,
which the window procedure turns into a clean `PostQuitMessage` and exit 0).

## What the window actually is

* Style `WS_POPUP`; extended styles `WS_EX_LAYERED | WS_EX_TOOLWINDOW |
  WS_EX_NOACTIVATE | WS_EX_TRANSPARENT`.
* Content comes from `UpdateLayeredWindow` fed by a top-down 32bpp DIB of
  premultiplied BGRA pixels (`CreateDIBSection` + a memory DC) — the classic
  Win32 path for per-pixel alpha, and the reason GDI's own `RoundRect` is not
  enough.
* The card is rasterised by hand — a rounded-rectangle distance field with
  coverage-based antialiasing in `src/draw.rs`. GDI's `RoundRect` is not used
  because it has neither alpha nor antialiasing.
* Card: `#0D111C` at 80% alpha, 8px corner radius, plus a 4px accent strip in
  `#3FD8E0`.
* **It never takes focus.** No `SetForegroundWindow`, no `SetFocus`, no
  `SetActiveWindow`, and `ShowWindow` is called with `SW_SHOWNOACTIVATE` — never
  `SW_SHOW`. Taking the foreground from the game is what makes a fullscreen game
  stop rendering, so it is the one thing this process must never do.
* **Click-through.** `WS_EX_TRANSPARENT` plus `WM_NCHITTEST` returning
  `HTTRANSPARENT`: every click goes to the game, none are swallowed.
* Not in the taskbar and not in Alt-Tab (`WS_EX_TOOLWINDOW`).

### Naming (plan §7, anti-signature)

The window class is `WndClsA1` and the window caption is the empty string:
nothing that enumerates windows looking for the product name will match. Keep it
that way — do not put a product name or phase name into the class, the caption,
or any window-derived string. `doppel-hud.exe` on disk is the only place the name
appears, and renaming the binary in D6 is a `Cargo.toml` change.

## Layout of the code

* `src/main.rs` — argument parsing, the window class and window, the DIB/blit
  path, the message loop, `WM_DESTROY` / `WM_CLOSE` / `WM_NCHITTEST`.
* `src/draw.rs` — the pure pixel layer: a premultiplied BGRA canvas, colour
  packing, coverage antialiasing, rounded rectangles. No Win32, so it is covered
  by unit tests that run anywhere (`cargo test`).

Redrawing is a single pass at startup: `UpdateLayeredWindow` keeps the composited
content, so there is nothing to repaint until the card's content can change — that
arrives with D1/D2.
