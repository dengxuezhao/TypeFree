cmake_minimum_required(VERSION 3.22.1)
project("typefree_rime_jni" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    typefree_rime_jni
    SHARED
    rime_jni.cpp
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(
    typefree_rime_jni
    ${log-lib}
    ${dl-lib}
)
