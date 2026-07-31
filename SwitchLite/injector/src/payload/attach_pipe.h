// attach_pipe.h — Windows Attach API via named pipe (no tools.jar needed)
//
// Implements the same protocol as com.sun.tools.attach.VirtualMachine
// on Windows, but entirely in C++. This allows payload.dll to trigger
// agentmain(String, Instrumentation) without needing tools.jar on the
// classpath (Minecraft runs on JRE, not JDK).
//
// Protocol (JDK 8 Windows):
//   1. Signal the JVM to start AttachListener: create %TEMP%\.attach_pid<PID>
//   2. Connect to named pipe: \\.\pipe\java_pid<PID>
//   3. Send load command: "load\0instrument\0true\0<jar_path>=<args>"
//   4. Read response
#pragma once

#ifdef _WIN32

#include <windows.h>
#include <string>

// Try to attach to the JVM via the named pipe protocol and load the agent.
// Returns true if the agent was loaded successfully (agentmain was called).
// Returns false if the attach failed.
bool attachAndLoadAgent(int pid, const char* agentJarPath, const char* agentArgs);

#endif // _WIN32
