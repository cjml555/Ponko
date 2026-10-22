#!/bin/bash
# Recompila libjllama.so (java-llama.cpp v5.1.0) para Android arm64 con el
# backend Vulkan y lo deja listo para empaquetar como AAR.
#
# Pasos (ver README en la cabecera del script):
#   1) clonar llama.cpp b10682 y aplicar los parches de java-llama.cpp
#   2) parchear jllama.cpp (casts de JNI) y ggml-vulkan.cpp (BDA)
#   3) compilar con el NDK, usando el glslc y las cabeceras SPIRV del NDK
#   4) strip y copiar al AAR
#
# El bootstrap de Vulkan (glslc + SPIRV-Headers) es el mismo que ya usa el
# build de sd.cpp en este proyecto: el NDK trae glslc y las cabeceras
# spirv, solo hay que generar el paquete CMake minimo que ggml-vulkan
# busca con find_package(SPIRV-Headers CONFIG).
set -e

NDK=${NDK:-/home/jhon/Android/sdk/ndk/28.2.13676358}
JLC=${JLC:-/tmp/jlc/llama}
LLAMA_SRC=${LLAMA_SRC:-/tmp/llama.cpp}
BUILD=${BUILD:-/tmp/jlc-build-vk}
OUT=${OUT:-/tmp/jllama-vulkan}
PONKO=${PONKO:-/home/jhon/Ponko}
THIRDPARTY="$PONKO/app/src/main/cpp/thirdparty/include"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

export ANDROID_NDK="$NDK"
export ANDROID_NDK_HOME="$NDK"

SPIRV_INC="$NDK/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include"
SPIRV_CFG="$BUILD/yumenode-spirv-headers"
mkdir -p "$SPIRV_CFG"
cat > "$SPIRV_CFG/SPIRV-HeadersConfig.cmake" <<EOF
set(SPIRV-Headers_FOUND TRUE)
set(SPIRV-Headers_INCLUDE_DIR "${SPIRV_INC}")
set(SPIRV-Headers_INCLUDE_DIRS "${SPIRV_INC}")
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_INC}")
endif()
EOF

JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which javac))))}"
mkdir -p "$BUILD" "$OUT"

cmake -S "$JLC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    -DJNI_INCLUDE_DIRS="$JAVA_HOME/include:$JAVA_HOME/include/linux" \
    -DOS_NAME=Android \
    -DOS_ARCH=arm64-v8a \
    -DGGML_VULKAN=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DJLLAMA_BACKEND_DL=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DVulkan_GLSLC_EXECUTABLE="$NDK/shader-tools/linux-x86_64/glslc" \
    -DSPIRV-Headers_DIR="$SPIRV_CFG" \
    -DFETCHCONTENT_SOURCE_DIR_LLAMA.CPP="$LLAMA_SRC" \
    -DCMAKE_C_FLAGS="-I$THIRDPARTY -I$SPIRV_INC -march=armv8.2-a+dotprod+fp16 -O3" \
    -DCMAKE_CXX_FLAGS="-I$THIRDPARTY -I$SPIRV_INC -DJNIEnv_=_JNIEnv -march=armv8.2-a+dotprod+fp16 -O3" \
    -DCMAKE_SHARED_LINKER_FLAGS="-march=armv8.2-a+dotprod+fp16" \
    2>&1 | tail -5

cmake --build "$BUILD" --target jllama -j4 2>&1 | tail -10

SO=$(find "$BUILD" -name "libjllama.so" | head -1)
[ -n "$SO" ] || { echo "NO SE HA GENERADO libjllama.so"; exit 1; }
cp "$SO" "$OUT/libjllama.so"
"$STRIP" --strip-all "$OUT/libjllama.so"
echo "=== $OUT/libjllama.so ==="
ls -la "$OUT/libjllama.so"
echo "backend vulkan: $(nm -D "$OUT/libjllama.so" | grep -ciE 'ggml_vk|ggml_backend_vk') simbolos"
readelf -d "$OUT/libjllama.so" | grep NEEDED
echo "BUILD OK"
