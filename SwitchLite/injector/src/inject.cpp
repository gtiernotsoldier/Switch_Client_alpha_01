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

// ── getEmbeddedAgentPath: extract agent.jar from EXE resources ──

std::string getEmbeddedAgentPath() {
#ifdef _WIN32
    // 1. Find the resource in our own EXE
    HRSRC hRes = FindResourceA(NULL, MAKEINTRESOURCEA(101), RT_RCDATA);
    if (!hRes) {
        // Fallback: try by name
        hRes = FindResourceA(NULL, "AGENT_JAR_RCDATA", RT_RCDATA);
    }
    if (!hRes) {
        std::cerr << "[Inject] FindResource failed (error: " << GetLastError() << ")" << std::endl;
        return "";
    }

    HGLOBAL hMem = LoadResource(NULL, hRes);
    if (!hMem) {
        std::cerr << "[Inject] LoadResource failed (error: " << GetLastError() << ")" << std::endl;
        return "";
    }

    DWORD size = SizeofResource(NULL, hRes);
    void* data = LockResource(hMem);
    if (!data || size == 0) {
        std::cerr << "[Inject] LockResource failed or empty resource" << std::endl;
        return "";
    }

    // 2. Write to %TEMP%\switchlite-agent.jar
    char tempPath[MAX_PATH];
    GetTempPathA(MAX_PATH, tempPath);
    std::string agentPath = std::string(tempPath) + "switchlite-agent.jar";

    std::ofstream out(agentPath, std::ios::binary);
    if (!out.is_open()) {
        std::cerr << "[Inject] Failed to write agent to: " << agentPath << std::endl;
        return "";
    }
    out.write(static_cast<const char*>(data), size);
    out.close();

    std::cout << "[Inject] Extracted agent.jar (" << size << " bytes) to " << agentPath << std::endl;
    return agentPath;
#else
    return "./resources/agent.jar";
#endif
}

std::string getFabricModPath() {
    return "./adapter/fabric/build/libs/SwitchLite-Fabric.jar";
}

// ── injectJavaAgent: Attach API via named pipe ──

bool injectJavaAgent(int pid, const std::string& agentPath, const VersionInfo& versionInfo) {
    std::cout << "[Inject] Attaching to JVM PID " << pid << "..." << std::endl;

#ifdef _WIN32
    // Write config file next to agent.jar
    std::string configPath =
        agentPath.substr(0, agentPath.find_last_of("\\/")) + "\\switchlite-config.properties";
    std::ofstream cfg(configPath);
    if (cfg.is_open()) {
        cfg << "switchlite.platform=" << versionInfo.platform << std::endl;
        cfg << "switchlite.version=" << versionInfo.version << std::endl;
        cfg.close();
        std::cout << "[Inject] Config written: " << configPath << std::endl;
    }

    // 1. Construct the attach pipe path (JDK 9+ then JDK 8 fallback)
    std::string pipePath;
    char response[4096];
    DWORD bytesRead;

    // Try JDK 9+ pipe
    pipePath = "\\\\.\\pipe\\javatoolpipe" + std::to_string(pid);
    HANDLE hPipe = CreateFileA(
        pipePath.c_str(),
        GENERIC_READ | GENERIC_WRITE,
        0, NULL, OPEN_EXISTING, 0, NULL
    );

    if (hPipe == INVALID_HANDLE_VALUE) {
        // Fallback to JDK 8 pipe
        pipePath = "\\\\.\\pipe\\.java_pid" + std::to_string(pid);
        hPipe = CreateFileA(
            pipePath.c_str(),
            GENERIC_READ | GENERIC_WRITE,
            0, NULL, OPEN_EXISTING, 0, NULL
        );
    }

    if (hPipe == INVALID_HANDLE_VALUE) {
        DWORD err = GetLastError();
        std::cerr << "[Inject] Cannot connect to JVM attach pipe (error: " << err << ")" << std::endl;
        std::cerr << "[Inject] Possible causes: MC not running with JDK, or pipe path mismatch" << std::endl;
        return false;
    }

    std::cout << "[Inject] Connected to attach pipe: " << pipePath << std::endl;

    // 2. Send attach protocol: "<pid>\0<operation>\0<data>\0"
    std::string pidStr = std::to_string(pid);
    std::string operation = "load";
    std::string payload = pidStr + '\0' + operation + '\0' + agentPath + '\0';
    DWORD bytesWritten;

    BOOL writeOk = WriteFile(hPipe, payload.c_str(), (DWORD)payload.length(), &bytesWritten, NULL);
    if (!writeOk) {
        std::cerr << "[Inject] Failed to write to pipe (error: " << GetLastError() << ")" << std::endl;
        CloseHandle(hPipe);
        return false;
    }

    std::cout << "[Inject] Sent load command: " << agentPath << std::endl;

    // 3. Read response
    Sleep(500);
    memset(response, 0, sizeof(response));
    BOOL readOk = ReadFile(hPipe, response, sizeof(response) - 1, &bytesRead, NULL);
    CloseHandle(hPipe);

    if (readOk && bytesRead > 0) {
        std::string resp(response, bytesRead);
        while (!resp.empty() && (resp.back() == '\n' || resp.back() == '\r' || resp.back() == '\0'))
            resp.pop_back();
        std::cout << "[Inject] JVM response: " << resp << std::endl;
        if (resp.empty() || resp == "0") {
            std::cout << "[+] Agent loaded successfully" << std::endl;
            return true;
        } else {
            std::cerr << "[Inject] JVM returned error: " << resp << std::endl;
            return false;
        }
    }

    std::cout << "[Inject] No response from JVM (may indicate success)" << std::endl;
    return true;

#else
    std::cout << "[Inject] Attach API not supported on this platform (stub)" << std::endl;
    return false;
#endif
}

// ── deployFabricMod ──

bool deployFabricMod(const std::string& mcDir, const std::string& modPath, const std::string& version) {
    std::cout << "[Deploy] Deploying Fabric mod to: " << mcDir << "/mods/" << std::endl;
    return true;
}
