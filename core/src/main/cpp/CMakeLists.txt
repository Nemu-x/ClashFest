cmake_minimum_required(VERSION 3.0)

# 获取git hash

message(STATUS "CMAKE_CURRENT_SOURCE_DIR= ${CMAKE_CURRENT_SOURCE_DIR}")
set(MIHOMO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../foss/golang/clash")
# Ask the mihomo submodule directly: `git submodule foreach` lists every
# submodule and the old "second line" pick landed on clash-companion's hash.
execute_process(
  COMMAND git -C ${MIHOMO_DIR} rev-parse --short=7 HEAD
  OUTPUT_VARIABLE COMMIT_HASH
  RESULT_VARIABLE COMMIT_HASH_RESULT
  OUTPUT_STRIP_TRAILING_WHITESPACE
  ERROR_QUIET
)
if (NOT COMMIT_HASH_RESULT EQUAL 0 OR "${COMMIT_HASH}" STREQUAL "")
  set(COMMIT_HASH "unknown")
endif ()
string(REGEX REPLACE "[\n\t\r]" "" COMMIT_HASH "${COMMIT_HASH}")
message(STATUS "git hash= ${COMMIT_HASH}")

# 获取分支名称
execute_process(
  COMMAND git submodule foreach git branch -r --contains ${COMMIT_HASH}
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
  OUTPUT_VARIABLE CURRENT_BRANCH
  OUTPUT_STRIP_TRAILING_WHITESPACE
  ERROR_QUIET
)
string(REPLACE "\n" ";" CURRENT_BRANCH "${CURRENT_BRANCH}")
list(LENGTH CURRENT_BRANCH CURRENT_BRANCH_LEN)
if (CURRENT_BRANCH_LEN GREATER 1)
  list(GET CURRENT_BRANCH 1 CURRENT_BRANCH)
else ()
  set(CURRENT_BRANCH "unknown")
endif ()
string(REGEX REPLACE "origin/" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
string(REGEX REPLACE "[\n\t\r]" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
#string(SUBSTRING ${CURRENT_BRANCH} 0 8 CURRENT_BRANCH)
message(STATUS "git current branch = ${CURRENT_BRANCH}")

# 获取核心语义化版本标签（例如 v1.19.23）
# The exact tag of HEAD when the submodule sits on a release; otherwise the
# nearest release tag. CORE_TAG is also exported on its own (version.h) and is
# what the subscription User-Agent advertises as `mihomo/<ver>`, so a value
# that is not a release tag must stay empty rather than be guessed.
execute_process(
  COMMAND git -C ${MIHOMO_DIR} describe --tags --exact-match --match v[0-9]*
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
  OUTPUT_VARIABLE CORE_TAG
  RESULT_VARIABLE CORE_TAG_RESULT
  OUTPUT_STRIP_TRAILING_WHITESPACE
  ERROR_QUIET
)
if (NOT CORE_TAG_RESULT EQUAL 0 OR "${CORE_TAG}" STREQUAL "")
  execute_process(
    COMMAND git -C ${MIHOMO_DIR} describe --tags --abbrev=0 --match v[0-9]*
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    OUTPUT_VARIABLE CORE_TAG
    RESULT_VARIABLE CORE_TAG_RESULT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
  )
endif ()

# Reconfigure whenever the mihomo submodule moves. CORE_TAG above is read at
# configure time and cached in .cxx/, which survives `gradlew clean` — without
# this a core bump keeps shipping the old version string (and constant.Version,
# which the Go side derives from it) until .cxx/ is deleted by hand.
execute_process(
  COMMAND git -C ${MIHOMO_DIR} rev-parse --absolute-git-dir
  OUTPUT_VARIABLE MIHOMO_GIT_DIR
  OUTPUT_STRIP_TRAILING_WHITESPACE
  ERROR_QUIET
)
if (EXISTS "${MIHOMO_GIT_DIR}/HEAD")
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${MIHOMO_GIT_DIR}/HEAD")
endif ()
string(REGEX REPLACE "[\n\t\r]" "" CORE_TAG "${CORE_TAG}")
if (NOT CORE_TAG_RESULT EQUAL 0)
  set(CORE_TAG "")
endif ()
message(STATUS "core tag = ${CORE_TAG}")

# 获取生成时间
string(TIMESTAMP COMPILE_TIME "%y%m%d")
string (REGEX REPLACE "[\n\t\r]" "" COMPILE_TIME ${COMPILE_TIME})
string(REGEX REPLACE "\"" "" COMPILE_TIME ${COMPILE_TIME})

# 生成版本信息
# Prefer release tag for stable core version display in app UI.
if (CORE_TAG_RESULT EQUAL 0 AND NOT "${CORE_TAG}" STREQUAL "")
  set(GIT_VERSION "${CORE_TAG}_${COMMIT_HASH}_${COMPILE_TIME}")
else ()
  set(GIT_VERSION "${CURRENT_BRANCH}_${COMMIT_HASH}_${COMPILE_TIME}")
endif ()
message(STATUS "version info = ${GIT_VERSION}")

# Keep Git metadata as inert preprocessor tokens before version.h stringifies it.
string(REGEX REPLACE "[^A-Za-z0-9._-]+" "_" GIT_VERSION "${GIT_VERSION}")
string(REGEX REPLACE "[^A-Za-z0-9._-]+" "_" CORE_TAG "${CORE_TAG}")

# 保存变量到文件
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/version.h.in ${CMAKE_CURRENT_SOURCE_DIR}/version.h @ONLY)

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)
