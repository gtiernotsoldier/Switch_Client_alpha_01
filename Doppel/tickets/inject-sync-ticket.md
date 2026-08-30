# Ticket: Named Event IPC Synchronization

**Branch:** `feat/inject-sync`
**Priority:** P0 (blocks real testing)

## Problem

Injector reports "injected successfully" after `LoadLibraryA(DllMain)` returns.
`DllMain` only spawns `ThreadProc` then returns — actual JNI work (findJVM, Attach, loadClass, bootstrap)
runs async in ThreadProc. Injector has NO idea if it succeeded.

Current fake flow:
```
injector -> CreateRemoteThread(LoadLibraryA) -> DllMain returns -> "success!" (LIE)
                                           -> ThreadProc runs in MC (may fail silently)
```

## Fix: Windows Named Event Synchronization

Use a named event for cross-process IPC:
```
payload ThreadProc:  OpenEvent("DoppelPayloadDone") -> do JNI work -> SetEvent
injector:            CreateEvent("DoppelPayloadDone") -> inject DLL -> WaitForSingleObject(event, 15s) -> check result
```

## What to Change (ONLY these 2 files)

### 1. `injector/src/payload/payload.cpp`

In `ThreadProc`, BEFORE any JNI work:
```cpp
HANDLE hDone = OpenEventA(EVENT_MODIFY_STATE, FALSE, "DoppelPayloadDone");
```

At the END of `ThreadProc` (both success and failure paths), BEFORE return:
```cpp
if (hDone) SetEvent(hDone);
if (hDone) CloseHandle(hDone);
```

In `DllMain` (DLL_PROCESS_ATTACH), AFTER `CreateThread(ThreadProc)`:
```cpp
HANDLE hDone = CreateEventA(NULL, TRUE, FALSE, "DoppelPayloadDone");
CloseHandle(hDone); // just create it, ThreadProc will signal
```

### 2. `injector/src/inject.cpp`

Replace the current wait logic:
```cpp
// OLD (remove):
WaitForSingleObject(hThread, 10000);

// NEW (after CreateRemoteThread):
HANDLE hDone = CreateEventA(NULL, TRUE, FALSE, "DoppelPayloadDone");
DWORD waitResult = WaitForSingleObject(hDone, 15000); // 15s timeout
CloseHandle(hDone);

if (waitResult == WAIT_TIMEOUT) {
    std::cerr << "[Inject] Payload timed out (15s) - Agent may not have loaded" << std::endl;
} else if (waitResult == WAIT_OBJECT_0) {
    std::cout << "[Inject] Payload signaled completion" << std::endl;
} else {
    std::cerr << "[Inject] Wait error: " << GetLastError() << std::endl;
}
```

Then keep the existing 5s countdown + `showDiagnosticLogs()` call.

## What NOT to Change (DO NOT TOUCH)

- **`agent/src/main/java/io/doppel/agent/Agent.java`** — DO NOT TOUCH. R key polling, file logging, bootstrap logic all stay as-is.
- **`injector/src/process.cpp`** — process detection
- **`injector/src/version.cpp`** — version detection
- **`injector/src/main.cpp`** — already calls `showDiagnosticLogs()`, keep that as-is
- **`injector/src/inject.h`** — already has `showDiagnosticLogs()`, keep that as-is
- **`injector/CMakeLists.txt`** — build config
- **`injector/resources/agent.rc`** — resource embedding
- **CI workflow files** (.github/)
- **Branch Protection rules**
- **Any Kotlin files** (adapter/, core/)

## Diagnostic Logs

Keep the existing `showDiagnosticLogs()` call AFTER the event wait. The flow should be:

1. Inject DLL
2. Wait for Named Event (up to 15s) with status messages
3. Show diagnostic logs from %TEMP%
4. Report final status

## Commit Message Format

```
feat(injector): replace fake success with Named Event IPC synchronization

- payload.cpp: signal DoppelPayloadDone event when ThreadProc completes
- inject.cpp: wait for event instead of LoadLibraryA thread
- Timeout 15s with clear error message
- Diagnostic logs still shown after wait
```

## Test

After CI builds, injector console should show:
```
[Inject] Waiting for payload to signal completion...
[Inject] Payload signaled completion        <- or timeout warning
[+] Java Agent injected successfully via DLL
========== DIAGNOSTIC LOGS ==========
...
```

No more fake "success" before Agent actually loads.
