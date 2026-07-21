// Sandwich Architecture - C++ Injector Entry Point
// Layer 1: Process detection, version/platform identification

#include <iostream>
#include <string>
#include <vector>
#include "process.h"
#include "inject.h"
#include "version.h"

int main(int argc, char* argv[]) {
    std::cout << "[SwitchLite Injector] Starting..." << std::endl;
    
    // Step 1: Detect Minecraft process
    ProcessInfo mcProcess = findMinecraftProcess();
    if (!mcProcess.valid) {
        std::cerr << "[Error] Minecraft process not found." << std::endl;
        return 1;
    }
    
    std::cout << "[Info] Found Minecraft process (PID: " << mcProcess.pid << ")" << std::endl;
    
    // Step 2: Identify platform and version
    VersionInfo versionInfo = parseMinecraftVersion(mcProcess.path);
    if (!versionInfo.valid) {
        std::cerr << "[Error] Failed to identify Minecraft version." << std::endl;
        return 1;
    }
    
    std::cout << "[Info] Platform: " << versionInfo.platform 
              << ", Version: " << versionInfo.version << std::endl;
    
    // Step 3: Load embedded agent.jar
    std::string agentPath = getEmbeddedAgentPath();
    if (agentPath.empty()) {
        std::cerr << "[Error] Embedded agent.jar not found." << std::endl;
        return 1;
    }
    
    std::cout << "[Info] Agent path: " << agentPath << std::endl;
    
    // Step 4: Inject Java Agent
    if (!injectJavaAgent(mcProcess.pid, agentPath, versionInfo)) {
        std::cerr << "[Error] Failed to inject Java Agent." << std::endl;
        return 1;
    }
    
    std::cout << "[Success] Java Agent injected successfully." << std::endl;
    
    // Step 5: For Fabric, deploy mod to mods directory
    if (versionInfo.platform == "Fabric") {
        std::string modPath = getFabricModPath();
        if (!deployFabricMod(versionInfo.mcDir, modPath, versionInfo.version)) {
            std::cerr << "[Warning] Failed to deploy Fabric mod." << std::endl;
        } else {
            std::cout << "[Success] Fabric mod deployed." << std::endl;
        }
    }
    
    std::cout << "[SwitchLite Injector] Completed." << std::endl;
    return 0;
}
