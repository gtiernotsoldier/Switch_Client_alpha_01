# Legacy C++ injector — kept as history, not built

This directory contains two code bases. Only one of them ships.

## What is still live

| Path | Built? | Role |
|---|---|---|
| `src/payload/` (`payload.cpp`, `attach_pipe.cpp`) | **Yes** — CI runs cmake on `src/payload/CMakeLists.txt` on Windows | The DLL injected into `javaw.exe`. It is what performs the in-process JNI attach (`JVM_EnqueueOperation` / Attach pipe) and loads the agent. |
| `resources/agent.jar` | Yes — overwritten by CI with the real fat jar (the committed file is a 0-byte placeholder) | Payload embedded as RCDATA. |

## What is legacy

| Path | Built? | Replaced by |
|---|---|---|
| `src/main.cpp` | No | `src/main.rs` (+ `window.rs`, `ui.rs`, `flow.rs`, `log.rs`) |
| `src/process.cpp` | No | `src/process.rs` |
| `src/inject.cpp` | No | `src/inject.rs` |
| `src/version.cpp` | No | `src/version.rs` |
| `src/process.h`, `src/inject.h`, `src/version.h` | No | — |

The root `CMakeLists.txt` still declares the `injector` executable target, but CI never
configures that file for it — it builds the payload DLL only. So these ~690 lines are
**dead code by design, not by accident**.

## Why keep it

It is the record of how the injection path was actually figured out: the Windows attach
protocol, the JVM class-loader discovery, the version probing, and the diagnostics that
were learned the hard way. When something breaks deep in the Rust port, this is the
reference implementation that provably worked at some point.

## Rules

1. **Do not delete it and do not "merge" it.** The decision was to keep both; a merge
   would lose the reference.
2. **Do not wire the `injector` executable back into CI.** If it is ever revived, it needs
   its own deliberate decision (and the Rust injector's fate decided at the same time).
3. **Do not port bug fixes into it.** It is frozen. Fixes go to the Rust side.
4. Renames and renames only, if a path must change — content stays byte-identical.
