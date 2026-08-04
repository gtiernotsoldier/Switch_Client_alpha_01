// payload_log.h — shared logger declaration for payload DLL modules
#pragma once

#ifdef _WIN32

// Shared logger function — defined in payload.cpp, used by attach_pipe.cpp etc.
// Writes to OutputDebugString and %TEMP%\switchlite-payload.log
void payloadLog(const char* fmt, ...);

#endif // _WIN32
