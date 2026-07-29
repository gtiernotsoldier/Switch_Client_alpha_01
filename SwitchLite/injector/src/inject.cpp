// Java Agent injection implementation
#include "inject.h"
#include <iostream>
#include <fstream>
#include <cstring>

#ifdef _WIN32
    #include <windows.h>
#else
    #include <dlfcn.h>
    #include <unistd.h>
#endif

// ── Resource extraction helper ──

static bool extractResource(int resId, const char* outPath) {
#ifdef _WIN32
    HRSRC hRes = FindResourceA(NULL, MAKEINTRESOURCEA(resId), RT_RCDATA);
    if (!hRes) {
        std::cerr << "[Inject] FindResource failed for id " << resId << " (error: " << GetLastError() << ")" << std::endl;
        return false;
    }
    HGLOBAL hMem = LoadResource(NULL, hRes);
    if (!hMem) return false;
    DWORD size = SizeofResource(NULL, hRes);
    void* data = LockResource(hMem);
    if (!data || size == 0) return false;

    std::ofstream out(outPath, std::ios::binary);
    if (!out.is_open()) return false;
    out.write(static_cast<const char*>(data), size);
    out.close();
    std::cout << "[Inject] Extracted " << outPath << " (" << size << " bytes)" << std::endl;
    return true;
#else
    return false;
#endif
}

// ── getEmbeddedAgentPath ──

std::string getEmbeddedAgentPath() {
#ifdef _WIN32
    char tempPath[MAX_PATH];
    GetTempPathA(MAX_PATH, tempPath);
    std::string agentPath = std::string(tempPath) + "switchlite-agent.jar";
    extractResource(101, agentPath.c_str());
    return agentPath;
#else
    return "./resources/agent.jar";
#endif
}

// ── getEmbeddedPayloadPath ──

std::string getEmbeddedPayloadPath() {
#ifdef _WIN32
    char tempPath[MAX_PATH];
    GetTempPathA(MAX_PATH, tempPath);
    std::string dllPath = std::string(tempPath) + "switchlite-payload.dll";
    extractResource(102, dllPath.c_str());
    return dllPath;
#else
    return "";
#endif
}

std::string getFabricModPath() {
    return "./adapter/fabric/build/libs/SwitchLite-Fabric.jar";
}

// ── injectJavaAgent: DLL injection via CreateRemoteThread ──

bool injectJavaAgent(int pid, const std::string& agentPath, const VersionInfo& versionInfo) {
    std::cout << "[Inject] Injecting via DLL + JNI into PID " << pid << "..." << std::endl;

#ifdef _WIN32
    // Write config file next to agent.jar
    std::string configDir = agentPath.substr(0, agentPath.find_last_of("\\/"));
    std::string configPath = configDir + "\\switchlite-config.properties";
    std::ofstream cfg(configPath);
    if (cfg.is_open()) {
        cfg << "switchlite.platform=" << versionInfo.platform << std::endl;
        cfg << "switchlite.version=" << versionInfo.version << std::endl;
        cfg.close();
        std::cout << "[Inject] Config written: " << configPath << std::endl;
    }

    // 1. Extract payload.dll from EXE resource
    std::string dllPath = getEmbeddedPayloadPath();
    if (dllPath.empty()) {
        std::cerr << "[Inject] Failed to extract payload.dll" << std::endl;
        return false;
    }

    // 2. Open target process
    HANDLE hProcess = OpenProcess(
        PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
        PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
        FALSE, pid
    );
    if (!hProcess) {
        std::cerr << "[Inject] Cannot open process (error: " << GetLastError() << ")" << std::endl;
        return false;
    }

    // 3. Allocate memory in target process for DLL path
    size_t pathSize = dllPath.length() + 1;
    LPVOID pRemoteMem = VirtualAllocEx(hProcess, NULL, pathSize,
        MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!pRemoteMem) {
        std::cerr << "[Inject] VirtualAllocEx failed (error: " << GetLastError() << ")" << std::endl;
        CloseHandle(hProcess);
        return false;
    }

    // 4. Write DLL path to target process
    if (!WriteProcessMemory(hProcess, pRemoteMem, dllPath.c_str(), pathSize, NULL)) {
        std::cerr << "[Inject] WriteProcessMemory failed (error: " << GetLastError() << ")" << std::endl;
        VirtualFreeEx(hProcess, pRemoteMem, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        return false;
    }

    // 5. Find LoadLibraryA in target process
    HMODULE hKernel32 = GetModuleHandleA("kernel32.dll");
    LPTHREAD_START_ROUTINE pLoadLibrary =
        (LPTHREAD_START_ROUTINE)GetProcAddress(hKernel32, "LoadLibraryA");

    // 6. Create remote thread to load the DLL
    HANDLE hThread = CreateRemoteThread(hProcess, NULL, 0, pLoadLibrary, pRemoteMem, 0, NULL);
    if (!hThread) {
        std::cerr << "[Inject] CreateRemoteThread failed (error: " << GetLastError() << ")" << std::endl;
        VirtualFreeEx(hProcess, pRemoteMem, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        return false;
    }

    // 7. Create Named Event for payload to signal completion
    //     Name includes PID so multiple MCs don't collide
    std::string eventName = "SwitchLitePayloadDone_" + std::to_string(pid);
    HANDLE hDoneEvent = CreateEventA(NULL, TRUE, FALSE, eventName.c_str());
    if (!hDoneEvent) {
        std::cerr << "[Inject] Failed to create done event (error: " << GetLastError() << ")" << std::endl;
    } else {
        std::cout << "[Inject] Created done event: " << eventName << std::endl;
    }

    // 8. Wait for LoadLibraryA thread (DLL load itself)
    WaitForSingleObject(hThread, 10000);

    // 9. Cleanup thread handle (keep event for now)
    CloseHandle(hThread);

    // 10. Wait for payload ThreadProc to signal done (real completion, not just DLL load)
    if (hDoneEvent) {
        std::cout << "[Inject] Waiting for payload to complete (up to 15s)..." << std::endl;
        DWORD waitResult = WaitForSingleObject(hDoneEvent, 15000);
        switch (waitResult) {
            case WAIT_OBJECT_0:
                std::cout << "[Inject] [+] Payload signaled completion (Agent loaded)" << std::endl;
                break;
            case WAIT_TIMEOUT:
                std::cerr << "[Inject] [!] Payload timed out after 15s — Agent may not have loaded" << std::endl;
                break;
            default:
                std::cerr << "[Inject] [!] Wait error: " << GetLastError() << std::endl;
                break;
        }
        CloseHandle(hDoneEvent);
    } else {
        // Fallback: old behavior (just wait fixed time)
        std::cout << "[Inject] [!] No done event, waiting 5s as fallback..." << std::endl;
        for (int i = 5; i > 0; i--) {
            Sleep(1000);
            std::cout << "[Inject] " << i << "..." << std::endl;
        }
    }

    // 11. Cleanup process handle
    VirtualFreeEx(hProcess, pRemoteMem, 0, MEM_RELEASE);
    CloseHandle(hProcess);

    std::cout << "[+] Java Agent injected successfully via DLL" << std::endl;
    return true;

#else
    std::cout << "[Inject] DLL injection not supported on this platform (stub)" << std::endl;
    return false;
#endif
}

// ── dumpLogFile: read and display a log file if it exists ──

static void dumpLogFile(const char* label, const char* path) {
    std::ifstream f(path);
    if (!f.is_open()) {
        std::cout << "[" << label << "] (no log file at " << path << ")" << std::endl;
        return;
    }
    std::cout << "[" << label << "] === " << path << " ===" << std::endl;
    std::string line;
    while (std::getline(f, line)) {
        std::cout << "[" << label << "] " << line << std::endl;
    }
    f.close();
}

// ── showDiagnosticLogs ──

void showDiagnosticLogs() {
    char tempPath[MAX_PATH];
    GetTempPathA(MAX_PATH, tempPath);
    std::string payloadLog = std::string(tempPath) + "switchlite-payload.log";
    std::string agentLog = std::string(tempPath) + "switchlite-agent.log";

    std::cout << std::endl;
    std::cout << "========== DIAGNOSTIC LOGS ==========" << std::endl;
    dumpLogFile("PAYLOAD", payloadLog.c_str());
    std::cout << std::endl;
    dumpLogFile("AGENT", agentLog.c_str());
    std::cout << "====================================" << std::endl;
}

// ── deployFabricMod ──

bool deployFabricMod(const std::string& mcDir, const std::string& modPath, const std::string& version) {
    std::cout << "[Deploy] Deploying Fabric mod to: " << mcDir << "/mods/" << std::endl;
    return true;
}
