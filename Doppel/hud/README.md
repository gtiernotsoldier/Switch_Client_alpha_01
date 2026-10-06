# doppel-hud — out-of-process HUD overlay

A standalone Win32 process that puts the **OpenCommunity in-game HUD** on top of
the Minecraft client: the watermark run and the module list, in **all four of the
source's modes** — Default, Rise, VapeV4, Tesseract. Its layered window covers the
**game window** — the rect the injector reads with `GetWindowRect` on Minecraft's
own window and passes as `--rect`, or the whole primary monitor when there is no
game window to follow — and the HUD is drawn only at the source's own margins;
every other pixel is fully transparent.

It stays **above the game** (`WS_EX_TOPMOST`, re-asserted whenever something gets
in front of it) and **never touches the mouse or the focus**: click-through, no
activation, no taskbar entry. It draws in the reference's **own Play typeface**,
embedded in the executable, not a substitute.

The HUD is a **port, not a re-design**: the user reviewed the source HUD and asked
for it to be copied, so the geometry, the colours and the animations in
`src/hud.rs` are transcribed from

| reference | what was taken |
| --------- | -------------- |
| `runtime/src/features/render/HUD.cpp` (654 lines) | everything: the four modes, the watermark, the rows, the accent functions, the entrance animation, the text shadow |
| `runtime/src/features/render/HUD.h` (49 lines) | the slide-progress map keyed by module name, the per-frame delta, the font roles |
| `runtime/src/features/render/RenderHook.cpp` | the font roles and the faces themselves: Play regular + Play bold, embedded as C arrays (see [Fonts](#fonts)) |
| `runtime/src/features/visuals/ArrayList.h` (106 lines) | the module options the HUD reads: mode, watermark, spaced modules, wave, the two wave colours |
| `shared/common/ModuleConfig.h` | the `HUD{}` block: the same toggles, the same colour defaults (`78,86,107` → `120,146,214`), `m_WaveSpeed` |

Every constant in `src/hud.rs` carries the source line it came from, so the two
can be diffed by eye. Nothing is injected, no game class is touched, and the
overlay cannot crash the game by drawing.

## Floating above the game (`WS_EX_TOPMOST`)

A layered window with `WS_EX_TRANSPARENT` and no `WS_EX_TOPMOST` is click-through,
focus-less, invisible in Alt-Tab — and **completely covered the moment the game has
focus**, which is the one thing it must not be. That was the bug: the HUD was
drawing correctly, into a window that the game was sitting on top of.

The window is now created with `WS_EX_TOPMOST` and raised once, immediately after
creation:

```c
SetWindowPos(hwnd, HWND_TOPMOST, x, y, w, h,
             SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_SHOWWINDOW);
```

* **`SWP_NOACTIVATE` is not optional.** The overlay must never take the foreground
  (see [Hard prerequisite](#hard-prerequisite-windowed--borderless-fullscreen-only)
  and the no-focus rule below), and raising a window without it would.
* **Topmost is not permanent.** A fullscreen transition, a shell surface, another
  always-on-top window or a driver can drop the overlay out of the band, and the
  user then sees nothing. Every **2 s** the frame loop checks two cheap things —
  `GetWindowLong(GWL_EXSTYLE) & WS_EX_TOPMOST`, and
  `GetWindow(hwnd, GW_HWNDPREV) != NULL` (something now sits above us) — and
  re-applies `SetWindowPos` only when one of them says the overlay has been
  overtaken. A settled session issues no `SetWindowPos` at all, just the poll.
  Re-asserts are logged (the first five, then every fiftieth) so a desktop that
  keeps something above the overlay is visible in the log rather than invisible.
* **`--no-topmost`** leaves the band entirely — the escape hatch. See the stealth
  note below for why it exists and why it is not the default.

The startup line in `%TEMP%\doppel-hud.log` records the outcome: the requested
state, the extended-style word **as read back from the window** with its flags
spelled out, whether `SetWindowPos` succeeded, and whether anything is above the
window. `--dump` and `--hide` print the topmost state too.

### Stealth: topmost is the more conspicuous combination

Plan §7 treats the overlay as something that should not advertise itself, and
**`WS_EX_TOPMOST` is the more conspicuous combination of the two**: a
non-topmost layered window can hide behind any ordinary window, while a topmost
one is in the always-on-top band for as long as it runs, is enumerated as such by
anything that walks the Z-order, and re-raises itself in front of whatever the
user brings forward. `WS_EX_TOPMOST` is a flag a checker can read off the window
in one call.

It is still the default, because **it is required for the overlay to be visible
over the game at all**: without it the focused Minecraft window covers the HUD
completely and the feature does not exist. That is the whole trade — the plan's
stealth note loses to the feature working. `--no-topmost` gives the stealthier
window back for anyone who needs it, at the cost of the HUD being hidden whenever
the game has focus.

## The rect: the game window, not the monitor

The overlay anchors its layout to the rect it is handed, so the rect decides
where the HUD lands. Two steps produce it:

1. **The injector** (`Doppel/injector/src/resource.rs`) finds Minecraft's window —
   the same `EnumWindows` search that already labels the process list, which now
   records the `HWND` alongside the window title — and reads its rect with
   `GetWindowRect`. Those corner coordinates become `--rect x y w h` on the
   overlay's command line, and `%TEMP%\doppel-hud-launch.log` records the rect and
   where it came from.
2. **The overlay** takes that rect **verbatim**. It is deliberately *not* clamped
   to, or intersected with, any monitor: a second monitor to the left of the
   primary one gives a negative `x`, one above gives a negative `y`, and a windowed
   game is narrower than the screen. Clamping would put the HUD somewhere the game
   is not.

When the window cannot be found — the game is not running yet, is minimised
(`IsIconic`), or reports a degenerate rect — the injector passes **no `--rect` at
all**, and the overlay falls back to the whole primary monitor
(`GetSystemMetrics(SM_CXSCREEN / SM_CYSCREEN)`), which is what the injector's
launch log says happened.

The one wrinkle is DPI. The overlay calls `SetProcessDPIAware`, so it wants
physical pixels; the injector is a plain DPI-unaware GUI app (making it aware
process-wide would re-scale its own launcher window), and on a scaled display
`GetWindowRect` would answer in virtualised coordinates — a 1920x1080 game
reported as 1280x720 on a 150 % display, and an overlay in the wrong place. The
HUD-launching thread therefore asks for
`SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)`, which
is per-thread and leaves the launcher alone. It is resolved with `GetProcAddress`
rather than imported, because the call only exists from Windows 10 1703 and a
static import would stop the injector from *loading* on anything older. When it is
unavailable the launch log says so explicitly rather than passing off possibly
virtualised numbers as correct.

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
  (the default) draws: two blocks, one in each of the rect's top corners, sharing
  the rect's top line (`topY = 4`; the watermark's text sits 2 px lower inside the
  Rise/VapeV4 panel, and Tesseract's rows start at its own 2 px). `--position left`
  swaps the two elements to the other pair of edges; the elements' own internal
  layout is untouched. Both blocks are anchored to the rect the overlay covers,
  **origin included**: a game window that is not at the screen's origin — or on a
  monitor left of the primary one, with a negative `x` — gets the same two corners,
  because the anchoring is measured in screen coordinates and then converted into
  the layered window's own surface at that rect's `(0, 0)`.
* **The wedge.** Rows are sorted by measured width, descending, and each row is
  right-flushed to the mode's margin, so the list forms the source's staircase.
* **Chips.** Rise/VapeV4 fill the whole row, Tesseract its own dark chip, Default
  nothing at all. Tesseract's chip is rounded 5 px and VapeV4's 6 px on their
  **left corners only** — the right corners stay square, where the accent bar sits.

## The watermark

The source draws one inline run — the product name in the mode's accent, ` | `,
the player's nickname, ` | `, `"<n> FPS"` — on one baseline. **Contract v2 carries
both of those segments in its header** (`nickname[24]` and `fps`), so the port now
draws the source's own run:

```
DOPPEL | <nickname> | <n> FPS
```

A segment with nothing to say is dropped, its separator included: an empty
nickname (or a v1 block, whose 16-byte header has no nickname field) removes the
middle segment, and `fps = 0` — the contract's "unknown" — removes the FPS one. A
block with neither draws the bare `DOPPEL`, which is exactly what this port drew
before contract v2 existed, so the v1 reader and the old look stay in step.

The enabled-module count that used to stand in for the missing nickname is gone:
the header supplies the source's own two segments, and the count was never one of
them.

In Rise and VapeV4 the run sits in the source's panel: a `rgba(0,0,0,88)` fill with
a 2 px accent bar down its right edge, and VapeV4 rounds the panel's left corners
by 6 px. **In Rise the title is `riseWatermarkTextColor`, not the accent** — that
is what HUD.cpp:556 does, and the README says so because the prompt's summary
("the product name in the accent") is the general case, not Rise's. The panel's
width is the source's `titleWidth + segmentWidth + fpsWidth + risePadX*2 +
riseRectWidth + 1` (HUD.cpp:544), with the middle terms now really measured from
the nickname and FPS segments instead of an assumed zero.

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

## Fonts

The HUD draws in the reference's **own typeface**: OpenCommunity embeds **Play**
(regular and bold) as C byte arrays and loads them through ImGui
(`runtime/src/features/render/RenderHook.cpp:408-437`). Those same bytes are now
in this executable.

* **Extraction.** `tools/extract_oc_fonts.py` parses
  `deps/imgui/fonts/play_regular_font.h` and `play_bold_font.h` — the
  `static const unsigned char play_*_data[171084] = { 0x00,0x01,... };` literals —
  checks each array against its `play_*_size` constant, and writes
  `assets/Play-Regular.ttf` (171,084 bytes) and `assets/Play-Bold.ttf`
  (170,232 bytes). It then reads each file's `name` table, so the family the Rust
  side asks GDI for is the font's own word for itself rather than a guess:
  `family = "Play"`, `subfamily = "Regular"` / `"Bold"`, version 1.002.
* **Embedding.** Both files are pulled in with `include_bytes!`, not shipped as
  sidecars: the injector embeds *this executable* as resource `RCDATA 103` and
  extracts it to `%TEMP%` on its own, so a `.ttf` sitting next to the build
  machine's exe would not travel with it.
* **Registration.** `AddFontMemResourceEx` registers the two faces at startup,
  then `CreateFontW` creates them by family name at **16 px × `--scale`** —
  `FW_BOLD` for module names (Default mode) and the watermark title, `FW_NORMAL`
  for tags, values and everything else. That is `RenderHook.cpp:530`'s
  `HUD::SetFonts(regular, bold, vape)` mapping; the reference's third face (a
  "vape font" read from disk) is substituted with regular Play exactly as
  `RenderHook.cpp:511-512` does when its file is missing.
* **Verification, not assumption.** After creating them, `GetTextFaceW` is asked
  which face GDI actually selected. If it is not the Play family — a driver that
  refuses memory fonts, a name-table mismatch — **both** roles are rebuilt as
  **Segoe UI Semibold / Segoe UI** (the previous substitute pair) and the startup
  log line says so. `%TEMP%\doppel-hud.log` and `--dump` both print the resolved
  face names, so "it fell back" is never a silent state.
* **Licence.** Play is under the **SIL Open Font License 1.1**
  (`Copyright (c) 2011, Jonas Hecksher, Playtypes, e-types AS`, licence URL
  `http://scripts.sil.org/OFL`). The reference tree carries no font licence file
  of its own, so `assets/FONTS.md` reproduces what each font declares about
  itself, records the sha256 of both files, and says what is required if this
  overlay is ever distributed on its own. **Read it before shipping the assets.**
* **What is deliberately not bundled.** The reference also embeds Inter regular +
  bold, but `RenderHook.cpp:533` hands those to `Notifications::SetFonts` — a
  subsystem this overlay does not have — so its ~670 KB of glyphs would ship for
  nothing. Same for Open Sans, SF UI Display and the reference's compressed
  variants.

**Size.** The overlay executable grew from **530,142 to 890,137 bytes**
(+359,995, +67.9 %): 341,316 bytes of that is the two TTFs, 12,413 bytes is the
PE section alignment around them, and 6,266 bytes is the new code (measured by
building the same sources with empty placeholder `.ttf` files: 536,408 bytes).

## GDI adaptations (the three things Win32 cannot do the way ImGui does)

1. **Per-corner rounding.** Tesseract's chips and VapeV4's panel are rounded on
   their left corners only. GDI's `RoundRect` takes one radius for all four;
   `CreateRoundRectRgn` + `CombineRgn` can build the region but it is an opaque
   1-bit mask with no alpha and no antialiasing, and this surface is premultiplied
   per-pixel alpha for `UpdateLayeredWindow`. The fill in `src/draw.rs` selects the
   radius per quadrant of its signed-distance field instead — the same cost as the
   single-radius fill, and the rim stays antialiased. Documented at the function.
2. **Fonts: memory-resident, not installed.** ImGui loads a TTF from a byte array
   and rasterises it itself. GDI can do the same thing — `AddFontMemResourceEx`
   plus `CreateFontW` — but the face has to be *registered* rather than merely
   pointed at, the buffer has to stay valid for as long as the face is used (which
   `include_bytes!` data does for the whole process), and GDI can quietly
   substitute another family if the registration did not take. Hence the
   `GetTextFaceW` read-back and the explicit fallback pair above. Text placement is
   unchanged: the top of the run's em box goes at the source's `textY`, where
   ImGui's `AddText` puts the top of the line box, with `internal_leading` rows of
   compensation for GDI's taller cell. Now that the face is Play, its metrics are
   the reference's, so the ink lands where the source puts it.
3. **`--scale <f>`.** The source has no DPI or scaling handling at all — every
   number in it is a physical pixel. `--scale` (default 1.0, allowed 0.5..4.0)
   multiplies every metric and the font size so the HUD can be made readable on a
   4K screen. **1.0 is the faithful setting** and the only one the source's numbers
   describe.

## Not reproduced

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
distance field, the state parser, the log, the port's pure geometry: the
per-mode master constants (pitch, insets, bar width, chip radii, margins), the
scale multiplier, the row box arithmetic, the tag gap, the wedge sort, the
anchoring on the monitor rect, the entrance rate, the HSV conversion, all four
accent functions at known timestamps and indices, the repaint rule — plus the
behaviour added since: **whether topmost needs re-asserting** (`win::topmost_lost`)
and the ex-style flag rendering, **the family match that decides whether the
embedded face was really selected**, the embedded TTFs' own sanity checks (sfnt
magic, the `Play` name, the size budget), and **the argument and rect rules**
(`--no-topmost`'s default, and that a `--rect` is never clamped to a monitor).

Contract v2 and the two-corner layout added their own: **the flags word bit by
bit** (including that bits 5..31 change nothing), the `nickname`/`fps` fields and
their NUL padding, both header sizes against a hostile file (short, foreign, an
unknown version — and that a full 4096-byte block's trailer is ignored), **the
header-versus-flag precedence field by field**, that a header change reaches the
frame signature, the watermark run's segment assembly and the panel width that
follows from it, **the two-corner anchors in all four modes** (watermark origin,
the x every row's box is flushed to, and the top line both blocks share), and one
frame drawn on canvases placed at four different origins — the regression that
would otherwise put the watermark at `rect.x + 10` *surface-local*, which is a
different place on every window position and no place at all off a negative-origin
surface.

Regenerating the assets (only needed if the reference fonts change):

```sh
python tools/extract_oc_fonts.py
```


## Running

```sh
target/release/doppel-hud.exe [options]
```

| Option | Meaning | Default |
| ------ | ------- | ------- |
| `--rect <x> <y> <w> <h>` | the screen rect the overlay covers: the layered window is created at `(x, y)` with that size and the HUD is laid out inside it at the mode's own margins | the whole primary monitor |
| `--x` / `--y` / `--w` / `--h` | per-field adjustments to the chosen rect | the rect's own values |
| `--position right\|left` | `right` = the source's own placement (watermark left, rows right); `left` = the two elements on the opposite edges | `right` |
| `--mode default\|rise\|vape\|tesseract` | which of the four modes is drawn (`vapev4` is accepted); an explicit flag overrides the state header | the header's mode, else `default` |
| `--scale <f>` | multiply every metric and the font size (0.5 .. 4.0) | `1.0` |
| `--primary <r,g,b>` | the Default wave's first colour | the header's `primary`, else `78,86,107` |
| `--secondary <r,g,b>` | the colour the wave blends towards | the header's `secondary`, else `120,146,214` |
| `--wave-speed <f>` | the Default wave's speed (`cycle = 2500/speed` ms) | `1` |
| `--no-wave` | flat primary accent in Default mode | wave on, or the header's `flags` bit 0 |
| `--no-watermark` | no watermark run (and no Rise/VapeV4 panel) | watermark on, or the header's `flags` bit 2 |
| `--spaced` | split `CamelCase` module names (`AutoClicker` → `Auto Clicker`) | off, or the header's `flags` bit 1 |
| `--no-topmost` | do **not** put the overlay in the always-on-top band; the game then covers the HUD whenever it has focus (see [Floating above the game](#floating-above-the-game-ws_ex_topmost)) | topmost on |
| `--dump <path.bmp>` | render one representative frame to a 32-bit BMP on the fixed 520x400 review canvas, print the measured geometry, an ASCII preview and the runtime rect, create no window, exit 0; draws the state file's rows and v2 header when one is readable, the built-in demo table otherwise | — |
| `--hide` | print one line, create no window, exit 0 | — |
| `--help` | print usage, exit 0 | — |

The rect the window covers is the whole primary monitor by default
(`GetSystemMetrics(SM_CXSCREEN / SM_CYSCREEN)`; the primary monitor's origin is
`(0, 0)` by definition — Win32 has no `SM_XSCREEN`/`SM_YSCREEN`), or 1920x1080 if
that call gives nothing usable. **This is the placement fix**: the window used to
be a 520x400 stage at `(0, 0)`, and since the HUD anchors to the edges of whatever
rect it is given, the list landed around x = 400 — the middle of a 1920-wide screen
— with the rest of the window transparent. The layout was never wrong; the rect it
hung off was. `--rect` is the hook the injector now uses to follow the game window
(see [The rect](#the-rect-the-game-window-not-the-monitor)); whatever rect arrives,
it is used exactly as given and never clamped to a monitor.

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
* one line on the **faces** it will draw with — whether the embedded Play faces
  registered, and the face names GDI resolved for the name and body roles, so a
  silent fallback to Segoe UI is visible;
* one line on **topmost** — requested or disabled, the extended-style word as read
  back from the window with its flags spelled out, whether `SetWindowPos` succeeded,
  and whether anything sits above the window;
* one line per **re-assert** (the first five, then every fiftieth) when the overlay
  is put back above something that overtook it;
* one line on exit — the reason (`window lost`, `state stale`, `state file never
  appeared`, or `error: <Win32 error>`) and how many frames it pushed to the
  compositor.

Zero frames drawn plus a start line means the state file never appeared; frames
drawn plus "nothing on screen" means the rect line and the topmost line are the
things to read. Logging is best-effort and can never fail the overlay, and a log
over 64 KB is truncated on the next start. The injector's half of the hand-off —
including the exact `--rect` it passed and where that rect came from — is in
`%TEMP%\doppel-hud-launch.log`.

Stop the overlay from that console with Ctrl+C, from Task Manager, or with
`taskkill /IM doppel-hud.exe` (without `/F`: a plain `taskkill` posts `WM_CLOSE`,
which the window procedure turns into a clean `PostQuitMessage` and exit 0).

## What the window actually is

* Style `WS_POPUP`; extended styles `WS_EX_LAYERED | WS_EX_TOPMOST |
  WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT` (drop
  `WS_EX_TOPMOST` with `--no-topmost`). `SetWindowPos(HWND_TOPMOST, ...)` with
  `SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_SHOWWINDOW` raises it into the
  always-on-top band, and the frame loop re-asserts it when it is lost.
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
  `SW_SHOW`. The `SetWindowPos` that keeps the overlay on top carries
  `SWP_NOACTIVATE` for the same reason: raising a window does not have to focus it.
  Taking the foreground from the game is what makes a fullscreen game stop
  rendering, so it is the one thing this process must never do.
* **Click-through.** `WS_EX_TRANSPARENT` plus `WM_NCHITTEST` returning
  `HTTRANSPARENT`: every click goes to the game, none are swallowed. `WS_EX_TOPMOST`
  changes only which band the window is drawn in, not how it is hit-tested.
* Not in the taskbar and not in Alt-Tab (`WS_EX_TOOLWINDOW`).

### The tag colour rule

The tag is the mode's muted grey — except in **VapeV4 while the module is in use**,
where it is `(255,85,85)` (HUD.cpp:640). The other three modes keep their grey
whatever the state says; that is the source's behaviour, not an omission. "In use"
is the state contract's `flash` byte, which is exactly the signal the source reads
as `mod->IsInUse()`.

### The state contract (D2)

`%TEMP%\doppel-hud.state`, written by `HudStateWriter` as one **4096-byte** block,
`#pragma pack(push,1)`, little-endian:

| offset | size | field |
| ------ | ---- | ----- |
| 0 | 4 | `uint32 magic` — `0x444F5050` ("DOPP") |
| 4 | 4 | `uint32 version` — **2** |
| 8 | 4 | `uint32 count` — entries written, ≤ 64 |
| 12 | 4 | `uint32 flags` — bit0 wave, bit1 spaced names, bit2 watermark, bits 3..4 mode (0 default, 1 rise, 2 vape, 3 tesseract), bits 5..31 reserved |
| 16 | 4 | `uint32 primary` — `0x00RRGGBB` |
| 20 | 4 | `uint32 secondary` — `0x00RRGGBB` |
| 24 | 4 | `uint32 fps` — 0 = unknown, the segment is omitted |
| 28 | 4 | reserved |
| 32 | 24 | `char nickname[24]` — UTF-8, NUL padded |
| 56 | 4 | reserved |
| 60 | 4 | reserved |
| 64 | 64 × 56 | `entries[]`, exactly as v1 |
| 3648 | 448 | reserved trailer, so the block is exactly 4096 bytes |

An entry is 56 bytes: `uint32 id`, `uint8 enabled`, `uint8 flash`, `uint8
highlight`, `uint8 pad`, `char name[24]`, `char value[24]` — UTF-8 and NUL padded,
unchanged from v1. The header and the entries occupy the first 3648 bytes; the
reader never looks past them.

**Version 1 is still read.** The shipped writer
(`HudStateWriter.VERSION = 1`) publishes the original 3600 bytes — a 16-byte
header (`magic`, `version`, `count`, `pad`) and the same 64 entries. That block is
decoded exactly as before, and because it carries none of the v2 header fields,
every one of them keeps the overlay's own default: the mode from `--mode`, the
switches from the flags, the colours from `--primary`/`--secondary`, no nickname
and no FPS. Reading v1 is therefore not a shim bolted on beside the real path; it
is the ordinary case until the writer is updated.

**Precedence.** The v2 header is the *default* for the mode, `m_Wave`,
`m_SpacedModules`, `m_Watermark` and the two wave colours; `--mode`, `--no-wave`,
`--spaced`, `--no-watermark`, `--primary` and `--secondary` are explicit overrides,
so a caller that names a flag always gets it. The nickname and the frame counter
have no flag — they are the header's alone. The header is re-read every frame, so a
game that changes its mode combo repaints without restarting the overlay, and the
frame signature covers the header fields, so such a change is not skipped as
"nothing moved".

The reader opens the file read-only — there is no back channel — and renders
nothing at all on a missing, short, foreign or unknown-version file. The startup
line in `%TEMP%\doppel-hud.log` says *which* of those it was ("no readable file",
"short (64 bytes, need 3600 for v1 or 4096 for v2)", "foreign magic 0x…",
"unknown version 3 (this reader knows 1 and 2)"), together with the entry count and,
for a v2 block, the mode/flags/nickname/FPS it decoded — so "the overlay never read
my header" is a log line rather than a guess.

The port reads three fields of each entry: `name` (the row's name), `value` (its
tag) and `flash` (the VapeV4 tag colour). `enabled` is honoured and `ArrayList` is
never listed, exactly as `HUD::GetActiveModules` does. `highlight` is unused by
this HUD.

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

Frames are pushed when the state, the v2 header, the fade, the mode or an
animation changed. The entrance and the accents are the animations now: a mode
whose accent is a function of the clock (always in Rise/VapeV4/Tesseract, and in
Default while the wave is on) pushes every frame, and a settled waveless Default
HUD pushes none until something changes. The header is part of the signature, so a
game that changes its mode combo, its nickname or its frame counter repaints. The
global 350 ms fade on the way out is the overlay's own (the source has no overlay
fade) and is quantised into the frame signature, so the ramp repaints.

### Naming (plan §7, anti-signature)

The window class is `WndClsA1` and the window caption is the empty string: nothing
that enumerates windows looking for the product name will match. Keep it that way —
do not put a product name or phase name into the class, the caption, or any
window-derived string. The watermark's product string (`hud::PRODUCT`) is drawn on
the canvas, not into any window property. `doppel-hud.exe` on disk is the only
place the binary's name appears, and renaming it in D6 is a `Cargo.toml` change.

## Layout of the code

* `src/main.rs` — argument parsing (including `--mode`, `--scale`, `--no-topmost`
  and the `HUD{}` switches), the window class and window, the rect the window covers
  (`--rect` / the primary monitor, taken verbatim), the topmost raise and its 2 s
  re-assert check, the DIB/blit path, the frame loop, `--dump`,
  `WM_DESTROY` / `WM_CLOSE` / `WM_NCHITTEST`.
* `src/win.rs` — every Win32 type, constant and `extern "system"` declaration, so
  the window code, the text rasteriser and the log share one transcription; also
  the two pure helpers `--dump` and the log build their lines from
  (`describe_ex_style`, `topmost_lost`).
* `src/draw.rs` — the pure pixel layer: a premultiplied BGRA canvas, colour packing,
  coverage antialiasing, and the per-corner rounded-box distance field the ported
  chips and panels need. No Win32, so it is covered by unit tests that run anywhere.
  The canvas also records which screen point its `(0, 0)` is (`Canvas::placed`),
  which is what the layout anchors to.
* `src/text.rs` — the GDI text rasteriser: a scratch 32bpp DIB, the two embedded
  Play faces (`AddFontMemResourceEx` + `CreateFontW`, at 16 px × `--scale`), the
  `GetTextFaceW` read-back and the Segoe UI fallback, the em-box placement and the
  coverage blit into the canvas.
* `assets/` — `Play-Regular.ttf` and `Play-Bold.ttf`, extracted from the reference,
  plus `FONTS.md` (licence and provenance). `tools/extract_oc_fonts.py` regenerates
  them.
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

The injector's half of the same two features lives outside this crate:
`Doppel/injector/src/process.rs` finds the game window and turns its `GetWindowRect`
corners into a `--rect` (`WindowRect::from_corners`, a pure function with its own
tests), and `Doppel/injector/src/resource.rs` passes those arguments when it spawns
this executable.

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
| **D9** | **Visible over the game: `WS_EX_TOPMOST` + re-assert, the game window's own rect as `--rect`, and the reference's own Play faces embedded** | **done** |
| **D10** | **Split layout verified in all four modes (watermark top-left, list top-right, anchored to the rect's own origin), state contract v2 (mode/flags/colours/nickname/FPS header, CLI flags as overrides, v1 still read)** | **done** |

Until D5 lands, nothing publishes the state file, so the overlay renders an empty
HUD (with the watermark's count at 0) and exits once its 30-second first-write
grace runs out — and the two lines in `%TEMP%\doppel-hud.log` say exactly that.
