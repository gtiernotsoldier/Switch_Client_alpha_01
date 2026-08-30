// Java Agent injection utilities
#ifndef INJECT_H
#define INJECT_H

#include <string>
#include "version.h"

// Inject Java Agent into running JVM process
bool injectJavaAgent(int pid, const std::string& agentPath, const VersionInfo& versionInfo);

// Get path to embedded agent.jar
std::string getEmbeddedAgentPath();

// Get path to embedded payload.dll (DLL injection)
std::string getEmbeddedPayloadPath();

// Get Fabric mod deployment path
std::string getFabricModPath();

// Deploy Fabric mod to mods directory
bool deployFabricMod(const std::string& mcDir, const std::string& modPath, const std::string& version);

// Show diagnostic logs from %TEMP% after injection
void showDiagnosticLogs();

#endif // INJECT_H
