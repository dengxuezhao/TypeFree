# SPDX-FileCopyrightText: 2015 - 2026 Rime community / TypeFree v2.7
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.22.1)

project(typefree_rime VERSION 2.7.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_link_options("LINKER:--build-id=none" "LINKER:--strip-debug" "LINKER:--gc-sections")

# Ensure upstream librime & dependencies are fetched before configuring subdirectories
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/librime/CMakeLists.txt")
  message(STATUS "Fetching upstream librime & dependencies via fetch_librime_deps.sh...")
  execute_process(
    COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/fetch_librime_deps.sh"
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    RESULT_VARIABLE FETCH_RES
  )
  if(NOT FETCH_RES EQUAL 0)
    message(FATAL_ERROR "Failed to fetch upstream librime dependencies")
  endif()
endif()

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# Static build for all third-party dependencies (glog, yaml-cpp, snappy, leveldb, marisa, opencc)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

include(Boost)

set(WITH_GFLAGS OFF CACHE BOOL "Use gflags" FORCE)
set(WITH_GTEST OFF CACHE BOOL "Use googletest" FORCE)
set(WITH_UNWIND none CACHE STRING "unwind driver" FORCE)
add_subdirectory(librime/deps/glog)
target_compile_options(
  glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(librime/deps/yaml-cpp)

set(SNAPPY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SNAPPY_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(SNAPPY_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(librime/deps/leveldb)

add_subdirectory(librime/deps/marisa-trie)
target_compile_options(
  marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(LIBMARISA marisa)
set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)
set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
add_subdirectory(librime/deps/opencc)
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

# Build upstream librime (librime.so) + plugins (librime-lua.so, librime-octagram.so, librime-predict.so)
include(Rime)

find_package(Opencc REQUIRED)
find_library(ANDROID_LOG_LIB log)

# Build thin JNI bridge (librime_jni.so) over librime C API (rime_get_api())
add_library(rime_jni SHARED rime_jni.cpp)
target_include_directories(rime_jni PRIVATE
  "${CMAKE_BINARY_DIR}/librime/src"
  "${CMAKE_SOURCE_DIR}/librime/src"
  "${Opencc_INCLUDE_PATH}"
)
target_link_libraries(rime_jni
  rime
  ${Opencc_LIBRARY}
  ${ANDROID_LOG_LIB}
)
