// Minecraft version detection implementation
#include "version.h"
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
#include <cctype>

#ifdef _WIN32
    #include <windows.h>
#else
    #include <unistd.h>
    #include <pwd.h>
    #include <dirent.h>
    #include <sys/stat.h>
#endif

// ── Helpers ──────────────────────────────────────────

static std::string pathJoin(const std::string& a, const std::string& b) {
    if (a.empty()) return b;
    char last = a.back();
    if (last == '/' || last == '\\') return a + b;
#ifdef _WIN32
    return a + "\\" + b;
#else
    return a + "/" + b;
#endif
}

static bool dirExists(const std::string& path) {
#ifdef _WIN32
    DWORD attr = GetFileAttributesA(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY);
#else
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
#endif
}

static bool fileExists(const std::string& path) {
#ifdef _WIN32
    DWORD attr = GetFileAttributesA(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
#else
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
#endif
}

/** Find .minecraft directory by walking up from exe/modules path. */
static std::string findMinecraftDir(const std::string& exePath) {
    std::string current = exePath;
    for (int i = 0; i < 10; i++) {
        // Strip to parent dir
        size_t pos = current.find_last_of("/\\");
        if (pos == std::string::npos) break;
        current = current.substr(0, pos);

        // Check if this is .minecraft
        std::string mcDir = pathJoin(current, ".minecraft");
        if (dirExists(mcDir)) return mcDir;

        // Also check if current itself is the .minecraft dir
        size_t nameStart = current.find_last_of("/\\");
        std::string dirName = (nameStart == std::string::npos) ? current : current.substr(nameStart + 1);
        if (dirName == ".minecraft") return current;
    }
    // Fallback: try APPDATA or HOME
    return getDefaultMinecraftDir();
}

/** List subdirectory names under a path. */
static std::vector<std::string> listDirs(const std::string& path) {
    std::vector<std::string> result;
#ifdef _WIN32
    std::string pattern = pathJoin(path, "*");
    WIN32_FIND_DATAA fd;
    HANDLE hFind = FindFirstFileA(pattern.c_str(), &fd);
    if (hFind == INVALID_HANDLE_VALUE) return result;
    do {
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            std::string name = fd.cFileName;
            if (name != "." && name != "..") result.push_back(name);
        }
    } while (FindNextFileA(hFind, &fd));
    FindClose(hFind);
#else
    DIR* dir = opendir(path.c_str());
    if (!dir) return result;
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        std::string name = entry->d_name;
        if (name == "." || name == "..") continue;
        std::string full = pathJoin(path, name);
        struct stat st;
        if (stat(full.c_str(), &st) == 0 && S_ISDIR(st.st_mode))
            result.push_back(name);
    }
    closedir(dir);
#endif
    return result;
}

/** Quick JSON string value extractor — looks for "key":"value" or "key": "value". */
static std::string jsonGet(const std::string& json, const std::string& key) {
    std::string search = "\"" + key + "\"";
    size_t pos = json.find(search);
    if (pos == std::string::npos) return "";
    pos = json.find(':', pos + search.length());
    if (pos == std::string::npos) return "";
    // skip whitespace
    while (++pos < json.length() && (json[pos] == ' ' || json[pos] == '\t' || json[pos] == '\n'));
    if (pos >= json.length()) return "";
    if (json[pos] != '"') return "";
    size_t end = json.find('"', pos + 1);
    if (end == std::string::npos) return "";
    return json.substr(pos + 1, end - pos - 1);
}

std::string readVersionsJson(const std::string& versionsDir) {
    // Try reading the version JSON that matches the directory name
    return ""; // used if we had a specific version name
}

std::string detectPlatform(const std::string& mcDir) {
    // Check mods folder for platform indicators
    std::string modsDir = pathJoin(mcDir, "mods");
    if (!dirExists(modsDir)) {
        // Also check versions/<version>/mods
        std::string versionsDir = pathJoin(mcDir, "versions");
        auto versions = listDirs(versionsDir);
        for (auto& v : versions) {
            std::string vmods = pathJoin(pathJoin(versionsDir, v), "mods");
            if (dirExists(vmods)) { modsDir = vmods; break; }
        }
    }

    if (dirExists(modsDir)) {
        auto files = listDirs(modsDir);
        for (auto& f : files) {
            std::string lower = f;
            for (auto& c : lower) c = tolower(c);
            // Forge mods: typically contain "forge" in filename or have .jar.meta
            if (lower.find("forge") != std::string::npos)
                return "Forge";
        }
        // Check for Fabric indicators
        for (auto& f : files) {
            std::string lower = f;
            for (auto& c : lower) c = tolower(c);
            if (lower.find("fabric-api") != std::string::npos ||
                lower.find("fabric-loader") != std::string::npos)
                return "Fabric";
        }
    }

    // Check versions directory for Forge version folders (e.g. "1.8.9-forge-...")
    std::string versionsDir = pathJoin(mcDir, "versions");
    auto versions = listDirs(versionsDir);
    for (auto& v : versions) {
        std::string lower = v;
        for (auto& c : lower) c = tolower(c);
        if (lower.find("forge") != std::string::npos)
            return "Forge";
        if (lower.find("fabric") != std::string::npos)
            return "Fabric";
    }

    return "Vanilla";
}

// ── Main entry ──────────────────────────────────────

VersionInfo parseMinecraftVersion(const std::string& mcPath) {
    VersionInfo result;

    // 1. Locate .minecraft directory
    std::string mcDir = findMinecraftDir(mcPath);
    result.mcDir = mcDir;
    if (mcDir.empty()) {
        result.platform = "Unknown";
        result.version = "Unknown";
        result.valid = false;
        return result;
    }

    // 2. Detect platform
    result.platform = detectPlatform(mcDir);

    // 3. Detect version from versions directory
    std::string versionsDir = pathJoin(mcDir, "versions");
    auto versionDirs = listDirs(versionsDir);

    std::string bestVersion;
    for (auto& dirName : versionDirs) {
        // Skip forge/fabric wrapper dirs (e.g. "1.8.9-forge-11.15.1.2318")
        // but we still want to extract the base version from them
        std::string candidate = dirName;

        // Try reading <dirName>/<dirName>.json for "id" field
        std::string jsonPath = pathJoin(pathJoin(versionsDir, dirName), dirName + ".json");
        std::ifstream jsonFile(jsonPath);
        if (jsonFile.is_open()) {
            std::stringstream ss;
            ss << jsonFile.rdbuf();
            std::string json = ss.str();
            std::string id = jsonGet(json, "id");
            if (!id.empty()) {
                // Prefer vanilla versions over forge/fabric wrappers
                if (id.find("forge") == std::string::npos && id.find("fabric") == std::string::npos) {
                    bestVersion = id;
                    break; // found a clean vanilla version
                }
                if (bestVersion.empty()) bestVersion = id;
            }
        }

        // Fallback: use dir name itself (strip forge/fabric suffix for display)
        if (bestVersion.empty() && !candidate.empty()) {
            bestVersion = candidate;
        }
    }

    // 4. Final validation
    if (!bestVersion.empty()) {
        result.version = bestVersion;
        result.valid = true;
    } else {
        result.version = "Unknown";
        result.valid = false;
    }

    return result;
}

std::string getDefaultMinecraftDir() {
#ifdef _WIN32
    const char* appdata = getenv("APPDATA");
    if (appdata) return pathJoin(std::string(appdata), ".minecraft");
#else
    const char* home = getenv("HOME");
    if (home) return pathJoin(std::string(home), ".minecraft");
#endif
    return "";
}
