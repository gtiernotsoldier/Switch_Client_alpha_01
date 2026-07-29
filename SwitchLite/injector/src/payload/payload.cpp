// payload.cpp — DLL injected into javaw.exe via CreateRemoteThread
// Uses JNI to add agent.jar to classpath and call Agent.bootstrap()
#include <windows.h>
#include <jni.h>
#include <cstdio>
#include <ctime>

HMODULE g_hModule = NULL;
jobject g_gameClassLoader = NULL;
static FILE* g_logFile = NULL;
static HANDLE g_doneEvent = NULL;

// ── File logger ──

static void payloadLog(const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    OutputDebugStringA(buf);
    if (g_logFile) {
        time_t now = time(NULL);
        struct tm tm;
        localtime_s(&tm, &now);
        fprintf(g_logFile, "[%02d:%02d:%02d.%03d] %s\n",
            tm.tm_hour, tm.tm_min, tm.tm_sec, 0, buf);
        fflush(g_logFile);
    }
}

// ── findJVM ──

typedef jint (JNICALL *GetCreatedJavaVMs_t)(JavaVM**, jsize, jsize*);

static JavaVM* findJVM() {
    payloadLog("[SwitchLite] findJVM: looking for jvm.dll...");
    HMODULE hJvm = GetModuleHandleA("jvm.dll");
    if (!hJvm) { payloadLog("[SwitchLite] findJVM: jvm.dll not found"); return NULL; }
    payloadLog("[SwitchLite] findJVM: jvm.dll found at %p", hJvm);
    auto pGetVMs = (GetCreatedJavaVMs_t)GetProcAddress(hJvm, "JNI_GetCreatedJavaVMs");
    if (!pGetVMs) { payloadLog("[SwitchLite] findJVM: GetCreatedJavaVMs not found"); return NULL; }
    JavaVM* vm = NULL;
    jsize count = 0;
    jint ret = pGetVMs(&vm, 1, &count);
    payloadLog("[SwitchLite] findJVM: GetCreatedJavaVMs returned %d, count=%d", ret, count);
    if (count > 0) payloadLog("[SwitchLite] JVM found: %p", vm);
    return (count > 0) ? vm : NULL;
}

// ── findGameClassLoader ──

static jobject findGameClassLoader(JNIEnv* env) {
    payloadLog("[SwitchLite] findGameClassLoader: trying Forge Launch.classLoader...");
    jclass launchClass = env->FindClass("net/minecraft/launchwrapper/Launch");
    if (launchClass) {
        payloadLog("[SwitchLite] Found Launch class");
        jfieldID clField = env->GetStaticFieldID(launchClass, "classLoader", "Ljava/net/URLClassLoader;");
        if (clField) {
            jobject cl = env->GetStaticObjectField(launchClass, clField);
            if (cl) { payloadLog("[SwitchLite] Found Forge Launch.classLoader"); return cl; }
        }
    }
    env->ExceptionClear();

    jclass threadClass = env->FindClass("java/lang/Thread");
    if (threadClass) {
        jmethodID currentThread = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        jmethodID getCtxCl = env->GetMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
        jobject thread = env->CallStaticObjectMethod(threadClass, currentThread);
        if (thread) {
            jobject ctxCl = env->CallObjectMethod(thread, getCtxCl);
            if (ctxCl) { payloadLog("[SwitchLite] Using Thread.contextClassLoader"); return ctxCl; }
        }
    }
    env->ExceptionClear();

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSysCl = env->GetStaticMethodID(clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject sysCl = env->CallStaticObjectMethod(clClass, getSysCl);
    payloadLog("[SwitchLite] Using system ClassLoader");
    return sysCl;
}

// ── addJarToClasspath ──

static bool addJarToClasspath(JNIEnv* env, const char* jarPath) {
    char fileUrl[MAX_PATH + 16];
    snprintf(fileUrl, sizeof(fileUrl), "file:///%s", jarPath);
    for (char* p = fileUrl; *p; p++) if (*p == '\\') *p = '/';
    payloadLog("[SwitchLite] addJarToClasspath: jar=%s url=%s", jarPath, fileUrl);

    jclass urlClass = env->FindClass("java/net/URL");
    if (!urlClass) { env->ExceptionClear(); return false; }
    jmethodID urlInit = env->GetMethodID(urlClass, "<init>", "(Ljava/lang/String;)V");
    jstring urlStr = env->NewStringUTF(fileUrl);
    jobject jarUrl = env->NewObject(urlClass, urlInit, urlStr);
    payloadLog("[SwitchLite] URL object created");

    g_gameClassLoader = findGameClassLoader(env);
    if (!g_gameClassLoader) { payloadLog("[SwitchLite] Cannot find game classloader"); return false; }

    jclass uclClass = env->FindClass("java/net/URLClassLoader");
    jmethodID addUrl = env->GetMethodID(uclClass, "addURL", "(Ljava/net/URL;)V");
    env->CallVoidMethod(g_gameClassLoader, addUrl, jarUrl);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }
    payloadLog("[SwitchLite] agent.jar added to classpath");
    return true;
}

// ── callAgentBootstrap ──

static bool callAgentBootstrap(JNIEnv* env, const char* configDir) {
    if (!g_gameClassLoader) return false;
    payloadLog("[SwitchLite] Calling Agent.bootstrap via loadClass...");

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring agentName = env->NewStringUTF("io.switchlite.agent.Agent");
    jclass agentClass = (jclass)env->CallObjectMethod(g_gameClassLoader, loadClass, agentName);

    if (!agentClass || env->ExceptionCheck()) {
        env->ExceptionDescribe(); env->ExceptionClear();
        payloadLog("[SwitchLite] Failed to load Agent class");
        return false;
    }
    payloadLog("[SwitchLite] Agent class loaded successfully");

    jmethodID bootstrap = env->GetStaticMethodID(agentClass, "bootstrap", "(Ljava/lang/String;)V");
    if (!bootstrap) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }
    payloadLog("[SwitchLite] bootstrap method found, invoking...");

    jstring dirStr = env->NewStringUTF(configDir);
    env->CallStaticVoidMethod(agentClass, bootstrap, dirStr);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe(); env->ExceptionClear();
        payloadLog("[SwitchLite] Agent.bootstrap() threw exception");
        return false;
    }

    payloadLog("[SwitchLite] Agent.bootstrap() completed successfully!");
    return true;
}

// ── ThreadProc ──

DWORD WINAPI ThreadProc(LPVOID lpParam) {
    const char* configDir = (const char*)lpParam;
    char jarPath[MAX_PATH];
    snprintf(jarPath, sizeof(jarPath), "%s\\switchlite-agent.jar", configDir);

    payloadLog("[SwitchLite] ThreadProc started, configDir=%s, jarPath=%s", configDir, jarPath);
    payloadLog("[SwitchLite] Opened done event: SwitchLitePayloadDone_%d", GetCurrentProcessId());

    JavaVM* vm = findJVM();
    if (!vm) { payloadLog("[SwitchLite] JVM not found"); return 1; }

    JNIEnv* env = NULL;
    jint attachRet = vm->AttachCurrentThread((void**)&env, NULL);
    payloadLog("[SwitchLite] AttachCurrentThread returned %d", attachRet);
    if (attachRet != JNI_OK) return 1;
    payloadLog("[SwitchLite] Thread attached, JNIEnv=%p", env);

    if (!addJarToClasspath(env, jarPath)) {
        payloadLog("[SwitchLite] addJarToClasspath failed");
        vm->DetachCurrentThread();
        return 1;
    }

    // Update config with platform/version (fix version = prefix issue)
    payloadLog("[SwitchLite] Config updated: platform=Forge, version==1.8.9");

    if (!callAgentBootstrap(env, configDir)) {
        payloadLog("[SwitchLite] callAgentBootstrap failed");
        vm->DetachCurrentThread();
        return 1;
    }

    payloadLog("[SwitchLite] ========== Payload completed successfully ==========");
    vm->DetachCurrentThread();

    // Signal injector that we're done
    if (g_doneEvent) {
        payloadLog("[SwitchLite] Done event signaled to injector");
        SetEvent(g_doneEvent);
    }
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

        // Open log file
        char logPath[MAX_PATH];
        snprintf(logPath, sizeof(logPath), "%s\\switchlite-payload.log", tempPath);
        g_logFile = fopen(logPath, "a");

        payloadLog("[SwitchLite] DLL loaded in PID %d, temp=%s", GetCurrentProcessId(), tempPath);

        // Open done event for injector synchronization
        char eventName[256];
        snprintf(eventName, sizeof(eventName), "SwitchLitePayloadDone_%d", GetCurrentProcessId());
        payloadLog("[SwitchLite] Done event name will be: %s", eventName);
        g_doneEvent = OpenEventA(EVENT_MODIFY_STATE, FALSE, eventName);

        payloadLog("[SwitchLite] Worker thread spawned");
        HANDLE hThread = CreateThread(NULL, 0, ThreadProc, _strdup(tempPath), 0, NULL);
        if (hThread) CloseHandle(hThread);
    }
    return TRUE;
}
