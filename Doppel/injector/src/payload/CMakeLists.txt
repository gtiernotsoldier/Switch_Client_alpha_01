cmake_minimum_required(VERSION 3.15)
project(Doppel_Payload)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Payload DLL (injected into javaw.exe via CreateRemoteThread) ──
# Built by CI with cmake directly (see .github/workflows/ci.yml), then copied
# into injector/resources/payload.dll for the Rust injector to embed.

set(PAYLOAD_SOURCES
    payload.cpp
    payload.h
    attach_pipe.cpp
    attach_pipe.h
    payload_log.h
)

add_library(payload SHARED ${PAYLOAD_SOURCES})

if(WIN32)
    find_package(JNI REQUIRED)
    target_include_directories(payload PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(payload psapi)
else()
    target_link_libraries(payload dl)
endif()
