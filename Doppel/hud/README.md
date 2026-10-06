# doppel-hud — out-of-process HUD overlay

A standalone Win32 process that puts the client HUD on top of the Minecraft
client: the **client watermark** (top corner) and the **module list** under it.
Its layered window covers the whole primary monitor — or the rect `--rect` names —
so the mockup's 16 px insets land on the *screen's* corners; the HUD itself is
still drawn only at those insets and every other pixel of the window is fully
transparent.
The mockup's third element, the **status pill** in the bottom corner, is **off by
default** — after reviewing the render the user asked for it not to be on screen;
`SHOW_STATUS_PILL` in `src/hud.rs` is the one switch that brings it back (see
*The elements* below). It is **not** part of the JVM: nothing is injected, no game
class is touched, and the overlay cannot crash the game by drawing.

The design is the designer's mockup, `E:\swl\doppel.html` (its `<style>` block is
also on disk as `.dsh-tmp/doppel-design.css`), followed literally: the same
palette (`#22D3EE` / `#A78BFA` / `#EDF2FA` / `#8B96AB`, the 55 % glass and the
16 % hairline), the same twelve font sizes and three letter-spacings, the same
paddings, radii and gaps, and the same motion — `cubic-bezier(.22,1,.36,1)`, the
150 ms / 800 ms watermark rise, the staggered 550 ms row slide, and the delayed
status pill with its 2.4 s pulsing ring (the pill is drawn only when
`SHOW_STATUS_PILL` is on, but its metrics and motion are still the spec).

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

The status pill is the mockup's third element and is **not drawn by default**:
the mockup has it, the user reviewed the render and asked for it off.
`SHOW_STATUS_PILL` in `src/hud.rs` (next to `ROW_CHIP_ALPHA`) is the single
switch — set it to `true` and the element comes back exactly as reviewed. While it
is `false` the pill's metrics helpers, its painter `paint_pill` and their tests
all stay in the tree as the geometry spec, and nothing else depends on the pill:
no inset, no reserved gap, and no endless repaint (see *Lifetime*).

Until D5 lands, nothing publishes the state file, so the overlay renders an empty
HUD and exits once its 30-second first-write grace runs out — and the two lines in
`%TEMP%\doppel-hud.log` say exactly that (see *The log*).

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
`extern "system"` FFI, exactly like `injector/src/window.rs` — the two calls the
placement fix and the log added (`GetSystemMetrics`, `GetLocalTime`) are declared
there with the rest and pull in no crate. The rasteriser in `src/draw.rs` is pure
Rust with no Win32 in it. `cargo test` covers the pixel blending, the rounded-box
distance field, the state parser, the log (its size cap and its tolerance of an
unopenable path) and the pure geometry: row/bar size, watermark metrics, pill
metrics, the design curve, the pulse ring, the count pop, and the anchoring of the
whole HUD to the rect the window covers (`anchor_x` / `anchor_top` /
`anchor_bottom`, right, left and a `--rect` override).

## Running

```sh
target/release/doppel-hud.exe [options]
```

| Option | Meaning | Default |
| ------ | ------- | ------- |
| `--rect <x> <y> <w> <h>` | the screen rect the overlay covers: the layered window is created at `(x, y)` with that size and the HUD is laid out inside it, 16 px in from the corners the mockup uses. A caller following the game window on a multi-monitor desktop passes that window's rect here | the whole primary monitor |
| `--x <i32>` / `--y <i32>` / `--w <u32>` / `--h <u32>` | per-field adjustments to the chosen rect | the rect's own values |
| `--position <where>` | `right` (the mockup's own layout: watermark + list top-right, and the status pill bottom-right only when `SHOW_STATUS_PILL` is on — it is off by default) or `left` (the same HUD mirrored into the left-hand corners) | `right` |
| `--dump <path.bmp>` | render one representative frame to a 32-bit BMP on the fixed 520x400 review canvas, print the measured geometry, an ASCII preview and the runtime rect the live window would cover, create no window, exit 0 | — |
| `--hide` | print one line, create no window, exit 0 | — |
| `--help` | print usage, exit 0 | — |

The rect the window covers is the whole primary monitor by default
(`GetSystemMetrics(SM_CXSCREEN / SM_CYSCREEN)`; the primary monitor's origin is
`(0, 0)` by definition — Win32 has no `SM_XSCREEN`/`SM_YSCREEN`), or 1920x1080 if
that call gives nothing usable. **This is the placement fix**: the window used to
be a 520x400 stage at `(0, 0)`, and since the mockup hangs its blocks off the
*right* edge of whatever rect it is given, the watermark and the list landed
around x = 400 — the middle of a 1920-wide screen — with the rest of the window
transparent. The layout was never wrong; the rect it hung off was. `--rect` is
the hook a later caller uses to follow the game window.

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
metrics, the rule and the slogan, every row's chip/bar/name/value geometry, and,
for the status pill, either its rect and inner positions (when `SHOW_STATUS_PILL`
is on) or the one line saying it is not drawn and what it would measure, all taken
from the same layout the painter used — and prints the same frame as ASCII
characters (4x8 canvas pixels per character) so the shape can be judged from a
terminal. It also prints the **runtime window** line: the monitor/`--rect` rect
the live window would cover and the screen position the watermark would land at
in it, because the dump's own canvas stays the fixed 520x400 review size.

## The log

The overlay appends to `%TEMP%\doppel-hud.log`, next to the state file (the
injector's launch log points at it). It is the answer to "it is running but I see
nothing", which a screenshot of an empty desktop cannot give:

* one line on start — local time, pid, the rect it covers and where that came
  from (primary monitor / fallback / `--rect`), the layout, the resolved state
  path and whether that file was readable *and* parseable;
* one line on exit — the reason (`window lost`, `state stale`, `state file never
  appeared`, or `error: <Win32 error>`) and how many frames it pushed to the
  compositor.

Zero frames drawn plus a start line means the state file never appeared; frames
drawn plus "nothing on screen" means the rect line is the thing to read. Logging
is best-effort and can never fail the overlay, and a log over 64 KB is truncated
on the next start, so it cannot grow without bound.

The console window is intentional — it is where `--hide` and argument errors
print. Stop the overlay from that console with Ctrl+C, from Task Manager, or with
`taskkill /IM doppel-hud.exe` (without `/F`: a plain `taskkill` posts `WM_CLOSE`,
which the window procedure turns into a clean `PostQuitMessage` and exit 0).

## What the window actually is

* Style `WS_POPUP`; extended styles `WS_EX_LAYERED | WS_EX_TOOLWINDOW |
  WS_EX_NOACTIVATE | WS_EX_TRANSPARENT`.
* The window **covers the whole rect it was given** — the primary monitor by
  default, `--rect` otherwise — and the HUD is drawn 16 px inside the corner it
  occupies (`MARGIN`); every other pixel of the window stays fully transparent.
  With the default rect on a 1920x1080 screen, `--position right` puts the
  watermark block at screen `(1920-16-112, 16) = (1792, 16)` and a row's chip
  against x = 1904; `--position left` mirrors both to x = 16. When the status pill
  is on it would sit at `(1920-16-129, 1080-16-29)` — that rect stays fully
  transparent while `SHOW_STATUS_PILL` is `false` (a hole in the transparent
  window, not a reserved gap: no other element is inset by it).
* Content comes from `UpdateLayeredWindow` fed by a top-down 32bpp DIB of
  premultiplied BGRA pixels (`CreateDIBSection` + a memory DC) — the classic
  Win32 path for per-pixel alpha, and the reason GDI's own `RoundRect` is not
  enough. The canvas that fills it carries the window's screen origin
  (`Canvas::placed`), which is what makes the layout anchor to the monitor's
  corner rather than to a surface origin.
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

### The elements

The mockup has three; all three are measured in `hud.rs` from the stylesheet and
the numbers are printed by `--dump` so the picture and the geometry cannot drift
apart. **Two are drawn by default** — the watermark and the module list — and the
third, the status pill, is off until `SHOW_STATUS_PILL` in `src/hud.rs` is set to
`true` (see the note under the status table). Every position is an inset from the
corner the block occupies, so `--position left` mirrors the whole HUD (the list's
spine moves to the left edge, the module values end up on the other side of their
names, and the watermark and pill hang off the left corner).

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
  **Not drawn by default.** The mockup has this element and the user, after
  reviewing the render, asked for it off screen — `SHOW_STATUS_PILL` is `false`.
  Set that one constant to `true` and it comes back unchanged: measuring
  (`pill_size_from` / `pill_size`), painting (`paint_pill`) and the geometry
  tests (`the_pill_measures_its_pieces`, `the_translucent_tokens_are_the_mockups_alphas`)
  were all kept in the tree for exactly that. While it is off there is no gap for
  it anywhere and, because its endless ring was the only thing that forced a
  repaint every frame, the HUD now repaints only when the content changes.

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
longer than three seconds; if that grace runs out the log says so
(`state file never appeared (first-write grace of 30s timed out)`) and the process
still exits cleanly. Both exits are written to `%TEMP%\doppel-hud.log` with a frame
count, and a window-lost exit (`taskkill` without `/F`, or the game closing it) is
logged as such. A failed `UpdateLayeredWindow` is the one path that is *not*
clean: it logs the Win32 error and exits 1 rather than looping with an empty
window.

Frames are pushed to the compositor when the state, the fade or an animation
changed. While the status pill is on screen that is deliberately **every** frame:
its ring pulses for as long as the HUD is up, so a visible HUD is never static.
With the pill off by default (`SHOW_STATUS_PILL`) nothing is endless any more —
`hud::paint` reports "still moving" only while an entrance or a flash is decaying,
so once the HUD settles it repaints only on a change of content (the fade-out is
quantised into the frame signature, so the exit ramp still repaints). The cost of
the frames that do get pushed is unchanged, which is why the text rasteriser only
clears the columns a run can touch rather than a whole scratch row.

### Naming (plan §7, anti-signature)

The window class is `WndClsA1` and the window caption is the empty string:
nothing that enumerates windows looking for the product name will match. Keep it
that way — do not put a product name or phase name into the class, the caption,
or any window-derived string. `doppel-hud.exe` on disk is the only place the name
appears, and renaming the binary in D6 is a `Cargo.toml` change.

## Layout of the code

* `src/main.rs` — argument parsing, the window class and window, the rect the
  window covers (`--rect` / the primary monitor), the DIB/blit path, the frame
  loop, `--dump`, `WM_DESTROY` / `WM_CLOSE` / `WM_NCHITTEST`.
* `src/win.rs` — every Win32 type, constant and `extern "system"` declaration, so
  the window code, the text rasteriser and the log share one transcription.
* `src/draw.rs` — the pure pixel layer: a premultiplied BGRA canvas, colour
  packing, coverage antialiasing, rounded rectangles and rounded strokes. No
  Win32, so it is covered by unit tests that run anywhere. The canvas also records
  which screen point its `(0, 0)` is (`Canvas::placed`), which is what the layout
  anchors to.
* `src/text.rs` — the GDI text rasteriser: a scratch 32bpp DIB, seven cached
  Segoe UI / Consolas faces at `ANTIALIASED_QUALITY` (one per CSS font-size the
  mockup asks for), per-character tracking, a mirrored run for the mark, a
  bilinear-scaled run for the count's pop, and the coverage blit into the canvas.
* `src/hud.rs` — the HUD itself: the palette, the design curve and the pulse/pop
  easing, the entrance and flash animation state, the screen `Rect` and the
  `anchor_*` helpers, the pure row/watermark/pill geometry, and the painting. The
  geometry helpers are unit-tested. `SHOW_STATUS_PILL` (and `ROW_CHIP_ALPHA`, the
  other display switch) live here.
* `src/state.rs` — the reader half of the shared-state contract, plus the FNV-1a
  the writer keys module ids with.
* `src/log.rs` — the `%TEMP%\doppel-hud.log` breadcrumbs: local time, pid, the
  rect, the state file and the exit reason, capped at 64 KB per file and
  best-effort throughout.
* `src/preview.rs` — the `--dump` output: a 32-bit BMP writer and the ASCII
  downsample. Not used by the live overlay.
