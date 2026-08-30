// Process detection and enumeration utilities
#ifndef PROCESS_H
#define PROCESS_H

#include <string>
#include <vector>

struct ProcessInfo {
    int pid;
    std::string path;
    std::string windowTitle;
    bool valid;
    
    ProcessInfo() : pid(-1), valid(false) {}
};

// Find Minecraft process by window title or executable name
ProcessInfo findMinecraftProcess();

// Get all running processes (for debugging)
std::vector<ProcessInfo> enumerateProcesses();

// Read window title from process handle
std::string readWindowTitle(int pid);

#endif // PROCESS_H
