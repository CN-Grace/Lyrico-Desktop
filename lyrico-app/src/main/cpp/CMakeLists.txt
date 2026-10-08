# ---------------------------------------------------------------------------
# lyrico-app 原生库 —— ebur128.dll（响度测量）+ quickjs-ng.dll（插件 JS 运行时）
#
# 桌面移植说明：去掉 Android NDK 的 `android` / `log` 链接与 GCC 专用旗标，
# 改为 MSVC 优先；SSE2 的 FTZ 优化在 x64 上本来默认开启，无需额外开关。
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.20)

project(lyrico-native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# ---------------------------------------------------------------------------
# 产物统一落到 <repo>/build/native/<platform>-<arch>
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
# 编译 / 链接选项
# ---------------------------------------------------------------------------
if(MSVC)
    add_compile_options(/utf-8 /W3 /Gy /Gw /Zc:__cplusplus)
    # 与 lyrico-audiotag 保持一致：静态链接 CRT
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
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
# JNI 头文件
#
# 故意不用 CMake 自带的 FindJNI：它会把含反斜杠的 JAVA_HOME 当成转义序列，
# 在 CMake 4.4 + Windows 路径下直接报 “Invalid character escape '\d'”。
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

# ===========================================================================
# ebur128 —— EBU R128 响度测量
# ===========================================================================
add_library(ebur128 SHARED
        ebur128.cpp              # C++ JNI 桥接层
        ebur128/ebur128.c        # ebur128 核心 C 实现
)

target_include_directories(ebur128 PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${JNI_INCLUDE_DIRS})

if(MSVC)
    target_compile_definitions(ebur128 PRIVATE _CRT_SECURE_NO_WARNINGS)
    # ebur128.c 用了 M_PI，MSVC 需要显式开 _USE_MATH_DEFINES 才在 <math.h> 里导出
    target_compile_definitions(ebur128 PRIVATE _USE_MATH_DEFINES)
    # ebur128.c 用 <sys/queue.h> 的 STAILQ_* 宏，MSVC 没有这个头，
    # 由 compat/sys/queue.h 提供最小实现。
    target_include_directories(ebur128 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/compat)
else()
    target_compile_options(ebur128 PRIVATE -Wno-cpp)
    # x86/x86_64：启用 SSE2 硬件 FTZ（Flush-To-Zero）；x64 上 MSVC 已默认开启
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86|i[3-6]86|AMD64|amd64|x86_64)$")
        target_compile_options(ebur128 PRIVATE -msse2 -mfpmath=sse)
    endif()
endif()

# ===========================================================================
# QuickJS 引擎 —— JavaScript 运行时
# ===========================================================================

# --- QuickJS 核心静态库 ---
add_library(quickjs STATIC
        quickjs-ng/quickjs.c     # 核心引擎
        quickjs-ng/libregexp.c   # 正则表达式
        quickjs-ng/libunicode.c  # Unicode 处理
        quickjs-ng/dtoa.c        # 数字与字符串转换
)

target_include_directories(quickjs PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/quickjs-ng)

target_compile_definitions(quickjs PRIVATE
        CONFIG_VERSION="0.14.0")

set_target_properties(quickjs PROPERTIES
        C_STANDARD 11
        C_STANDARD_REQUIRED ON)

if(MSVC)
    # 上游为 GCC/Clang 编写，MSVC 上有一批可忽略的告警（截断 / 不安全 CRT 等）
    target_compile_definitions(quickjs PRIVATE _CRT_SECURE_NO_WARNINGS)
    target_compile_options(quickjs PRIVATE /wd4244 /wd4267 /wd4996 /wd4018 /wd4146)
endif()

# --- QuickJS JNI 桥接动态库 ---
add_library(quickjs-ng SHARED
        quickjs_bridge.cpp
)

target_link_libraries(quickjs-ng PRIVATE quickjs)
target_include_directories(quickjs-ng PRIVATE ${JNI_INCLUDE_DIRS})
