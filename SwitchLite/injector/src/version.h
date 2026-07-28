// Minecraft version and platform detection utilities
#ifndef VERSION_H
#define VERSION_H

#include <string>

struct VersionInfo {
    std::string platform;  // "Forge" or "Fabric"
    std::string version;   // e.g., "1.8.9", "1.20.1"
    std::string mcDir;     // .minecraft directory path
    bool valid;
    
    VersionInfo() : valid(false) {}
};

// Parse Minecraft version from installation directory
VersionInfo parseMinecraftVersion(const std::string& mcPath);

// Parse version from exe path + window title (title goes through fast path)
VersionInfo parseMinecraftVersion(const std::string& mcPath, const std::string& windowTitle);

// Read versions.json from .minecraft/versions
std::string readVersionsJson(const std::string& versionsDir);

// Detect platform (Forge/Fabric) from mods or config files
std::string detectPlatform(const std::string& mcDir);

// Get default .minecraft directory (APPDATA on Windows, HOME on Linux)
std::string getDefaultMinecraftDir();

#endif // VERSION_H
