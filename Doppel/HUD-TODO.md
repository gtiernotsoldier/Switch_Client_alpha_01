# Doppel HUD — open work (handoff)

Written after the session that closed every item the previous handoff listed, plus the round-2,
3 and 4 passes below (newest first). Everything under "Done" is verified by the build/test commands
and the commits named with it; everything under "Still open" is scoped so it can be picked up
without re-deriving anything.

## Where things stand

| Item | State |
| --- | --- |
| Local `main` | round 1-3: `2f9451e`…`ea8e9e1`. Round 4: `1171427`, `4f677e8`, `810c674`, `e33a25a`, `7764bae`. Round 5: `efcf6db`, `c70866c`, `d12f35b`, `25d8175`, `f0f0822`. Round 6: `ac922ed`, `de155c2`, `e283ec8`, `c79ede6`. Round 7: `3a7b0ad`, `b918a62`, `6266e5d`, `0480cef`. Round 8 (this pass) adds the commits listed under "Round 8". **Not pushed to GitLab yet** |
| Testable binary | `C:\Users\Administrator\Desktop\swl\doppel-injector-fixed.exe`, rebuilt 20:35:56 with the current agent jar and overlay; embedded blobs hash-match their sources |
| Kotlin build | `:core:test` (94 tests), `:adapter:common:test` (169 tests, 23 classes), `:adapter:forge:v1_8_9:compileKotlin`, `:agent:jar` all green |
| Rust builds | `hud`: 87 tests green. `injector`: 10 tests green |
| Reference sources | `E:\swl` holds LiquidBounce, Raven-bPLUS-main, raven-bS, FDPClient, OpenCommunity and a 1.8.9 MCP tree — use them instead of guessing |
| State contract | **v3**, writer and reader, verified against each other across the two languages; v1 and v2 are still read so a stale file cannot break the overlay |
| Overlay | always-on-top click-through layered window anchored to the game window's **client area** (below the title bar) and following it (`--follow-pid`); **five** modes (Default/Rise/VapeV4/Tesseract/**Modern**); row entrance **and exit** animation; decaying red **work flash**; user-set scale, line spacing, background opacity and row order |
| In-game Keystrokes | reads *effective* key states, so WTap/STap/ADTap/JumpReset light the boxes; Rainbow no longer touches AWT |
| WebUI | keyboard-only numeric fields (slider + an underlined typed value), colour pickers, a HUD list panel (search, pill show/hide, up/down), keybind chips beside the module name reading `[NONE]`/`[N]` |
| Launcher | restyled to the OpenCommunity dark palette with a short intro fade |
| Option sanity | Min/Max option pairs are kept ordered on every write, so `Random.nextInt(min, max + 1)` can no longer be handed an inverted range |

## Round 11 — Aurora, the HUD's own look (overlay mode 5)

The user's verdict on the HUD after living with the ports: 视觉不够好看 — the overlay was a copy of
other clients, not a look of its own. This pass adds **Aurora** (`--mode aurora`, contract mode 5,
the WebUI's new default first choice): the WebUI's own glass-card language carried into the game.

* **The card** — a near-black navy glass panel (`#0D111A @ 82 %`) inside a 1 px slate border with a
  1 px highlight along its top edge, radius 7 on all four corners, 24 px tall at pitch 28 (the
  tallest row of any mode). The accent is not a bar glued to the right edge any more: it is a
  3 px **gradient spine** floating inside the card's left padding, top stop the row's live accent,
  bottom stop 450 ms further down the same sweep, so the gradient visibly flows downward.
* **The type** — names in Play Bold near-white (`#ECF2F8`), values muted (`#98A4B8`) 9 px after the
  name, a value lifted into the accent when the entry carries `highlight` (the contract's highlight
  byte is finally plumbed into the painter, through `RowShape`). The work pulse tints all of it
  towards the mode red exactly as in the other five modes.
* **The watermark** — the same glass pill, with a 6 px gradient dot as the brand mark before a
  near-white bold `DOPPEL`, nickname and FPS in the muted grey.
* **The sweep** — the header's primary/secondary on a 6 s cosine, 0.13 cycles per row. While the
  header still carries the reference's muted wave defaults bit-for-bit, the mode substitutes its
  own cold cyan `#4DC7FF` → warm violet `#B370FF` pair (the Modern palette, i.e. the WebUI's); the
  moment the user picks their own colours in the WebUI they are honoured exactly.
* **Plumbing** — `draw.rs` grew `fill_rounded_rect_gradient` (the flat fill's SDF with a per-pixel
  two-stop lerp); `state.rs` decodes mode 5; `main.rs` maps and documents it; `HudStateWriter`
  publishes it and `HUD.kt` offers it **first** in `OverlayMode`, so a fresh config starts on it (a
  saved choice still wins). The WebUI renders the dropdown from the module's options, so it needed
  no change of its own.
* **Verified without a desktop**: `cargo check --all-targets` on the real crate, and the pure
  geometry/colour suites (50 tests, the four new Aurora ones included) run green under a scratch
  harness kept **outside the tree** (`#[path]`-includes of `draw.rs`/`state.rs`/`hud.rs` plus
  `hud/src/text_stub.rs`, a stand-in text engine the Windows build never compiles); the two
  remaining pixel-assertion tests are GDI-metric-dependent and fail identically under the stub
  with and without this pass, so they are Windows-runner material as before. The design was tuned
  on a pixel-faithful mockup (`scripts/hud_aurora_mockup.html`, real Play faces, the same
  constants) and reviewed as screenshots before any of the code was written.

## Round 10 — NoCameraBob (固定准星)

Added `NoCameraBob` (Utility): the crosshair stays where you are aiming while you walk, and the
held-item sway stays exactly as it was.

The first claim about this was wrong. I had said the two could not be separated because vanilla
drives both from `GameSettings.viewBobbing`. Disassembling the official 1.8.9 jar settles it:
`ItemRenderer` reads **no GameSettings field at all** (the item bob comes from the player's walk
distance), and only `EntityRenderer` — the camera transform — is gated on that setting. So switching
that one setting off stops the camera sway and leaves the hand alone: no bytecode hook needed, which
is why this module is trivial where Freelook is not.

The setting is found **by shape, not by name**: at runtime FML deobfuscates class names to MCP but
leaves members SRG, and the settings file has no `viewBobbing` mapping. So the platform locates the
`GameSettings.Options` constant whose language key is `options.viewBobbing` through structural
reflection — the only declared no-arg String method on that enum (`getEnumString`), the only declared
void method taking one float (`setValue`), the only float-returning one (`getValue`). That is the
lesson from `Blocks.AIR`: never resolve an obfuscated member by a guessed name.

The player's own preference is preserved — whatever the setting was when the module went on is
restored when it goes off.


### NoFOV is gone
The user asked for it to be removed, so the module, its registration (Forge + the AgentBridge
reflection map), the `lockFovModifier` contract flag, the platform `applyFovOverride`/`walkSpeed`
machinery and the legacy `resetFovModifier` handler are all deleted, along with its two tests.

### Eagle had a second, independent bug
Even with the comparison fixed, Eagle could never fire in a real game. The edge detector read
`Blocks.AIR` with `getField("AIR")` — but `net.minecraft.init.Blocks` keeps its deobfuscated **class**
name at runtime while its **fields** are SRG, so AIR is `field_150340_c` and the lookup threw
`NoSuchFieldException`. The lazy initialiser handed that exception to the detector's `catch`, which
answered `false`, so every edge test silently said "not on an edge".

Fixed by asking the world directly — `MappingContext.invokeMethod(world, "forge:world_isAirBlock", pos)`,
Raven's own `isAirBlock(pos)` call, with the mapping added to `mappings/forge/v1_8_9.json` — and by
keeping the block-state comparison as a fallback that resolves `Blocks.air` through
`forge:blocks_air` → `field_150340_c` → `AIR`. If nothing resolves, the detector returns `false`, so a
mapping change can only make Eagle quiet, never make it sneak at the wrong moment.

## Round 8 — the real Eagle bug, reference-faithful modules, and the category names

### `isOnBlockEdge()` was inverted — that is why Eagle "did not work"
The platform helper returned `b !== blocksAir`, i.e. "the block under the player is **not** air".
Raven's rule is the opposite (`isAirBlock(pos)`): standing on an edge means the block carrying you is
air. So Eagle pressed sneak on solid ground and released it at the drop — exactly backwards — and
`BridgeAssist` (which uses the same helper) only assisted away from the edge. Fixed, along with the
Y offset: the reference uses `posY - ((posY % 1.0 == 0.0) ? 1 : 0)`, because with the feet at a
fractional height (slab, stair, snow layer, farmland) the carrying block is the one at `posY`, not
`posY - 1`.

### Modules rewritten against the real reference implementations
`E:\swl` turned out to hold the sources the user kept referring to — LiquidBounce, Raven-bPLUS-main,
raven-bS, FDPClient, OpenCommunity, a 1.8.9 MCP tree — so these are ports of working code, not guesses.

- **Eagle**: a pure, tested `EagleLogic.tick` holding a real sneak state machine (press once on a
  fresh edge, keep holding while the edge holds, count down the randomised hold after leaving, then
  release). The old one pressed and released off the same boolean — its own documented hold never
  happened — and then wrote sneak=false every tick, actively cancelling a manual sneak.
- **FastPlace**: `Speed` had 0 meaning "off", so the most useful setting (instant placement) did
  nothing; `OnlyBlocks` was gated on eating/bow-drawing and happily fired with an empty hand. Now it
  is Raven's: right-click-active window, an activation delay, and a tick delay where 0 = instant and
  4 = never touched.
- **KeepSprint**: it used to restore speed to a running-max cap with jitter (default = a full undo of
  the 0.6 damp, i.e. a boost). Now it undoes vanilla's damp and applies Raven's `(100-slow)/100`,
  optional `stopSprint` X-only halving, and re-asserts the sprint flag; clamped to the observed sprint
  speed so it can never become a boost. **Interaction to know: don't run it alongside
  SprintReset/WTap/STap — one side exists to force a reset, the other to prevent it.**

### NoHurtCam and NoFOV
Both were per-tick writes that could not work (and NoHurtCam's was harmful). NoHurtCam zeroed
`hurtTime`, which is the "just got hit" signal HitSelect and Velocity read and the driver of the red
damage tint; it now raises `maxHurtTime` on the render frame instead — 1.8.9's `hurtCameraEffect`
divides the roll by that number and its two yaw rotations cancel, so the tilt is cancelled without
touching `hurtTime`. NoFOV's 20 Hz write could never stick because `updateFovModifierHand()` runs
inside `Minecraft.runTick()`; it now holds the modifier on the render frame **and** neutralises what
the per-tick recompute reads (`walkSpeed = 0`, vanilla's own 1.0 escape hatch), restoring everything
on disable.

### InvMove (背包行走)
New `Movement` module: while a screen is open, the platform re-applies W/A/S/D/space/shift from the
*effective* key accessors, so taps and JumpReset keep working inside the inventory and Keystrokes stays
truthful. It writes key **state** only, never a key event, so typing "w" in chat still types "w".
Options: KeepSprint (true), OnlyInventory (false).

### Work-flash coverage, per the user's list
`markWorked()` was added to Reach, SuperKnockback, SprintReset, KnockbackDisplace, JumpReset,
ClickAssist, BridgeAssist, AutoTool, Eagle and KeepSprint — at the transition where the module
actually does something, never on a steady hold. DelayRemover was explicitly excluded. Note
`HUDEntry.isRed` (which excludes COMBAT) is dead: the writer publishes `flash` from
`flashStrength()` alone, so COMBAT rows do flash.

### RENDER and WORLD are now VISUAL and UTILITY
The category enum, every module, the theme and the WebUI tabs read `Visual` and `Utility`.

## Round 7 — the route bug that made half the panel dead

### Every per-module route except the on/off toggle was a silent no-op
This is the one that mattered, and it explains four complaints at once. `handleModules` derived the
module name like this:

```kotlin
val name = path.removePrefix("/api/modules/").removeSuffix("/toggle")
```

so `/api/modules/Velocity/hud` produced the name **`"Velocity/hud"`**, `ModuleRegistry.get()` returned
null, and the handler did nothing — while still answering `200` with the module's *unchanged* values.
The panel, which trusts the response, then put the old value back on screen. So:

- **keybinds never saved** (the POST was a no-op) → "the keybind does not work";
- **the HUD eye never hid anything** → "hiding a module does nothing";
- **the red dot never toggled** anything;
- **the panel never responded**, because it was handed a success it could not tell from a refusal.

`moduleRoute()` now splits the path into *(name, action)* — pure, and pinned by `ModuleRouteTest` with
the exact paths the page uses — and an unknown module or action answers **404 with an error** instead
of a 200 lie. The keybind response reads the value back from the module rather than echoing the request.
The panel's half: every POST now ends in a toast, a non-2xx surfaces the server's own error text, a
fresh bind is fenced against an in-flight poll writing `NONE` back over it, and a dead link says so
once instead of failing silently.

### The overlay always draws the length staircase
The user asked for the rows to be ordered by length. `Sort`, `Reversed` and the whole manual-order
machinery (`HudOrder` option, `hudOrderedNames`/`hudIndexOf`/`moveInHud`, `POST /api/hud/order`, the
`hudIndex` field) are deleted: the panel that drove them is gone, and the overlay now sorts by the
rendered width of each row unconditionally.

### Red is the font, not a red band
The work pulse used to paint a translucent red fill across the whole row box. It is now a colour
**lerp on the row's text** — name and value — starting from whatever colour that mode would otherwise
draw, so all five modes keep their own look and only the glyphs go red. Measured on `--dump` output:
at max flash the glyph cores land exactly on `#FF5555` while the background probe is bit-identical to
a flash-free dump (the old build differed by ~1800-2300 pixels and flipped that same probe to
`(168,43,45)`). Two integer lerps per row, no extra pass.

### The red dot is gone and the eye reads at a glance
The user did not want the red dot, so it is deleted. The eye is now unmistakable: listed = the panel's
accent cyan with a soft hover glow, hidden = flat grey at 55% opacity with no glow in any state, and
the strike-through stays so the state is not carried by colour alone.

## Round 6 — the HUD list goes away, and the HUD finally follows its own options

### Why the overlay ignored the HUD module's settings
`Position` and `Sort`/`Reversed` are tunable in the panel and had **no path to the overlay at all**:
the contract had no field for the side the list hangs off, and the published-order flag only turned on
for a manually arranged list. So changing them did nothing — which is what "the HUD does not keep up
with the HUD module's config" was.

- Contract: **flags bit 7 = `positionLeft`**. Reserved in v2/v3, so it is a backward-compatible use of
  a reserved bit — a reader that does not know it masks it off, no other field moved, and the wire
  version stays 3. `--position` still wins over the header, which wins over the default (Right, the
  reference placement).
- `Sort`/`Reversed` now make the overlay follow the published order the moment they are not the
  default, the same way an arranged list already did.
- The Rust side also had to mix the position bit into the frame `signature()`, or a position-only
  change would rewrite the state file, leave the signature identical, and skip the frame — the bug
  would have survived the fix.
- **`HUDOptionContractTest` is the guard**: for every option the HUD module exposes, changing it must
  change what the overlay is told. The next option added without a wire path fails there instead of in
  the user's game.

### Pressing a key says so
A key press with no visible result is indistinguishable from a key press that never arrived — which is
why "my keybind does not work" was unanswerable. A keybind toggle now puts a short-lived line at the
top of the HUD (`WTap ON` / `WTap OFF`) for 1.5 s, driven through `EventBridge.noticeModuleToggled`
from `Module.tryKeybindToggle`, so it works for every module including one that just switched off and
therefore has no row of its own. It rides the existing name+value row rather than a new mechanism.
The panel's keybind chain was re-checked end to end and pinned by `ModuleJsonContractTest` (the payload
carries `keybind`, `keyName`, `hudHidden`, `hudShown`, `redIndicator`).

### The work flash now has something to flash about
Only five call sites marked a module as working (WTap/STap/ADTap/Velocity/AutoClicker), and
AutoClicker is a silent module by default — so in normal play the red wash almost never appeared even
though the mechanism was fine. `markWorked()` now also fires on TriggerBot's clicks (both click
strategies), HitSelect's counter-hits, AutoBlock's block *transitions* (never the steady hold),
BlockHit's presses and KnockbackDelay actually holding a packet back.

### The HUD list is gone
The user asked what it was for and said to delete it, so it is deleted: markup, CSS, JS and the
`renderHudList` path. Its two controls moved onto every module card, in the order the user asked for:
`[icon] name [KEYBIND] [EYE] [RED DOT]` — a real SVG eye (open = listed, struck through = hidden) that
posts `{"hidden":bool}`, and a red dot that posts `{"red":bool}`. Both keep the optimistic flip, the
`hudBusy` guard against the poll fighting a click, and the authoritative value from the response. The
server endpoints stay (this page simply no longer calls `/api/hud/order`).

## Round 5 — visibility, animations, and the knobs that should not exist

### The animation that only ran on the phone
The panel had a `prefers-reduced-motion: reduce` block with a blanket `animation: none !important`.
The user's PC reports that preference (Windows' own "show animations" setting is off), so the whole
designed experience — entrances, pulses, the aurora — was dead on the machine they actually use,
while their phone animated normally. The block is gone, and the mobile pass no longer kills
animations either; its layout and touch-target work stays. **Do not reintroduce a global
animation/transition kill switch** — the product owner has asked for the animations explicitly.

### The HUD list only belongs over the game window
The overlay was always-on-top over *every* application, so alt-tabbing to a browser left the HUD
glued on top of it. It is now gated on the game window being in front: visible while the followed
pid's window is the foreground window and not minimised, hidden (`SW_HIDE`, no window on screen)
otherwise, re-checked on the follow poll's slow cadence while the frame loop keeps running. No pid
means no way to know which window is the game, so that case keeps the old always-shown behaviour.

### The overlay was spawning a visible console window
Found while wiring the gate above, and it was the bigger stealth hole: `doppel-hud.exe` is a
console-subsystem binary and the injector spawned it without `CREATE_NO_WINDOW`. The injector itself
is a GUI app launched by `javaw.exe`, so it has no console to hand down — Windows gave the overlay a
brand-new console window: a black box titled with the exe path, floating over the game, listed in the
taskbar, and (because a new console takes the foreground) it made the new visibility gate see *our*
pid in front and hide the HUD until the game was clicked again. The spawn now passes
`CREATE_NO_WINDOW`, so the console never exists.

### Two knobs that asked the user to make the same choice twice
`HUD.HudOrder` was the raw `"WTap,STap,…"` string the ▲▼ buttons maintain, and it was rendered as a
free-text field in the option list; it is now a hidden option like the keybind. And
`OverlayOrder` (Width / Published) is gone entirely: the overlay follows the user's arrangement as
soon as they have one, and stays the reference's width wedge until then. Arranging a row clears the
cached header style, so the first ▲/▼ takes effect immediately.

### Keybinds: the last gap in the round trip
Round 4 made the keybind persist, but the file was still only read as a side effect of the *WebUI
module* starting — with the WebUI switched off, a restart left every keybind unbound again. The
config now loads at bootstrap, before anything can read it, and the startup line reports how many
keybinds came back (`N module keybind(s) restored`), which is the line to check when asking "did the
game get told?".

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

## Round 3 — the user's second round of feedback

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
8. **The ClickGUI will be external — decided, deliberately not started.** The user's call after
   being burned by drawing a GUI inside the game: the future ClickGUI renders in an **external
   overlay window**, never in the game's render pipeline — the model the HUD overlay already
   proves (own process, layered topmost window, `--follow-pid` client-area tracking). Do not start
   until the user calls for it. When it does start, the genuinely new problems are input routing
   (the overlay is click-through while idle but must take the mouse when the GUI opens, while the
   game keeps reading raw input), foreground/focus against a fullscreen game, and reusing the
   panel work's component language (`window.rs`, the VAPE-style pane) in overlay form. The old
   HUD mockups in `scripts/` stay as design reference for that pass.

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
