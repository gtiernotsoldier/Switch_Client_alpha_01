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
    // 1. Walk up from exe path (existing logic)
    std::string current = exePath;
    for (int i = 0; i < 10; i++) {
        size_t pos = current.find_last_of("/\\");
        if (pos == std::string::npos) break;
        current = current.substr(0, pos);
        std::string mcDir = pathJoin(current, ".minecraft");
        if (dirExists(mcDir)) return mcDir;
        size_t nameStart = current.find_last_of("/\\");
        std::string dirName = (nameStart == std::string::npos) ? current : current.substr(nameStart + 1);
        if (dirName == ".minecraft") return current;
    }

    // 2. Fallback: %APPDATA%/.minecraft
    std::string appdataDir = getDefaultMinecraftDir();
    if (dirExists(appdataDir)) return appdataDir;

    // 3. Same-drive root .minecraft (PCL2 often stores game in D:\\.minecraft etc.)
    if (exePath.length() >= 2 && exePath[1] == ':') {
        char driveRoot[4] = { exePath[0], ':', '\\', '\0' };
        std::string driveMc = pathJoin(std::string(driveRoot), ".minecraft");
        if (dirExists(driveMc)) return driveMc;
    }

    // 4. Walk up further looking for PCL2/HMCL launcher folders
    current = exePath;
    for (int i = 0; i < 15; i++) {
        size_t pos = current.find_last_of("/\\");
        if (pos == std::string::npos) break;
        current = current.substr(0, pos);
        size_t nameStart = current.find_last_of("/\\");
        std::string name = (nameStart == std::string::npos) ? current : current.substr(nameStart + 1);
        std::string lower = name;
        for (auto& c : lower) c = tolower(c);
        if (lower.find("pcl") != std::string::npos || lower.find("hmcl") != std::string::npos ||
            lower.find("launcher") != std::string::npos) {
            std::string sibling = pathJoin(current, ".minecraft");
            if (dirExists(sibling)) return sibling;
            size_t parentPos = current.find_last_of("/\\");
            if (parentPos != std::string::npos) {
                std::string parentMc = pathJoin(current.substr(0, parentPos), ".minecraft");
                if (dirExists(parentMc)) return parentMc;
            }
        }
    }

    return "";
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
    return "";
}

// ── Window title version parser ──────────────────────────

/** Extract Minecraft version from window title, e.g. "Minecraft 1.8.9" → "1.8.9" */
static std::string parseVersionFromTitle(const std::string& title) {
    std::string lower = title;
    for (auto& c : lower) c = tolower(c);
    size_t mcPos = lower.find("minecraft");
    if (mcPos == std::string::npos) return "";
    size_t pos = mcPos + 9; // skip "minecraft"

    // Skip whitespace/asterisks/dashes
    while (pos < title.length() && (title[pos] == ' ' || title[pos] == '*' || title[pos] == '-'))
        pos++;

    // Read version: digits and dots only
    std::string version;
    while (pos < title.length() && (isdigit(title[pos]) || title[pos] == '.')) {
        version += title[pos];
        pos++;
    }

    // Must have at least "X.Y" format
    if (version.length() < 3 || version.find('.') == std::string::npos) return "";
    return version;
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
    return parseMinecraftVersion(mcPath, "");
}

static std::string normalizeVersion(const std::string& raw) {
    std::string s = raw;
    // trim
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    s = s.substr(b, e - b + 1);
    if (s.empty()) return "";

    // Pull out the first dotted numeric core, e.g. "1.8", "1.8.9", "1.12.2".
    std::string numeric;
    bool started = false;
    for (char c : s) {
        if (isdigit((unsigned char)c) || c == '.') {
            numeric += c;
            started = true;
        } else if (started) {
            break; // stop at first non-numeric/non-dot after the numeric core
        }
    }
    // trim trailing dots
    while (!numeric.empty() && numeric.back() == '.') numeric.pop_back();
    if (numeric.empty()) return "";

    // Split into major/minor.
    std::string major, minor;
    size_t dot = numeric.find('.');
    if (dot != std::string::npos) {
        major = numeric.substr(0, dot);
        minor = numeric.substr(dot + 1);
        size_t dot2 = minor.find('.');
        if (dot2 != std::string::npos) minor = minor.substr(0, dot2);
    } else {
        major = numeric;
    }

    // Collapse the whole 1.8 family (1.8, 1.8.1..1.8.9) to 1.8.9 — same SRG layout.
    if (major == "1" && minor == "8") return "1.8.9";
    return numeric;
}

VersionInfo parseMinecraftVersion(const std::string& mcPath, const std::string& windowTitle) {
    VersionInfo result;

    // 1. Locate .minecraft directory FIRST — the filesystem version is authoritative.
    std::string mcDir = findMinecraftDir(mcPath);
    result.mcDir = mcDir;

    // 2. Try the versions/ directory as the authoritative version source. It reflects the
    //    REAL installed version, unlike window titles that third-party launchers
    //    (FPSMaster etc.) can spoof/truncate (e.g. "Minecraft 1.8" for an 1.8.9 install).
    std::string dirVersion;
    if (!mcDir.empty()) {
        std::string versionsDir = pathJoin(mcDir, "versions");
        auto versionDirs = listDirs(versionsDir);

        for (auto& dirName : versionDirs) {
            std::string jsonPath = pathJoin(pathJoin(versionsDir, dirName), dirName + ".json");
            std::ifstream jsonFile(jsonPath);
            if (jsonFile.is_open()) {
                std::stringstream ss;
                ss << jsonFile.rdbuf();
                std::string json = ss.str();
                std::string id = jsonGet(json, "id");
                if (!id.empty()) {
                    if (id.find("forge") == std::string::npos && id.find("fabric") == std::string::npos) {
                        dirVersion = id;
                        break;
                    }
                    if (dirVersion.empty()) dirVersion = id;
                }
            }
            if (dirVersion.empty() && !dirName.empty()) dirVersion = dirName;
        }
    }

    // 3. Detect platform from filesystem
    if (!mcDir.empty()) {
        result.platform = detectPlatform(mcDir);
    } else {
        result.platform = "Unknown";
    }

    // 4. Resolve version: directory source wins; window title is only a fallback.
    std::string raw = !dirVersion.empty() ? dirVersion : parseVersionFromTitle(windowTitle);
    result.version = normalizeVersion(raw);

    // 5. Validation: version from any source is enough
    result.valid = !result.version.empty();

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
