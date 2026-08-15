cmake_minimum_required(VERSION 3.22)
project("reascale_ncnn")

# ncnn 静态库 + 头文件
# 路径解析：${CMAKE_SOURCE_DIR} = <项目根>/app，故 ../../build_tmp = <项目根上级>/build_tmp
# （即 H:\Zcode-workspace\aiworkspace\build_tmp\ncnn-20240820-android-vulkan，位于仓库外）
# 换机器时需把 ncnn 官方 android-vulkan 预编译包解压到该目录：<上级>/build_tmp/ncnn-20240820-android-vulkan/
set(NCNN_PATH "${CMAKE_SOURCE_DIR}/../../build_tmp/ncnn-20240820-android-vulkan/${ANDROID_ABI}")
message(STATUS "NCNN_PATH=${NCNN_PATH}")

add_library(reascale_ncnn SHARED
    src/main/cpp/reascale_ncnn.cpp
)

target_include_directories(reascale_ncnn PRIVATE
    ${NCNN_PATH}/include
)

target_link_libraries(reascale_ncnn
    ${NCNN_PATH}/lib/libncnn.a
    ${NCNN_PATH}/lib/libglslang.a
    ${NCNN_PATH}/lib/libMachineIndependent.a
    ${NCNN_PATH}/lib/libOSDependent.a
    ${NCNN_PATH}/lib/libOGLCompiler.a
    ${NCNN_PATH}/lib/libSPIRV.a
    ${NCNN_PATH}/lib/libGenericCodeGen.a
    ${NCNN_PATH}/lib/libglslang-default-resource-limits.a
    -ljnigraphics
    -landroid
    -llog
    -lz
)

target_compile_options(reascale_ncnn PRIVATE
    -std=c++17
    -fexceptions
    -frtti
    -fvisibility=hidden
    -fopenmp
)
# ncnn 使用 OpenMP，静态链接 libomp（避免运行时 missing libomp.so）
# NDK r27 libomp.a 在 clang lib/linux/<abi>/ 下
# NDK r27 libomp.a 在 clang lib/linux/<triple>/ 下（arm64-v8a → aarch64）
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OMP_TRIPLE "aarch64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(OMP_TRIPLE "arm")
else()
    set(OMP_TRIPLE ${ANDROID_ABI})
endif()
set(OMP_STATIC "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/lib/clang/18/lib/linux/${OMP_TRIPLE}/libomp.a")
message(STATUS "OMP_STATIC=${OMP_STATIC}")
if(EXISTS ${OMP_STATIC})
    target_link_libraries(reascale_ncnn ${OMP_STATIC})
else()
    target_link_libraries(reascale_ncnn -lomp)
endif()