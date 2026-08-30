// payload.cpp — DLL injected into javaw.exe via CreateRemoteThread
// Uses JNI to add agent.jar to classpath and call Agent.bootstrap()
// Then uses Windows Attach pipe protocol to trigger agentmain with Instrumentation
#include <windows.h>
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include "attach_pipe.h"

HMODULE g_hModule = NULL;
jobject g_gameClassLoader = NULL;
static bool g_isForge = false;

// Named event for cross-process sync with injector
// Event name includes PID so multiple MCs don't collide
static HANDLE g_hDoneEvent = NULL;
static char g_doneEventName[64] = {0};

// ── File logger (same dir as agent.jar, guaranteed writable) ──

static FILE* g_logFile = NULL;

void payloadLog(const char* fmt, ...) {
    // OutputDebugString (for DebugView)
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    _vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    OutputDebugStringA(buf);
    OutputDebugStringA("\n");

    // Also write to %TEMP%\doppel-payload.log
    if (!g_logFile) {
        char tempPath[MAX_PATH];
        GetTempPathA(MAX_PATH, tempPath);
        char logPath[MAX_PATH];
        _snprintf(logPath, sizeof(logPath), "%s\\doppel-payload.log", tempPath);
        g_logFile = fopen(logPath, "a");
        if (g_logFile) {
            fprintf(g_logFile, "=== payload.dll log ===\n");
            fflush(g_logFile);
        }
    }
    if (g_logFile) {
        SYSTEMTIME st;
        GetLocalTime(&st);
        fprintf(g_logFile, "[%02d:%02d:%02d.%03d] %s\n",
            st.wHour, st.wMinute, st.wSecond, st.wMilliseconds, buf);
        fflush(g_logFile);
    }
}

// ── findJVM: locate jvm.dll in javaw.exe memory ──

typedef jint (JNICALL *GetCreatedJavaVMs_t)(JavaVM**, jsize, jsize*);

static JavaVM* findJVM() {
    payloadLog("[Doppel] findJVM: looking for jvm.dll...");
    HMODULE hJvm = GetModuleHandleA("jvm.dll");
    if (!hJvm) {
        payloadLog("[Doppel] findJVM: jvm.dll not loaded in this process!");
        return NULL;
    }
    payloadLog("[Doppel] findJVM: jvm.dll found at %p", hJvm);

    auto pGetVMs = (GetCreatedJavaVMs_t)GetProcAddress(hJvm, "JNI_GetCreatedJavaVMs");
    if (!pGetVMs) {
        payloadLog("[Doppel] findJVM: JNI_GetCreatedJavaVMs not found!");
        return NULL;
    }

    JavaVM* vm = NULL;
    jsize count = 0;
    jint result = pGetVMs(&vm, 1, &count);
    payloadLog("[Doppel] findJVM: GetCreatedJavaVMs returned %d, count=%d", result, count);
    return (count > 0) ? vm : NULL;
}

// ── findGameClassLoader: Forge → Fabric → System ──

static jobject findGameClassLoader(JNIEnv* env) {
    // Strategy 1: Forge 1.8.9 — net.minecraft.launchwrapper.Launch.classLoader
    payloadLog("[Doppel] findGameClassLoader: trying Forge Launch.classLoader...");
    jclass launchClass = env->FindClass("net/minecraft/launchwrapper/Launch");
    if (launchClass) {
        payloadLog("[Doppel] Found Launch class");
        jfieldID clField = env->GetStaticFieldID(launchClass, "classLoader", "Lnet/minecraft/launchwrapper/LaunchClassLoader;");
        if (clField) {
            jobject cl = env->GetStaticObjectField(launchClass, clField);
            if (cl) {
                payloadLog("[Doppel] Found Forge Launch.classLoader");
                g_isForge = true;
                return cl;
            } else {
                payloadLog("[Doppel] Launch.classLoader field is NULL");
            }
        } else {
            payloadLog("[Doppel] classLoader field not found on Launch class");
        }
    } else {
        payloadLog("[Doppel] Launch class not found via FindClass (expected if MC not on system CL)");
    }
    env->ExceptionClear();

    // Strategy 2: Fabric — Thread.currentThread().getContextClassLoader()
    payloadLog("[Doppel] findGameClassLoader: trying Thread.contextClassLoader...");
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (threadClass) {
        jmethodID currentThread = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        jmethodID getCtxCl = env->GetMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
        jobject thread = env->CallStaticObjectMethod(threadClass, currentThread);
        if (thread) {
            jobject ctxCl = env->CallObjectMethod(thread, getCtxCl);
            if (ctxCl) {
                payloadLog("[Doppel] Using Thread.contextClassLoader");
                return ctxCl;
            }
        }
    }
    env->ExceptionClear();

    // Strategy 3: Fallback — ClassLoader.getSystemClassLoader()
    payloadLog("[Doppel] findGameClassLoader: falling back to system ClassLoader");
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSysCl = env->GetStaticMethodID(clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject sysCl = env->CallStaticObjectMethod(clClass, getSysCl);
    payloadLog("[Doppel] Using system ClassLoader (classes may not be visible to MC)");
    return sysCl;
}

// ── addJarToClasspath ──

static bool addJarToClasspath(JNIEnv* env, const char* jarPath) {
    char fileUrl[MAX_PATH + 16];
    snprintf(fileUrl, sizeof(fileUrl), "file:///%s", jarPath);
    for (char* p = fileUrl; *p; p++) if (*p == '\\') *p = '/';
    payloadLog("[Doppel] addJarToClasspath: jar=%s url=%s", jarPath, fileUrl);

    jclass urlClass = env->FindClass("java/net/URL");
    if (!urlClass) { env->ExceptionClear(); payloadLog("[Doppel] URL class not found"); return false; }
    jmethodID urlInit = env->GetMethodID(urlClass, "<init>", "(Ljava/lang/String;)V");
    jstring urlStr = env->NewStringUTF(fileUrl);
    jobject jarUrl = env->NewObject(urlClass, urlInit, urlStr);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }
    payloadLog("[Doppel] URL object created");

    g_gameClassLoader = findGameClassLoader(env);
    if (!g_gameClassLoader) { payloadLog("[Doppel] Cannot find game classloader"); return false; }

    jclass uclClass = env->FindClass("java/net/URLClassLoader");
    jmethodID addUrl = env->GetMethodID(uclClass, "addURL", "(Ljava/net/URL;)V");
    env->CallVoidMethod(g_gameClassLoader, addUrl, jarUrl);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); payloadLog("[Doppel] addURL threw exception"); return false; }
    payloadLog("[Doppel] agent.jar added to classpath");
    return true;
}

// ── callAgentBootstrap ──

static bool callAgentBootstrap(JNIEnv* env, const char* configDir) {
    if (!g_gameClassLoader) { payloadLog("[Doppel] No classloader available"); return false; }
    payloadLog("[Doppel] Calling Agent.bootstrap via loadClass...");

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring agentName = env->NewStringUTF("io.doppel.agent.Agent");
    jclass agentClass = (jclass)env->CallObjectMethod(g_gameClassLoader, loadClass, agentName);

    if (!agentClass || env->ExceptionCheck()) {
        payloadLog("[Doppel] Failed to load Agent class via loadClass!");
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        return false;
    }
    payloadLog("[Doppel] Agent class loaded successfully");

    jmethodID bootstrap = env->GetStaticMethodID(agentClass, "bootstrap", "(Ljava/lang/String;)V");
    if (!bootstrap) {
        payloadLog("[Doppel] bootstrap(String) method not found on Agent class");
        env->ExceptionDescribe(); env->ExceptionClear();
        return false;
    }
    payloadLog("[Doppel] bootstrap method found, invoking...");

    jstring dirStr = env->NewStringUTF(configDir);
    env->CallStaticVoidMethod(agentClass, bootstrap, dirStr);

    if (env->ExceptionCheck()) {
        payloadLog("[Doppel] Agent.bootstrap() threw exception!");
        env->ExceptionDescribe(); env->ExceptionClear();
        return false;
    }

    payloadLog("[Doppel] Agent.bootstrap() completed successfully!");
    return true;
}

// ── signalDone: notify injector that ThreadProc finished ──

static void signalDone() {
    if (g_hDoneEvent) {
        SetEvent(g_hDoneEvent);
        payloadLog("[Doppel] Done event signaled to injector");
    } else {
        payloadLog("[Doppel] (done event not available, injector may timeout)");
    }
}

// ── updateConfigPlatform: fix Vanilla→Forge when Launch class detected ──
//
// This function is ONLY called when the Launch class was detected in the
// target JVM (g_isForge). Its contract is therefore absolute: after it runs,
// the config MUST say platform=Forge — the injector's filesystem detection
// (mods/versions folder scan) can legitimately misdetect a Forge install as
// Vanilla/Fabric/Unknown (e.g. third-party launchers whose versions/<v>
// folder is named "1.8.9" with forge only inside the JSON).
//
// RENAME REGRESSION FIX (2026-08-31): the SwitchLite→Doppel rename shortened
// the key prefixes ("switchlite.platform=" 20 chars → "doppel.platform=" 16)
// but left the hardcoded strncmp length constants at 19/20. strncmp(prefix,
// n) with n > strlen(prefix) only matches when the line is EXACTLY the
// prefix, so every comparison here silently failed. The stale
// "doppel.platform=Vanilla" line was preserved while a fresh
// "doppel.platform=Forge" line was PREPENDED — and java.util.Properties
// keeps the LAST duplicate key, so the agent read platform=Vanilla, skipped
// ForgeBootstrap.init() entirely and fell back to the smaller AgentBridge
// registration path (35 instead of 43 modules, HUD never enabled, forge:*
// mappings unresolved → blank HUD).
//
// Prefix lengths are now computed with strlen() so they can never drift
// again. Stale platform/version lines are DROPPED and rewritten in one
// canonical position — never duplicated.

static void updateConfigPlatform(const char* configDir) {
    char cfgPath[MAX_PATH];
    _snprintf(cfgPath, sizeof(cfgPath), "%s\\doppel-config.properties", configDir);

    const char* platformPrefix = "doppel.platform=";
    const char* versionPrefix  = "doppel.version=";
    const size_t platformPrefixLen = strlen(platformPrefix);
    const size_t versionPrefixLen  = strlen(versionPrefix);

    // Read all lines EXCEPT stale platform/version entries.
    char lines[64][256];
    int lineCount = 0;
    char version[64] = "1.8.9";
    bool foundVersion = false;

    FILE* f = fopen(cfgPath, "r");
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f) && lineCount < 64) {
            if (strncmp(line, platformPrefix, platformPrefixLen) == 0) {
                continue; // drop stale platform lines — fresh one is written below
            }
            if (strncmp(line, versionPrefix, versionPrefixLen) == 0) {
                const char* p = line + versionPrefixLen;
                strncpy(version, p, sizeof(version) - 1);
                version[sizeof(version) - 1] = '\0';
                char* nl = strchr(version, '\n'); if (nl) *nl = '\0';
                char* cr = strchr(version, '\r'); if (cr) *cr = '\0';
                // Legacy artifact: some older payloads wrote "=1.8.9".
                if (version[0] == '=') {
                    memmove(version, version + 1, sizeof(version) - 1);
                }
                foundVersion = true;
                continue; // rewritten at the end in canonical form
            }
            strncpy(lines[lineCount], line, sizeof(lines[lineCount]) - 1);
            lines[lineCount][sizeof(lines[lineCount]) - 1] = '\0';
            lineCount++;
        }
        fclose(f);
    }

    if (version[0] == '\0') {
        // Empty version in the file — fall back to the safe default.
        _snprintf(version, sizeof(version), "1.8.9");
        foundVersion = false;
    }

    // Write back: canonical platform first, preserved lines, canonical version last.
    f = fopen(cfgPath, "w");
    if (f) {
        fprintf(f, "doppel.platform=Forge\n");
        for (int i = 0; i < lineCount; i++) {
            fputs(lines[i], f);
        }
        fprintf(f, "doppel.version=%s\n", version);
        fclose(f);
        payloadLog("[Doppel] Config rewritten: platform=Forge, version=%s%s (%d other lines preserved)",
                   version, foundVersion ? "" : " (default)", lineCount);
    } else {
        payloadLog("[Doppel] WARNING: could not rewrite config %s (errno=%d)", cfgPath, errno);
    }
}

// ── ThreadProc ──

DWORD WINAPI ThreadProc(LPVOID lpParam) {
    const char* configDir = (const char*)lpParam;
    char jarPath[MAX_PATH];
    snprintf(jarPath, sizeof(jarPath), "%s\\doppel-agent.jar", configDir);
    payloadLog("[Doppel] ThreadProc started, configDir=%s, jarPath=%s", configDir, jarPath);

    // Open the done event created by injector (name includes PID)
    if (g_doneEventName[0] != '\0') {
        g_hDoneEvent = OpenEventA(EVENT_MODIFY_STATE, FALSE, g_doneEventName);
        if (g_hDoneEvent) {
            payloadLog("[Doppel] Opened done event: %s", g_doneEventName);
        } else {
            payloadLog("[Doppel] WARNING: Failed to open done event: %s (err=%d)", g_doneEventName, GetLastError());
        }
    }

    JavaVM* vm = findJVM();
    if (!vm) {
        payloadLog("[Doppel] FATAL: JVM not found");
        signalDone();
        return 1;
    }
    payloadLog("[Doppel] JVM found: %p", vm);

    JNIEnv* env = NULL;
    jint attachResult = vm->AttachCurrentThread((void**)&env, NULL);
    payloadLog("[Doppel] AttachCurrentThread returned %d", attachResult);
    if (attachResult != JNI_OK) {
        payloadLog("[Doppel] FATAL: AttachCurrentThread failed (code=%d)", attachResult);
        signalDone();
        return 1;
    }
    payloadLog("[Doppel] Thread attached, JNIEnv=%p", env);

    if (!addJarToClasspath(env, jarPath)) {
        payloadLog("[Doppel] FATAL: addJarToClasspath failed");
        vm->DetachCurrentThread();
        signalDone();
        return 1;
    }

    // Fix platform detection: if Launch class found, it's Forge
    if (g_isForge) {
        updateConfigPlatform(configDir);
    }

    if (!callAgentBootstrap(env, configDir)) {
        payloadLog("[Doppel] FATAL: callAgentBootstrap failed");
        vm->DetachCurrentThread();
        signalDone();
        return 1;
    }

    // ── Step 2: Use JVM_EnqueueOperation to get Instrumentation ──
    //
    // Agent.bootstrap() initializes everything (config, mappings, modules, threads)
    // BUT it receives inst=null via JNI, so Transformer.install(null) fails.
    //
    // On Windows, the AttachListener thread is ALWAYS running at JVM startup
    // (unlike Linux where it's lazy-initialized via SIGQUIT). We call
    // JVM_EnqueueOperation — an exported function from jvm.dll — to enqueue
    // a "load" command. The AttachListener dequeues it, loads the agent,
    // and calls agentmain("jni-attach", inst) with a real Instrumentation.
    //
    // This is the same mechanism that JDK's WindowsVirtualMachine uses, but
    // since we're already inside the process, we can call JVM_EnqueueOperation
    // directly — no CreateRemoteThread injection needed, no tools.jar needed
    // (Minecraft runs on JRE, not JDK).

    payloadLog("[Doppel] Using JVM_EnqueueOperation to obtain Instrumentation...");
    int pid = GetCurrentProcessId();
    bool attachOk = attachAndLoadAgent(pid, jarPath, "jni-attach");

    if (attachOk) {
        payloadLog("[Doppel] JVM_EnqueueOperation succeeded — agentmain called with Instrumentation");
    } else {
        payloadLog("[Doppel] WARNING: JVM_EnqueueOperation failed — rendering pipeline may not work");
        payloadLog("[Doppel] Agent is running but Transformer hook is NOT installed");
        payloadLog("[Doppel] HUD overlay will not appear until hook is installed");
    }

    payloadLog("[Doppel] ========== Payload completed successfully ==========");
    vm->DetachCurrentThread();
    signalDone();
    return 0;
}

// ── DllMain ──

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID lpReserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_hModule = hModule;
        DisableThreadLibraryCalls(hModule);
        char tempPath[MAX_PATH];
        GetTempPathA(MAX_PATH, tempPath);
        size_t len = strlen(tempPath);
        if (len > 0 && tempPath[len - 1] == '\\') tempPath[len - 1] = '\0';

        char msg[512];
        snprintf(msg, sizeof(msg), "[Doppel] DLL loaded in PID %d, temp=%s", GetCurrentProcessId(), tempPath);
        payloadLog(msg);

        // Pre-build the done event name (injector creates the actual event object).
        // We just record the name here so ThreadProc can OpenEventA() it.
        _snprintf(g_doneEventName, sizeof(g_doneEventName),
                  "DoppelPayloadDone_%d", GetCurrentProcessId());
        payloadLog("[Doppel] Done event name will be: %s", g_doneEventName);

        HANDLE hThread = CreateThread(NULL, 0, ThreadProc, _strdup(tempPath), 0, NULL);
        if (hThread) {
            payloadLog("[Doppel] Worker thread spawned");
            CloseHandle(hThread);
        } else {
            payloadLog("[Doppel] FATAL: CreateThread failed, error=%d", GetLastError());
        }
    }
    return TRUE;
}
