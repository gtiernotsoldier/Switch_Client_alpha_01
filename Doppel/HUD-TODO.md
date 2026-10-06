# Doppel HUD — open work (handoff)

Written at the point this session's context ran out. Everything below is verified by the
commits listed, and the remaining items are scoped so a fresh session can start on any of
them without re-deriving anything.

## Where things stand

| Item | State |
| --- | --- |
| GitLab `main` | pushed; last commit `c8734ef` (Keystrokes rollback) |
| Testable binary | `C:\Users\Administrator\Desktop\swl\doppel-injector-fixed.exe`, 8.69 MB, 16:22:25 |
| Build | HUD crate: `cargo build/test --release` green (50 tests). Kotlin: `:adapter:common:compileKotlin`, `:adapter:common:test`, `:adapter:forge:v1_8_9:compileKotlin`, `:agent:jar` all green. |
| Overlay | separate always-on-top click-through layered window; anchored to a rect; Play font embedded; four module-list modes; reads state contract v1 **and** v2 |
| State contract | Rust reader: v2 ready (64-byte header, `STATE_SIZE` 4096 with a 448-byte reserved trailer, v1 still the ordinary case). **Kotlin writer is still v1** |
| Keystrokes | rolled back to the pre-Lunar Raven-style renderer (`KeystrokesLayout.kt` + its 26 tests remain but unused) |

Build/test environment (copy-paste):
```powershell
$ws='C:\Users\Administrator\Desktop\swl'; $t="$ws\.tools"
$env:JAVA_HOME="$t\jdk8\jdk8u504-b01"; $env:GRADLE_USER_HOME="$t\gradle-home"
$env:CARGO_HOME="$t\cargo"; $env:RUSTUP_HOME="$t\rustup"; $env:TEMP="$t\tmp"; $env:TMP="$t\tmp"
$env:JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
$env:PATH="$env:JAVA_HOME\bin;$t\w64devkit\w64devkit\bin;$t\gradle-8.5\bin;$t\MinGit\cmd;$t\cargo\bin;$env:PATH"
cd "$ws\doppel-final\Doppel"; gradle --offline --no-daemon --console=plain :adapter:common:compileKotlin :adapter:common:test :adapter:forge:v1_8_9:compileKotlin :agent:jar
```
Kotlin daemon flakiness (`Unknown or invalid session`) is environmental: delete `Doppel\adapter\common\build` and rerun; never run two Gradle/Cargo builds against the same tree at once.

Injector rebuild ritual (the `resources/` copies are build inputs, not sources):
```powershell
$jar = Get-ChildItem "$ws\doppel-final\Doppel\agent\build\libs\doppel-agent-*.jar" | Select-Object -First 1
Copy-Item $jar.FullName "$ws\doppel-final\Doppel\injector\resources\agent.jar" -Force
Copy-Item "$ws\doppel-final\Doppel\hud\target\release\doppel-hud.exe" "$ws\doppel-final\Doppel\injector\resources\doppel-hud.exe" -Force
(Get-Item "$ws\doppel-final\Doppel\injector\build.rs").LastWriteTime = Get-Date   # cargo fingerprints by mtime: without this it will not re-embed
cd "$ws\doppel-final\Doppel\injector"; cargo build --release
Copy-Item .\target\release\doppel-injector.exe "$ws\doppel-injector-fixed.exe" -Force
Copy-Item "$ws\.dsh-tmp\agent.jar.placeholder" .\resources\agent.jar -Force   # restore the 0-byte tracked placeholder
```
Then verify the RCDATA blobs match the sources by hashing `target\release\build\**\out\{agent.jar,doppel-hud.exe}` against the originals.

## A. Fixes the user has reported

### A1. Keystrokes does not react to the modules (W/S/A/D/JumpReset)
Root cause: after the sticky-key fix, `EventBridge.isKey*Down` reports the **physical** key state
only, so module-driven presses (WTap pressing W, JumpReset pressing space) never light the boxes.
Do **not** revert to reading `KeyBinding.pressed` back — that is what caused the sticky keys.
Instead add an "effective" accessor per key in `adapter/common/.../api/EventBridge.kt`:
`isKeyForwardActive = isKeyForwardDown || <module pressed it>`, where "module pressed it" is a flag
the platform sets in `pressKey`/`releaseKey` (`adapter/forge/v1_8_9/.../ForgeEventBridge.kt`
already owns both paths and knows when a write is synthetic). Point `Keystrokes` at the effective
accessors. Add a test that a synthetic press lights the box while the physical key is up.

### A2. The overlay does not follow the game window (seen in fullscreen)
The injector passes `--rect x y w h` **once**, from `GetWindowRect`, at launch
(`injector/src/resource.rs::launch_hud_when_ready`, using `injector/src/process.rs::find_game_window_rect`).
Any later fullscreen switch or window resize leaves the overlay on the old rect. Fix: pass the game
**pid** as well (`--follow-pid <pid>`), and in the HUD re-read the game window rect every ~1 s
(`EnumWindows` + `GetWindowThreadProcessId` + `GetWindowRect`, skipping `IsIconic`) and re-place the
window when it changes. Keep the plain `--rect` behaviour as the fallback.
Note for the user: with **exclusive** fullscreen no external overlay can ever be visible (Windows
hands the display to the game); borderless/windowed is required.

### A3. Text rendering as squares (tofu) — reported once, not yet diagnosed
Entry point: `%TEMP%\doppel-hud.log`, the `faces:` line. It says either
`embedded Play (2 face(s) registered, family Play)` or a fallback message. If it says Play, the
glyphs are present and the squares come from somewhere else (check the WebUI, and MC's own font in
the in-game HUDs). If it fell back, find out why `AddFontMemResourceEx` failed before changing faces.

### A4. Frame drops around the HUD
The Keystrokes Rainbow colour calls `java.awt.Color.getHSBColor` **every frame**
(`Keystrokes.kt::rainbowColor`). AWT inside the render loop is a stall source; cache it (recompute
every ~50 ms). If drops persist, audit that `RenderUtils.rect` and the module HUDs restore every GL
state they touch (that is what "interfering with Minecraft's state rendering" looks like).

## B. Remaining features

### B1. Contract v2 writer (Kotlin) + HUD module options
The Rust reader is done and tested. Implement the writer to the same spec in
`adapter/common/.../hud/HudStateWriter.kt`:
```
STATE_SIZE 4096, header 64 bytes, ENTRY_SIZE 56, MAX_MODULES 64
  0  u32 magic 0x444F5050 | 4 u32 version 2 | 8 u32 count
 12  u32 flags  bit0 wave, bit1 spaced, bit2 watermark, bits3..4 mode (0 default,1 rise,2 vape,3 tesseract)
 16  u32 primary 0x00RRGGBB | 20 u32 secondary | 24 u32 fps (0 = unknown)
 28  u32 reserved | 32 char nickname[24] UTF-8 NUL-padded | 56 u32 reserved | 60 u32 reserved
 64  entries[] (u32 id; u8 enabled; u8 flash; u8 highlight; u8 pad; char name[24]; char value[24])
```
Then expose the overlay's options in the HUD module (`adapter/common/.../module/`): `Mode`
(Default/Rise/VapeV4/Tesseract), `Wave`, `Spaced`, `Watermark`, `PrimaryColor`, `SecondaryColor`
(reference defaults `0x4E566B` / `0x7892D6`), publish them in the header, and feed `nickname` from
`mc.thePlayer.getName()` and `fps` from the client's frame counter when one exists. This is what
lets the user pick a list effect from the game/WebUI instead of a CLI flag.

### B2. WebUI numeric options + value mismatch
- Numeric options cannot be tuned finely: allow decimals and a declared step, `-`/`+` buttons that
  respect it, typed exact values, clamp only on commit, and keep the precision when saving. The
  option layer groundwork (declared-type coercion) is already committed (`4607c57`); the WebUI
  (`adapter/common/src/main/resources/doppel/webui/index.html` + `ConfigStore`) still needs it.
- The external HUD's values disagree with the WebUI for some modules (Velocity named). Both come
  from the module table: find where the published value is formatted differently from the module's
  own tag and publish the module's own string.

### B3. Launcher look
Restyle `injector/src/{window.rs,ui.rs}` after `E:\swl\OpenCommunity\launcher\src\ui\Screen.cpp`
with the dark palette in `OpenCommunity\deps\imgui\colors.h:86-111`, plus a short startup
splash/fade. A salvaged first attempt is at `C:\Users\Administrator\Desktop\swl\.dsh-tmp\salvage\theme.rs`.

## C. Reference material on this machine

- OpenCommunity (the client whose HUD/launcher we mirror): `E:\swl\OpenCommunity\`
  - HUD: `runtime\src\features\render\HUD.cpp` (four modes, all the numbers)
  - launcher UI: `launcher\src\ui\Screen.cpp`, palette `deps\imgui\colors.h`
- Lunar Keystrokes v3-3.0: `.minecraft\versions\1.8.9-Forge_11.15.1.1902-OptiFine_M5\mods\[1.8.9] Lunar Keystrokes Mod v3-3.0.jar.disabled`
  (disassembly + extracted constants: `.dsh-tmp\lunar-ks\`, geometry pinned in `KeystrokesLayout.kt`)
- Design mockup for the overlay: `E:\swl\doppel.html` (and `.dsh-tmp\doppel-design.css`)
- Review images: `.tools\tmp\hud-mode-{default,rise,vape,tesseract}.png`, `hud-live-corner.png`
- Runtime logs: `%TEMP%\doppel-hud.log` (overlay), `%TEMP%\doppel-hud-launch.log` (injector hand-off),
  `%TEMP%\doppel-agent.log`, `%TEMP%\doppel-payload.log`
