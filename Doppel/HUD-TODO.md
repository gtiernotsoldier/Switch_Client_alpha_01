# Doppel HUD — open work (handoff)

Written after the session that closed every item the previous handoff listed, plus the round-2,
3 and 4 passes below (newest first). Everything under "Done" is verified by the build/test commands
and the commits named with it; everything under "Still open" is scoped so it can be picked up
without re-deriving anything.

## Where things stand

| Item | State |
| --- | --- |
| Local `main` | round 1: `2f9451e`, `9f03f5a`, `c489f73`, `19f267c`, `6429935`. Round 2: `a29fb8e`, `75b1c6a`, `0d946be`, `e7ad03b`. Round 3: `3f720f5`, `c2fbcfc`, `c660949`, `ea8e9e1`. Round 4 (this pass) adds the commits listed under "Round 4". **Not pushed to GitLab yet** |
| Testable binary | `C:\Users\Administrator\Desktop\swl\doppel-injector-fixed.exe`, rebuilt 18:21:51 with the current agent jar and overlay; both embedded RCDATA blobs hash-match their sources |
| Kotlin build | `:core:test` (94 tests), `:adapter:common:compileKotlin`, `:adapter:common:test` (97 tests, 19 classes), `:adapter:forge:v1_8_9:compileKotlin`, `:agent:jar` all green |
| Rust builds | `hud`: 80 tests green. `injector`: 10 tests green, only the 3 pre-existing warnings (`flow.rs` platform, `inject.rs` WAIT_TIMEOUT, `process.rs` GetLastError) |
| State contract | **v3**, writer and reader, verified against each other across the two languages; v1 and v2 are still read so a stale file cannot break the overlay |
| Overlay | always-on-top click-through layered window anchored to the game window's **client area** (below the title bar) and following it (`--follow-pid`); **five** modes (Default/Rise/VapeV4/Tesseract/**Modern**); row entrance **and exit** animation; decaying red **work flash**; user-set scale, line spacing, background opacity and row order |
| In-game Keystrokes | reads *effective* key states, so WTap/STap/ADTap/JumpReset light the boxes; Rainbow no longer touches AWT |
| WebUI | keyboard-only numeric fields (slider + an underlined typed value), colour pickers, a HUD list panel (search, pill show/hide, up/down), keybind chips beside the module name reading `[NONE]`/`[N]` |
| Launcher | restyled to the OpenCommunity dark palette with a short intro fade |
| Option sanity | Min/Max option pairs are kept ordered on every write, so `Random.nextInt(min, max + 1)` can no longer be handed an inverted range |

## Round 4 — world-load exits, keybinds that did not stick, and the phone

### The keybind was never saved (that is what "keybind does not work" was)
`Module.keybind` was a plain field, and only *registered options* are written to `doppel-config.json`
— so every key the user bound was silently forgotten on the next start. The keybind and the red
work-flash flag are now registered as **hidden options**: persisted, validated and reloaded through
the normal path, but filtered out of the option list so the panel does not grow a "Keybind: -1"
slider. `ModuleKeybindPersistenceTest` pins the round trip. The poll also stopped swallowing its
exceptions silently — a broken poll now leaves one line per 10 s in `doppel-agent.log`, and a
successful toggle was already logged by `Module.enable/disable`, so the two cases are
distinguishable.

### The HUD stopped dying during a world load
The overlay decided "the game is gone" purely from the state file's mtime, so Minecraft's
multi-second terrain generation (no rendered frame, no publish) looked exactly like a dead game and
the overlay faded out and exited. With `--follow-pid` — which the injector always passes — the game
**process** is now the verdict: alive means stay on screen with the last frame, gone means exit.
Without a pid the old three-second stale rule is untouched.

Independently verified by A/B on one hand-built stale (30 s old) block with an off-screen rect:
with `--follow-pid <live pid>` the overlay was still drawing after 5 s (and still re-asserting
topmost); without a pid the same file ends it in 0.4 s. A pid that cannot be opened (access denied)
counts as alive — never kill a live game over a permissions quirk.

### Min/Max HUD values, for every module
The round-3 fix was Velocity's own string; the reported behaviour ("more than Velocity") was the
*derived* path, where a module without a `HudLineProvider` showed only the first number of its
range. `HUD.autoHudValue` now looks for a Min/Max pair first and renders both ends (`25-55 ms`,
`3.00-3.20 blocks`), collapsing to one value when they are equal.

### The HUD list panel, and the red flash
The panel hid and ordered HUD lines, but nothing said so and the user reasonably asked what it was
for. Its header and hint now say it: shown = listed on the HUD, the pill lists/unlists a line (not
the module), ▲▼ is the draw order. It also gained the control the user guessed at — a per-module
**red work-flash** toggle, backed by the same endpoint (`{"red": bool}`) and the persisted
`RedIndicator` option.

### Phone pass
One narrow-viewport query (≤720 px) collapses the grid to one column, stacks the chrome, gives every
control a ≥40 px touch target and drops the expensive looks (no `backdrop-filter`, no glow shadows,
no infinite animations, transitions capped at 0.12 s) while keeping the same palette at higher alpha.
A separate `prefers-reduced-motion` query does the same for motion-sensitive users — including
`body::before`, which the old `*`-only rule never matched, so the 24 s aurora drift used to keep
running for them. Desktop rules are untouched (all scoped to the queries).

## Round 3 — the users second round of feedback

### A Min above its Max was a crash, not just a bad number
Every ranged module feeds its two options straight into `Random.nextInt(min, max + 1)`, which
**throws** when `min > max` — a whole-tick crash — and the float paths silently returned values
outside the range. Nothing stopped the panel, a hand-edited config or a half-applied import from
inverting a pair. `ConfigManager` now keeps them ordered on every write (and on `resetOption` /
`resetAll`, which bypass `set`): setting the minimum above the maximum raises the maximum, and the
reverse lowers the minimum. The sibling is derived from the option's *name* (`optionPairOf`, the last
`min`/`max` token, casing preserved), so no module has to declare its pairs and a module added later
gets the behaviour for free; the 20 real pairs in the tree are pinned by a test. `MathUtils` is
defensive as well, as a backstop for anything that reads a pair before the option layer sees it.

### Velocity's HUD value was a number nobody configured
`hudValue()` printed the *midpoint* of each range, so 0%..100% showed as "50%". It now prints the
ranges the user actually set — `Legit 80%-100%/100%`, collapsing an axis to one value when both ends
are equal — and Click mode reports its click burst instead of the ranges it does not use.

### The keybind is visible, and the HUD row carries it
The HUD row appends the bound key (`Human: ... [N]`), because an unbound module showing nothing is
exactly the ambiguity that made the user ask whether binding had worked. The panel's keybind chip
moved out of the drawer to the card header, immediately right of the module name, and reads `[NONE]`
when unbound — with a toast naming the module and the key on a successful bind, and one on Esc.
`KeyNames` (in `api`, so the module layer can use it without depending on the HTTP server) is the one
table behind both.

### WebUI readability
Module names were being ellipsised: the columns are wider now, the label row wraps instead of
truncating, and the chips are slightly smaller. The HUD list's ambiguous `◉` dot became a real pill
toggle with the row dimmed while hidden and a `shown / total` count. The precise numeric field is the
underlined blank the user asked for — transparent, a single bottom rule, the number right-aligned
inside it — with the commit behaviour (clamp and snap to the declared step) unchanged.

## Round 2 — what the user asked for and where it landed

### 1. The HUD sat over the title bar
Fixed on both ends: the injector's one-shot `--rect` now comes from `GetClientRect` + `ClientToScreen`
(`injector/src/process.rs`), and the overlay's `--follow-pid` poll does the same (`hud/src/follow.rs`,
`client_rect()`). The client area excludes the title bar and the borders, so the HUD's top row starts
below the window frame. A zero-sized client area still falls back to "no rect" / keep-the-last-good
one rather than placing a degenerate overlay.

### 2. WebUI numeric options: keyboard only
The `-`/`+` nudge buttons are gone (template, listener and CSS). Each numeric option keeps the slider
for coarse dragging and a typed field that commits on change/blur, clamping and snapping to the
option's declared `step`/`decimals` at that moment. `COLOR` options now render as a swatch plus a hex
field instead of `—`, and the server publishes their 0..`0xFFFFFF` bounds.

### 3. Toggle transition + red work flash
The contract's entry byte stopped being the old "this module is enabled" flag and became a 0..255
**work-pulse strength** sampled from `Module.markWorked()` / `flashStrength()` — code that had existed
for a long time and was never read by anything (`HUD.flashByte`, published per frame). The overlay
washes the row red by that strength and lets it decay to nothing, and a row that leaves the published
list now fades and slides back out over ~220 ms instead of vanishing (`Anim::exits`). Verified on a
real dump: a row published with `flash = 200` measures 4478 red-washed pixels, the idle row next to
it measures zero.

### 4. Keybinds: English, and not fighting the game's own keys
`WebUIServer.keyName` returns `Unbound` / `Space` / `LShift` / `A` / `F1` / … instead of `未绑定` /
`空格` / `键A`, so the panel and Minecraft's own controls screen use one vocabulary (`WebUIKeyNameTest`
also pins that every label is plain ASCII). The panel shows which vanilla actions commonly use a key
while the user is binding it. And the module keybind poll now refuses to run while a screen is open —
a keybind bound to `E` used to fire while the player was typing in chat.

### 5. Performance
- `HudStateWriter` no longer issues a 4 KB write every frame: it hashes the packed block and skips the
  syscall when nothing changed, with a 1-second keep-alive because the file's mtime is the overlay's
  liveness signal (`writeCount` is exposed for the tests).
- `HUD.overlayHeader()` caches the option-derived half of the header and invalidates on a config
  change, instead of reading eight options through `ConfigManager` every frame.
- The keybind poll above stops doing per-frame reflective work while a screen is open.

### 6. The HUD list is the user's
New `HUD` options: `OverlayScale`, `OverlayLineSpacing` (a separate knob from the scale, which would
also resize the text), `OverlayOpacity` (background fills only — the labels stay readable) and
`OverlayOrder` (`Width` = the reference's wedge, `Published` = the user's order). The manual order is
a persisted name list with a `POST /api/hud/order` endpoint behind it, and the WebUI grew a "HUD list"
panel: search, per-module show/hide, up/down reordering, and each module's own published value.

### 7. A modern HUD
`Mode::Modern` is a fifth mode alongside the ported four, selectable with `--mode modern` or the
WebUI's `OverlayMode`. It draws rounded four-corner cards with a 3 px accent bar at the right edge, a
value tag in the live accent, a rounded watermark card, and its own slow cyan→violet sweep. The four
ported modes are byte-identical to before — the mode's geometry and colours are pinned by the
existing tests.

## Done in this pass

### A1 — Keystrokes did not react to the modules
`EventBridge.isKeyForwardActive` / `isKeyBackActive` / `isKeyLeftActive` / `isKeyRightActive` /
`isKeyJumpActive` derive what the *game* sees from the same inputs the platform writes into the
KeyBinding (the tap modules' synthetic flags plus the physical mirror), and `Keystrokes` reads those
instead of the physical-only fields. `KeyBinding.pressed` is deliberately still never read back —
that latch is what caused the sticky keys. 8 tests in `EventBridgeEffectiveKeysTest`, including "a
synthetic press lights the box while the physical key is up" and "a W-tap reads as released even
though the player still holds W".

### A2 — The overlay did not follow the game window
The injector now passes `--follow-pid <pid>` in addition to the one-shot `--rect`, and the overlay
re-reads the game window every second (`hud/src/follow.rs`: `EnumWindows` +
`GetWindowThreadProcessId` + `GetWindowRect`, skipping hidden/minimised/degenerate windows) and
moves/resizes itself, rebuilding its DIB when the size changes. `--rect` is still the initial
placement and the whole answer without a pid. Unknown/zero pids are refused at the command line.
6 unit tests pin the window choice (titled Minecraft first, else largest visible) and the
corner→origin+size conversion.

**Note for the user:** under *exclusive* fullscreen no external overlay can ever be visible —
Windows hands the display to the game. Windowed or borderless fullscreen is required. This is now
in the `--help` text as well.

### A3 — "Text renders as squares"
Settled, and made mechanically detectable rather than a judgement call:
- The last run's `faces:` line says `embedded Play (2 face(s) registered, family Play)`, so the
  registration path is not the problem.
- New: the overlay asks GDI which of the characters it actually draws the selected face has glyphs
  for (`GetGlyphIndicesW` + `GGI_MARK_NONEXISTING_GLYPHS`, `text.rs::missing_glyphs`). Measured on
  this machine: **72/72** of the sample the HUD's own strings use, so the overlay's own text cannot
  be the tofu source.
- The one realistic in-overlay source is a non-Latin player name in the watermark (the embedded
  Play family is Latin-only). Forcing a CJK nickname into the state file makes the probe report
  exactly `nickname "玩家Player" — 2 character(s) the name face cannot draw`.
- The remaining candidates are therefore outside the overlay: the **WebUI panel** (its Chinese
  labels fall back to whatever the browser has) and the **in-game HUDs** (MC's own font). Neither
  surface draws a non-ASCII string from this codebase today — the only CJK string literal in the
  render code is a log message — so if the report was about text over the game, the next question is
  which surface it was on.

### A4 — Frame drops around the HUD
`Keystrokes.rainbowColor` no longer calls `java.awt.Color.getHSBColor` every frame. It computes
HSV→RGB locally and caches the result for 50 ms (a 3.75 s cycle moves ~1.3% in that window, so it is
invisible), which also means AWT never loads on the render path. `KeystrokesColorTest` pins the local
computation against AWT's own `getHSBColor` across the whole cycle, and pins the caching.

### B1 — Contract v2 writer (Kotlin) + HUD module options
`HudStateWriter` now publishes v2: a 64-byte header (flags, mode, primary/secondary, FPS, nickname)
over the same 56-byte entries, 4096 bytes with the 448-byte reserved trailer. The `HUD` module gained
`OverlayMode` (Default/Rise/VapeV4/Tesseract), `OverlayWave`, `OverlaySpaced`, `OverlayWatermark`,
`OverlayPrimaryColor`, `OverlaySecondaryColor` (defaults `0x4E566B` / `0x7892D6`), and publishes them
in the header; the Forge adapter fills the nickname from the player's display name (formatting codes
stripped) and the FPS from its own frame counter. The user can now pick a list effect from the game
or the WebUI instead of a CLI flag.

Verified across languages: the Kotlin test writes the block, then
`doppel-hud.exe --dump` reads it back as
`v2 block, 2 entries; header mode rise wave true spaced true watermark false, nick "Steve", 144 fps`
and renders the watermark as `DOPPEL | Steve | 144 FPS`.

Two real bugs were found by the new tests on the way: the fixed-field writer could leave a dangling
UTF-8 lead byte when the 24-byte boundary fell inside a multi-byte character, and the rainbow cache
never populated on its first call (`now - Long.MIN_VALUE` overflows).

### B2 — WebUI numeric options + value mismatch
- The server now publishes each option's declared `step` and the `decimals` it implies
  (`OptionMeta.decimals()`), and the WebUI uses them end to end: a typed value field, slider snapping,
  and saving at the declared precision instead of a hard-coded tenth. Clamping happens on commit, not
  while typing. (Round 2 removed the `-`/`+` buttons the user did not want; the field is the control.)
- The module's own published HUD string (`hudValue()`) is now sent to the panel and shown on the
  module card, so the panel and the external overlay display the *same* string instead of two
  different formattings of the same option — the reported Velocity case. The auto-derived HUD value
  for modules without a provider now formats through the same precision rule.
- 5 tests in `OptionMetaTest` (steps, decimals, locale independence) plus the WebUI script
  syntax-checked with `node --check`.

### B3 — Launcher look
Restyled to the OpenCommunity dark palette with `injector/src/theme.rs` as the single source of
truth (native window and terminal fallback share it), the reference's `SetupImGuiStyle` metrics, a
~420 ms intro fade, and owner-drawn list rows. The old cyan/violet palette is gone. Details and the
pixel-sampling evidence are in commit `6429935`.

## Still open

Scoped, all of it cosmetic or environmental rather than broken:

1. **Push to GitLab.** Every commit above is local only.
2. **Not verified against a running game.** Nothing in these passes could be exercised against
   Minecraft on this machine: the client-area rect, the follow poll, the liveness rule, the keybind
   end-to-end path, the work flash and the Modern mode are verified by unit tests, by the `--dump`
   renderer, by pixel analysis of its BMP output, and — for the liveness rule — by an A/B run of the
   real binary against a hand-built stale state file. A real world load and a real keypress are still
   the last mile.
3. **The WebUI's DOM and phone rendering are reasoned, not observed** — there is no browser here.
   The script passes `node --check`, its helpers were exercised against a DOM shim, and the CSS was
   audited for balance, but the ≤720 px layout, the touch targets, the `prefers-reduced-motion` path
   and the `:has()` progressive rule have not been seen on a device.
4. **Owner-drawn list rows are unverified against live data** — there was no running `javaw.exe` on
   this machine, so `WM_DRAWITEM`/`WM_MEASUREITEM` never fired with real rows.
5. **The stock list scrollbar is still OS-drawn** (light next to `#0B0E13`); marked `TODO(B3)` in
   `window.rs` along with rounded *window* corners, per-row hover and DPI awareness.
4. **The injector is still DPI-unaware**, so its fixed 900x550 client is bitmap-stretched at 125/150%.
5. **`theme.rs` carries one module-scoped `#![allow(dead_code)]`** because it transcribes the whole
   palette while the injector uses a subset. If that is unwanted, prune the unused entries instead.
6. **A3's remaining surfaces** — if the tofu report was in the WebUI panel or over the game, the
   overlay's log has now ruled itself out; the next step is to ask which surface it was.
7. **D6 de-characterisation** is still pending by design: the state file name, the window class and
   the process names are all fixed.

## Verification (copy-paste)

Kotlin (adapter/common 88 tests + core 94 tests):
```powershell
$ws='C:\Users\Administrator\Desktop\swl'; $t="$ws\.tools"
$env:JAVA_HOME="$t\jdk8\jdk8u504-b01"; $env:GRADLE_USER_HOME="$t\gradle-home"
$env:CARGO_HOME="$t\cargo"; $env:RUSTUP_HOME="$t\rustup"; $env:TEMP="$t\tmp"; $env:TMP="$t\tmp"
$env:JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
$env:PATH="$env:JAVA_HOME\bin;$t\w64devkit\w64devkit\bin;$t\gradle-8.5\bin;$t\MinGit\cmd;$t\cargo\bin;$env:PATH"
cd "$ws\doppel-final\Doppel"; gradle --offline --no-daemon --console=plain :core:test :adapter:common:compileKotlin :adapter:common:test :adapter:forge:v1_8_9:compileKotlin :agent:jar
```
Rust (hud 72 / injector 10):
```powershell
cd "$ws\doppel-final\Doppel\hud";      cargo test --release
cd "$ws\doppel-final\Doppel\injector"; cargo test --release
```
The overlay reviewed with no game running (also prints the glyph-coverage answer):
```powershell
cd "$ws\doppel-final\Doppel\hud"; cargo build --release
& .\target\release\doppel-hud.exe --dump "$env:TEMP\review.bmp" --mode modern --line-spacing 130
```
Kotlin daemon flakiness (`Unknown or invalid session`) is environmental: delete
`Doppel\adapter\common\build` and rerun; never run two Gradle/Cargo builds against the same tree at
once.

## Injector rebuild ritual (the `resources/` copies are build inputs, not sources)

```powershell
$jar = Get-ChildItem "$ws\doppel-final\Doppel\agent\build\libs\doppel-agent-*.jar" | Select-Object -First 1
Copy-Item $jar.FullName "$ws\doppel-final\Doppel\injector\resources\agent.jar" -Force
Copy-Item "$ws\doppel-final\Doppel\hud\target\release\doppel-hud.exe" "$ws\doppel-final\Doppel\injector\resources\doppel-hud.exe" -Force
(Get-Item "$ws\doppel-final\Doppel\injector\build.rs").LastWriteTime = Get-Date   # cargo fingerprints by mtime: without this it will not re-embed
cd "$ws\doppel-final\Doppel\injector"; cargo build --release
Copy-Item .\target\release\doppel-injector.exe "$ws\doppel-injector-fixed.exe" -Force
Copy-Item "$ws\.dsh-tmp\agent.jar.placeholder" .\resources\agent.jar -Force   # restore the 0-byte tracked placeholder
```
Then verify the RCDATA blobs match the sources by hashing
`injector\target\release\build\**\out\{agent.jar,doppel-hud.exe}` against the originals. Both matched
for the current binary (`agent.jar` `8A04AE3B…`, `doppel-hud.exe` `1039C3AD…`).

## Reference material on this machine

- **The state contract**: `Doppel/docs/hud-contract-v3.md` — the frozen wire format both sides
  implement. Any change there is a protocol change: bump the version on both sides and update the
  two test suites together.
- OpenCommunity (the client whose HUD/launcher we mirror): `E:\swl\OpenCommunity\`
  - HUD: `runtime\src\features\render\HUD.cpp` (four modes, all the numbers)
  - launcher UI: `launcher\src\ui\Screen.cpp`, palette `deps\imgui\colors.h`
  - the B3 research write-up: `.dsh-tmp\b3-launcher-spec.md`
- Lunar Keystrokes v3-3.0: `.minecraft\versions\1.8.9-Forge_11.15.1.1902-OptiFine_M5\mods\[1.8.9] Lunar Keystrokes Mod v3-3.0.jar.disabled`
  (disassembly + extracted constants: `.dsh-tmp\lunar-ks\`, geometry pinned in `KeystrokesLayout.kt`)
- Design mockup for the overlay: `E:\swl\doppel.html` (and `.dsh-tmp\doppel-design.css`)
- Review images: `.tools\tmp\hud-mode-{default,rise,vape,tesseract}.png`, `hud-live-corner.png`
- Runtime logs: `%TEMP%\doppel-hud.log` (overlay; now carries `faces:` and `glyphs:` lines),
  `%TEMP%\doppel-hud-launch.log` (injector hand-off), `%TEMP%\doppel-agent.log`,
  `%TEMP%\doppel-payload.log`
