// payload.cpp — DLL injected into javaw.exe via CreateRemoteThread
// Uses JNI to add agent.jar to classpath and call Agent.bootstrap()
#include <windows.h>
#include <jni.h>
#include <cstdio>

HMODULE g_hModule = NULL;

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

// ── addJarToClasspath ──

static bool addJarToClasspath(JNIEnv* env, const char* jarPath) {
    char fileUrl[MAX_PATH + 16];
    snprintf(fileUrl, sizeof(fileUrl), "file:///%s", jarPath);
    for (char* p = fileUrl; *p; p++) if (*p == '\\') *p = '/';

    jclass urlClass = env->FindClass("java/net/URL");
    if (!urlClass) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }
    jmethodID urlInit = env->GetMethodID(urlClass, "<init>", "(Ljava/lang/String;)V");
    jstring urlStr = env->NewStringUTF(fileUrl);
    jobject jarUrl = env->NewObject(urlClass, urlInit, urlStr);

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSysCL = env->GetStaticMethodID(clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject sysCL = env->CallStaticObjectMethod(clClass, getSysCL);

    jclass uclClass = env->FindClass("java/net/URLClassLoader");
    jmethodID addUrl = env->GetMethodID(uclClass, "addURL", "(Ljava/net/URL;)V");
    env->CallVoidMethod(sysCL, addUrl, jarUrl);

    return !env->ExceptionCheck();
}

// ── callAgentBootstrap ──

static bool callAgentBootstrap(JNIEnv* env, const char* configDir) {
    jclass agentClass = env->FindClass("io/switchlite/agent/Agent");
    if (!agentClass) { env->ExceptionDescribe(); return false; }
    jmethodID bootstrap = env->GetStaticMethodID(agentClass, "bootstrap", "(Ljava/lang/String;)V");
    if (!bootstrap) { env->ExceptionDescribe(); return false; }
    jstring dirStr = env->NewStringUTF(configDir);
    env->CallStaticVoidMethod(agentClass, bootstrap, dirStr);
    return !env->ExceptionCheck();
}

// ── ThreadProc ──

DWORD WINAPI ThreadProc(LPVOID lpParam) {
    const char* configDir = (const char*)lpParam;
    char jarPath[MAX_PATH];
    snprintf(jarPath, sizeof(jarPath), "%s\\switchlite-agent.jar", configDir);

    JavaVM* vm = findJVM();
    if (!vm) { OutputDebugStringA("[SwitchLite] Failed to find JVM\n"); return 1; }

    JNIEnv* env = NULL;
    if (vm->AttachCurrentThread((void**)&env, NULL) != JNI_OK) {
        OutputDebugStringA("[SwitchLite] Failed to attach thread\n");
        return 1;
    }

    if (!addJarToClasspath(env, jarPath)) {
        OutputDebugStringA("[SwitchLite] Failed to add agent.jar to classpath\n");
        vm->DetachCurrentThread();
        return 1;
    }

    if (!callAgentBootstrap(env, configDir)) {
        OutputDebugStringA("[SwitchLite] Agent.bootstrap() failed\n");
        vm->DetachCurrentThread();
        return 1;
    }

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
        HANDLE hThread = CreateThread(NULL, 0, ThreadProc, _strdup(tempPath), 0, NULL);
        if (hThread) CloseHandle(hThread);
    }
    return TRUE;
}
