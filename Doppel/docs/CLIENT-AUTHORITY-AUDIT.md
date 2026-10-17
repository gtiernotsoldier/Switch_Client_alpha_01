# Client Authority Audit

**The question this document answers**: which advantages can be taken from vanilla mechanics in a way
a server genuinely cannot distinguish from a legitimate player?

**The answer in one line**: only where the server has no independent channel to know the truth.

This is a working audit, not a catalogue. Its value is the *method* in §1–§4, the settled ledger in
§5 (so nobody re-derives it), and §7 (how to prove a candidate is real).

---

## 1. The rule

The server cannot know three things:

- your **keys** (W/A/S/D, jump, sprint, sneak, use),
- your **mouse** (left/right buttons, look delta),
- your **intent** (why you did something, and in what order within a tick).

It has no second source for any of them. The protocol's answer is to let the client declare them and
to trust the declaration. Everything in §5 that is marked *open* traces back to this.

**Therefore**: an advantage is undetectable exactly when it is expressed **in the units the server
already accepts from you**, rather than in units it computes for itself.

> Change what the server **knows** → it stays consistent.
> Change what the server **computes** → it disagrees, permanently.

That single distinction explains every result in this repository's combat work.

---

## 2. Three filters

A candidate has to pass all three before it is worth building.

| # | Filter | Fails when |
|---|---|---|
| 1 | Does the server have an independent channel to know this? | It computes it, or validates it against its own state |
| 2 | Can the client change it **legitimately**? (a standard packet, or real input) | It requires lying in a field the server cross-checks |
| 3 | Is there real advantage? | The change is cosmetic, or the gain is smaller than the variance |

Almost every dead end in §5 fails filter 1 or 2 — and knowing *which* one is what saves the time.

---

## 3. Four classes of change

| Class | What it changes | Examples in this repo | Detectable? |
|---|---|---|---|
| **A · Declaration** | a fact the server cannot know, sent on a standard packet | KeepSprint, SprintReset, Reach, FastPlace, DelayRemover, NoJumpDelay | **No** |
| **B · Timing** | when you act; no unusual packets | JumpReset, AutoClicker, HitSelect, NoJumpDelay | **No** |
| **C · Position in the formula** | your *input*, so the server's own formula does the work | Velocity · Sprint mode, W-tap | **No** |
| **D · Value** | a quantity the server computes for itself | Velocity · Legit/Click, KnockbackDelay, KnockbackDisplace, StrafeFix | **Always** |

**Class D is not a tuning problem.** The server runs the same line on its own copy (§5.3), so a client
that skips it is in permanent disagreement. No parameter setting fixes that; the only question is
whether the specific server checks.

Note what class A actually contains — it is wider than "state flags":

- protocol state the server trusts (`C0B` sprint/sneak, `C08` use item),
- **client-local timers and judgements the server never sees at all** — `leftClickCounter`,
  `rightClickDelayTimer`, jump cooldown, `objectMouseOver` for the attack raycast.

The second half is why Reach/FastPlace/DelayRemover/NoJumpDelay are all legal. The server does not
have the concept, so there is nothing to contradict.

**A fifth class, for completeness: perception.** Not everything valuable changes what you *do*; some
changes only what you *know*. ESP and entity tracking do not fit the four classes above because they
are invisible at the protocol level in a stronger sense than class B — the data is **already being
sent to you**. The server replicates nearby entities so the client can render them, walls or not; an
ESP draws what you were given. Its counter-measures are therefore somewhere else entirely (§5.4).

---

## 4. The search template

MC-69459 is the model case, and the pattern generalises:

```
1. Find a piece of logic that BOTH sides execute.        (e.g. attack: setSprinting(false))
2. Ask whether both sides have the same inputs.          (client has the key, server does not)
3. Where the server lacks an input, it substitutes its
   own maintained state.                                 (server: "you are walking")
4. That state may differ from the client's reality.      (client: "I am sprinting")
5. If the client can legally reconcile the two, that is
   the advantage.                                        (send the C0B vanilla would send)
```

Step 1 is mechanical: attack, knockback, damage, movement integration, item use. Step 2 is where the
work is, because "does the server know this?" is a fact about *the server's code*, not about the
client's.

---

## 5. The 1.8 ledger

### 5.1 Open, and used

| Face | How | Where |
|---|---|---|
| Sprint state (post-attack desync) | send the `C0B` vanilla itself would send | KeepSprint |
| Sprint state (for the next hit's knockback) | `C0B` stop/start pair | SprintReset, SuperKnockback |
| Knockback formula's momentum term | be moving toward the source; the `/2` term does the rest | Velocity · Sprint |
| Being hit at all | jump on the hit tick — a legal action on a legal tick | JumpReset |
| Attack range | overwrite the local attack raycast; the server does not verify distance | Reach |
| Block/use state | declare it; the server trusts `C08` | AutoBlock, BlockHit |
| Client-side timers | `rightClickDelayTimer`, `leftClickCounter`, jump cooldown | FastPlace, DelayRemover, NoJumpDelay |
| i-frame window | do not swing into a discarded hit | HitSelect, AutoBlock · Adaptive |

### 5.2 Open, and **not** usable

| Face | Why not |
|---|---|
| `onGround` for critical hits | It is a **declaration about the client's own physics**, and the server can compare it to the position it received. Lying there is class D in class A clothing. There is also already a legal route: `HitSelect.Criticals` only swings when a crit is real. |
| Attack slowdown (`motion *= 0.6`) | Bilateral — the server runs the same line. And vanilla returns the speed within a few ticks anyway (§5.3). |
| Sneak state (1.8) | It changes almost nothing on the server: 1.8 sneaking alters movement speed and the client's eye height, not the hitbox, and the server does not verify movement. |
| View angle | Server-side movement validation models yaw against displacement; see 5.3 on StrafeFix. |

### 5.3 Closed — the server validates, or computes it itself

| Face | Mechanism |
|---|---|
| Post-hit motion | `motionX/Z *= 0.6` runs on **both** sides. A client that skips it is persistently faster than the server's model. |
| Position | Distance-per-tick checks ("moved too quickly"). |
| Block breaking | Server-authoritative timing. |
| Inventory | Server-authoritative windows. |
| Flight / abilities | Gated on server-side game mode. |
| **Displacement direction vs. yaw** | The server can model it: vanilla's own turning is an inertia blend (`v_old × 0.546` against `a × 0.13`), so a client whose direction snaps exactly to its input is *more* correct than vanilla and therefore distinguishable. This is the whole of StrafeFix's problem. |

### 5.4 A fifth face: perception

| | |
|---|---|
| Changes | what you **know**, not what you do |
| Carrier | **none** — no packet is involved |
| Server-side visibility | **none at the protocol level**, because the data was already sent |
| Where it is detectable | the **client** (memory / screenshot / plugin scan), or the **behaviour** (reacting to what you should not have seen) |

This is why ESP has outlived every class-D technique: there is no model for the server to compare
against, because nothing about the connection changes. It also means the counter-measures live in a
different place — shrinking entity tracking distance is a **server config**, and client scanning is a
**client-side** anti-cheat. Neither is the kind of validation §5.3 is about.

Note the asymmetry this creates: class D is *provable* on the server, class E is *invisible* to it, and
the behaviour layer is the only place both can be caught. A reaction that is too fast for what you
could have seen is the same kind of evidence as a displacement that is too large for your input.

---

## 6. What is left

Two honest observations.

**6.1 In 1.8, very little.** The client-authority faces have been worked on by the PvP community since
2014 — sprint reset, jump reset, w-tap, block hit, strafe, reach, crits. The ledger above is close to
complete. Remaining 1.8 candidates are likely to be **small and compositional** rather than a large
undiscovered mechanism:

- **combinations** (the Adaptive work: three modes that already existed, their *information* combined),
- **quantification** (things that look worthless until measured — this is what `behavioral-tests`
  exists for).

**6.2 In 1.9+, considerably more.** The combat model changed (attack cooldown, shields, offhand,
swimming, elytra), and the surrounding tooling is younger. The same three filters apply, but the
answers are different, and we have not done the work:

| Face to investigate | Why it might be open |
|---|---|
| Shield / `isHandActive` | Server trusts the use-item declaration; 1.9's blocking is not 1.8's |
| Swimming hitbox (`isSwimming`) | Pose affects the hitbox; check whether the server derives or is told |
| Attack cooldown | Server computes it — check whether anything it reads is client-declared |
| Offhand switching | Declaration-driven |
| Elytra / firework | Client-physics-heavy |
| Crawling / 1.14 poses | Same family as swimming |

This repository already has a `fabric/v1_21` adapter. That is where the same kind of work would be
done next, and it is the direction this audit recommends if the goal is a *new* mechanism rather than
a better-tuned existing one.

---

## 7. How to verify a candidate

A candidate is not real until it is measured. The method already exists in this project's separate
verification workspace (three layers, deliberately separated). That workspace does not live in this
repository — it is the Python bench referenced throughout the combat work — so the tool names below
are pointers, not paths:

| Layer | Question | Tool |
|---|---|---|
| **Distribution** | does the outcome distribution match a human's? | `ks_test`, `cv_test`, `trend_test` |
| **Rhythm** | are the *events* scheduled like a human's? | `event_runlength` (rate and rhythm separable) |
| **Physics** | does per-tick state contradict the model? | `grim_offset` |

And the protocol that makes a candidate convincing rather than anecdotal:

1. **State the mechanism as a falsifiable claim** — one sentence, with the game rule it depends on.
2. **Build a model** of it. A Python transcription of the few equations that decide the outcome is
   enough to start — that is how the Sprint brace and the BlockArbiter timing were checked while the
   Kotlin could not be compiled.
3. **Compute the null** — what does an honest player produce here? No number means nothing.
4. **Compare at the right layer.** A result at the wrong layer is how *"it looks human"* gets
   confused with *"it is undetectable"*: KeepSprint passes all three; StrafeFix passes the first
   two and fails the third.
5. **Say what would falsify it.**

**Two rules learned the hard way in this repository:**

- **"No unusual packets, no speed modification" is not a safety argument.** StrafeFix satisfies both
  sentences and is still distinguishable, because it changed the *direction*, and direction is in the
  position packet.
- **A commit that says "not compiled" is a real risk, not a formality.** Ten commits in this series
  carried that note. The first build that actually ran found two compile errors among them:
  `2033fb7` declared `serverSprintState` twice, and `51f4f27` passed a `Float` where a `Double` was
  required. Neither would have survived a single `gradle compileKotlin`.

---

## 8. Conclusion

Legal advantage comes from exactly one place: **the gap between what the server can verify and what
it must take your word for.** Every such gap is either already worked (1.8, §5.1), provably closed
(§5.3), or small and compositional.

If the goal is a large *new* mechanism, the honest expectation is that it is not in 1.8. It is in the
versions whose combat model has not been mined with the same fifteen years of attention — and the
`v1_21` adapter is where that would start.

**The useful output of this document is not its conclusions but its filters**: before building
anything, ask whether the server has an independent channel, whether the change is expressed in units
the server accepts from you, and what the null looks like.
