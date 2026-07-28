// Sandwich Architecture - C++ Injector Entry Point
// Layer 1: Process detection, version/platform identification

#include <iostream>
#include <string>
#include <vector>
#include "process.h"
#include "inject.h"
#include "version.h"

// Windows console color helpers
#ifdef _WIN32
#include <windows.h>
enum ConsoleColor {
    COLOR_GRAY   = 8,
    COLOR_GREEN  = 10,
    COLOR_YELLOW = 14,
    COLOR_RED    = 12,
    COLOR_WHITE  = 15
};
static void setConColor(int color) {
    SetConsoleTextAttribute(GetStdHandle(STD_OUTPUT_HANDLE), color);
}
#else
enum ConsoleColor { COLOR_GRAY=0, COLOR_GREEN=0, COLOR_YELLOW=0, COLOR_RED=0, COLOR_WHITE=0 };
static void setConColor(int) {}
#endif

static void pauseExit() {
    setConColor(COLOR_YELLOW);
    std::cout << "\n[!] Press any key to exit..." << std::endl;
    setConColor(COLOR_WHITE);
#ifdef _WIN32
    system("pause >nul");
#else
    std::cin.get();
#endif
}

int main(int argc, char* argv[]) {
    setConColor(COLOR_GRAY);
    std::cout << "[*] SwitchLite Injector v0.1.0-alpha" << std::endl;
    setConColor(COLOR_WHITE);

    // Step 1: Detect Minecraft process
    setConColor(COLOR_GRAY);
    std::cout << "[*] Scanning for Minecraft process..." << std::endl;
    setConColor(COLOR_WHITE);

    ProcessInfo mcProcess = findMinecraftProcess();
    if (!mcProcess.valid) {
        setConColor(COLOR_RED);
        std::cerr << "[x] Minecraft process not found." << std::endl;
        setConColor(COLOR_WHITE);
        pauseExit();
        return 1;
    }

    setConColor(COLOR_GREEN);
    std::cout << "[+] Found Minecraft (PID: " << mcProcess.pid << ", Window: \"" << mcProcess.windowTitle << "\")" << std::endl;
    setConColor(COLOR_WHITE);

    // Step 2: Identify platform and version
    setConColor(COLOR_GRAY);
    std::cout << "[*] Detecting version & platform..." << std::endl;
    setConColor(COLOR_WHITE);

    VersionInfo versionInfo = parseMinecraftVersion(mcProcess.path);
    if (!versionInfo.valid) {
        setConColor(COLOR_RED);
        std::cerr << "[x] Failed to identify Minecraft version." << std::endl;
        setConColor(COLOR_WHITE);
        pauseExit();
        return 1;
    }

    setConColor(COLOR_GREEN);
    std::cout << "[+] Version: " << versionInfo.version
              << " | Platform: " << versionInfo.platform << std::endl;
    std::cout << "[*] Minecraft dir: " << versionInfo.mcDir << std::endl;
    setConColor(COLOR_WHITE);

    // Step 3: Load embedded agent.jar
    setConColor(COLOR_GRAY);
    std::cout << "[*] Extracting embedded agent.jar..." << std::endl;
    setConColor(COLOR_WHITE);

    std::string agentPath = getEmbeddedAgentPath();
    if (agentPath.empty()) {
        setConColor(COLOR_RED);
        std::cerr << "[x] Embedded agent.jar not found." << std::endl;
        setConColor(COLOR_WHITE);
        pauseExit();
        return 1;
    }

    setConColor(COLOR_GREEN);
    std::cout << "[+] Agent ready: " << agentPath << std::endl;
    setConColor(COLOR_WHITE);

    // Step 4: Inject Java Agent
    setConColor(COLOR_GRAY);
    std::cout << "[*] Injecting into JVM..." << std::endl;
    setConColor(COLOR_WHITE);

    if (!injectJavaAgent(mcProcess.pid, agentPath, versionInfo)) {
        setConColor(COLOR_RED);
        std::cerr << "[x] Failed to inject Java Agent." << std::endl;
        setConColor(COLOR_WHITE);
        pauseExit();
        return 1;
    }

    setConColor(COLOR_GREEN);
    std::cout << "[+] Java Agent injected successfully." << std::endl;
    setConColor(COLOR_WHITE);

    // Step 5: For Fabric, deploy mod to mods directory
    if (versionInfo.platform == "Fabric") {
        std::string modPath = getFabricModPath();
        if (!deployFabricMod(versionInfo.mcDir, modPath, versionInfo.version)) {
            setConColor(COLOR_YELLOW);
            std::cout << "[!] Failed to deploy Fabric mod." << std::endl;
            setConColor(COLOR_WHITE);
        } else {
            setConColor(COLOR_GREEN);
            std::cout << "[+] Fabric mod deployed." << std::endl;
            setConColor(COLOR_WHITE);
        }
    }

    setConColor(COLOR_GREEN);
    std::cout << "\n[+] Done. Press any key to exit..." << std::endl;
    setConColor(COLOR_WHITE);
#ifdef _WIN32
    system("pause >nul");
#else
    std::cin.get();
#endif
    return 0;
}
