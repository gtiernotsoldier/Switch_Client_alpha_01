# doppel-hud — out-of-process HUD overlay

A standalone Win32 process that puts the client HUD on top of the Minecraft
client: the **client watermark** (top corner), the **module list** under it and
the **status pill** in the bottom corner. It is **not** part of the JVM: nothing
is injected, no game class is touched, and the overlay cannot crash the game by
drawing.

The design is the designer's mockup, `E:\swl\doppel.html` (its `<style>` block is
also on disk as `.dsh-tmp/doppel-design.css`), followed literally: the same
palette (`#22D3EE` / `#A78BFA` / `#EDF2FA` / `#8B96AB`, the 55 % glass and the
16 % hairline), the same twelve font sizes and three letter-spacings, the same
paddings, radii and gaps, and the same motion — `cubic-bezier(.22,1,.36,1)`, the
150 ms / 800 ms watermark rise, the staggered 550 ms row slide, the delayed
status pill and the 2.4 s pulsing ring.

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
| **D7** | Re-skin to the mockup: watermark + module list + status pill | **done** |

Until D5 lands, nothing publishes the state file, so the overlay renders an empty
HUD and exits three seconds after it starts.

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
blending, the rounded-box distance field, the state parser and the new pure
geometry (row/bar size, watermark metrics, pill metrics, the design curve, the
pulse ring and the count pop).

## Running

```sh
target/release/doppel-hud.exe [options]
```

| Option | Meaning | Default |
| ------ | ------- | ------- |
| `--x <i32>` / `--y <i32>` | screen position of the stage's top-left corner | `0`, `0` |
| `--w <u32>` / `--h <u32>` | stage size in physical pixels | `520x400` |
| `--position <where>` | `right` (the mockup's own layout: watermark + list top-right, status pill bottom-right) or `left` (the same HUD mirrored into the left-hand corners) | `right` |
| `--dump <path.bmp>` | render one representative frame to a 32-bit BMP, print the measured geometry and an ASCII preview, create no window, exit 0 | — |
| `--hide` | print one line, create no window, exit 0 | — |
| `--help` | print usage, exit 0 | — |

`stacked` (and `stack`/`default`) is still accepted as a synonym of `right`: the
old card drew its brand pill in the top-right corner, and the mockup's watermark
now lives there.

`--hide` is the automation hook: it proves the executable starts, parses its
arguments and resolves its imports without touching the desktop, which is what a
CI smoke check needs from a release binary.

`--dump` is the review hook: it needs no desktop session (the text rasteriser
only asks for a memory DC), writes a BMP composited over a mid-tone backdrop so
the glass, the hairline and the glows are all visible, prints the measured
geometry of the frame it just drew — the watermark block, the mark and wordmark
metrics, the rule and the slogan, every row's chip/bar/name/value geometry, and
the status pill's rect and inner positions, all taken from the same layout the
painter used — and prints the same frame as ASCII characters (4x8 canvas pixels
per character) so the shape can be judged from a terminal.

The console window is intentional — it is where `--hide` and argument errors
print. Stop the overlay from that console with Ctrl+C, from Task Manager, or with
`taskkill /IM doppel-hud.exe` (without `/F`: a plain `taskkill` posts `WM_CLOSE`,
which the window procedure turns into a clean `PostQuitMessage` and exit 0).

## What the window actually is

* Style `WS_POPUP`; extended styles `WS_EX_LAYERED | WS_EX_TOOLWINDOW |
  WS_EX_NOACTIVATE | WS_EX_TRANSPARENT`.
* The window is a **stage**: the HUD is drawn 16 px inside the corner it occupies
  (`MARGIN`) and every other pixel stays fully transparent. Default stage origin
  is `(0, 0)`, so with `--position right` the watermark block lands at screen
  `(520-16-112, 16)` and the status pill at `(520-16-129, 400-16-29)`.
* Content comes from `UpdateLayeredWindow` fed by a top-down 32bpp DIB of
  premultiplied BGRA pixels (`CreateDIBSection` + a memory DC) — the classic
  Win32 path for per-pixel alpha, and the reason GDI's own `RoundRect` is not
  enough.
* The HUD is rasterised by hand — a rounded-rectangle distance field with
  coverage-based antialiasing in `src/draw.rs` — and text is drawn by GDI into a
  scratch DIB and blitted as a coverage mask, so a glyph's antialiasing ends up in
  the same premultiplied buffer as the glass.
* **It never takes focus.** No `SetForegroundWindow`, no `SetFocus`, no
  `SetActiveWindow`, and `ShowWindow` is called with `SW_SHOWNOACTIVATE` — never
  `SW_SHOW`. Taking the foreground from the game is what makes a fullscreen game
  stop rendering, so it is the one thing this process must never do.
* **Click-through.** `WS_EX_TRANSPARENT` plus `WM_NCHITTEST` returning
  `HTTRANSPARENT`: every click goes to the game, none are swallowed.
* Not in the taskbar and not in Alt-Tab (`WS_EX_TOOLWINDOW`).

### The three elements

All three are measured in `hud.rs` from the stylesheet, and the numbers are
printed by `--dump` so the picture and the geometry cannot drift apart. Every
position is an inset from the corner the block occupies, so `--position left`
mirrors the whole HUD (the list's spine moves to the left edge, the module values
end up on the other side of their names, and the watermark and pill hang off the
left corner).

* **Watermark** — no panel, exactly like the mockup: legibility comes from the
  HUD-wide `text-shadow`. The `.mark` box is 21x15: a 62 % `#EDF2FA` "D" at the
  box origin with a **mirrored** `#22D3EE` "D" at 92 % seven pixels to its right
  (`scaleX(-1)`, drawn through `TextEngine::draw_mirrored`). 11 px later the
  wordmark `DOPPEL` at 13.5 px bold, letter-spacing **0.4 em = 5.4 px per
  character** with the trailing space taken back (the stylesheet's
  `margin-right: -.4em`), and the two P's in the accent. Under it, 8 px down, the
  1 px rule `#22D3EE → #A78BFA at 46 % → transparent` across the block's width,
  and 6 px under that the mono slogan `statistically you.` at 9 px, 0.06 em, in
  `#8B96AB`, right-aligned to the same edge. Entrance: 10 px rise + fade over
  800 ms with the design curve, starting at 150 ms.
* **Module list** — one chip per published module with a name, right-aligned and
  sorted width-descending (the mockup's `measure()` staircase). A row is
  `[8 px pad][3x11 bar, radius 2][8 px][name][10 px][value][8 px pad]`, 23 px
  tall (the 17 px name cell and the 11 px bar, plus 3 px of padding above and
  below), 5 px radius, 3 px between rows. The bar is on the screen-edge side, so
  the bars form the mockup's spine and fade `ACCENT → ACCENT2` down the list; it
  carries a cheap cyan halo (`0 0 9px rgba(34,211,238,.35)`). The name is 12.5 px
  semibold with 0.04 em of tracking; **the value follows its own name** 10 px
  later in 11 px Consolas (`#22D3EE` when the row's `highlight` flag is set,
  `#8B96AB` otherwise). The mockup gives rows no background outside `:hover`;
  the user asked for a little one, so each row gets the glass token at
  `ROW_CHIP_ALPHA` (0.4) of its alpha — **set that constant to `0.0` for a
  byte-for-byte faithful copy of the mockup**. Entrance: 18 px slide in from the
  outer edge + fade over 550 ms with the design curve, 45 ms stagger from 140 ms.
* **Status pill** — bottom corner: `rgba(13,18,28,.55)` glass, a 1 px
  `rgba(148,163,184,.16)` hairline, 9 px radius, 8x13 px padding, 9 px gaps. A
  7 px accent dot with a halo, wrapped by the ring that scales 0.6 → 2.4 while
  fading 0.8 → 0 over 2.4 s (`ease-out`, holding the faded state for the last
  quarter of the cycle); then `ENABLED` at 9.5 px bold with **0.32 em = 3.04 px
  per character** in `#8B96AB`; a 1x11 `rgba(255,255,255,.14)` separator; then
  the enabled-module count in 11 px semibold Consolas in the accent, with a
  300 ms pop to 1.45x when the count changes. Entrance: 10 px rise + fade over
  800 ms with the design curve, starting at 500 ms.

### The work flash

A `flash` flag on a module sets its strength to 255; a clear one loses 15 per
frame (~280 ms at 60 Hz), which is the same decay the old card used. The strength
scales the whole mockup flash language: the row background gains
`rgba(255,90,90,.055)`, the name goes `#EDF2FA → #FF6B6B` with a red text glow,
and the bar goes to its spine colour → `#FF5A5A` with the stronger red halo
(`0 0 12px rgba(255,90,90,.85)`).

### What a canvas cannot do (and what is done instead)

* `backdrop-filter: blur(14px) saturate(1.35)` — there is no framebuffer behind a
  layered window to sample, so the glass is the flat `rgba(13,18,28,.55)` the
  mockup also paints underneath it.
* `box-shadow` glows — no blur passes: `glow()` stacks two expanding, fading
  fills of the same shape at low alpha, and the shape itself is painted over the
  middle.
* `text-shadow: 0 1px 6px rgba(0,0,0,.7)` — one 1 px dark offset copy of the run,
  not a 6 px blur.

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
seconds it fades the HUD out over 350 ms and exits 0. Before the first write it
waits 30 seconds instead, because Forge + OptiFine + a world load routinely take
longer than three seconds.

Frames are pushed to the compositor when the state, the fade or an animation
changed — and, deliberately, **always** while the status pill is on screen: its
ring pulses for as long as the HUD is up, so a visible HUD is never static. The
cost of a still frame is the same as an animating one, which is why the text
rasteriser only clears the columns a run can touch rather than a whole scratch
row.

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
* `src/text.rs` — the GDI text rasteriser: a scratch 32bpp DIB, seven cached
  Segoe UI / Consolas faces at `ANTIALIASED_QUALITY` (one per CSS font-size the
  mockup asks for), per-character tracking, a mirrored run for the mark, a
  bilinear-scaled run for the count's pop, and the coverage blit into the canvas.
* `src/hud.rs` — the HUD itself: the palette, the design curve and the pulse/pop
  easing, the entrance and flash animation state, the pure row/watermark/pill
  geometry, and the painting. The geometry helpers are unit-tested.
* `src/state.rs` — the reader half of the shared-state contract, plus the FNV-1a
  the writer keys module ids with.
* `src/preview.rs` — the `--dump` output: a 32-bit BMP writer and the ASCII
  downsample. Not used by the live overlay.
