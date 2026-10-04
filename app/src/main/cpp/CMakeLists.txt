cmake_minimum_required(VERSION 3.22)
project(tune_native C CXX)

include(FetchContent)

# Chromaprint and TagLib both define "docs" and "uninstall" helper targets, and
# Ninja refuses duplicates. We need neither, so drop them.
function(add_custom_target name)
    if(name STREQUAL "docs" OR name STREQUAL "uninstall")
        return()
    endif()
    _add_custom_target(${name} ${ARGN})
endfunction()

# Chromaprint computes the audio fingerprints AcoustID matches against.
FetchContent_Declare(
    chromaprint
    URL https://github.com/acoustid/chromaprint/releases/download/v1.5.1/chromaprint-1.5.1.tar.gz
    URL_HASH SHA256=a1aad8fa3b8b18b78d3755b3767faff9abb67242e01b478ec9a64e190f335e1c
)
FetchContent_GetProperties(chromaprint)
if(NOT chromaprint_POPULATED)
    FetchContent_Populate(chromaprint)

    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(FFT_LIB kissfft CACHE STRING "" FORCE)
    # Chromaprint looks for its bundled KissFFT relative to the top-level project,
    # and the NDK toolchain would re-root find_path() into the sysroot anyway.
    set(KISSFFT_SOURCE_DIR ${chromaprint_SOURCE_DIR}/src/3rdparty/kissfft CACHE PATH "" FORCE)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)

    add_subdirectory(${chromaprint_SOURCE_DIR} ${chromaprint_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

# TagLib reads and writes tags (ID3, MP4, Vorbis, FLAC...) inside the audio files.
# Its release tarball leaves the utfcpp submodule empty, so fetch that too.
FetchContent_Declare(
    utfcpp
    URL https://github.com/nemtrif/utfcpp/archive/refs/tags/v4.0.5.tar.gz
    URL_HASH SHA256=ffc668a310e77607d393f3c18b32715f223da1eac4c4d6e0579a11df8e6b59cf
)
FetchContent_Declare(
    taglib
    URL https://github.com/taglib/taglib/releases/download/v2.0.2/taglib-2.0.2.tar.gz
    URL_HASH SHA256=0de288d7fe34ba133199fd8512f19cc1100196826eafcb67a33b224ec3a59737
)
FetchContent_GetProperties(utfcpp)
if(NOT utfcpp_POPULATED)
    FetchContent_Populate(utfcpp)
endif()
FetchContent_GetProperties(taglib)
if(NOT taglib_POPULATED)
    FetchContent_Populate(taglib)

    set(BUILD_BINDINGS OFF CACHE BOOL "" FORCE)
    set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(WITH_ZLIB OFF CACHE BOOL "" FORCE)
    # Pre-seeding the cache makes TagLib's find_path() use our copy.
    set(utf8cpp_INCLUDE_DIR ${utfcpp_SOURCE_DIR}/source CACHE PATH "" FORCE)

    add_subdirectory(${taglib_SOURCE_DIR} ${taglib_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

add_library(tune_native SHARED fingerprint_jni.cpp taglib_jni.cpp)
# Neither library exports its headers for in-tree (non-installed) use.
target_include_directories(tune_native PRIVATE
    ${chromaprint_SOURCE_DIR}/src
    ${taglib_SOURCE_DIR}/taglib
    ${taglib_SOURCE_DIR}/taglib/toolkit
    ${taglib_BINARY_DIR}
    ${taglib_BINARY_DIR}/taglib
)
target_compile_definitions(tune_native PRIVATE CHROMAPRINT_NODLL TAGLIB_STATIC)
target_link_libraries(tune_native PRIVATE chromaprint tag log)
