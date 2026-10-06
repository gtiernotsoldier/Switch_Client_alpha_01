# doppel-hud — out-of-process HUD overlay

A standalone Win32 process that puts the **OpenCommunity in-game HUD** on top of
the Minecraft client: the watermark run and the module list, in **all four of the
source's modes** — Default, Rise, VapeV4, Tesseract. Its layered window covers the
whole primary monitor — or the rect `--rect` names — and the HUD is drawn only at
the source's own margins; every other pixel is fully transparent.

The HUD is a **port, not a re-design**: the user reviewed the source HUD and asked
for it to be copied, so the geometry, the colours and the animations in
`src/hud.rs` are transcribed from

| reference | what was taken |
| --------- | -------------- |
| `runtime/src/features/render/HUD.cpp` (654 lines) | everything: the four modes, the watermark, the rows, the accent functions, the entrance animation, the text shadow |
| `runtime/src/features/render/HUD.h` (49 lines) | the slide-progress map keyed by module name, the per-frame delta, the font roles |
| `runtime/src/features/visuals/ArrayList.h` (106 lines) | the module options the HUD reads: mode, watermark, spaced modules, wave, the two wave colours |
| `shared/common/ModuleConfig.h` | the `HUD{}` block: the same toggles, the same colour defaults (`78,86,107` → `120,146,214`), `m_WaveSpeed` |

Every constant in `src/hud.rs` carries the source line it came from, so the two
can be diffed by eye. Nothing is injected, no game class is touched, and the
overlay cannot crash the game by drawing.

## The four modes

`--mode default|rise|vape|tesseract` selects one at run time; the config-free
default is the source's `Default`. The master constants (HUD.cpp:469-491), at
`--scale 1.0`:

| | font | `topY` | `lineHeight` | `moduleSpacing` | `itemHeight` | row pitch | text inset | accent bar | right margin |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Default | 16 px | 4 | 16 | 2 | 18 | 20 | 0, 0 | none | 6 |
| Rise | 16 px | 4 | 16 | 0 | 22 | 22 | 4, 2 | 2 px | 4 |
| VapeV4 | 16 px | 4 | 16 | 0 | 22 | 22 | 4, 2 | 2 px | 4 |
| Tesseract | 16 px | 4 | 16 | 2 | 20 | 22 | 6, 2 | 3 px | 6 |

The palette is the source's: shadow `rgba(0,0,0,120)`, secondary text
`(200,200,200)` and Rise/VapeV4 `(154,154,154)`, the Rise-like chip and panel
`rgba(0,0,0,88)`, `riseWatermarkTextColor (232,232,232)`, the VapeV4 active tag
`(255,85,85)`, the Tesseract chip `(25,25,30,240)`.

* **Anchoring.** The source hangs the watermark 10 px inside the *left* edge
  (`cursorX = 10`) and flushes the rows against the *right* one
  (`targetX = screenW - boxWidth - rightMargin`). That is what `--position right`
  (the default) draws. `--position left` swaps the two elements to the other pair
  of edges; the elements' own internal layout is untouched.
* **The wedge.** Rows are sorted by measured width, descending, and each row is
  right-flushed to the mode's margin, so the list forms the source's staircase.
* **Chips.** Rise/VapeV4 fill the whole row, Tesseract its own dark chip, Default
  nothing at all. Tesseract's chip is rounded 5 px and VapeV4's 6 px on their
  **left corners only** — the right corners stay square, where the accent bar sits.

## The watermark, and the contract-v2 TODO

The source draws one inline run — the product name in the mode's accent, ` | `,
the player's nickname, ` | `, `"<n> FPS"` — on one baseline. The 3600-byte state
contract this overlay reads carries a **module table only**: no nickname and no
frame counter. So the port draws what the contract can support:

```
DOPPEL | <enabled module count>
```

and omits the FPS segment entirely.

> **TODO (contract v2).** The watermark is one segment short of the source. A
> `nickname[..]` field and a `uint32 fps` in the state header would complete it
> verbatim: the writer already knows both, and the painter is already the source's
> run — `hud::paint_watermark` draws the title and one segment, so completing it is
> one more `draw_shadowed` for `"<n> FPS"` plus the FPS width term the source adds
> to the panel (`watermark_panel_width`, HUD.cpp:544), which this port simply does
> not have. Until then the middle segment is the module count, which is the one
> "player-ish" number the contract does carry.

In Rise and VapeV4 the run sits in the source's panel: a `rgba(0,0,0,88)` fill with
a 2 px accent bar down its right edge, and VapeV4 rounds the panel's left corners
by 6 px. **In Rise the title is `riseWatermarkTextColor`, not the accent** — that
is what HUD.cpp:556 does, and the README says so because the prompt's summary
("the product name in the accent") is the general case, not Rise's.

## Animations

* **Entrance** — a row slides in from the screen's right edge:
  `progress += dt * 6`, `eased = 1 - (1-p)^3` (easeOutCubic), with the frame delta
  clamped to 0.1 s (HUD.cpp:502-506, 590-612). **No opacity ramp**: a row is
  opaque from its first frame.
* **Exit** — instant: the row is dropped and the rows below snap up (the source
  erases `m_SlideProgress` entries for modules that are no longer listed).
* **Accents** — per-row phase offsets, one function per mode (HUD.cpp:249-309):
  * **Default + Wave** — a cosine blend of the two configurable colours over
    `2500 / max(speed, 0.01)` ms, `+400 ms` per row. Both colours and the speed
    are CLI values with the source's defaults.
  * **Rise** — `phase = 0.5 + 0.5*sin(t*1.35 + i*0.5)`,
    `hue = 0.58 - phase*0.18` → `HSV(hue, 0.72, 0.95)`.
  * **VapeV4** — `sin(t*1.55 + i*0.45)` lerping soft purple `(0.82,0.50,0.98)`
    towards pink `(1.0,0.43,0.74)`.
  * **Tesseract** — `hue = fmod(t_ms/2000 + i*0.618034, 1)` → `HSV(h, 0.75, 0.9)`;
    its watermark has its own faster sweep, `HSV(ceil(t_ms/20)/360, 0.7, 0.8)`.
  * HSV→RGB is implemented in `hud.rs` (the source gets it from ImGui).
* **Repaint** — `hud::paint` reports "still moving" while any row is sliding in,
  **or** while the mode's accent is a function of the clock (Rise/VapeV4/Tesseract
  always, Default only with the wave on). So those frames are pushed every frame,
  and a waveless Default HUD really is static: measured on this machine, a
  Tesseract run pushed 202 frames in 3.3 s and a `--mode default --no-wave` run
  pushed 32 (the entrance, then nothing).
* **The only text effect** is the source's: a 1 px down-right copy of the glyph run
  in `rgba(0,0,0,120)`. No outline, no glow, no letter spacing.

## GDI adaptations (the three things Win32 cannot do the way ImGui does)

1. **Per-corner rounding.** Tesseract's chips and VapeV4's panel are rounded on
   their left corners only. GDI's `RoundRect` takes one radius for all four;
   `CreateRoundRectRgn` + `CombineRgn` can build the region but it is an opaque
   1-bit mask with no alpha and no antialiasing, and this surface is premultiplied
   per-pixel alpha for `UpdateLayeredWindow`. The fill in `src/draw.rs` selects the
   radius per quadrant of its signed-distance field instead — the same cost as the
   single-radius fill, and the rim stays antialiased. Documented at the function.
2. **Fonts.** The source embeds the **Play** face (regular and bold) at 16 px
   through ImGui. Play is not installed here and may not be on the target's, so
   names use **Segoe UI Semibold** and everything else **Segoe UI**, still at
   **16 px** — one em is one em, so every constant taken from the source keeps its
   value, and `lineHeight` stays exactly the font size (HUD.cpp:472). Text is
   placed with the top of its em box at the source's `textY`, which is where
   ImGui's `AddText` puts the top of the line box; the substitute face's metrics
   are not Play's, so glyph placement is within a pixel or two rather than exact.
3. **`--scale <f>`.** The source has no DPI or scaling handling at all — every
   number in it is a physical pixel. `--scale` (default 1.0, allowed 0.5..4.0)
   multiplies every metric and the font size so the HUD can be made readable on a
   4K screen. **1.0 is the faithful setting** and the only one the source's numbers
   describe.

## Not reproduced

* **The FPS segment and the nickname** in the watermark — the state contract has
  neither (see the TODO above).
* **The Play typeface** — substituted, see above.
* **Minecraft `§` colour codes in a tag.** The source has a formatted-tag painter
  (`DrawFormattedTag` / `CalcFormattedTagWidth`, HUD.cpp:311-434) for tags that
  carry `§a`-style codes. This side of the contract publishes plain UTF-8 values
  (`value[24]`), so the port draws a row's tag as one run in the mode's tag colour.
  A `§`-aware tag would need the formatted path back.
* **The source's float epoch.** `GetWaveRGB` and `GetTesseractRGB` take
  `time_since_epoch()` as a float: at ~1e12 ms a float has ~65 s of resolution, so
  the source's phase is coarse and arbitrary. The port keeps the formula exactly
  (`fmod(elapsed, cycle)/cycle`, the cosine blend, the lerp) but does the modulus
  in `f64` and starts the clock at process start, so the animation is smooth rather
  than quantised. Nothing else about the shape of the motion changed.
* **`m_Background` / `m_ColorBar`** from the source's `HUD{}` block: the current
  `HUD.cpp` does not read them (the chips and bars above are unconditional), so
  neither does the port.

## Building

```sh
cargo build --release
```

Zero third-party dependencies: user32, gdi32 and kernel32 are called through raw
`extern "system"` FFI. `cargo test` covers the pixel blending and the per-corner
distance field, the state parser, the log, and the port's pure geometry: the
per-mode master constants (pitch, insets, bar width, chip radii, margins), the
scale multiplier, the row box arithmetic, the tag gap, the wedge sort, the
anchoring on the monitor rect, the entrance rate, the HSV conversion, all four
accent functions at known timestamps and indices, and the repaint rule.

## Running

```sh
target/release/doppel-hud.exe [options]
```

| Option | Meaning | Default |
| ------ | ------- | ------- |
| `--rect <x> <y> <w> <h>` | the screen rect the overlay covers: the layered window is created at `(x, y)` with that size and the HUD is laid out inside it at the mode's own margins | the whole primary monitor |
| `--x` / `--y` / `--w` / `--h` | per-field adjustments to the chosen rect | the rect's own values |
| `--position right\|left` | `right` = the source's own placement (watermark left, rows right); `left` = the two elements on the opposite edges | `right` |
| `--mode default\|rise\|vape\|tesseract` | which of the four modes is drawn (`vapev4` is accepted) | `default` |
| `--scale <f>` | multiply every metric and the font size (0.5 .. 4.0) | `1.0` |
| `--primary <r,g,b>` | the Default wave's first colour | `78,86,107` |
| `--secondary <r,g,b>` | the colour the wave blends towards | `120,146,214` |
| `--wave-speed <f>` | the Default wave's speed (`cycle = 2500/speed` ms) | `1` |
| `--no-wave` | flat primary accent in Default mode | wave on |
| `--no-watermark` | no watermark run (and no Rise/VapeV4 panel) | watermark on |
| `--spaced` | split `CamelCase` module names (`AutoClicker` → `Auto Clicker`) | off |
| `--dump <path.bmp>` | render one representative frame to a 32-bit BMP on the fixed 520x400 review canvas, print the measured geometry, an ASCII preview and the runtime rect, create no window, exit 0 | — |
| `--hide` | print one line, create no window, exit 0 | — |
| `--help` | print usage, exit 0 | — |

The rect the window covers is the whole primary monitor by default
(`GetSystemMetrics(SM_CXSCREEN / SM_CYSCREEN)`; the primary monitor's origin is
`(0, 0)` by definition — Win32 has no `SM_XSCREEN`/`SM_YSCREEN`), or 1920x1080 if
that call gives nothing usable. **This is the placement fix**: the window used to
be a 520x400 stage at `(0, 0)`, and since the HUD anchors to the edges of whatever
rect it is given, the list landed around x = 400 — the middle of a 1920-wide screen
— with the rest of the window transparent. The layout was never wrong; the rect it
hung off was. `--rect` is the hook a later caller uses to follow the game window.

`stacked` (and `stack`/`default`) is still accepted as a synonym of `right`.

`--hide` is the automation hook: it proves the executable starts, parses its
arguments and resolves its imports without touching the desktop.

`--dump` is the review hook: it needs no desktop session (the text rasteriser only
asks for a memory DC), writes a BMP composited over a **dark, game-like backdrop**
(the ported HUD's Default accent is a dim blue-grey that a mid-tone backdrop
swallows, and its chips are near-black panels), prints the mode's metrics, the
watermark run and every row's box, insets, bar and tag colour from the same layout
the painter used, and prints the same frame as ASCII (4x8 canvas pixels per
character — coarse for 16 px text, which is what the PNG is for). It also prints
the **runtime window** line: the monitor/`--rect` rect the live window would cover
and where the watermark run and the first row would land in it, because the dump's
own canvas stays the fixed 520x400 review size.

For a review frame the dump **seeds** the animation: every row is settled, and the
accent clock starts at exactly 2000 ms, so the frame's colours are the accent
functions at a known timestamp rather than whatever the wall clock said.

## Hard prerequisite: windowed / borderless fullscreen only

**The overlay does not appear under exclusive fullscreen.** A layered window is
composited by the desktop compositor (DWM); a game in exclusive fullscreen owns the
display output and bypasses that compositor, so nothing layered is ever drawn on
top of it. This is a property of the platform, not a bug that will be fixed later.

## The log

The overlay appends to `%TEMP%\doppel-hud.log`, next to the state file. It is the
answer to "it is running but I see nothing", which a screenshot of an empty
desktop cannot give:

* one line on start — local time, pid, the rect it covers and where that came from
  (primary monitor / fallback / `--rect`), the position, the **mode and scale**,
  the watermark/wave switches, the resolved state path and whether that file was
  readable *and* parseable;
* one line on exit — the reason (`window lost`, `state stale`, `state file never
  appeared`, or `error: <Win32 error>`) and how many frames it pushed to the
  compositor.

Zero frames drawn plus a start line means the state file never appeared; frames
drawn plus "nothing on screen" means the rect line is the thing to read. Logging is
best-effort and can never fail the overlay, and a log over 64 KB is truncated on
the next start.

Stop the overlay from that console with Ctrl+C, from Task Manager, or with
`taskkill /IM doppel-hud.exe` (without `/F`: a plain `taskkill` posts `WM_CLOSE`,
which the window procedure turns into a clean `PostQuitMessage` and exit 0).

## What the window actually is

* Style `WS_POPUP`; extended styles `WS_EX_LAYERED | WS_EX_TOOLWINDOW |
  WS_EX_NOACTIVATE | WS_EX_TRANSPARENT`.
* The window **covers the whole rect it was given** and the HUD is drawn at the
  mode's margins inside it; every other pixel of the window stays fully
  transparent.
* Content comes from `UpdateLayeredWindow` fed by a top-down 32bpp DIB of
  premultiplied BGRA pixels (`CreateDIBSection` + a memory DC) — the classic Win32
  path for per-pixel alpha, and the reason GDI's own `RoundRect` is not enough. The
  canvas that fills it carries the window's screen origin (`Canvas::placed`), which
  is what makes the layout anchor to the monitor's edges rather than to a surface
  origin.
* The HUD is rasterised by hand — a rounded-rectangle distance field with
  per-corner radii and coverage-based antialiasing in `src/draw.rs` — and text is
  drawn by GDI into a scratch DIB and blitted as a coverage mask, so a glyph's
  antialiasing ends up in the same premultiplied buffer as the chips.
* **It never takes focus.** No `SetForegroundWindow`, no `SetFocus`, no
  `SetActiveWindow`, and `ShowWindow` is called with `SW_SHOWNOACTIVATE` — never
  `SW_SHOW`. Taking the foreground from the game is what makes a fullscreen game
  stop rendering, so it is the one thing this process must never do.
* **Click-through.** `WS_EX_TRANSPARENT` plus `WM_NCHITTEST` returning
  `HTTRANSPARENT`: every click goes to the game, none are swallowed.
* Not in the taskbar and not in Alt-Tab (`WS_EX_TOOLWINDOW`).

### The tag colour rule

The tag is the mode's muted grey — except in **VapeV4 while the module is in use**,
where it is `(255,85,85)` (HUD.cpp:640). The other three modes keep their grey
whatever the state says; that is the source's behaviour, not an omission. "In use"
is the state contract's `flash` byte, which is exactly the signal the source reads
as `mod->IsInUse()`.

### The state contract (D2)

`%TEMP%\doppel-hud.state`, written by `HudStateWriter` as one 3600-byte block:
`magic 0x444F5050`, `version 1`, `count`, then up to 64 entries of 56 bytes
(`id:u32`, `enabled:u8`, `flash:u8`, `highlight:u8`, `pad:u8`, `name[24]`,
`value[24]`, UTF-8 and NUL padded). The reader opens the file read-only — there is
no back channel — and renders nothing at all on a missing, short, foreign or
wrong-version file.

The port reads three of those fields: `name` (the row's name), `value` (its tag)
and `flash` (the VapeV4 tag colour). `enabled` is honoured and `ArrayList` is never
listed, exactly as `HUD::GetActiveModules` does. `highlight` is unused by this HUD.

### Lifetime

The process runs until it is killed or the window is closed, and it exits by itself
when the game stops: when the state file has not been written for three seconds it
fades the HUD out over 350 ms and exits 0. Before the first write it waits 30
seconds instead, because Forge + OptiFine + a world load routinely take longer than
three seconds; if that grace runs out the log says so and the process still exits
cleanly. Both exits are written to `%TEMP%\doppel-hud.log` with a frame count, and
a window-lost exit (`taskkill` without `/F`, or the game closing it) is logged as
such. A failed `UpdateLayeredWindow` is the one path that is *not* clean: it logs
the Win32 error and exits 1 rather than looping with an empty window.

Frames are pushed when the state, the fade, the mode or an animation changed. The
entrance and the accents are the animations now: a mode whose accent is a function
of the clock (always in Rise/VapeV4/Tesseract, and in Default while the wave is on)
pushes every frame, and a settled waveless Default HUD pushes none until something
changes. The global 350 ms fade on the way out is the overlay's own (the source has
no overlay fade) and is quantised into the frame signature, so the ramp repaints.

### Naming (plan §7, anti-signature)

The window class is `WndClsA1` and the window caption is the empty string: nothing
that enumerates windows looking for the product name will match. Keep it that way —
do not put a product name or phase name into the class, the caption, or any
window-derived string. The watermark's product string (`hud::PRODUCT`) is drawn on
the canvas, not into any window property. `doppel-hud.exe` on disk is the only
place the binary's name appears, and renaming it in D6 is a `Cargo.toml` change.

## Layout of the code

* `src/main.rs` — argument parsing (including `--mode`, `--scale` and the `HUD{}`
  switches), the window class and window, the rect the window covers
  (`--rect` / the primary monitor), the DIB/blit path, the frame loop, `--dump`,
  `WM_DESTROY` / `WM_CLOSE` / `WM_NCHITTEST`.
* `src/win.rs` — every Win32 type, constant and `extern "system"` declaration, so
  the window code, the text rasteriser and the log share one transcription.
* `src/draw.rs` — the pure pixel layer: a premultiplied BGRA canvas, colour packing,
  coverage antialiasing, and the per-corner rounded-box distance field the ported
  chips and panels need. No Win32, so it is covered by unit tests that run anywhere.
  The canvas also records which screen point its `(0, 0)` is (`Canvas::placed`),
  which is what the layout anchors to.
* `src/text.rs` — the GDI text rasteriser: a scratch 32bpp DIB, the two 16 px faces
  (`--scale`-multiplied), the em-box placement and the coverage blit into the
  canvas.
* `src/hud.rs` — the ported HUD: the four modes and their metrics, the palette, the
  accent functions (including HSV→RGB), the entrance state, the screen `Rect` and
  the edge-anchoring helpers, the painting, and the `--dump` description. Every
  constant cites its `HUD.cpp` line.
* `src/state.rs` — the reader half of the shared-state contract, plus the FNV-1a
  the writer keys module ids with.
* `src/log.rs` — the `%TEMP%\doppel-hud.log` breadcrumbs, capped at 64 KB per file
  and best-effort throughout.
* `src/preview.rs` — the `--dump` output: a 32-bit BMP writer and the ASCII
  downsample. Not used by the live overlay.

## Status

| Phase | Scope | State |
| ----- | ----- | ----- |
| **D0** | Skeleton, transparent click-through window, argument parsing | **done** |
| **D1** | Text: GDI rasterised into the premultiplied canvas | **done** |
| **D2** | State transport: read `%TEMP%\doppel-hud.state` every frame, read-only | **done** |
| **D3** | Module list and entrance animation | **done** |
| **D4** | Work-flash signal keyed on module id | **done** |
| **D5** | JVM-side hook that stops drawing in-game and calls `HudStateWriter.publish` | not started |
| **D6** | De-characterised naming across the whole pipeline | partially — see above |
| **D7** | Re-skin to the designer's mockup | **superseded** |
| **D8** | **Port the OpenCommunity HUD: all four modes, accents, `--scale`** | **done** |

Until D5 lands, nothing publishes the state file, so the overlay renders an empty
HUD (with the watermark's count at 0) and exits once its 30-second first-write
grace runs out — and the two lines in `%TEMP%\doppel-hud.log` say exactly that.
