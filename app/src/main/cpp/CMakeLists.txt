cmake_minimum_required(VERSION 3.22.1)
project(androidlm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LM_THIRD_PARTY)
    message(FATAL_ERROR "LM_THIRD_PARTY is not set. Run scripts/fetch-deps.sh first.")
endif()
set(TP        "${LM_THIRD_PARTY}")
set(LLAMA_DIR "${TP}/llama.cpp")

# --------------------------------------------------------------------------
# llama.cpp / ggml: every backend is a separate runtime-loaded .so (GGML_BACKEND_DL)
# so one APK adapts to whatever phone it lands on:
#   * CPU: all ARM variants (dotprod / i8mm / SVE / SME ...) - the best one for the phone is picked at runtime
#   * OpenCL: Qualcomm Adreno GPUs
#   * Vulkan: Mali / Xclipse / Adreno / PowerVR GPUs
# A backend whose driver is missing is simply skipped when loading.
# --------------------------------------------------------------------------
set(BUILD_SHARED_LIBS        ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON       ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL            OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS    ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              ON  CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI        ON  CACHE BOOL "" FORCE)
set(GGML_SYSTEM_ARCH         "ARM")

# ---- OpenCL (Adreno). Headers + a link-time ICD loader stub; the vendor libOpenCL.so is used at runtime.
set(GGML_OPENCL                    ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS      ON CACHE BOOL "" FORCE)
set(OPENCL_ICD_LOADER_HEADERS_DIR  "${TP}/OpenCL-Headers" CACHE PATH "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory("${TP}/OpenCL-Headers"      "${CMAKE_BINARY_DIR}/opencl-headers" EXCLUDE_FROM_ALL)
add_subdirectory("${TP}/OpenCL-ICD-Loader"   "${CMAKE_BINARY_DIR}/opencl-icd"     EXCLUDE_FROM_ALL)
set(OpenCL_INCLUDE_DIR "${TP}/OpenCL-Headers" CACHE PATH "" FORCE)
set(OpenCL_LIBRARY     "${CMAKE_BINARY_DIR}/opencl-icd/libOpenCL.so" CACHE FILEPATH "" FORCE)

# ---- Vulkan. The NDK ships glslc + the C headers; vulkan.hpp / SPIR-V headers come from third_party/install.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
file(GLOB _lm_glslc "${ANDROID_NDK}/shader-tools/*/glslc")
list(GET _lm_glslc 0 _lm_glslc0)
set(Vulkan_GLSLC_EXECUTABLE "${_lm_glslc0}" CACHE FILEPATH "" FORCE)
set(Vulkan_INCLUDE_DIR      "${TP}/install/include" CACHE PATH "" FORCE)
set(Vulkan_LIBRARY          "${CMAKE_SYSROOT}/usr/lib/${CMAKE_ANDROID_ARCH_TRIPLE}/${ANDROID_PLATFORM_LEVEL}/libvulkan.so" CACHE FILEPATH "" FORCE)
set(SPIRV-Headers_DIR       "${TP}/install/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)

add_subdirectory("${LLAMA_DIR}" build-llama)

if(TARGET ggml-opencl)
    add_dependencies(ggml-opencl OpenCL)
endif()

# --------------------------------------------------------------------------
# JNI engine
# --------------------------------------------------------------------------
add_library(androidlm SHARED lm_engine.cpp)

target_include_directories(androidlm PRIVATE
    "${LLAMA_DIR}"
    "${LLAMA_DIR}/common"
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
    "${LLAMA_DIR}/ggml/src"
    "${LLAMA_DIR}/vendor")

target_link_libraries(androidlm PRIVATE llama llama-common android log)
