# Ticket: Fix ClassLoader Signature + Platform Detection

**Branch:** `fix/classloader-signature`
**Priority:** P0 (blocks all Agent functionality)

## Problem (from real test results)

```
[PAYLOAD] Found Launch class
[PAYLOAD] classLoader field not found on Launch class   ← BUG 1
[PAYLOAD] Using system ClassLoader (classes may not be visible to MC)
[AGENT] Platform: Vanilla | Version: 1.8.9              ← BUG 2
[AGENT] Mapping load FAILED: Base mapping not found
[AGENT] [Chat] MC class not found — wrong classloader
```

Two bugs causing complete Agent failure:

### Bug 1: ClassLoader field signature mismatch

`Launch.classLoader` is declared as `LaunchClassLoader`, not `URLClassLoader`.
JNI `GetStaticFieldID` requires **exact declared type** — no polymorphism.

Current (wrong):
```cpp
jfieldID clField = env->GetStaticFieldID(launchClass, "classLoader", "Ljava/net/URLClassLoader;");
```

Actual Forge declaration:
```java
public static LaunchClassLoader classLoader;  // in net.minecraft.launchwrapper.Launch
```

Fix: Change signature to `"Lnet/minecraft/launchwrapper/LaunchClassLoader;"`.

Also: `g_gameClassLoader` is typed as `jobject` — that's fine, keep it.
And: `addURL()` is defined on `URLClassLoader` (parent of `LaunchClassLoader`), so
`GetMethodID` on `URLClassLoader` class for `addURL` still works — do NOT change that.

### Bug 2: Platform always "Vanilla" on PCL2

PCL2 window title is just "Minecraft 1.8.9" (no "Forge" substring).
`detectPlatform()` in version.cpp checks `versions/` dirs and `mods/` files for "forge",
but PCL2 may not have "forge" in folder names, so it falls back to "Vanilla".

Fix: In `payload.cpp` ThreadProc, after `findGameClassLoader()` Strategy 1 finds
the `Launch` class, we KNOW it's Forge. Update the config file before calling bootstrap:
```cpp
// After Strategy 1 succeeds (found Launch class):
// Update config to "Forge" since we have proof
std::string configPath = std::string(configDir) + "\\doppel-config.properties";
// overwrite platform=Forge
```

This is more reliable than filesystem guessing because we check actual JVM classes.

## What to Change (ONLY payload.cpp)

### File: `injector/src/payload/payload.cpp`

**Change 1** — Fix field signature in `findGameClassLoader()`:

```cpp
// OLD (line ~31):
jfieldID clField = env->GetStaticFieldID(launchClass, "classLoader", "Ljava/net/URLClassLoader;");

// NEW:
jfieldID clField = env->GetStaticFieldID(launchClass, "classLoader", "Lnet/minecraft/launchwrapper/LaunchClassLoader;");
```

**Change 2** — In `findGameClassLoader()`, after Strategy 1 finds the classLoader,
return a flag or write a global so ThreadProc knows to update platform.

Simplest: set a global bool `g_isForge = true` in Strategy 1.

**Change 3** — In `ThreadProc()`, after `findGameClassLoader()` returns and before
`addJarToClasspath()`, if `g_isForge` is true, update the config file:

```cpp
if (g_isForge) {
    // Overwrite platform in config from Vanilla to Forge
    std::string configPath = std::string(configDir) + "\\doppel-config.properties";
    // Read existing config, replace platform line, write back
    // Or simply: rewrite the whole file with platform=Forge
    FILE* cfg = fopen(configPath.c_str(), "w");
    if (cfg) {
        fprintf(cfg, "doppel.platform=Forge\n");
        fprintf(cfg, "doppel.version=1.8.9\n");  // preserve version from original
        fclose(cfg);
        payloadLog("[Doppel] Config updated: platform=Forge (detected Launch class)");
    }
}
```

Wait — we don't have the version string in ThreadProc. Better approach:
just append/overwrite the platform line only. Read the file, find "doppel.platform=",
replace value, write back. Or even simpler: just overwrite the whole config since we know
both values (version is not needed by payload — Agent reads it from config).

Actually simplest reliable approach:
```cpp
if (g_isForge) {
    std::string configPath = std::string(configDir) + "\\doppel-config.properties";
    // Read existing content
    std::ifstream in(configPath);
    std::string content((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    in.close();
    // Replace platform value
    size_t pos = content.find("doppel.platform=");
    if (pos != std::string::npos) {
        size_t lineEnd = content.find('\n', pos);
        if (lineEnd == std::string::npos) lineEnd = content.length();
        content.replace(pos, lineEnd - pos, "doppel.platform=Forge");
    }
    // Write back
    std::ofstream out(configPath);
    out << content;
    out.close();
    payloadLog("[Doppel] Config updated: platform=Forge");
}
```

But we're in C, not C++. payload.cpp uses C-style stdio. Use fopen/fread/fwrite:
```cpp
if (g_isForge) {
    char cfgPath[MAX_PATH];
    _snprintf(cfgPath, sizeof(cfgPath), "%s\\doppel-config.properties", configDir);
    // Read file
    FILE* f = fopen(cfgPath, "r");
    if (f) {
        char buf[4096] = {0};
        size_t len = fread(buf, 1, sizeof(buf) - 1, f);
        fclose(f);
        // Find and replace platform line
        char* pos = strstr(buf, "doppel.platform=");
        if (pos) {
            char* lineEnd = strchr(pos, '\n');
            size_t restLen = lineEnd ? (lineEnd - pos) : (strlen(pos));
            char replacement[] = "doppel.platform=Forge";
            size_t repLen = strlen(replacement);
            // Build new content
            char newBuf[4096];
            size_t before = pos - buf;
            memcpy(newBuf, buf, before);
            memcpy(newBuf + before, replacement, repLen);
            if (lineEnd) {
                strcpy(newBuf + before + repLen, lineEnd);
            } else {
                newBuf[before + repLen] = '\0';
            }
            // Write back
            f = fopen(cfgPath, "w");
            if (f) {
                fputs(newBuf, f);
                fclose(f);
                payloadLog("[Doppel] Config updated: platform=Forge (detected Launch class)");
            }
        }
    }
}
```

Hmm, this is getting complex. Simpler approach — just rewrite the whole config:

```cpp
static void updateConfigPlatform(const char* configDir) {
    char cfgPath[MAX_PATH];
    _snprintf(cfgPath, sizeof(cfgPath), "%s\\doppel-config.properties", configDir);
    // Read existing config to preserve version
    FILE* f = fopen(cfgPath, "r");
    char version[64] = "1.8.9";
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f)) {
            if (strncmp(line, "doppel.version=", 19) == 0) {
                strncpy(version, line + 19, sizeof(version) - 1);
                char* nl = strchr(version, '\n');
                if (nl) *nl = '\0';
                char* cr = strchr(version, '\r');
                if (cr) *cr = '\0';
            }
        }
        fclose(f);
    }
    // Rewrite with Forge
    f = fopen(cfgPath, "w");
    if (f) {
        fprintf(f, "doppel.platform=Forge\n");
        fprintf(f, "doppel.version=%s\n", version);
        fclose(f);
        payloadLog("[Doppel] Config updated: platform=Forge, version=%s", version);
    }
}
```

This is clean and simple. Call it in ThreadProc after findGameClassLoader.

## Summary of ALL changes in payload.cpp

1. Add `static bool g_isForge = false;` global
2. In `findGameClassLoader()` Strategy 1 success: set `g_isForge = true`
3. Add `static void updateConfigPlatform(const char* configDir)` helper function
4. In `ThreadProc()`, after `addJarToClasspath()` returns, call `updateConfigPlatform(configDir)` if `g_isForge`
5. Change field signature from `"Ljava/net/URLClassLoader;"` to `"Lnet/minecraft/launchwrapper/LaunchClassLoader;"`

## What NOT to Change

- **`injector/src/inject.cpp`** — DO NOT TOUCH
- **`injector/src/inject.h`** — DO NOT TOUCH
- **`injector/src/main.cpp`** — DO NOT TOUCH
- **`injector/src/process.cpp`** — DO NOT TOUCH
- **`injector/src/version.cpp`** — DO NOT TOUCH (filesystem detection stays as fallback)
- **`injector/CMakeLists.txt`** — DO NOT TOUCH
- **`agent/src/main/java/io/doppel/agent/Agent.java`** — DO NOT TOUCH
- **Any Kotlin files** — DO NOT TOUCH
- **CI workflow files** — DO NOT TOUCH

## Expected test result after fix

```
[PAYLOAD] findGameClassLoader: trying Forge Launch.classLoader...
[PAYLOAD] Found Launch class
[PAYLOAD] Found Forge Launch.classLoader               ← fixed!
[PAYLOAD] agent.jar added to classpath
[PAYLOAD] Config updated: platform=Forge, version=1.8.9  ← fixed!
[PAYLOAD] Agent class loaded successfully
[PAYLOAD] Agent.bootstrap() completed successfully!

[AGENT] Platform: Forge | Version: 1.8.9              ← fixed!
[AGENT] Mapping load OK                                 ← fixed!
[AGENT] [Chat] Sent: [Doppel] Agent injected! Press R for GUI  ← fixed!
[AGENT] [KeyPoll] Thread started, polling LWJGL2 Keyboard for R key
```

Then pressing R in-game should show `[Doppel] GUI: ON/OFF` in chat.
