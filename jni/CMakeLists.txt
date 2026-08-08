cmake_minimum_required(VERSION 3.16)

project(webview_kmp_jni C CXX)
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Builds the JNI shared library by default. Native (cinterop) targets build
# only the static webview library by passing -DBUILD_JNI=OFF.
option(BUILD_JNI "Build the JNI shared library" ON)

set(WEBVIEW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../webview)

# ============================================================================
# webview static library (submodule sources)
#
# The webview CMakeLists is pulled in as a subdirectory. Its options default
# to the top-level build behaviour (tests, examples, docs, shared library,
# checks, packaging, amalgamation), so they are all disabled here. The
# submodule working tree is never modified: everything is configured into
# this project's binary directory.
# ============================================================================
set(WEBVIEW_BUILD_SHARED_LIBRARY OFF CACHE BOOL "Build shared libraries" FORCE)
set(WEBVIEW_BUILD_STATIC_LIBRARY ON CACHE BOOL "Build static libraries" FORCE)
set(WEBVIEW_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(WEBVIEW_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(WEBVIEW_BUILD_DOCS OFF CACHE BOOL "Build documentation" FORCE)
set(WEBVIEW_BUILD_AMALGAMATION OFF CACHE BOOL "Build amalgamated library" FORCE)
set(WEBVIEW_ENABLE_PACKAGING OFF CACHE BOOL "Enable packaging" FORCE)
set(WEBVIEW_ENABLE_CHECKS OFF CACHE BOOL "Enable checks" FORCE)
set(WEBVIEW_INSTALL_TARGETS OFF CACHE BOOL "Install targets" FORCE)
if(WIN32)
    set(WEBVIEW_USE_COMPAT_MINGW ON CACHE BOOL "Use compatibility helper for MinGW" FORCE)
endif()

add_subdirectory(${WEBVIEW_DIR} webview)

# ============================================================================
# JNI shared library
# ============================================================================
if(BUILD_JNI)
    add_library(webview_jni SHARED ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp)
    set_target_properties(webview_jni PROPERTIES OUTPUT_NAME webview_jni)
    if(WIN32)
        # MinGW prefixes DLLs with "lib"; strip it so the artifact is
        # webview_jni.dll everywhere (NativeLoader expects no prefix).
        set_target_properties(webview_jni PROPERTIES PREFIX "")
        target_compile_definitions(webview_jni PRIVATE DLL_EXPORT)
    endif()

    if(JNI_INCLUDE_DIR)
        target_include_directories(webview_jni PRIVATE ${JNI_INCLUDE_DIR})
        if(JNI_INCLUDE_DIR_PLATFORM)
            target_include_directories(webview_jni PRIVATE ${JNI_INCLUDE_DIR_PLATFORM})
        endif()
    else()
        find_package(JNI REQUIRED)
        target_include_directories(webview_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()

    target_link_libraries(webview_jni PRIVATE webview::core_static)
endif()
