# IPC protocol v1 — thin stub ↔ core process

Implementation of `PROTECTION.md` §9.5 (P6 v2). This document describes **what is
in the tree today**, not what is planned; §9 is the design rationale, this is the
byte table and the runbook.

- Core process: `Doppel/core-rs/` (`doppel-core`, Rust, zero third-party deps)
- Thin stub: `Doppel/core/src/main/kotlin/io/doppel/core/ipc/` (`CoreStub.kt`, `Wire.kt`)
- Status: **vertical slice complete** — see [§9 Implementation status](#9-implementation-status)

---

## 1. Why the transport is TCP loopback, not a named pipe

§9.5.1 selected a named pipe. That was written against the *core* side, where a
pipe is trivial. The JVM side is the constraint:

| Concern | Named pipe | TCP loopback |
|---|---|---|
| Java 8 (the game's runtime) | needs JNA or a native shim | `java.net.Socket`, built in |
| New dependency in the agent jar | yes (JNA ≈ 1.5 MB, native extraction) | none |
| p50 round trip | 7.9 µs (measured, `ipc_latency_bench.c`) | **6.70 µs** (measured, `doppel-core --bench`) |
| Deadline headroom (1 ms) | 127× | 80× at p99 |

The protocol is transport-agnostic: `transport.rs` is the only file that knows
which pipe it is on, and `Endpoint` already parses `unix:` alongside `tcp:`.
Swapping to a named pipe later is a change to that one file plus a JNA decision,
with no protocol bump.

**Both transports are loopback-only.** The token handshake is a backstop against
another local process, not a substitute for not exposing the port.

## 2. Framing

Every message is exactly **384 bytes**: a 32-byte header and up to 352 bytes of
payload. Fixed size means the reader is one `readFully` into a stack buffer, there
is no length prefix to desynchronise, and the shared-memory path (if ever needed)
can reuse the same layout unchanged.

384, not the original 256. While velocity was the only module its snapshot was
160 bytes and the remainder was headroom. Aim needs the target's per-tick velocity
and world-space box — the predictive lead reads the first, the multipoint reads
the second — and the generic modules need an op byte plus up to 14 f64 arguments
behind the snapshot. One 232-byte snapshot plus a 14-argument tail is 346 bytes,
which does not fit in 224. Still one page, still a single read.

All integers little-endian. Floats are IEEE-754 and are sent as **raw bits**.

Position and motion travel as **f64**, scalar config as **f32**. The split is not
arbitrary: anything that multiplies into a decision (positions, velocities) must
arrive at the core with the same bits the JVM held, because `motion × factor` on a
value that made a round trip through f32 differs in the last few mantissa bits.
That difference is invisible in play and fatal to replay parity — it took one
e2e run to find, at tick 0. Scalars that are read from the config UI are f32 on
both sides already, so widening them would buy nothing.

### 2.1 Header

| Offset | Size | Field | Notes |
|---:|---:|---|---|
| 0 | 4 | `magic` | `0x44504C31` (`"DPL1"`). Rejects wrong-endian / garbage. |
| 4 | 2 | `version` | `1`. A mismatch is answered with `Status.BadVersion`, not a drop. |
| 6 | 2 | `kind` | see §3 |
| 8 | 4 | `seq` | client-assigned, monotonic. Replies echo it. |
| 12 | 4 | `tick` | game tick the message refers to |
| 16 | 2 | `payload_len` | `<= 224`, else `BadPayload` |
| 18 | 2 | `status` | 0 on requests; error code on replies |
| 20 | 1 | `module` | `1` velocity, `2` click, `3` aim |
| 21 | 1 | `flags` | reserved, 0 |
| 22 | 2 | — | reserved, 0 |
| 24 | 8 | `correlation` | echoed verbatim; unused in v1 |

A frame that fails to parse is **not** a reason to kill the process. The core
answers with an `Error` frame carrying the status and keeps the connection — a
half-dead peer that sends garbage must degrade, not crash the thing that is
supposed to be the crash barrier.

## 3. Message kinds

| Value | Name | Direction | Purpose |
|---:|---|---|---|
| 1 | `Hello` | stub → core | payload = token, UTF-8, zero-padded |
| 2 | `HelloAck` | core → stub | `session_id: u64`, `seed: u64`, `build: u32`, `features: u32` |
| 3 | `Snapshot` | stub → core | one tick of state, §4 |
| 4 | `Decision` | core → stub | what to do this tick, §5 |
| 5 | `Config` | stub → core | config snapshot, §6 |
| 6 | `Ping` / 7 `Pong` | both | liveness only; the snapshot is the real heartbeat |
| 8 | `Error` | core → stub | `status` says why |
| 9 | `Bye` | stub → core | clean shutdown, no reply |

### Status codes

| Value | Name | Meaning |
|---:|---|---|
| 0 | `Ok` | |
| 1 | `BadVersion` | unknown protocol version |
| 2 | `BadToken` | handshake token mismatch |
| 3 | `UnknownKind` | kind not in the table above |
| 4 | `BadPayload` | truncated / out of range |
| 5 | `Degraded` | strategy panicked; **the tick is lost on purpose** |
| 6 | `NoSession` | no successful handshake on this connection |

## 4. `Snapshot` payload — 232 bytes (all modules)

Offsets are from the start of the payload. Every module receives this same
block, whether or not it reads all of it: one layout means one decoder, and the
offsets cannot drift between modules.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | `mode` (informational; config wins) |
| 1 | 1 | `is_knockback_hit` |
| 2 | 2 | `flags` bitfield, §4.1 |
| 4 | 24 | `original_motion.x/y/z` (**f64**) |
| 28 | 24 | `position.x/y/z` (f64) |
| 52 | 24 | `motion.x/y/z` (f64) |
| 76 | 4 | `yaw` (f32) |
| 80 | 4 | `pitch` (f32) |
| 84 | 4 | `health` (f32) |
| 88 | 4 | `hurt_time` (i32) |
| 92 | 4 | `hurt_resistant_time` (i32) |
| 96 | 1 | `weapon` (0 other, 1 sword, 2 axe) |
| 97 | 1 | `has_target` |
| 98 | 2 | — |
| 100 | 4 | `target.id` (i32) |
| 104 | 24 | `target.position.x/y/z` (f64) |
| 128 | 24 | `target.motion.x/y/z` (f64) — aim's predictive lead |
| 152 | 4 | `target.distance` (f32) |
| 156 | 4 | `target.health` (f32) |
| 160 | 4 | `target.hurt_time` |
| 164 | 1 | `target.is_moving_backward` |
| 165 | 1 | `target.is_moving_towards_player` |
| 166 | 2 | — |
| 168 | 48 | `target.hitbox` min.xyz/max.xyz (f64 ×6) — aim's multipoint |
| 216 | 4 | `ticks` (u32, low half of the JVM `Long`) |
| 220 | 4 | `packet_id` — reserved for the `CancelPacket(handle: Any)` replacement |
| 224 | 4 | `config_revision` — reserved |
| 228 | 4 | — |

When `has_target == 0`, bytes 100–215 are zero. The block is **always present**
(116 bytes either way); the core skips it rather than branching the layout, so the
offsets never shift.

`target.motion` and `target.hitbox` were added when aim moved into the core.
Substituting zeros and a default player-shaped box works for a standing player
and fails for everything else: a sneaking target is 0.3 blocks shorter, and a
moving one is led in the wrong direction. Both are f64 because both feed a
decision.

### 4.1 Flags

| Bit | Field | Bit | Field |
|---:|---|---:|---|
| 0 | `on_ground` | 5 | `is_blocking` |
| 1 | `is_moving` | 6 | `is_using_item` |
| 2 | `is_moving_forward` | 7 | `is_sneaking` |
| 3 | `is_sprinting` | 8 | `is_attack_key_down` |
| 4 | `is_looking_at_target` | 9 | `is_mining` |

## 5. `Decision` payload — 40 bytes

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | `action` — 0 `NoOp`, 1 `Pass`, 2 `Modify`, 3 `ClickBurst`, 4 `Cancel` |
| 1 | 1 | `reason` — diagnostic only, §5.1 |
| 2 | 2 | `flags` — bit 0: a delayed packet was also released this tick |
| 4 | 24 | `motion.x/y/z` (f64) — meaningful for `Modify` |
| 28 | 4 | `target_id` (i32) — for `ClickBurst` |
| 32 | 4 | `clicks` (i32) — for `ClickBurst` |
| 36 | 4 | `tick` (echo) |

### 5.1 Reason codes

`0 none`, `1 condition_failed`, `2 hit_frame_filter`, `3 gate_skip`,
`4 modified`, `5 no_target`, `6 hurt_time_mismatch`, `7 facing`, `8 out_of_range`,
`9 delayed`, `10 released`, `11 pass_through`, `12 panic`.

The stub never branches on `reason`. It exists so a failing replay says
"tick 4127 diverged at `gate_skip`" instead of "bytes differ".

## 6. `Config` payload — 104 bytes (module 1)

Written whole on every change, not diffed. A config push is ~100 bytes at
human-interaction frequency; a delta protocol would be more code than it saves.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | `mode` (0 legit, 1 click, 2 sprint) |
| 1 | 1 | `only_on_hit_frame` |
| 2 | 1 | `when_facing_enemy_only` |
| 3 | 1 | `require_weapon` |
| 4 | 16 | `horizontal_min/max`, `vertical_min/max` |
| 20 | 12 | `click_range`, `max_angle_difference`, `sprint_sector` |
| 32 | 40 | ten i32: gate probabilities, burst bounds, hurt time, sprint window, delay bounds |
| 72 | 2 | `trigger_flags`, §6.1 |
| 74 | 2 | — |
| 76 | 8 | `min_distance`, `max_distance` |
| 84 | 4 | `trigger.chance` |
| 88 | 8 | `trigger.delay_ticks`, `trigger.delay_ms` |
| 96 | 4 | `look_angle_threshold` |
| 100 | 4 | — |

### 6.1 Trigger flag bits

`0 only_ground`, `1 only_air`, `2 only_move`, `3 only_move_forward`,
`4 only_move_backward`, `5 only_strafe`, `6 only_when_target_goes_back`,
`7 only_when_target_approaches`, `8 on_look`, `9 only_current_view`,
`10 disable_on_mine`, `11 only_on_click`, `12 disabled_in_air`.

## 7. Session, seed, determinism

1. Stub sends `Hello` with the token (empty if the core was started without `--token`).
2. Core replies `HelloAck` with `session_id`, and a **seed** it derives as
   `base_seed ^ (session_id × 0x9E3779B97F4A7C15)`.
3. Both sides initialise the same PCG32 from that seed.
4. Every subsequent `Snapshot` advances both sequences by exactly the same
   amount, because the branch order in `strategy/velocity.rs` matches
   `LegitVelocityStrategy.kt` step for step.

This is what makes the replay gate real. `DecisionRng` (JVM) and `rng.rs` (Rust)
are pinned by the same four golden numbers in both test suites; if either drifts,
the suites disagree before a replay ever runs.

> **Fixed during implementation.** `MathUtils.randomFloat/randomInt` and
> `ConditionChecker`'s chance roll used `Random.Default`, which is seeded from the
> clock. §9.5.5 assumed a seed parameter that did not exist. `RandomSource` was
> introduced with a default of `DefaultRandomSource` (identical production
> behaviour) so the replay path can inject `DecisionRng`. This was a prerequisite,
> not a nicety — without it "diff == 0" was unreachable.

## 8. Timing and degradation

```
tick boundary (50 ms)
 ① stub snapshots state            → 112 B
 ② write → core decides (µs) → read  ← same tick
 ③ stub applies via the existing adapter path
```

- Deadline: **1 ms** per synchronous decision (`CoreStub.deadlineNanos`).
  Measured p99 is 12.5 µs, so the budget is ~80× the envelope.
- Miss → return `Pass(original motion)`. **Not** the previous command: replaying
  a `Modify` would apply last tick's scale factor to this tick's knockback and the
  position error would accumulate. "Hold the previous command" is implemented as
  "skip this tick" for modify-type actions; the worst case stays at one lost
  reduction.
- Socket death → reconnect on the next tick; the tick that noticed degrades.
- Strategy panic → `catch_unwind`, session state dropped, `Status.Degraded`,
  process survives. Losing the gate chain is a quality regression, not a crash.
- The snapshot **is** the heartbeat. There is no keepalive to misconfigure.

### Offline is not a HUD concern

"Core is not attached" is a **process-liveness fact**, not game state. `Strategies.online`
(`CoreLink.stub != null`) is the session-level view on the JVM side: a single deadline miss
does **not** count as offline (the stub is still there), while a process exit or a failed
`start()` does. Per the 2026-11-08 shape in `PROTECTION.md` §9.4, the offline indicator is
rendered by an **external process** watching whether this wire got established — absent when
attached, present when not. It does not enter `HUD.kt`, does not add a HUD entry, and does not
change the v3 state-file contract. Nothing on the JVM publishes it.

The degraded direction differs per op; see `PROTECTION.md` §9.5.3 for the table.

## 9. Implementation status

### Done (in tree, tested)

| Piece | Where | Evidence |
|---|---|---|
| Wire v1 encode/decode, both sides | `wire.rs`, `Wire.kt` | 5 Rust + 7 JVM tests |
| TCP + UDS transports | `transport.rs` | e2e test over TCP |
| Handshake, token, seed derivation | `main.rs`, `CoreStub.kt` | e2e (`seed != 0`) |
| Session state on the core side | `session.rs` | snapshot/config round-trip tests |
| Velocity LEGIT / CLICK / SPRINT-arm | `strategy/velocity.rs` | 9 Rust tests |
| KnockbackDelay (enqueue / pump) | `strategy/velocity.rs` | 2 Rust tests |
| Full `ConditionChecker` port | `condition.rs` | 7 Rust tests |
| Deterministic RNG, both sides | `rng.rs`, `DecisionRng.kt` | golden vectors agree |
| 1 ms deadline + degradation | `CoreStub.kt` | dead-core test |
| f64 position/motion on the wire | `wire.rs` / `Wire.kt` | precision test + e2e parity |
| Panic containment | `session.rs` | degradation path test |
| Kotlin↔Rust decision parity, 2000 ticks | `VelocityParityTest` | bit-exact on f64 |
| Process-level e2e, 500 ticks | `CoreStubE2ETest` | tick-for-tick equal |
| Latency benchmark on the real path | `--bench` | p50 6.70 µs / p99 12.50 µs |
| Aim port (LEGIT / NORMAL / LINEAR / SELF_ADAPTIVE) | `strategy/aim.rs`, `geom.rs` | `AimParityTest`, 2000 ticks |
| Click port (probabilistic + cooldown crit FSM) | `strategy/click.rs` | `ClickParityTest`, 4000 ticks |
| Movement port (SOCD / strafe / instant stop) | `strategy/movement.rs` | `MovementParityTest`, 600 ticks |
| Tap / combat / reach ports | `strategy/{tap,combat,reach}.rs` | 20 Rust unit tests |
| Six-module session dispatch | `session.rs`, `codec.rs` | `ModuleSessionParityTest`, 1300 round trips |
| Portable fdlibm (sin/cos/atan/atan2/acos) | `trig.rs`, `PortableMath.kt` | C reference, 1M values, 0 diffs |
| Eagle sneak machine | `strategy/eagle.rs` | `EagleParityTest`, 300 ticks |
| Eagle AutoPlace aim/cooldown machine | `strategy/eagle_place.rs` | `EaglePlaceParityTest`, 320 ticks + `CoreStubE2ETest` real-socket round trip |
| Backtrack hold window | `strategy/backtrack.rs` | `BacktrackParityTest`, 400 ticks |
| KnockbackDisplace flick | `strategy/knockback_displace.rs` | `KnockbackDisplaceParityTest`, 300 hits |
| Reach sampling gates | `strategy/reach_sample.rs` | `ReachParityTest`, 300 presses |
| ClickAssist guard chain + 500 ms CPS window | `strategy/click_assist.rs` | `ClickAssistParityTest`, 300 presses |
| HitSelect windows + click gate | `strategy/hit_select.rs` | `HitSelectParityTest`, 500 interleaved events |
| SprintReset rising-edge / throttle / delay | `strategy/sprint_reset.rs` | `SprintResetParityTest`, 400 ticks + `CoreStubE2ETest` real-socket round trip |
| JumpReset knock / pump cooldown + delayed jump | `strategy/jump_reset.rs` | `JumpResetParityTest`, 500 events + `CoreStubE2ETest` real-socket round trip |
| SuperKnockback phase machine / throttle / force sequence | `strategy/super_knockback.rs` | `SuperKnockbackParityTest`, 500 ticks + `CoreStubE2ETest` real-socket round trip |
| WTap / STap range draws (post-delay, duration, threshold) | `strategy/tap_sample.rs` | `TapSampleParityTest`, 500 rows + `CoreStubE2ETest` real-socket round trip |
| ADTap fire-chance / duration / gap draws | `strategy/adtap_sample.rs` | `ADTapSampleParityTest`, 500 rows + `CoreStubE2ETest` real-socket round trip |
| AutoBlock chance gates (per-tick Chance, Lag window start) | `strategy/autoblock_sample.rs` | `AutoBlockSampleParityTest`, 500 rows + `CoreStubE2ETest` real-socket round trip |
| BlockHit draws (post-delay, wait, threshold) | `strategy/blockhit_sample.rs` | `BlockHitSampleParityTest`, 500 rows + `CoreStubE2ETest` real-socket round trip |

### Module request shapes

Beyond the shared snapshot, each module appends its own tail. Aim and click get
named fields because their configs are large and positional; movement, tap,
combat and reach use a tagged op with an f64 argument vector, because a bespoke
decoder for each of their twelve small functions would be twelve decoders that
all look alike and none of which can be checked without counting bytes.

| Module | Tail after byte 232 |
|---|---|
| Velocity | none for a knockback; `op u8, nargs u8, nargs × f64` for the tick-level questions |
| Aim | `mouse_dx f32, mouse_dy f32, sensitivity f32` (12 bytes) |
| Click | `attack_cooldown f32, is_falling u8, pad 3` (8 bytes) |
| Movement / Tap / Combat / Reach | `op u8, nargs u8, nargs × f64` |
| Player (8) | `op u8, nargs u8, nargs × f64` — ops 0..11, up to 11 args |

Op ids are append-only, same reasoning as module ids: the stub and the core are
separate binaries. The tables live beside their decoders in `codec.rs` and are
mirrored in `Wire.Ops`.

Velocity's tail distinguishes the two shapes by length: a knockback is exactly
the snapshot, and anything longer carries an op byte after it. That is why the
module needs two ops for things that are not per-hit — the sprint brace and the
knockback delay both run on the tick clock, not the packet clock.

### `Reset` frames

`Kind::Reset` (10) carries no payload; the module id in the header says whose
state to forget. Configuration is deliberately kept — a module toggle must not
reset the user's settings — while everything the module learned is dropped: the
key that was held, the tap whose remainder belonged to the previous activation,
the velocity gate's last verdict, the brace window, the delay queue.

Sent on enable and on disable. Skipping it does not fail loudly: a stale
`SocdResolver` believes the opposite key was pressed later than it was, a stale
tap timer fires an instantaneous key press on the first tick back, and a stale
velocity gate biases the next hit's probability. All three look like "the module
misbehaves after being toggled" and none of them point at the queue.

`TAP_WTAP_SAMPLE` (7) and `TAP_STAP_SAMPLE` (8) are the WTap / STap range
draws that used to run through `kotlin.random.Random` on the JVM. The module
marshals the six configured endpoints (`postDelayMin/Max`, `actionMin/Max`,
`onceEveryMin/Max`) plus a `kind` (0 fire point, 1 the state machine said
"start now" — duration only, 2 module enable — initial hit threshold only),
and the core answers one packed scalar: post-delay in the low 10 bits, the tap
duration in the next 10, the threshold above that. Degenerate ranges (min ≥
max) normalise instead of drawing, so a kind that never uses a field leaves it
zero and its stream untouched. The two ops own **separate** streams
(`rng_wtap` / `rng_stap`) so one module's tap can never shift the other's
replay, and neither touches `rng_tap`, which the side-selection ops still use.
A missing answer degrades to "do not tap" — never a local guess.

`TAP_ADTAP_SAMPLE` (9) is the same closure for ADTap: the fire-point chance
roll plus the tap-duration and inter-tap-gap draws that also used to live in
`kotlin.random`. The module marshals `kind` (0 fire point, 1 a tap just ended
— gap only) plus `chance` and the two range pairs (`tapMin/Max`,
`gapMin/Max`); the core rolls the chance first — never when `chance == 100`,
always when it is 0 — then draws exactly one of duration (a pass) or gap (a
fail), and answers one packed scalar: duration in the low 10 bits, gap in the
next 10, the passed flag above that. Side selection stays on `rng_tap`
(`TAP_SEVEN` / `TAP_RANDOM` / `TAP_CHASE`) and interleaves freely with this op
because the two streams are independent. A missing answer degrades to "do not
tap / do not arm a gap" — never a local guess.

`CBT_AUTOBLOCK_CHANCE` (5) closes AutoBlock's `kotlin.random` hole: the two
chance gates that used to run on the JVM — the per-tick `Chance` roll and the
Lag window's start roll. Both are the same algorithm
(`chance >= 100 || nextInt(100) < chance`), so the op takes just `chance` and
answers a bool; there is no `kind`, and the stream order is simply the call
order, exactly as the JVM's single shared stream saw it: one roll per armed
tick (GUI closed, sword held) and one more only when a lag window wants to
start. The roll never happens at `chance == 100`, always happens at 0 (and
always fails), on AutoBlock's own `rng_autoblock` stream so neither the
trigger engine's `rng_combat` draws nor BlockHit's can shift it. A missing
answer degrades to `false` — do not block — the same direction as `gate` and
`range`.

`CBT_BLOCKHIT_SAMPLE` (6) does the same for BlockHit: the post-delay and
block-duration draws at the fire point, and the hit-threshold draw on module
enable. The module marshals `kind` (0 fire point, 1 enable) plus the three
range pairs (`postDelayMin/Max`, `waitMin/Max`, `hitPerMin/Max`); the core
draws the post-delay first and the duration second — the module's original
order — and `INIT` draws only the threshold, clamped to at least one hit.
Degenerate ranges never advance the stream, the answer is one packed scalar
(post-delay in the low 10 bits, wait in the next 10, threshold above that) on
BlockHit's own `rng_blockhit` stream, and BlockHit's `Chance` itself was
already rolling inside `CBT_DECIDE` on `rng_combat`. A missing answer
degrades to "do not block-hit" — never a local guess.

Combat's `CBT_DECIDE` (op 4) is the one op that merges two questions — the
condition gate and the trigger — into a single request. They have to share a
round trip because they share the module's random stream: the gate may draw once
and the trigger draws once or twice, and splitting them across two ops lets the
draws interleave differently on the two sides. `mode < 0` asks for the gate
alone, which is what the modules with no trigger behind it use.

Each module owns **its own RNG stream**, all seeded from the session seed. A
single shared stream would make one module's decisions depend on which other
modules were enabled and in what order they ran, so toggling aim would quietly
change click timing.

`Module::Player` (id 8) carries the modules that used to keep their whole
logic in the jar. Ops 0..11 are append-only like the rest. `PLAYER_HIT_TICK` (5)
and `PLAYER_HIT_GATE` (6) are the only pair that shares **one** state across two
callers: the tick feeds the target windows, the click gate reads them. The
adapter asks the gate only when a physical attack is actually being applied —
asking it on every idle frame would mean one round trip per frame and a chance
roll driven by the frame rate. A missing answer on the gate means *deliver* the
click; swallowing a real hit without being told to is worse than letting it
through, so this op's degraded direction is the opposite of Reach's.

`PLAYER_EAGLE_PLACE` (7) is Eagle AutoPlace's aim-first placement machine. The
adapter sends the two judgements it can make — `gates_open` (`shouldAutoPlace`)
and `pitch_in_tolerance` (a geometry comparison on the whitelist) — plus
`bridge_assist_enabled`, `max_wait_ticks` and the cooldown bounds, and gets back
a step code: 0 none, 1 aim, 2 click, 3 abort. The aim-in-flight flag, the wait
counter, the cooldown **and its random draw** are core state (one call, one
round trip); the draw is consumed only on a CLICK, so an idle or cooling-down
tick never advances the stream. It has its own `rng_eagle_place` stream — the
sneak machine draws from `rng_player`, and interleaving the two would make a
replay diverge. The request is sent **every tick even when the gates are
closed**: the core needs `gates_open = false` to cancel a pending aim and forget
its cooldown, otherwise the next opening would resume a stale aim. A missing
answer means write neither a rotation nor a right-click: a block placed without
the core's decision cannot be taken back.

`PLAYER_SPRINT_RESET` (8) moves SprintReset's rising-edge bit, hit-count
throttle, per-target cycle and delayed-send window into the core. The adapter
sends only the judgements it can make — `has_target`, `in_range`, `gate_ok`
(the `combatDecide` gate, `mode < 0`), `is_hurt` — plus the clock and the tuning
knobs, and gets back a step code: 0 none, `1 + mode` send (mode 0 Nostop, 1
Silent). The `chance` roll is consumed **only** at the fire point and only when
`chance < 100`, on its own `rng_sprint_reset` stream; the gate call that
precedes it uses `rng_combat` and does not draw (a `mode < 0` `CBT_DECIDE`
returns after `condition::check`), so the two round trips per tick do not
interleave any random stream. A delayed send arms a deadline on the edge and
fires with the captured mode at that deadline — the whole tick short-circuits
while armed, exactly as the original JVM timer did. A missing answer means send
nothing: inventing a C0B/C03 burst without the core's decision is worse than
missing one. This is the first of the Reset family.

`PLAYER_JUMP_KNOCK` (9) and `PLAYER_JUMP_PUMP` (10) are the second pair to
share one state across two callers, but for a different reason than
HitSelect: JumpReset's `knock` runs on the velocity (Netty) thread when an
S12/S27 arrives, while its `pump` runs once per client tick. The adapter
folds all its judgements into one `gates_open` bit (sprinting / on ground /
not in fluid, the 120° front-angle geometry check — whitelisted frame-level
geometry — and the `combatDecide` gate), then sends the clock and the tuning
knobs: `chance`, `cooldown_mode` (0 Ticks, 1 ReceivedHits), `ticks_until`,
`hits_until`, `delay_ms`. Both halves return step code 0 none / 1 jump now.

The core owns the cooldown counter, the pending delayed jump and its
deadline, and the chance roll on its own `rng_jump_reset` stream. The roll is
consumed **only** in `knock`, after the gates and only when `chance < 100`;
`pump` never draws — a tick-driven roll would advance the stream with frame
timing and diverge on the first replay. A `delay_ms > 0` arms a deadline on
the knock and `pump` fires the jump at that deadline; the pump also advances
the Ticks cooldown, and both happen even on the firing tick, mirroring the
two independent `if`s in the original tick listener. The final "still
sprinting at jump time" check stays in the module and uses the *current*
frame's player, not a snapshot cached at knockback time. A missing answer on
either half means queue no jump: inventing a jump without the core's
decision is worse than missing one.

`PLAYER_SUPER_KNOCKBACK` (11) is the last member of the Reset family and the
most stateful. The adapter marshals twelve judgements/knobs per tick:
`now_ns`, `has_target`, `trigger_ok` (a one-tick attack pulse for RisingEdge
or an exact `hurtTime` match — both evaluated on the JVM), `in_range`,
`gate_ok`, `entity_id`, `chance`, `hit_count`, `delay_ms`, `mode` (0
SprintTap, 1 Old, 2 SneakPacket), `sprint_tap_allowed`
(`player.isSprinting && serverSprintState`) and `coordination_active`. The
core owns the Idle / Delay / SprintTap phase machine, the delay deadline, the
one-tick coordination offset, the SprintTap three-tick force sequence, the
per-target hit-count throttle and the chance roll on its own
`rng_super_knockback` stream. The roll is consumed **only** at the fire point
and only when `chance < 100`.

The scalar answer is **packed**: the low nibble is the SprintTap force-sprint
step at the top of the tick (0 none, 1 break, 2 restore, 3 release), the high
nibble is the fire step (0 none, 4 SprintTap, 5 Old, 6 SneakPacket). Both are
platform writes and a single tick can carry both, because the original
`onTick` advanced the force machine before evaluating the trigger without
short-circuiting. Unknown codes degrade to NONE on each half; a missing
answer means write nothing. With this op the Reset family is complete.

`ClickAssistFlag` in `Wire.kt` is the bit layout for `PLAYER_CLICK_ASSIST`'s
thirteen predicates. It lives beside the op rather than in the module for the
same reason as `write_trigger`: two layouts would disagree about which bit means
`onlyAimingEntity`, and that disagreement would show up as extra clicks with
nothing in the logs to explain it.

### Not done (deliberately out of this slice)

| Item | Why it is not here |
|---|---|
| `PacketScheduler` queue move | Needs the versioned verb table (§9.5.4). The queue stays in Kotlin until `CancelPacket(handle: Any)` becomes an abstract packet id; `Snapshot.packet_id` is reserved for it. The class is currently referenced by nothing, so the table would abstract a call chain that does not exist. |
| WebUI service move out of the adapter | **Not part of the shape.** Config values are inputs that Kotlin packs into a request; where they are stored and edited is a separate question. |
| ~~Owner of the core process lifecycle~~ | **Done (2026-11-08).** The injector launches the core (`resource::launch_core()`, `--port-file`) and publishes the endpoint as `doppel.core.endpoint` in `doppel-config.properties`; `Agent.coreInit` sets the property and `CoreSupervisor.attach()` connects only. `doppel.core.path` remains as an explicit dev/test spawn fallback. Wire protocol unchanged — verified by `CoreSupervisorTest`'s external-core round trip. |
| Seed ownership | **Not part of the shape.** `main.rs` already does `seed.unwrap_or_else(now_nanos)` and `hello_ack` returns the seed to the JVM; the injector passes no `--seed`. |
| External overlay's offline indicator | Not implemented. The injector is the only entity that knows whether a core was started (`--port-file` is its readiness signal); an external process can read that to decide. It does not enter Kotlin, `HUD.kt`, or the v3 state-file contract. |
| Windows named pipe transport | §1. Blocked on a JNA decision, not on protocol design. |
| Windows on-device validation | The sandbox is Linux. Everything here is platform-neutral except `transport.rs`'s `cfg(unix)` branch, which is compiled out on Windows. |
| Golden replay against real recorded sessions | Current fixture is synthetic (2000 generated snapshots). A real trace needs the adapter to emit one, which is the next slice. |

### Building and running

```bash
# core process
cd Doppel/core-rs
cargo test                       # 31 unit tests
cargo run -- --selftest          # determinism, band, wire, rng vectors
cargo run --release -- --bench   # round-trip latency on the real path

# generate / refresh the parity fixture
cargo run -- --emit-golden golden

# JVM side
cd Doppel
gradle :core:test --tests "io.doppel.core.ipc.*"
```

Run the core by hand:

```bash
./target/debug/doppel-core --listen tcp:127.0.0.1:4173 --token s3cret --seed 42
```

## 10. What breaks first in production

Honest list, in expected order:

1. **Seed determinism across a restart.** The core derives a new seed per session,
   so a reconnect mid-fight produces a different noise sequence. Correct for
   anti-detection, but it means a replay cannot span a reconnect — the fixture
   must record the seed and the session id.
2. **The 1 ms deadline on a loaded machine.** p999 is 23.7 µs in a quiet sandbox;
   on a machine with a busy scheduler a GC pause on the JVM side can exceed 1 ms
   and the tick degrades. That is the designed behaviour, but it will look like
   "velocity stopped working" in a log unless `degradedTicks` is surfaced in the HUD.
3. **Config drift between the two defaults.** `VelocityConfig` now exists twice.
   There is no test asserting the two default values agree; the parity fixture
   passes an explicit config, which papers over it. Add a defaults test before
   adding modules.
