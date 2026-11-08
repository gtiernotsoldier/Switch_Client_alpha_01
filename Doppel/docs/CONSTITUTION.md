# The Sandwich Constitution

> The manifesto-level statement lives in the repository root `README.md`. This file is the operative constitution: the four principles, what each one *forbids*, and how the 2026-11 process split enforces them in code.

## Article 1 — Safety First

All modules default to human-limit behavior. No supernatural movement or input.

- Defaults must stay inside human-observable limits: soft boundaries, gradual corrections, random perturbation.
- Brutal modules (KillAura, Fly, Speed, Jesus) are banned outright.
- Modules activate only in tactical scenarios (`onlyGround`, `onlyMoveForward`, range gates), never permanently.
- Every module's failure mode must be a *safe* direction: a module that cannot reach the core degrades to "do nothing vanilla wouldn't do", never to a stuck claim, held packets or a forced state. The per-op degradation table lives in `PROTECTION.md` §9.5.3.

## Article 2 — Debuggability

Strategy and execution are separable, and the whole personality is testable offline.

- Algorithms live in the core process (`core-rs`), not in the game. They are pure with respect to game state: everything they need crosses the wire.
- The replay gate is real: same seed → same decisions, `diff == 0`, bit for bit, verified per module by golden fixtures (`core-rs/golden/`) and the JVM parity suites.
- The Kotlin `core/` tree is the oracle twin that keeps the ports honest; it is never shipped.
- Configuration is data (`config/` JSON), hot-loadable, never compiled-in thresholds.

## Article 3 — Strategy

Behavior adapts; randomness is coordinated, not scattered.

- All randomness lives in the core as named PCG32 streams, one per module family, so no module's draws can shift another module's replay. The adapter contains zero `kotlin.random` calls (2026-11-08).
- All cross-tick memory (windows, latches, phase machines, cooldowns) lives in the core. The JVM is stateless between ticks by design: on every tick it knows only what the core tells it.
- "Statistically indistinguishable from a human" means one correlated personality across all 50 modules — not 50 independently random modules.

## Article 4 — Assistance

The player is the protagonist; modules are co-pilots.

- No module replaces a player decision: AimAssist does not select targets, the clicker only shapes rhythm while the player holds the button, WTap only acts while the player moves forward.
- Outputs carry human imperfection — reaction delay, overshoot, jitter — as first-class algorithm behavior in the core, not as decoration.
- The measure of success is long-term survival with a clean record, not peak effect.

## Enforcement (what the constitution looks like in the tree)

| Principle | Enforcement in code |
|---|---|
| Safety | Per-op degradation directions; module comments must state the safe failure direction |
| Debuggability | Golden parity suites must stay green; fixtures regenerate via `cargo run -- --emit-golden golden` |
| Strategy | RNG stream discipline in `session.rs`; op table append-only in `codec.rs` |
| Assistance | Trigger conditions ride module options; nothing fires without a player-context predicate |

A change that violates any row of that table is a constitutional change: it needs an explicit decision, not a quiet PR.
