cmake_minimum_required(VERSION 3.22.1)
project(neartalk_crypto LANGUAGES C CXX)

add_library(neartalk_crypto SHARED kem_jni.cpp mlkem-native/mlkem_native.c)
target_include_directories(neartalk_crypto PRIVATE . mlkem-native)
target_compile_definitions(neartalk_crypto PRIVATE MLK_CONFIG_FILE="neartalk_config.h")
target_compile_options(neartalk_crypto PRIVATE -Wall -Wextra -Werror=unused-result)
set_target_properties(neartalk_crypto PROPERTIES C_STANDARD 99 CXX_STANDARD 17)

if(NOT ANDROID)
    find_package(JNI REQUIRED COMPONENTS JVM)
    target_include_directories(neartalk_crypto PRIVATE ${JNI_INCLUDE_DIRS})
endif()
