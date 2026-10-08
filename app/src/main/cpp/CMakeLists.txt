cmake_minimum_required(VERSION 3.22.1)
project(f1tme LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(f1tme SHARED f1tme_jni.cpp)

target_compile_definitions(f1tme PRIVATE F1TME_NATIVE_BOUNDARY=1)

# GPAC is intentionally optional at this boundary. When a pinned Android GPAC
# build is supplied, set GPAC_ROOT to its exported include/lib tree and define
# F1TME_WITH_GPAC. Until then the library reports native availability=false;
# it must never masquerade as a working merger.
if(DEFINED GPAC_ROOT)
    target_include_directories(f1tme PRIVATE "${GPAC_ROOT}/include")
    add_library(gpac SHARED IMPORTED)
    if(EXISTS "${GPAC_ROOT}/lib/${ANDROID_ABI}/libgpac.so")
        set_target_properties(gpac PROPERTIES IMPORTED_LOCATION "${GPAC_ROOT}/lib/${ANDROID_ABI}/libgpac.so")
    else()
        set_target_properties(gpac PROPERTIES IMPORTED_LOCATION "${GPAC_ROOT}/lib/libgpac.so")
    endif()
    target_compile_definitions(f1tme PRIVATE F1TME_WITH_GPAC=1)
    target_link_libraries(f1tme gpac log)
else()
    target_link_libraries(f1tme log)
endif()
