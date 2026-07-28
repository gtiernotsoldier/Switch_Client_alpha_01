cmake_minimum_required(VERSION 3.15)
project(SwitchLite_Injector)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Injector executable ──

set(SOURCES
    src/main.cpp
    src/process.cpp
    src/inject.cpp
    src/version.cpp
)

set(HEADERS
    src/process.h
    src/inject.h
    src/version.h
)

add_executable(injector ${SOURCES} ${HEADERS})

configure_file(resources/agent.jar ${CMAKE_CURRENT_BINARY_DIR}/resources/agent.jar COPYONLY)

if(WIN32)
    target_link_libraries(injector psapi dbghelp)
    target_sources(injector PRIVATE resources/agent.rc)
    set_target_properties(injector PROPERTIES
        WIN32_EXECUTABLE FALSE
        LINK_FLAGS "/SUBSYSTEM:CONSOLE"
    )
else()
    target_link_libraries(injector dl)
endif()

# ── Payload DLL (injected into javaw.exe via CreateRemoteThread) ──

if(WIN32)
    set(PAYLOAD_SOURCES src/payload/payload.cpp src/payload/payload.h)
    add_library(payload SHARED ${PAYLOAD_SOURCES})
    target_link_libraries(payload psapi)

    # Copy payload.dll to resources/ for embedding via agent.rc (ID=102)
    add_custom_command(TARGET payload POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:payload>
            ${CMAKE_CURRENT_SOURCE_DIR}/resources/payload.dll
    )

    # Make injector depend on payload so it's built first
    add_dependencies(injector payload)
endif()
