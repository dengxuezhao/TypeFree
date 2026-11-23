cmake_minimum_required(VERSION 3.22.1)
project(typefree_slm_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Point to pinned llama.cpp checkout fetched by scripts/fetch_offline_deps.sh
get_filename_component(LLAMA_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/llama.cpp" ABSOLUTE)
if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_CPP_DIR}. Run `bash scripts/fetch_offline_deps.sh` first.")
endif()

# Enforce static linking of llama.cpp/ggml into a single self-contained libtypefree_slm_jni.so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama_build" EXCLUDE_FROM_ALL)

add_library(typefree_slm_jni SHARED
    typefree_slm_jni.cpp
)

target_include_directories(typefree_slm_jni PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/common"
    "${LLAMA_CPP_DIR}/ggml/include"
)

if(TARGET llama-common)
    set(LLAMA_COMMON_TARGET llama-common)
elseif(TARGET common)
    set(LLAMA_COMMON_TARGET common)
else()
    message(FATAL_ERROR "Neither llama-common nor common target found in llama.cpp")
endif()

find_library(ANDROID_LOG_LIB log)

target_link_libraries(typefree_slm_jni PRIVATE
    llama
    ${LLAMA_COMMON_TARGET}
    ${ANDROID_LOG_LIB}
)

# Guarantee 16KB ELF page-size alignment for Android 15+ devices
target_link_options(typefree_slm_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
    "-Wl,--gc-sections"
)

target_compile_options(typefree_slm_jni PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)
