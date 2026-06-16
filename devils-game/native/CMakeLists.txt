# CMakeLists.txt — Build Stockfish JNI shared library
#
# This compiles all Stockfish source files together with sf_jni.cpp
# into a single shared library (DLL/SO/DYLIB).
#
# Prerequisites:
#   - CMake 3.16+
#   - C++17 compiler (GCC 9+, Clang 10+, MSVC 2019+)
#   - JDK with JNI headers (JAVA_HOME set)
#
# Usage:
#   mkdir build && cd build
#   cmake .. -DJAVA_HOME=/path/to/jdk -DCMAKE_BUILD_TYPE=Release
#   cmake --build . --config Release
#
# The output library is placed in:
#   build/stockfish_jni.dll     (Windows)
#   build/libstockfish_jni.so   (Linux)
#   build/libstockfish_jni.dylib (macOS)

cmake_minimum_required(VERSION 3.16)
project(stockfish_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── Find JNI ────────────────────────────────────────────────────────────────

if(DEFINED JAVA_HOME)
    set(JAVA_AWT_LIBRARY NotNeeded)
    set(JAVA_JVM_LIBRARY NotNeeded)
    set(JAVA_AWT_INCLUDE_PATH NotNeeded)
    set(ENV{JAVA_HOME} "${JAVA_HOME}")
endif()

find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})

# ─── Stockfish source directory ──────────────────────────────────────────────

# Adjust this path to point to the Stockfish source tree
set(STOCKFISH_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../Souce 1.21.11/Source Github/Stockfish-master/src"
    CACHE PATH "Path to Stockfish source directory")

if(NOT EXISTS "${STOCKFISH_SRC_DIR}/uci.h")
    message(FATAL_ERROR
        "Stockfish source not found at: ${STOCKFISH_SRC_DIR}\n"
        "Set STOCKFISH_SRC_DIR to the Stockfish 'src' directory.\n"
        "Expected: ${STOCKFISH_SRC_DIR}/uci.h")
endif()

message(STATUS "Stockfish source: ${STOCKFISH_SRC_DIR}")

# ─── Collect Stockfish source files ──────────────────────────────────────────

file(GLOB STOCKFISH_SOURCES
    "${STOCKFISH_SRC_DIR}/*.cpp"
)

# Exclude main.cpp — we replace it with our JNI entry point
list(FILTER STOCKFISH_SOURCES EXCLUDE REGEX ".*main\\.cpp$")

# Also exclude any test files
list(FILTER STOCKFISH_SOURCES EXCLUDE REGEX ".*_test\\.cpp$")

message(STATUS "Found ${STOCKFISH_SOURCES}")

# ─── JNI shim source ─────────────────────────────────────────────────────────

set(JNI_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/src/sf_jni.cpp")

# ─── Build shared library ────────────────────────────────────────────────────

add_library(stockfish_jni SHARED
    ${JNI_SOURCE}
    ${STOCKFISH_SOURCES}
)

target_include_directories(stockfish_jni PRIVATE
    ${STOCKFISH_SRC_DIR}
    ${JNI_INCLUDE_DIRS}
)

# Compile definitions for Stockfish
target_compile_definitions(stockfish_jni PRIVATE
    NNUE_EMBEDDING_OFF  # Use external NNUE files, not embedded
)

# Platform-specific settings
if(WIN32)
    target_compile_definitions(stockfish_jni PRIVATE
        _CRT_SECURE_NO_WARNINGS
        WIN32
    )
    # Use static runtime on Windows
    set_property(TARGET stockfish_jni PROPERTY
        MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
elseif(UNIX AND NOT APPLE)
    target_link_libraries(stockfish_jni PRIVATE pthread)
elseif(APPLE)
    # macOS: nothing extra needed
endif()

# Optimization
if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    if(MSVC)
        target_compile_options(stockfish_jni PRIVATE /O2 /Oi /GL)
        target_link_options(stockfish_jni PRIVATE /LTCG)
    else()
        target_compile_options(stockfish_jni PRIVATE -O3 -flto -march=native)
        target_link_options(stockfish_jni PRIVATE -flto)
    endif()
endif()

# Output name
set_target_properties(stockfish_jni PROPERTIES
    OUTPUT_NAME "stockfish_jni"
    PREFIX ""  # No "lib" prefix on Windows (Linux/macOS keep lib prefix from CMake)
)

# On Linux, ensure the lib prefix is present
if(UNIX)
    set_target_properties(stockfish_jni PROPERTIES
        PREFIX "lib"
    )
endif()

# ─── Copy NNUE files to build directory ──────────────────────────────────────

# Find NNUE files in the Stockfish source
file(GLOB NNUE_FILES "${STOCKFISH_SRC_DIR}/nn-*.nnue")
if(NNUE_FILES)
    foreach(nnue_file ${NNUE_FILES})
        get_filename_component(nnue_name ${nnue_file} NAME)
        configure_file(${nnue_file} "${CMAKE_CURRENT_BINARY_DIR}/${nnue_name}" COPYONLY)
        message(STATUS "Copied NNUE: ${nnue_name}")
    endforeach()
else()
    message(WARNING "No NNUE files found in ${STOCKFISH_SRC_DIR}")
endif()

# ─── Install ─────────────────────────────────────────────────────────────────

install(TARGETS stockfish_jni
    RUNTIME DESTINATION bin
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
)

if(NNUE_FILES)
    install(FILES ${NNUE_FILES} DESTINATION bin)
endif()
