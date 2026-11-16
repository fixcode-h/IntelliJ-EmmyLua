#!/usr/bin/env bash
# 用 NDK 编 Android 真机附加 agent，产物放到 <输出目录>/<abi>/libemmylua_agent.so。
# 用法：ANDROID_NDK=/path/to/ndk native/platforms/android/build_agent.sh [输出目录]
# 可选：EMMY_SHADOWHOOK_DIR=已下载的 android-inline-hook 源码（离线构建时用）
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
ndk="${ANDROID_NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "$ndk" || ! -f "$ndk/build/cmake/android.toolchain.cmake" ]]; then
  echo "请设置 ANDROID_NDK 指向 NDK 根目录（r23 以上）" >&2
  exit 1
fi
out="${1:-$here/../../build/android-agent}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
extra=()
if [[ -n "${EMMY_SHADOWHOOK_DIR:-}" ]]; then
  extra+=("-DEMMY_SHADOWHOOK_DIR=$EMMY_SHADOWHOOK_DIR")
fi
if [[ -n "${EMMY_AGENT_VERSION:-}" ]]; then
  extra+=("-DEMMY_AGENT_VERSION=$EMMY_AGENT_VERSION")
fi

for abi in arm64-v8a armeabi-v7a; do
  build="$out/.build-$abi"
  cmake -S "$here/agent" -B "$build" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release ${extra[@]+"${extra[@]}"}
  cmake --build "$build" --target emmylua_agent -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
  mkdir -p "$out/$abi"
  # 插件包里放去掉符号的版本；带符号的留在 .build-<abi>/ 里，排查真机崩溃时用
  "$ndk/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-strip" --strip-unneeded \
    -o "$out/$abi/libemmylua_agent.so" "$build/libemmylua_agent.so"
  echo "→ $out/$abi/libemmylua_agent.so"
done
