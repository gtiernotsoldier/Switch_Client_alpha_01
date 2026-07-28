// payload.cpp — DLL injected into javaw.exe via CreateRemoteThread
// Uses JNI to add agent.jar to classpath and call Agent.bootstrap()
#include <windows.h>
#include <cstdio>

// JNI type stubs — avoid depending on JDK jni.h in CI
typedef void*  JavaVM;
typedef void*  JNIEnv;
struct JNIInvokeInterface_ {
    void* reserved0; void* reserved1; void* reserved2;
    int  (*DestroyJavaVM)(JavaVM*);
    int  (*AttachCurrentThread)(JavaVM*, void**, void*);
    int  (*DetachCurrentThread)(JavaVM*);
    int  (*GetEnv)(JavaVM*, void**, int);
    int  (*AttachCurrentThreadAsDaemon)(JavaVM*, void**, void*);
};
struct JavaVM_ { const JNIInvokeInterface_* functions; };

typedef int (JNICALL *GetCreatedJavaVMs_t)(JavaVM**, int, int*);

static HMODULE g_hModule = NULL;

// ── findJVM: locate jvm.dll in javaw.exe memory ──

static JavaVM* findJVM() {
    HMODULE hJvm = GetModuleHandleA("jvm.dll");
    if (!hJvm) return NULL;

    auto pGetVMs = (GetCreatedJavaVMs_t)GetProcAddress(hJvm, "JNI_GetCreatedJavaVMs");
    if (!pGetVMs) return NULL;

    JavaVM* vm = NULL;
    int count = 0;
    pGetVMs(&vm, 1, &count);
    return (count > 0) ? vm : NULL;
}

// ── JNI helper wrappers ──

static int AttachThread(JavaVM* vm, void** penv) {
    return vm->functions->AttachCurrentThread(vm, penv, NULL);
}
static int DetachThread(JavaVM* vm) {
    return vm->functions->DetachCurrentThread(vm);
}

static void* FindClass(JNIEnv* env, const char* name) {
    // Using raw JNIEnv* vtable access (offset 6 = FindClass)
    typedef void* (JNICALL *FindClass_t)(JNIEnv*, const char*);
    FindClass_t fn = *((FindClass_t*)((char*)*(void**)env + 6 * sizeof(void*)));
    void* cls = fn(env, name);
    if (!cls) {
        // ExceptionDescribe via vtable offset 12
        typedef void (JNICALL *ExceptDesc_t)(JNIEnv*);
        ExceptDesc_t ed = *((ExceptDesc_t*)((char*)*(void**)env + 12 * sizeof(void*)));
        ed(env);
        // ExceptionClear via vtable offset 13
        typedef void (JNICALL *ExceptClear_t)(JNIEnv*);
        ExceptClear_t ec = *((ExceptClear_t*)((char*)*(void**)env + 13 * sizeof(void*)));
        ec(env);
    }
    return cls;
}

static void* GetStaticMethodID(JNIEnv* env, void* cls, const char* name, const char* sig) {
    typedef void* (JNICALL *GetSMID_t)(JNIEnv*, void*, const char*, const char*);
    GetSMID_t fn = *((GetSMID_t*)((char*)*(void**)env + 113 * sizeof(void*)));
    return fn(env, cls, name, sig);
}

static void* GetMethodID(JNIEnv* env, void* cls, const char* name, const char* sig) {
    typedef void* (JNICALL *GetMID_t)(JNIEnv*, void*, const char*, const char*);
    GetMID_t fn = *((GetMID_t*)((char*)*(void**)env + 33 * sizeof(void*)));
    return fn(env, cls, name, sig);
}

static void* NewObject(JNIEnv* env, void* cls, void* mid, ...) {
    typedef void* (JNICALL *NewObject_t)(JNIEnv*, void*, void*, ...);
    NewObject_t fn = *((NewObject_t*)((char*)*(void**)env + 43 * sizeof(void*)));
    return fn(env, cls, mid);
}

static void* NewStringUTF(JNIEnv* env, const char* str) {
    typedef void* (JNICALL *NewStringUTF_t)(JNIEnv*, const char*);
    NewStringUTF_t fn = *((NewStringUTF_t*)((char*)*(void**)env + 164 * sizeof(void*)));
    return fn(env, str);
}

static void* CallStaticObjectMethod(JNIEnv* env, void* cls, void* mid, ...) {
    typedef void* (JNICALL *CSOM_t)(JNIEnv*, void*, void*, ...);
    CSOM_t fn = *((CSOM_t*)((char*)*(void**)env + 149 * sizeof(void*)));
    return fn(env, cls, mid);
}

static void CallStaticVoidMethod(JNIEnv* env, void* cls, void* mid, ...) {
    typedef void (JNICALL *CSVM_t)(JNIEnv*, void*, void*, ...);
    CSVM_t fn = *((CSVM_t*)((char*)*(void**)env + 151 * sizeof(void*)));
    fn(env, cls, mid);
}

static void CallVoidMethod(JNIEnv* env, void* obj, void* mid, ...) {
    typedef void (JNICALL *CVM_t)(JNIEnv*, void*, void*, ...);
    CVM_t fn = *((CVM_t*)((char*)*(void**)env + 56 * sizeof(void*)));
    fn(env, obj, mid);
}

// ── addJarToClasspath ──

static bool addJarToClasspath(JNIEnv* env, const char* jarPath) {
    char fileUrl[MAX_PATH + 16];
    snprintf(fileUrl, sizeof(fileUrl), "file:///%s", jarPath);
    for (char* p = fileUrl; *p; p++) if (*p == '\\') *p = '/';

    void* urlClass = FindClass(env, "java/net/URL");
    if (!urlClass) return false;
    void* urlInit = GetMethodID(env, urlClass, "<init>", "(Ljava/lang/String;)V");
    void* jarUrlStr = NewStringUTF(env, fileUrl);
    void* jarUrl = NewObject(env, urlClass, urlInit, jarUrlStr);

    // Optional: fix va_args calling convention on x64
    void* clClass = FindClass(env, "java/lang/ClassLoader");
    void* getSysCL = GetStaticMethodID(env, clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    void* sysCL = CallStaticObjectMethod(env, clClass, getSysCL);

    void* uclClass = FindClass(env, "java/net/URLClassLoader");
    void* addUrl = GetMethodID(env, uclClass, "addURL", "(Ljava/net/URL;)V");
    CallVoidMethod(env, sysCL, addUrl, jarUrl);

    return true;
}

// ── callAgentBootstrap ──

static bool callAgentBootstrap(JNIEnv* env, const char* configDir) {
    void* agentClass = FindClass(env, "io/switchlite/agent/Agent");
    if (!agentClass) return false;
    void* bootstrap = GetStaticMethodID(env, agentClass, "bootstrap", "(Ljava/lang/String;)V");
    if (!bootstrap) return false;
    void* dirStr = NewStringUTF(env, configDir);
    CallStaticVoidMethod(env, agentClass, bootstrap, dirStr);
    return true;
}

// ── ThreadProc ──

DWORD WINAPI ThreadProc(LPVOID lpParam) {
    const char* configDir = (const char*)lpParam;
    char jarPath[MAX_PATH];
    snprintf(jarPath, sizeof(jarPath), "%s\\switchlite-agent.jar", configDir);

    JavaVM* vm = findJVM();
    if (!vm) { OutputDebugStringA("[SwitchLite] Failed to find JVM\n"); return 1; }

    JNIEnv* env = NULL;
    AttachThread(vm, (void**)&env);
    if (!env) { OutputDebugStringA("[SwitchLite] Failed to attach thread\n"); return 1; }

    if (!addJarToClasspath(env, jarPath)) {
        OutputDebugStringA("[SwitchLite] Failed to add agent.jar to classpath\n");
        return 1;
    }

    if (!callAgentBootstrap(env, configDir)) {
        OutputDebugStringA("[SwitchLite] Agent.bootstrap() failed\n");
        return 1;
    }

    DetachThread(vm);
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
        if (len > 0 && tempPath[len-1] == '\\') tempPath[len-1] = '\0';
        HANDLE hThread = CreateThread(NULL, 0, ThreadProc, _strdup(tempPath), 0, NULL);
        if (hThread) CloseHandle(hThread);
    }
    return TRUE;
}
