# doppel-hud — out-of-process HUD overlay

A standalone Win32 process that puts the HUD card — brand pill plus module list —
on top of the Minecraft client. It is **not** part of the JVM: nothing is
injected, no game class is touched, and the overlay cannot crash the game by
drawing.

The card is the same design the in-JVM renderer drew (`drawHudCard` +
`drawGlass` + the palette in `Theme.kt`): the same pill geometry, paddings,
colours, the ACCENT→ACCENT2 spine down the list, the 350 ms staggered row
entrance and the ~300 ms work flash.

## Status

| Phase | Scope | State |
| ----- | ----- | ----- |
| **D0** | Skeleton, transparent click-through window, argument parsing | **done** |
| **D1** | Text: GDI (Segoe UI / Consolas) rasterised into the premultiplied canvas | **done** |
| **D2** | State transport: read `%TEMP%\doppel-hud.state` every frame, read-only | **done** |
| **D3** | Brand pill + module list, entrance animation | **done** |
| **D4** | Flash decay keyed on module id | **done** |
| **D5** | JVM-side hook that stops drawing in-game and calls `HudStateWriter.publish` | not started |
| **D6** | De-characterised naming across the whole pipeline | partially — see below |

Until D5 lands, nothing publishes the state file, so the overlay renders an empty
card and exits three seconds after it starts.

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
`src/draw.rs` is pure Rust with no Win32 in it. `cargo test` covers the pixel
blending, the rounded-box distance field and the state parser.

## Running

```sh
target/release/doppel-hud.exe [options]
```

| Option | Meaning | Default |
| ------ | ------- | ------- |
| `--x <i32>` / `--y <i32>` | screen position of the stage's top-left corner | `0`, `0` |
| `--w <u32>` / `--h <u32>` | stage size in physical pixels | `520x400` stacked, screen width x 400 split |
| `--position <where>` | `stacked` (pill on top, list under it), `left` (list left, pill right) or `right` (list right, pill left) | `stacked` |
| `--dump <path.bmp>` | render one representative frame to a 32-bit BMP, print the measured geometry and an ASCII preview, create no window, exit 0 | — |
| `--hide` | print one line, create no window, exit 0 | — |
| `--help` | print usage, exit 0 | — |

`--hide` is the automation hook: it proves the executable starts, parses its
arguments and resolves its imports without touching the desktop, which is what a
CI smoke check needs from a release binary.

`--dump` is the review hook: it needs no desktop session (the text rasteriser
only asks for a memory DC), writes a BMP composited over a mid-tone backdrop so
the translucent panel, its rim and its drop shadow are all visible, prints the
measured geometry of the frame it just drew (pill and sheet rectangles with their
alphas, the row band and each row's `nameEnd -> valueX` distance, taken from the
same layout the painter used) and prints the same frame as ~106x44 ASCII
characters so the shape can be judged from a terminal.

The console window is intentional — it is where `--hide` and argument errors
print. Stop the overlay from that console with Ctrl+C, from Task Manager, or with
`taskkill /IM doppel-hud.exe` (without `/F`: a plain `taskkill` posts `WM_CLOSE`,
which the window procedure turns into a clean `PostQuitMessage` and exit 0).

## What the window actually is

* Style `WS_POPUP`; extended styles `WS_EX_LAYERED | WS_EX_TOOLWINDOW |
  WS_EX_NOACTIVATE | WS_EX_TRANSPARENT`.
* The window is a **stage**: the card is drawn at 24 px from the top corner it
  occupies and every other pixel stays fully transparent. Default stage origin is
  `(0, 0)`, so with `--position stacked` the pill lands at screen `(24, 24)` and
  the list sits directly under it.
* Content comes from `UpdateLayeredWindow` fed by a top-down 32bpp DIB of
  premultiplied BGRA pixels (`CreateDIBSection` + a memory DC) — the classic
  Win32 path for per-pixel alpha, and the reason GDI's own `RoundRect` is not
  enough.
* The card is rasterised by hand — a rounded-rectangle distance field with
  coverage-based antialiasing in `src/draw.rs` — and text is drawn by GDI into a
  scratch DIB and blitted as a coverage mask, so a glyph's antialiasing ends up in
  the same premultiplied buffer as the panel.
* **It never takes focus.** No `SetForegroundWindow`, no `SetFocus`, no
  `SetActiveWindow`, and `ShowWindow` is called with `SW_SHOWNOACTIVATE` — never
  `SW_SHOW`. Taking the foreground from the game is what makes a fullscreen game
  stop rendering, so it is the one thing this process must never do.
* **Click-through.** `WS_EX_TRANSPARENT` plus `WM_NCHITTEST` returning
  `HTTRANSPARENT`: every click goes to the game, none are swallowed.
* Not in the taskbar and not in Alt-Tab (`WS_EX_TOOLWINDOW`).

### The card

* **Pill** — `#0D111C` at 65 % (alpha `0xA6`), radius = height/2, a 1 px accent
  rim, a 1 px `TOP_HIGHLIGHT` line along the top inside edge and a four-layer
  `SHADOW_DEEP` drop shadow. Inside: the accent `D` mark, `DOPPEL` with 2 px
  letter tracking (the two P's in the accent), a 1 px `0x24FFFFFF` divider, then
  the mono version chip. 7 px horizontal / 5 px vertical padding, 15 px Segoe UI
  semibold for the wordmark.
* **Sheet** — the module list sits on one rounded rect (8 px radius) behind the
  rows: the pill's own body colour at ~38 % (alpha `0x60`), a 1 px
  `0x14FFFFFF` highlight inside its top edge and a three-layer `SHADOW_DEEP` drop
  shadow. It deliberately gets no accent rim, so it reads as the sheet the rows
  sit on and not as a second card. Padded 6 px outside the spine bars, 8 px past
  the widest row's content, 8 px above the first row and 8 px below the last. A
  list with no named rows draws no sheet at all.
* **List** — one row per published module with a name: a 2 px bar (4 px gap), the
  name in 15 px Segoe UI, then *that row's own* value 10 px after the name, in
  14 px Consolas. Values are left-aligned at their own name rather than on a
  shared column, so a short name gets a short gap and the right edge is
  deliberately ragged; the list (and the sheet) is exactly as wide as the widest
  `name + 10 px + value` row. Row height = font cell + 5, 2 px between rows. Bars
  fade `ACCENT` → `ACCENT2` down the list (`Theme.spineColor`). Sorted
  width-descending, the old card's default staircase. A value is `ACCENT` when the
  row's `highlight` flag is set and `TEXT_DIM` otherwise.
* **Animation** — new rows slide in 10 px and fade over 350 ms, staggered 45 ms
  per row (`entranceEase`); a flash flag sets a per-module strength to 255 that
  then decays 15 per frame, tinting the row with a low-alpha rounded
  `FLASH_RED` background, reddening the name and the bar.

### The state contract (D2)

`%TEMP%\doppel-hud.state`, written by `HudStateWriter` as one 3600-byte block:
`magic 0x444F5050`, `version 1`, `count`, then up to 64 entries of 56 bytes
(`id:u32`, `enabled:u8`, `flash:u8`, `highlight:u8`, `pad:u8`, `name[24]`,
`value[24]`, UTF-8 and NUL padded). The reader opens the file read-only — there is
no back channel — and renders nothing at all on a missing, short, foreign or
wrong-version file.

### Lifetime

The process runs until it is killed or the window is closed, and it exits by
itself when the game stops: when the state file has not been written for three
seconds it fades the card out over 350 ms and exits 0. Before the first write the
same three seconds are counted from process start. Frames are only pushed to the
compositor when the state, the fade or an animation actually changed, so a still
card costs one file read per 16 ms and nothing else.

### Naming (plan §7, anti-signature)

The window class is `WndClsA1` and the window caption is the empty string:
nothing that enumerates windows looking for the product name will match. Keep it
that way — do not put a product name or phase name into the class, the caption,
or any window-derived string. `doppel-hud.exe` on disk is the only place the name
appears, and renaming the binary in D6 is a `Cargo.toml` change.

## Layout of the code

* `src/main.rs` — argument parsing, the window class and window, the DIB/blit
  path, the frame loop, `--dump`, `WM_DESTROY` / `WM_CLOSE` / `WM_NCHITTEST`.
* `src/win.rs` — every Win32 type, constant and `extern "system"` declaration, so
  the window code and the text rasteriser share one transcription.
* `src/draw.rs` — the pure pixel layer: a premultiplied BGRA canvas, colour
  packing, coverage antialiasing, rounded rectangles and rounded strokes. No
  Win32, so it is covered by unit tests that run anywhere.
* `src/text.rs` — the GDI text rasteriser: a scratch 32bpp DIB, cached Segoe UI /
  Consolas faces at `ANTIALIASED_QUALITY`, and the coverage blit into the canvas.
* `src/hud.rs` — the card itself: palette, `spineColor`/`lerpArgb`, the entrance
  and flash animation state, the pill and the list layout. Pure geometry, with the
  sheet and the per-row value geometry (`row_width`, `value_x`, `panel_rect`)
  exposed as pure functions and unit-tested.
* `src/state.rs` — the reader half of the shared-state contract, plus the FNV-1a
  the writer keys module ids with.
* `src/preview.rs` — the `--dump` output: a 32-bit BMP writer and the ASCII
  downsample. Not used by the live overlay.
