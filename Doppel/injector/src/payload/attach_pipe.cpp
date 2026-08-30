// attach_pipe.cpp — Windows Attach API via JVM_EnqueueOperation (no tools.jar needed)
//
// CORRECT Windows attach protocol (JDK 8):
//
// On Windows, the AttachListener thread is ALWAYS started at JVM startup
// (unlike Linux where it's lazy-initialized via SIGQUIT + .attach_pid file).
// The AttachListener blocks on a Win32 semaphore (_wakeup), waiting for
// operations to be enqueued.
//
// JDK's WindowsVirtualMachine uses CreateRemoteThread to inject a stub that
// calls JVM_EnqueueOperation — an exported function from jvm.dll.
// But since our DLL is already inside the target process, we can call
// JVM_EnqueueOperation directly — no remote thread injection needed.
//
// The previous implementation incorrectly used the Linux attach protocol
// (signal file + \\.\pipe\java_pid<PID>). That pipe never exists on Windows
// because Windows AttachListener doesn't create it. The Windows mechanism
// uses a client-created pipe (\\.\pipe\javatool<random>) for the response,
// and JVM_EnqueueOperation to enqueue the operation.
//
// Protocol flow:
//   1. Get JVM_EnqueueOperation from jvm.dll (already loaded in process)
//   2. Create a named pipe server for the response
//   3. Call JVM_EnqueueOperation("load", "instrument", "true", "<jar>=<args>", "<pipename>")
//   4. AttachListener wakes up, dequeues the operation, loads the agent
//   5. AttachListener writes the result to our named pipe
//   6. We read the result

#ifdef _WIN32

#include "attach_pipe.h"
#include "payload_log.h"
#include <windows.h>
#include <jni.h>
#include <cstdio>
#include <cstring>

// ── JVM_EnqueueOperation function pointer ──
//
// Exported from jvm.dll. On 32-bit Windows, the stdcall-decorated name
// is _JVM_EnqueueOperation@20. On 64-bit, it's JVM_EnqueueOperation.
// We try both names to handle all architectures.

#ifdef _WIN64
#define ATTACH_CALL
#else
#define ATTACH_CALL __stdcall
#endif

typedef jint (ATTACH_CALL *EnqueueOperationFunc)(
    char* cmd,
    char* arg0,
    char* arg1,
    char* arg2,
    char* pipename
);

static EnqueueOperationFunc getEnqueueFunc() {
    HMODULE hJvm = GetModuleHandleA("jvm");
    if (!hJvm) {
        payloadLog("[AttachPipe] jvm.dll not found in process");
        return NULL;
    }
    payloadLog("[AttachPipe] jvm.dll found at %p", hJvm);

    // Try undecorated name first (64-bit)
    EnqueueOperationFunc func = (EnqueueOperationFunc)GetProcAddress(hJvm, "JVM_EnqueueOperation");
    if (func) {
        payloadLog("[AttachPipe] Found JVM_EnqueueOperation (undecorated)");
        return func;
    }

    // Try stdcall decorated name (32-bit)
    func = (EnqueueOperationFunc)GetProcAddress(hJvm, "_JVM_EnqueueOperation@20");
    if (func) {
        payloadLog("[AttachPipe] Found _JVM_EnqueueOperation@20 (stdcall)");
        return func;
    }

    payloadLog("[AttachPipe] JVM_EnqueueOperation not found in jvm.dll");
    return NULL;
}

// ── Create named pipe server for response ──
//
// The AttachListener writes the result to this pipe after processing
// the load command. We create the pipe server before calling
// JVM_EnqueueOperation, so it's ready when the AttachListener tries
// to connect.

static HANDLE createResponsePipe(char* pipeName, int pipeNameSize) {
    // Generate a unique pipe name using PID + random
    srand(GetTickCount());
    _snprintf(pipeName, pipeNameSize, "\\\\.\\pipe\\doppel_attach_%d_%d",
              GetCurrentProcessId(), rand());

    HANDLE hPipe = CreateNamedPipeA(
        pipeName,
        PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
        1,      // max instances
        4096,   // output buffer size
        4096,   // input buffer size
        0,      // default timeout
        NULL    // default security
    );

    if (hPipe == INVALID_HANDLE_VALUE) {
        payloadLog("[AttachPipe] Failed to create response pipe: %s (err=%d)",
                   pipeName, GetLastError());
        return INVALID_HANDLE_VALUE;
    }

    payloadLog("[AttachPipe] Created response pipe: %s", pipeName);
    return hPipe;
}

// ── Wait for AttachListener to connect and read response ──
//
// The AttachListener processes the operation and writes the result
// to our named pipe. The result format is:
//   "<result_code>\n<result_message>"
// where result_code 0 = success, non-zero = failure.

static bool readAttachResponse(HANDLE hPipe, int timeoutMs) {
    // Set up overlapped I/O for ConnectNamedPipe with timeout
    OVERLAPPED ol = {0};
    ol.hEvent = CreateEvent(NULL, TRUE, FALSE, NULL);
    if (!ol.hEvent) {
        payloadLog("[AttachPipe] Failed to create overlapped event (err=%d)", GetLastError());
        return false;
    }

    // Start ConnectNamedPipe (non-blocking with overlapped I/O)
    BOOL connected = ConnectNamedPipe(hPipe, &ol);
    DWORD lastErr = GetLastError();

    if (connected) {
        // ConnectNamedPipe returned TRUE — client already connected
        payloadLog("[AttachPipe] Client already connected to response pipe");
    } else if (lastErr == ERROR_IO_PENDING) {
        // Waiting for client to connect — wait with timeout
        DWORD waitResult = WaitForSingleObject(ol.hEvent, timeoutMs);
        if (waitResult != WAIT_OBJECT_0) {
            payloadLog("[AttachPipe] Timeout waiting for AttachListener response (%d ms)", timeoutMs);
            CancelIo(hPipe);
            CloseHandle(ol.hEvent);
            return false;
        }
        payloadLog("[AttachPipe] AttachListener connected to response pipe");
    } else if (lastErr == ERROR_PIPE_CONNECTED) {
        // Client connected between CreateNamedPipe and ConnectNamedPipe
        payloadLog("[AttachPipe] Client connected (pipe already connected)");
    } else {
        payloadLog("[AttachPipe] ConnectNamedPipe failed (err=%d)", lastErr);
        CloseHandle(ol.hEvent);
        return false;
    }

    CloseHandle(ol.hEvent);

    // Read the response
    char buf[4096];
    DWORD bytesRead = 0;
    BOOL ok = ReadFile(hPipe, buf, sizeof(buf) - 1, &bytesRead, NULL);

    if (!ok || bytesRead == 0) {
        payloadLog("[AttachPipe] ReadFile failed (read=%d, err=%d)", bytesRead, GetLastError());
        return false;
    }

    buf[bytesRead] = '\0';

    // Parse the result code (first line, format: "0\nmessage" or "1\nerror")
    int result = -1;
    if (sscanf(buf, "%d", &result) == 1) {
        payloadLog("[AttachPipe] Response: result=%d, message='%s'", result, buf);
    } else {
        payloadLog("[AttachPipe] Failed to parse response: '%s'", buf);
    }

    return (result == 0);
}

// ── Public API ──

bool attachAndLoadAgent(int pid, const char* agentJarPath, const char* agentArgs) {
    payloadLog("[AttachPipe] === Starting Windows Attach (JVM_EnqueueOperation) ===");
    payloadLog("[AttachPipe] PID=%d, jar=%s, args=%s", pid, agentJarPath, agentArgs);

    // Step 1: Get JVM_EnqueueOperation from jvm.dll
    EnqueueOperationFunc enqueueOp = getEnqueueFunc();
    if (!enqueueOp) {
        payloadLog("[AttachPipe] FATAL: Cannot find JVM_EnqueueOperation in jvm.dll");
        return false;
    }

    // Step 2: Create response pipe (before enqueue, so it's ready when AttachListener writes)
    char pipeName[256];
    HANDLE hPipe = createResponsePipe(pipeName, sizeof(pipeName));
    if (hPipe == INVALID_HANDLE_VALUE) {
        payloadLog("[AttachPipe] FATAL: Cannot create response pipe");
        return false;
    }

    // Step 3: Build the arg2 string: jar_path=args
    char arg2[1024];
    _snprintf(arg2, sizeof(arg2), "%s=%s", agentJarPath, agentArgs);

    // Step 4: Call JVM_EnqueueOperation
    // This enqueues the operation and releases the AttachListener's semaphore.
    // The AttachListener will wake up, dequeue the operation, and process it.
    payloadLog("[AttachPipe] Calling JVM_EnqueueOperation: load instrument true %s %s",
               arg2, pipeName);

    jint result = enqueueOp("load", "instrument", "true", arg2, pipeName);

    if (result != 0) {
        payloadLog("[AttachPipe] JVM_EnqueueOperation returned error: %d", result);
        CloseHandle(hPipe);
        return false;
    }

    payloadLog("[AttachPipe] JVM_EnqueueOperation succeeded, waiting for AttachListener response...");

    // Step 5: Read the response from the AttachListener
    bool success = readAttachResponse(hPipe, 10000);

    // Cleanup
    DisconnectNamedPipe(hPipe);
    CloseHandle(hPipe);

    if (success) {
        payloadLog("[AttachPipe] === Agent loaded successfully via JVM_EnqueueOperation ===");
    } else {
        payloadLog("[AttachPipe] === Agent load FAILED ===");
    }

    return success;
}

#endif // _WIN32
