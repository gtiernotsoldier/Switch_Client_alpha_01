// payload.cpp — DLL injected into javaw.exe via CreateRemoteThread
// Uses JNI to add agent.jar to classpath and call Agent.bootstrap()
#include <windows.h>
#include <jni.h>
#include <cstdio>
#include <cstring>

HMODULE g_hModule = NULL;
jobject g_gameClassLoader = NULL;

// Named event for cross-process sync with injector
// Event name includes PID so multiple MCs don't collide
static HANDLE g_hDoneEvent = NULL;
static char g_doneEventName[64] = {0};

// ── File logger (same dir as agent.jar, guaranteed writable) ──

static FILE* g_logFile = NULL;

static void payloadLog(const char* fmt, ...) {
    // OutputDebugString (for DebugView)
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    _vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    OutputDebugStringA(buf);
    OutputDebugStringA("\n");

    // Also write to %TEMP%\switchlite-payload.log
    if (!g_logFile) {
        char tempPath[MAX_PATH];
        GetTempPathA(MAX_PATH, tempPath);
        char logPath[MAX_PATH];
        _snprintf(logPath, sizeof(logPath), "%s\\switchlite-payload.log", tempPath);
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
    payloadLog("[SwitchLite] findJVM: looking for jvm.dll...");
    HMODULE hJvm = GetModuleHandleA("jvm.dll");
    if (!hJvm) {
        payloadLog("[SwitchLite] findJVM: jvm.dll not loaded in this process!");
        return NULL;
    }
    payloadLog("[SwitchLite] findJVM: jvm.dll found at %p", hJvm);

    auto pGetVMs = (GetCreatedJavaVMs_t)GetProcAddress(hJvm, "JNI_GetCreatedJavaVMs");
    if (!pGetVMs) {
        payloadLog("[SwitchLite] findJVM: JNI_GetCreatedJavaVMs not found!");
        return NULL;
    }

    JavaVM* vm = NULL;
    jsize count = 0;
    jint result = pGetVMs(&vm, 1, &count);
    payloadLog("[SwitchLite] findJVM: GetCreatedJavaVMs returned %d, count=%d", result, count);
    return (count > 0) ? vm : NULL;
}

// ── findGameClassLoader: Forge → Fabric → System ──

static jobject findGameClassLoader(JNIEnv* env) {
    // Strategy 1: Forge 1.8.9 — net.minecraft.launchwrapper.Launch.classLoader
    payloadLog("[SwitchLite] findGameClassLoader: trying Forge Launch.classLoader...");
    jclass launchClass = env->FindClass("net/minecraft/launchwrapper/Launch");
    if (launchClass) {
        payloadLog("[SwitchLite] Found Launch class");
        jfieldID clField = env->GetStaticFieldID(launchClass, "classLoader", "Ljava/net/URLClassLoader;");
        if (clField) {
            jobject cl = env->GetStaticObjectField(launchClass, clField);
            if (cl) {
                payloadLog("[SwitchLite] Found Forge Launch.classLoader");
                return cl;
            } else {
                payloadLog("[SwitchLite] Launch.classLoader field is NULL");
            }
        } else {
            payloadLog("[SwitchLite] classLoader field not found on Launch class");
        }
    } else {
        payloadLog("[SwitchLite] Launch class not found via FindClass (expected if MC not on system CL)");
    }
    env->ExceptionClear();

    // Strategy 2: Fabric — Thread.currentThread().getContextClassLoader()
    payloadLog("[SwitchLite] findGameClassLoader: trying Thread.contextClassLoader...");
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (threadClass) {
        jmethodID currentThread = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        jmethodID getCtxCl = env->GetMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
        jobject thread = env->CallStaticObjectMethod(threadClass, currentThread);
        if (thread) {
            jobject ctxCl = env->CallObjectMethod(thread, getCtxCl);
            if (ctxCl) {
                payloadLog("[SwitchLite] Using Thread.contextClassLoader");
                return ctxCl;
            }
        }
    }
    env->ExceptionClear();

    // Strategy 3: Fallback — ClassLoader.getSystemClassLoader()
    payloadLog("[SwitchLite] findGameClassLoader: falling back to system ClassLoader");
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSysCl = env->GetStaticMethodID(clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject sysCl = env->CallStaticObjectMethod(clClass, getSysCl);
    payloadLog("[SwitchLite] Using system ClassLoader (classes may not be visible to MC)");
    return sysCl;
}

// ── addJarToClasspath ──

static bool addJarToClasspath(JNIEnv* env, const char* jarPath) {
    char fileUrl[MAX_PATH + 16];
    snprintf(fileUrl, sizeof(fileUrl), "file:///%s", jarPath);
    for (char* p = fileUrl; *p; p++) if (*p == '\\') *p = '/';
    payloadLog("[SwitchLite] addJarToClasspath: jar=%s url=%s", jarPath, fileUrl);

    jclass urlClass = env->FindClass("java/net/URL");
    if (!urlClass) { env->ExceptionClear(); payloadLog("[SwitchLite] URL class not found"); return false; }
    jmethodID urlInit = env->GetMethodID(urlClass, "<init>", "(Ljava/lang/String;)V");
    jstring urlStr = env->NewStringUTF(fileUrl);
    jobject jarUrl = env->NewObject(urlClass, urlInit, urlStr);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }
    payloadLog("[SwitchLite] URL object created");

    g_gameClassLoader = findGameClassLoader(env);
    if (!g_gameClassLoader) { payloadLog("[SwitchLite] Cannot find game classloader"); return false; }

    jclass uclClass = env->FindClass("java/net/URLClassLoader");
    jmethodID addUrl = env->GetMethodID(uclClass, "addURL", "(Ljava/net/URL;)V");
    env->CallVoidMethod(g_gameClassLoader, addUrl, jarUrl);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); payloadLog("[SwitchLite] addURL threw exception"); return false; }
    payloadLog("[SwitchLite] agent.jar added to classpath");
    return true;
}

// ── callAgentBootstrap ──

static bool callAgentBootstrap(JNIEnv* env, const char* configDir) {
    if (!g_gameClassLoader) { payloadLog("[SwitchLite] No classloader available"); return false; }
    payloadLog("[SwitchLite] Calling Agent.bootstrap via loadClass...");

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring agentName = env->NewStringUTF("io.switchlite.agent.Agent");
    jclass agentClass = (jclass)env->CallObjectMethod(g_gameClassLoader, loadClass, agentName);

    if (!agentClass || env->ExceptionCheck()) {
        payloadLog("[SwitchLite] Failed to load Agent class via loadClass!");
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        return false;
    }
    payloadLog("[SwitchLite] Agent class loaded successfully");

    jmethodID bootstrap = env->GetStaticMethodID(agentClass, "bootstrap", "(Ljava/lang/String;)V");
    if (!bootstrap) {
        payloadLog("[SwitchLite] bootstrap(String) method not found on Agent class");
        env->ExceptionDescribe(); env->ExceptionClear();
        return false;
    }
    payloadLog("[SwitchLite] bootstrap method found, invoking...");

    jstring dirStr = env->NewStringUTF(configDir);
    env->CallStaticVoidMethod(agentClass, bootstrap, dirStr);

    if (env->ExceptionCheck()) {
        payloadLog("[SwitchLite] Agent.bootstrap() threw exception!");
        env->ExceptionDescribe(); env->ExceptionClear();
        return false;
    }

    payloadLog("[SwitchLite] Agent.bootstrap() completed successfully!");
    return true;
}

// ── signalDone: notify injector that ThreadProc finished ──

static void signalDone() {
    if (g_hDoneEvent) {
        SetEvent(g_hDoneEvent);
        payloadLog("[SwitchLite] Done event signaled to injector");
    } else {
        payloadLog("[SwitchLite] (done event not available, injector may timeout)");
    }
}

// ── ThreadProc ──

DWORD WINAPI ThreadProc(LPVOID lpParam) {
    const char* configDir = (const char*)lpParam;
    char jarPath[MAX_PATH];
    snprintf(jarPath, sizeof(jarPath), "%s\\switchlite-agent.jar", configDir);
    payloadLog("[SwitchLite] ThreadProc started, configDir=%s, jarPath=%s", configDir, jarPath);

    // Open the done event created by injector (name includes PID)
    if (g_doneEventName[0] != '\0') {
        g_hDoneEvent = OpenEventA(EVENT_MODIFY_STATE, FALSE, g_doneEventName);
        if (g_hDoneEvent) {
            payloadLog("[SwitchLite] Opened done event: %s", g_doneEventName);
        } else {
            payloadLog("[SwitchLite] WARNING: Failed to open done event: %s (err=%d)", g_doneEventName, GetLastError());
        }
    }

    JavaVM* vm = findJVM();
    if (!vm) {
        payloadLog("[SwitchLite] FATAL: JVM not found");
        signalDone();
        return 1;
    }
    payloadLog("[SwitchLite] JVM found: %p", vm);

    JNIEnv* env = NULL;
    jint attachResult = vm->AttachCurrentThread((void**)&env, NULL);
    payloadLog("[SwitchLite] AttachCurrentThread returned %d", attachResult);
    if (attachResult != JNI_OK) {
        payloadLog("[SwitchLite] FATAL: AttachCurrentThread failed (code=%d)", attachResult);
        signalDone();
        return 1;
    }
    payloadLog("[SwitchLite] Thread attached, JNIEnv=%p", env);

    if (!addJarToClasspath(env, jarPath)) {
        payloadLog("[SwitchLite] FATAL: addJarToClasspath failed");
        vm->DetachCurrentThread();
        signalDone();
        return 1;
    }

    if (!callAgentBootstrap(env, configDir)) {
        payloadLog("[SwitchLite] FATAL: callAgentBootstrap failed");
        vm->DetachCurrentThread();
        signalDone();
        return 1;
    }

    payloadLog("[SwitchLite] ========== Payload completed successfully ==========");
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
        snprintf(msg, sizeof(msg), "[SwitchLite] DLL loaded in PID %d, temp=%s", GetCurrentProcessId(), tempPath);
        payloadLog(msg);

        // Pre-build the done event name (injector creates the actual event object).
        // We just record the name here so ThreadProc can OpenEventA() it.
        _snprintf(g_doneEventName, sizeof(g_doneEventName),
                  "SwitchLitePayloadDone_%d", GetCurrentProcessId());
        payloadLog("[SwitchLite] Done event name will be: %s", g_doneEventName);

        HANDLE hThread = CreateThread(NULL, 0, ThreadProc, _strdup(tempPath), 0, NULL);
        if (hThread) {
            payloadLog("[SwitchLite] Worker thread spawned");
            CloseHandle(hThread);
        } else {
            payloadLog("[SwitchLite] FATAL: CreateThread failed, error=%d", GetLastError());
        }
    }
    return TRUE;
}
