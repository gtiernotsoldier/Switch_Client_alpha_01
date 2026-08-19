cmake_minimum_required(VERSION 3.15)
project(SwitchLite_Payload)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Payload DLL (injected into javaw.exe via CreateRemoteThread) ──
# Built from the Rust injector's build.rs (cmake -S src/payload -B ...).
# JNI_INCLUDE_DIR is passed by build.rs (path containing jni.h).

set(PAYLOAD_SOURCES
    payload.cpp
    payload.h
    attach_pipe.cpp
    attach_pipe.h
    payload_log.h
)

add_library(payload SHARED ${PAYLOAD_SOURCES})

if(WIN32)
    if(JNI_INCLUDE_DIR)
        target_include_directories(payload PRIVATE ${JNI_INCLUDE_DIR})
    endif()
    target_link_libraries(payload psapi)
else()
    target_link_libraries(payload dl)
endif()
