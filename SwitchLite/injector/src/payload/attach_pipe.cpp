// attach_pipe.cpp — Windows Attach API via named pipe (no tools.jar needed)
//
// This implements the JDK 8 Windows attach protocol entirely in C++.
// The protocol is:
//   1. Create a signal file %TEMP%\.attach_pid<PID> to trigger AttachListener
//   2. Wait for the AttachListener to create the named pipe
//   3. Connect to \\.\pipe\java_pid<PID>
//   4. Send the "load" command with the agent path
//   5. Read the response
//
// This is the same protocol that tools.jar's VirtualMachine.attach() uses,
// but we don't need tools.jar at all. This is critical because Minecraft
// runs on JRE, not JDK — tools.jar doesn't exist on JRE.

#ifdef _WIN32

#include "attach_pipe.h"
#include <windows.h>
#include <cstdio>
#include <cstring>
#include <string>

// External logger from payload.cpp
extern void payloadLog(const char* fmt, ...);

// ── Step 1: Signal the JVM to start AttachListener ──
//
// On Windows, the AttachListener thread checks for a file named
// .attach_pid<PID> in java.io.tmpdir. If found, it creates the
// named pipe and starts listening for commands.
//
// We also need to send a BREAK event to the JVM to wake up the
// AttachListener thread (it polls every 200ms in JDK 8, but the
// signal speeds it up).

static bool signalAttachListener(int pid) {
    // Create the signal file in %TEMP%
    char tempPath[MAX_PATH];
    GetTempPathA(MAX_PATH, tempPath);

    char attachFile[MAX_PATH];
    _snprintf(attachFile, sizeof(attachFile), "%s.attach_pid%d", tempPath, pid);

    // Remove stale file if it exists
    DeleteFileA(attachFile);

    // Create the signal file
    HANDLE hFile = CreateFileA(attachFile, GENERIC_WRITE, 0, NULL,
                               CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hFile == INVALID_HANDLE_VALUE) {
        payloadLog("[AttachPipe] Failed to create attach signal file: %s (err=%d)",
                   attachFile, GetLastError());
        return false;
    }
    CloseHandle(hFile);
    payloadLog("[AttachPipe] Created attach signal file: %s", attachFile);

    // Send a CTRL_BREAK_EVENT to the JVM process to trigger AttachListener
    // This is what the JDK's WindowsAttachProvider does
    // (GenerateConsoleCtrlEvent is not reliable for this; we use the file signal)
    // The AttachListener thread polls every ~200ms in JDK 8

    return true;
}

// ── Step 2: Connect to the named pipe ──

static HANDLE connectToPipe(int pid, int timeoutMs) {
    char pipeName[128];
    _snprintf(pipeName, sizeof(pipeName), "\\\\.\\pipe\\java_pid%d", pid);

    payloadLog("[AttachPipe] Trying to connect to pipe: %s", pipeName);

    DWORD startTime = GetTickCount();
    while (true) {
        // Try to connect
        HANDLE hPipe = CreateFileA(pipeName, GENERIC_READ | GENERIC_WRITE,
                                   0, NULL, OPEN_EXISTING, 0, NULL);
        if (hPipe != INVALID_HANDLE_VALUE) {
            payloadLog("[AttachPipe] Connected to pipe: %s", pipeName);
            return hPipe;
        }

        // Check if pipe is busy (another client connected)
        if (GetLastError() == ERROR_PIPE_BUSY) {
            // Wait for the pipe to become available
            if (!WaitNamedPipeA(pipeName, 1000)) {
                payloadLog("[AttachPipe] WaitNamedPipe failed (err=%d)", GetLastError());
            }
        }

        // Check timeout
        DWORD elapsed = GetTickCount() - startTime;
        if (elapsed > (DWORD)timeoutMs) {
            payloadLog("[AttachPipe] Timeout waiting for pipe (%d ms)", timeoutMs);
            return INVALID_HANDLE_VALUE;
        }

        // Wait a bit before retrying
        Sleep(100);
    }
}

// ── Step 3: Send the load command ──
//
// The JDK 8 Windows attach protocol uses a simple binary format:
//   - First 4 bytes: protocol version (big-endian int)
//   - Then a series of null-terminated strings:
//     "load\0instrument\0true\0<jar_path>=<args>\0"
//
// The response format is:
//   - First 4 bytes: protocol version (big-endian int)
//   - Then: result code (int, big-endian) + message (null-terminated string)

static bool sendLoadCommand(HANDLE hPipe, const char* agentJarPath, const char* agentArgs) {
    // Build the command string
    // Format: "load\0instrument\0true\0<jar_path>=<args>"
    char argBuf[1024];
    _snprintf(argBuf, sizeof(argBuf), "%s=%s", agentJarPath, agentArgs);

    // JDK 8 Windows attach protocol: version (4 bytes) + command strings
    // The version is 1 (big-endian)
    char versionBytes[4];
    versionBytes[0] = 0;
    versionBytes[1] = 0;
    versionBytes[2] = 0;
    versionBytes[3] = 1;  // version = 1

    // Calculate total message size
    // version(4) + "load\0" + "instrument\0" + "true\0" + argBuf + "\0"
    int totalLen = 4 + 5 + 11 + 5 + (int)strlen(argBuf) + 1;
    char* buf = new char[totalLen];
    int pos = 0;

    // Write version
    memcpy(buf + pos, versionBytes, 4); pos += 4;

    // Write command strings
    memcpy(buf + pos, "load", 4); pos += 4; buf[pos++] = '\0';
    memcpy(buf + pos, "instrument", 10); pos += 10; buf[pos++] = '\0';
    memcpy(buf + pos, "true", 4); pos += 4; buf[pos++] = '\0';
    memcpy(buf + pos, argBuf, strlen(argBuf)); pos += (int)strlen(argBuf); buf[pos++] = '\0';

    // Write to pipe
    DWORD written;
    BOOL ok = WriteFile(hPipe, buf, totalLen, &written, NULL);
    delete[] buf;

    if (!ok || written != (DWORD)totalLen) {
        payloadLog("[AttachPipe] WriteFile failed (written=%d, expected=%d, err=%d)",
                   written, totalLen, GetLastError());
        return false;
    }

    payloadLog("[AttachPipe] Sent load command: %s (%d bytes)", argBuf, totalLen);
    return true;
}

// ── Step 4: Read the response ──

static bool readResponse(HANDLE hPipe) {
    // Read response: version(4) + result(4) + message(variable)
    char respBuf[1024];
    DWORD totalRead = 0;
    DWORD toRead = sizeof(respBuf) - 1;

    // Read available data
    DWORD available = 0;
    PeekNamedPipe(hPipe, NULL, 0, NULL, &available, NULL);

    if (available == 0) {
        // Wait a bit for data
        Sleep(500);
        PeekNamedPipe(hPipe, NULL, 0, NULL, &available, NULL);
    }

    if (available == 0) {
        payloadLog("[AttachPipe] No response data available");
        return false;
    }

    DWORD toReadNow = min(available, toRead);
    DWORD bytesRead = 0;
    BOOL ok = ReadFile(hPipe, respBuf, toReadNow, &bytesRead, NULL);

    if (!ok || bytesRead < 8) {
        payloadLog("[AttachPipe] ReadFile failed (read=%d, err=%d)", bytesRead, GetLastError());
        return false;
    }

    // Parse response
    // Version (first 4 bytes, big-endian)
    int respVersion = (respBuf[0] << 24) | (respBuf[1] << 16) | (respBuf[2] << 8) | respBuf[3];
    // Result code (next 4 bytes, big-endian)
    int result = (respBuf[4] << 24) | (respBuf[5] << 16) | (respBuf[6] << 8) | respBuf[7];

    // Message (remaining bytes, null-terminated)
    char* message = "";
    if (bytesRead > 8) {
        respBuf[bytesRead] = '\0';
        message = respBuf + 8;
    }

    payloadLog("[AttachPipe] Response: version=%d, result=%d, message='%s'", respVersion, result, message);

    return (result == 0);  // 0 = success
}

// ── Cleanup: Remove the signal file ──

static void cleanupSignalFile(int pid) {
    char tempPath[MAX_PATH];
    GetTempPathA(MAX_PATH, tempPath);
    char attachFile[MAX_PATH];
    _snprintf(attachFile, sizeof(attachFile), "%s.attach_pid%d", tempPath, pid);
    DeleteFileA(attachFile);
}

// ── Public API ──

bool attachAndLoadAgent(int pid, const char* agentJarPath, const char* agentArgs) {
    payloadLog("[AttachPipe] === Starting Windows Attach pipe protocol ===");
    payloadLog("[AttachPipe] PID=%d, jar=%s, args=%s", pid, agentJarPath, agentArgs);

    // Step 1: Signal the JVM to start AttachListener
    if (!signalAttachListener(pid)) {
        payloadLog("[AttachPipe] Failed to signal AttachListener");
        return false;
    }

    // Step 2: Connect to the named pipe (wait up to 10 seconds)
    HANDLE hPipe = connectToPipe(pid, 10000);
    if (hPipe == INVALID_HANDLE_VALUE) {
        payloadLog("[AttachPipe] Failed to connect to named pipe");
        cleanupSignalFile(pid);
        return false;
    }

    // Step 3: Send the load command
    bool sendOk = sendLoadCommand(hPipe, agentJarPath, agentArgs);
    if (!sendOk) {
        payloadLog("[AttachPipe] Failed to send load command");
        CloseHandle(hPipe);
        cleanupSignalFile(pid);
        return false;
    }

    // Step 4: Read the response
    bool result = readResponse(hPipe);

    // Cleanup
    CloseHandle(hPipe);
    cleanupSignalFile(pid);

    if (result) {
        payloadLog("[AttachPipe] === Agent loaded successfully via Attach pipe ===");
    } else {
        payloadLog("[AttachPipe] === Agent load FAILED via Attach pipe ===");
    }

    return result;
}

#endif // _WIN32
