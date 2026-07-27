cmake_minimum_required(VERSION 3.15)
project(SwitchLite_Injector)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Source files
set(SOURCES
    src/main.cpp
    src/process.cpp
    src/inject.cpp
    src/version.cpp
)

# Headers
set(HEADERS
    src/process.h
    src/inject.h
    src/version.h
)

add_executable(injector ${SOURCES} ${HEADERS})

# Embed agent.jar as build-time resource
# (Copy to build dir so the injector can find it next to the binary)
configure_file(resources/agent.jar ${CMAKE_CURRENT_BINARY_DIR}/resources/agent.jar COPYONLY)

# Platform-specific settings
if(WIN32)
    target_link_libraries(injector psapi dbghelp)

    # Embed Windows version resource (agent.rc)
    target_sources(injector PRIVATE resources/agent.rc)
    set_target_properties(injector PROPERTIES
        WIN32_EXECUTABLE FALSE
        LINK_FLAGS "/SUBSYSTEM:CONSOLE"
    )
else()
    # Linux: no extra libraries needed for stub
    target_link_libraries(injector dl)
endif()
