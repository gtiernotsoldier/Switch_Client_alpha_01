// Process detection implementation (stub for Linux/Windows)
#include "process.h"
#include <iostream>

#ifdef _WIN32
    #define _WIN32_WINNT 0x0601  // Windows 7+ for EnumProcessModulesEx
    #include <windows.h>
    #include <tlhelp32.h>
    #include <psapi.h>
#else
    #include <dirent.h>
    #include <fstream>
    #include <unistd.h>
#endif

ProcessInfo findMinecraftProcess() {
    ProcessInfo result;
    
#ifdef _WIN32
    // Windows implementation using Toolhelp32
    HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnapshot == INVALID_HANDLE_VALUE) return result;
    
    PROCESSENTRY32 pe;
    pe.dwSize = sizeof(PROCESSENTRY32);
    
    if (Process32First(hSnapshot, &pe)) {
        do {
            std::string exeName = pe.szExeFile;
            if (exeName == "javaw.exe" || exeName == "java.exe") {
                // Enumerate all windows owned by this javaw.exe process
                struct WindowData {
                    DWORD targetPid;
                    HWND foundHwnd;
                    std::string foundTitle;
                };
                WindowData wd;
                wd.targetPid = pe.th32ProcessID;
                wd.foundHwnd = NULL;

                EnumWindows([](HWND hWnd, LPARAM lParam) -> BOOL {
                    WindowData* data = reinterpret_cast<WindowData*>(lParam);
                    DWORD windowPid;
                    GetWindowThreadProcessId(hWnd, &windowPid);
                    if (windowPid != data->targetPid) return TRUE;

                    if (!IsWindowVisible(hWnd)) return TRUE;
                    char title[256];
                    if (GetWindowTextA(hWnd, title, 256) == 0) return TRUE;

                    std::string titleStr(title);
                    std::string lower = titleStr;
                    for (char &c : lower) c = tolower(c);
                    if (lower.find("minecraft") != std::string::npos) {
                        data->foundHwnd = hWnd;
                        data->foundTitle = titleStr;
                        return FALSE;
                    }
                    return TRUE;
                }, reinterpret_cast<LPARAM>(&wd));

                if (wd.foundHwnd != NULL) {
                    result.pid = pe.th32ProcessID;
                    result.valid = true;
                    result.windowTitle = wd.foundTitle;

                    // Get exe path via ModuleFileName
                    HANDLE hProc = OpenProcess(
                        PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, pe.th32ProcessID);
                    if (hProc) {
                        HMODULE hMods[1024];
                        DWORD cbNeeded;
                        if (EnumProcessModulesEx(hProc, hMods, sizeof(hMods), &cbNeeded,
                            LIST_MODULES_32BIT | LIST_MODULES_64BIT)) {
                            char modName[MAX_PATH];
                            if (GetModuleFileNameExA(hProc, hMods[0], modName, MAX_PATH)) {
                                result.path = std::string(modName);
                            }
                        }
                        CloseHandle(hProc);
                    }
                    break;
                }
            }
        } while (Process32Next(hSnapshot, &pe));
    }
    CloseHandle(hSnapshot);
#else
    // Linux implementation using /proc
    DIR* dir = opendir("/proc");
    if (!dir) return result;
    
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        int pid = atoi(entry->d_name);
        if (pid <= 0) continue;
        
        std::string cmdlinePath = "/proc/" + std::to_string(pid) + "/cmdline";
        std::ifstream cmdline(cmdlinePath);
        if (cmdline.is_open()) {
            std::string cmdlineContent((std::istreambuf_iterator<char>(cmdline)),
                                       std::istreambuf_iterator<char>());
            if (cmdlineContent.find("minecraft") != std::string::npos ||
                cmdlineContent.find("net.minecraft") != std::string::npos) {
                result.pid = pid;
                result.valid = true;
                // TODO: Get full path from /proc/[pid]/exe
                break;
            }
        }
    }
    closedir(dir);
#endif
    
    return result;
}

std::vector<ProcessInfo> enumerateProcesses() {
    std::vector<ProcessInfo> processes;
    // TODO: Implement full enumeration
    return processes;
}

std::string readWindowTitle(int pid) {
    // TODO: Implement window title reading
    return "";
}
