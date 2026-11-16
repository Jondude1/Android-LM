#!/usr/bin/env bash
# Fetches the pinned native dependencies into third_party/ (idempotent).
# Used by CI and for local builds. Everything is pinned so builds are reproducible.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP"

# llama.cpp commit the JNI layer (app/src/main/cpp/lm_engine.cpp) is written against.
LLAMA_SHA="ef6bc6f367082338006c8379bc7bcab3e1584766"
OPENCL_TAG="v2026.05.29"
VULKAN_TAG="vulkan-sdk-1.4.363.0"
SPIRV_TAG="vulkan-sdk-1.4.363.0"

fetch() { # name url ref
  local dir="$TP/$1" url="$2" ref="$3"
  if [ -f "$dir/.fetched-$ref" ]; then echo "[deps] $1 @ $ref (cached)"; return; fi
  echo "[deps] fetching $1 @ $ref"
  rm -rf "$dir"; mkdir -p "$dir"
  git -C "$dir" init -q
  git -C "$dir" remote add origin "$url"
  git -C "$dir" fetch -q --depth 1 origin "$ref"
  git -C "$dir" checkout -q FETCH_HEAD
  touch "$dir/.fetched-$ref"
}

fetch llama.cpp          https://github.com/ggml-org/llama.cpp.git                "$LLAMA_SHA"
fetch OpenCL-Headers     https://github.com/KhronosGroup/OpenCL-Headers.git       "$OPENCL_TAG"
fetch OpenCL-ICD-Loader  https://github.com/KhronosGroup/OpenCL-ICD-Loader.git   "$OPENCL_TAG"
fetch Vulkan-Headers     https://github.com/KhronosGroup/Vulkan-Headers.git       "$VULKAN_TAG"
fetch SPIRV-Headers      https://github.com/KhronosGroup/SPIRV-Headers.git        "$SPIRV_TAG"

# Header-only packages: install them so CMake find_package()/include paths work.
if [ ! -f "$TP/install/.done-$VULKAN_TAG" ]; then
  echo "[deps] installing Vulkan-Headers + SPIRV-Headers"
  rm -rf "$TP/install" "$TP/build-hdrs"
  cmake -S "$TP/Vulkan-Headers" -B "$TP/build-hdrs/vk" -DCMAKE_INSTALL_PREFIX="$TP/install" >/dev/null
  cmake --install "$TP/build-hdrs/vk" >/dev/null
  cmake -S "$TP/SPIRV-Headers" -B "$TP/build-hdrs/spv" -DCMAKE_INSTALL_PREFIX="$TP/install" >/dev/null
  cmake --install "$TP/build-hdrs/spv" >/dev/null
  touch "$TP/install/.done-$VULKAN_TAG"
fi
echo "[deps] done"
