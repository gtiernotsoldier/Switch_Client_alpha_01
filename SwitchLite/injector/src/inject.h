// Java Agent injection utilities
#ifndef INJECT_H
#define INJECT_H

#include <string>
#include "version.h"

// Inject Java Agent into running JVM process
bool injectJavaAgent(int pid, const std::string& agentPath, const VersionInfo& versionInfo);

// Get path to embedded agent.jar
std::string getEmbeddedAgentPath();

// Get Fabric mod deployment path
std::string getFabricModPath();

// Deploy Fabric mod to mods directory
bool deployFabricMod(const std::string& mcDir, const std::string& modPath, const std::string& version);

#endif // INJECT_H
