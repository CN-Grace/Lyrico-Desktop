# ---------------------------------------------------------------------------
# lyrico-audiotag —— TagLib + JNI 桥接，产物 taglib.dll
#
# 桌面移植说明：
#   * 原来只支持 Android NDK（链接 android/log、用 POSIX fd 传文件描述符）。
#     现在改为 MSVC 优先的跨编译器构建，并直接链接静态 TagLib。
#   * JNI 入口由 `jint fd` 改为 `jstring path`：TagLib 的 FileStream(int) 在
#     Windows 上不可用（tfilestream.cpp 中 _WIN32 分支直接返回 InvalidFileHandle）。
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.20)

project(lyrico-audiotag LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# ---------------------------------------------------------------------------
# 产物统一落到 <repo>/build/native/<platform>-<arch>，供 Gradle 打包时拷贝
# ---------------------------------------------------------------------------
if(WIN32)
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        set(LYRICO_NATIVE_PLATFORM "windows-x64")
    else()
        set(LYRICO_NATIVE_PLATFORM "windows-x86")
    endif()
elseif(APPLE)
    set(LYRICO_NATIVE_PLATFORM "macos-${CMAKE_SYSTEM_PROCESSOR}")
else()
    set(LYRICO_NATIVE_PLATFORM "linux-${CMAKE_SYSTEM_PROCESSOR}")
endif()

set(LYRICO_NATIVE_OUT
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../build/native/${LYRICO_NATIVE_PLATFORM}"
        CACHE PATH "Directory the native libraries are written to")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${LYRICO_NATIVE_OUT}")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${LYRICO_NATIVE_OUT}")
foreach(_cfg DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${_cfg} "${LYRICO_NATIVE_OUT}")
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${_cfg} "${LYRICO_NATIVE_OUT}")
endforeach()

# ---------------------------------------------------------------------------
# 编译 / 链接选项：按编译器分派
# ---------------------------------------------------------------------------
if(MSVC)
    # /utf-8 是必须的：源文件与签名里含中文。
    add_compile_options(/utf-8 /W3 /Gy /Gw /Zc:__cplusplus)
    # TagLib 的 ENABLE_STATIC_RUNTIME 会把 /MD 换成 /MT，但那是给旧版 CMake 用的：
    # CMake 4.x 的 CMAKE_CXX_FLAGS_RELEASE 已不再包含 /MD，运行时选择完全由
    # CMAKE_MSVC_RUNTIME_LIBRARY 控制，所以这里必须显式指定，否则正则改了个寂寞。
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    set(ENABLE_STATIC_RUNTIME ON CACHE BOOL "" FORCE)
    add_link_options(/OPT:REF /OPT:ICF /LTCG /INCREMENTAL:NO)
else()
    add_compile_options(-O3 -fvisibility=hidden -flto -fdata-sections
            -ffunction-sections -fomit-frame-pointer)
    set(CMAKE_SHARED_LINKER_FLAGS
            "${CMAKE_SHARED_LINKER_FLAGS} -flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -s -Wl,--build-id=none")
endif()

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN YES)

# ---------------------------------------------------------------------------
# TagLib（静态库，整块编进 taglib.dll）
# ---------------------------------------------------------------------------
set(VISIBILITY_HIDDEN ON CACHE BOOL "" FORCE)
set(BUILD_BINDINGS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory(taglib EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# JNI 头文件（桌面构建不再由 NDK 的 sysroot 提供 jni.h）
#
# 故意不用 CMake 自带的 FindJNI：它会把含反斜杠的 JAVA_HOME 当成转义序列，
# 在 CMake 4.4 + Windows 路径下直接报 “Invalid character escape '\d'”。
# JNI 方法由 JVM 在运行时解析，原生库本身不需要链接 libjvm。
# ---------------------------------------------------------------------------
if(NOT JAVA_HOME AND DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
file(TO_CMAKE_PATH "${JAVA_HOME}" JAVA_HOME)
find_path(LYRICO_JNI_INCLUDE_DIR
        NAMES jni.h
        PATHS "${JAVA_HOME}/include"
              "${JAVA_HOME}/include/win32"
              "${JAVA_HOME}/include/darwin"
              "${JAVA_HOME}/include/linux"
        DOC "Directory containing jni.h / jni_md.h")
if(NOT LYRICO_JNI_INCLUDE_DIR)
    message(FATAL_ERROR "找不到 jni.h，请设置 JAVA_HOME（当前：${JAVA_HOME}）")
endif()
set(JNI_INCLUDE_DIRS "${LYRICO_JNI_INCLUDE_DIR}")
if(EXISTS "${LYRICO_JNI_INCLUDE_DIR}/win32")
    list(APPEND JNI_INCLUDE_DIRS "${LYRICO_JNI_INCLUDE_DIR}/win32")
endif()
message(STATUS "JNI headers: ${JNI_INCLUDE_DIRS}")

include_directories(
        taglib/taglib
        taglib/taglib/toolkit
        taglib/taglib/asf
        taglib/taglib/mpeg
        taglib/taglib/ogg
        taglib/taglib/ogg/flac
        taglib/taglib/flac
        taglib/taglib/mp4
        taglib/taglib/ogg/vorbis
        taglib/taglib/ogg/opus
        taglib/taglib/mpeg/id3v2
        taglib/taglib/mpeg/id3v2/frames
        taglib/taglib/mpeg/id3v1
        taglib/taglib/ape
        taglib/taglib/wavpack
        taglib/taglib/riff
        taglib/taglib/riff/aiff
        taglib/taglib/riff/wav
        taglib/taglib/dsf
        taglib/taglib/dsdiff)

add_library(taglib SHARED
        taglib.cpp
        fileref_ext.cpp)

target_include_directories(taglib PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(taglib PRIVATE tag)

if(NOT MSVC AND CMAKE_OBJCOPY)
    add_custom_command(TARGET taglib POST_BUILD
            COMMAND ${CMAKE_OBJCOPY}
            --remove-section .comment
            --remove-section .note
            --strip-debug $<TARGET_FILE:taglib>)
endif()
