// payload.cpp — DLL injected into javaw.exe via CreateRemoteThread
// Uses JNI to add agent.jar to classpath and call Agent.bootstrap()
#include <windows.h>
#include <jni.h>
#include <cstdio>

HMODULE g_hModule = NULL;
jobject g_gameClassLoader = NULL;

// ── findJVM: locate jvm.dll in javaw.exe memory ──

typedef jint (JNICALL *GetCreatedJavaVMs_t)(JavaVM**, jsize, jsize*);

static JavaVM* findJVM() {
    HMODULE hJvm = GetModuleHandleA("jvm.dll");
    if (!hJvm) return NULL;
    auto pGetVMs = (GetCreatedJavaVMs_t)GetProcAddress(hJvm, "JNI_GetCreatedJavaVMs");
    if (!pGetVMs) return NULL;
    JavaVM* vm = NULL;
    jsize count = 0;
    pGetVMs(&vm, 1, &count);
    return (count > 0) ? vm : NULL;
}

// ── findGameClassLoader: Forge → Fabric → System ──

static jobject findGameClassLoader(JNIEnv* env) {
    // Strategy 1: Forge 1.8.9 — net.minecraft.launchwrapper.Launch.classLoader
    jclass launchClass = env->FindClass("net/minecraft/launchwrapper/Launch");
    if (launchClass) {
        jfieldID clField = env->GetStaticFieldID(launchClass, "classLoader", "Ljava/net/URLClassLoader;");
        if (clField) {
            jobject cl = env->GetStaticObjectField(launchClass, clField);
            if (cl) { OutputDebugStringA("[SwitchLite] Found Forge Launch.classLoader\n"); return cl; }
        }
    }
    env->ExceptionClear();

    // Strategy 2: Fabric — Thread.currentThread().getContextClassLoader()
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (threadClass) {
        jmethodID currentThread = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        jmethodID getCtxCl = env->GetMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
        jobject thread = env->CallStaticObjectMethod(threadClass, currentThread);
        if (thread) {
            jobject ctxCl = env->CallObjectMethod(thread, getCtxCl);
            if (ctxCl) { OutputDebugStringA("[SwitchLite] Using Thread.contextClassLoader\n"); return ctxCl; }
        }
    }
    env->ExceptionClear();

    // Strategy 3: Fallback — ClassLoader.getSystemClassLoader()
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSysCl = env->GetStaticMethodID(clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject sysCl = env->CallStaticObjectMethod(clClass, getSysCl);
    OutputDebugStringA("[SwitchLite] Using system ClassLoader\n");
    return sysCl;
}

// ── addJarToClasspath ──

static bool addJarToClasspath(JNIEnv* env, const char* jarPath) {
    char fileUrl[MAX_PATH + 16];
    snprintf(fileUrl, sizeof(fileUrl), "file:///%s", jarPath);
    for (char* p = fileUrl; *p; p++) if (*p == '\\') *p = '/';

    jclass urlClass = env->FindClass("java/net/URL");
    if (!urlClass) { env->ExceptionClear(); return false; }
    jmethodID urlInit = env->GetMethodID(urlClass, "<init>", "(Ljava/lang/String;)V");
    jstring urlStr = env->NewStringUTF(fileUrl);
    jobject jarUrl = env->NewObject(urlClass, urlInit, urlStr);

    g_gameClassLoader = findGameClassLoader(env);
    if (!g_gameClassLoader) { OutputDebugStringA("[SwitchLite] Cannot find game classloader\n"); return false; }

    jclass uclClass = env->FindClass("java/net/URLClassLoader");
    jmethodID addUrl = env->GetMethodID(uclClass, "addURL", "(Ljava/net/URL;)V");
    env->CallVoidMethod(g_gameClassLoader, addUrl, jarUrl);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }
    OutputDebugStringA("[SwitchLite] agent.jar added to classpath\n");
    return true;
}

// ── callAgentBootstrap ──

static bool callAgentBootstrap(JNIEnv* env, const char* configDir) {
    if (!g_gameClassLoader) return false;

    // Use classLoader.loadClass() — NOT FindClass() (FindClass uses system CL, not Forge's CL)
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring agentName = env->NewStringUTF("io.switchlite.agent.Agent");
    jclass agentClass = (jclass)env->CallObjectMethod(g_gameClassLoader, loadClass, agentName);

    if (!agentClass || env->ExceptionCheck()) {
        env->ExceptionDescribe(); env->ExceptionClear();
        OutputDebugStringA("[SwitchLite] Failed to load Agent class\n");
        return false;
    }

    jmethodID bootstrap = env->GetStaticMethodID(agentClass, "bootstrap", "(Ljava/lang/String;)V");
    if (!bootstrap) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }

    jstring dirStr = env->NewStringUTF(configDir);
    env->CallStaticVoidMethod(agentClass, bootstrap, dirStr);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe(); env->ExceptionClear();
        OutputDebugStringA("[SwitchLite] Agent.bootstrap() threw exception\n");
        return false;
    }

    OutputDebugStringA("[SwitchLite] Agent.bootstrap() completed\n");
    return true;
}

// ── ThreadProc ──

DWORD WINAPI ThreadProc(LPVOID lpParam) {
    const char* configDir = (const char*)lpParam;
    char jarPath[MAX_PATH];
    snprintf(jarPath, sizeof(jarPath), "%s\\switchlite-agent.jar", configDir);
    OutputDebugStringA("[SwitchLite] ThreadProc started\n");

    JavaVM* vm = findJVM();
    if (!vm) { OutputDebugStringA("[SwitchLite] JVM not found\n"); return 1; }
    OutputDebugStringA("[SwitchLite] JVM found\n");

    JNIEnv* env = NULL;
    if (vm->AttachCurrentThread((void**)&env, NULL) != JNI_OK) {
        OutputDebugStringA("[SwitchLite] AttachCurrentThread failed\n");
        return 1;
    }
    OutputDebugStringA("[SwitchLite] Thread attached\n");

    if (!addJarToClasspath(env, jarPath)) {
        OutputDebugStringA("[SwitchLite] addJarToClasspath failed\n");
        vm->DetachCurrentThread();
        return 1;
    }

    if (!callAgentBootstrap(env, configDir)) {
        OutputDebugStringA("[SwitchLite] callAgentBootstrap failed\n");
        vm->DetachCurrentThread();
        return 1;
    }

    OutputDebugStringA("[SwitchLite] Payload completed\n");
    vm->DetachCurrentThread();
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
        snprintf(msg, sizeof(msg), "[SwitchLite] DLL loaded, temp=%s\n", tempPath);
        OutputDebugStringA(msg);

        HANDLE hThread = CreateThread(NULL, 0, ThreadProc, _strdup(tempPath), 0, NULL);
        if (hThread) CloseHandle(hThread);
    }
    return TRUE;
}
