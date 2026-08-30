// attach_pipe.h — Windows Attach API via JVM_EnqueueOperation (no tools.jar needed)
//
// Implements the JDK 8 Windows attach mechanism by calling JVM_EnqueueOperation,
// which is exported from jvm.dll. On Windows, the AttachListener thread is always
// running at JVM startup — it blocks on a Win32 semaphore, waiting for operations
// to be enqueued. JVM_EnqueueOperation adds the operation and releases the semaphore.
//
// Since our DLL is already inside the target process, we can call this function
// directly — no CreateRemoteThread injection or tools.jar needed (Minecraft runs
// on JRE, not JDK).
//
// Protocol (JDK 8 Windows):
//   1. Get JVM_EnqueueOperation from jvm.dll (already loaded in process)
//   2. Create a named pipe server for the response
//   3. Call JVM_EnqueueOperation("load", "instrument", "true", "<jar>=<args>", "<pipename>")
//   4. AttachListener dequeues the operation, loads the agent, calls agentmain()
//   5. AttachListener writes the result to our named pipe
//   6. We read the result
#pragma once

#ifdef _WIN32

#include <windows.h>
#include <string>

// Try to attach to the JVM via the named pipe protocol and load the agent.
// Returns true if the agent was loaded successfully (agentmain was called).
// Returns false if the attach failed.
bool attachAndLoadAgent(int pid, const char* agentJarPath, const char* agentArgs);

#endif // _WIN32
