cmake_minimum_required(VERSION 3.22.1)
project(f1tme LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED GPAC_ROOT)
    set(GPAC_ROOT "$ENV{GPAC_ROOT}")
endif()

if(NOT GPAC_ROOT)
    message(FATAL_ERROR "GPAC_ROOT is required: native TME must never silently build without the real merger")
endif()

if(NOT EXISTS "${GPAC_ROOT}/include/gpac/filters.h")
    message(FATAL_ERROR "Invalid GPAC_ROOT: include/gpac/filters.h not found")
endif()

set(GPAC_LIB "${GPAC_ROOT}/lib/${ANDROID_ABI}/libgpac.a")
if(NOT EXISTS "${GPAC_LIB}")
    set(GPAC_LIB "${GPAC_ROOT}/lib/libgpac.a")
endif()
if(NOT EXISTS "${GPAC_LIB}")
    message(FATAL_ERROR "GPAC static library not found for ${ANDROID_ABI}")
endif()

add_library(gpac STATIC IMPORTED GLOBAL)
set_target_properties(gpac PROPERTIES
    IMPORTED_LOCATION "${GPAC_LIB}"
    INTERFACE_INCLUDE_DIRECTORIES "${GPAC_ROOT}/include"
)

add_library(f1tme SHARED f1tme_jni.cpp)
target_include_directories(f1tme PRIVATE "${GPAC_ROOT}/include")
target_compile_definitions(f1tme PRIVATE
    F1TME_NATIVE_BOUNDARY=1
    F1TME_WITH_GPAC=1
)
target_link_libraries(f1tme
    gpac
    log
    android
    z
    m
    dl
    atomic
)
