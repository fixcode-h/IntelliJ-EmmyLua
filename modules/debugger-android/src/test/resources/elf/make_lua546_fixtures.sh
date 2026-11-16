#!/usr/bin/env bash
# 生成特征码 / MiniDebugInfo 测试夹具：真 Lua 5.4.6 核心，arm64 与 armv7（Thumb）各两次构建，
# 参考（未裁剪）和目标（链接顺序不同、前面多一段代码和数据、裁剪过）。没有 NDK 时借主机 glibc 头文件只编译不运行。
# 用法：make_lua546_fixtures.sh <仓库根> <输出目录>
set -euo pipefail
ROOT=$1; OUT=$2; L=$ROOT/native/third-party/lua-5.4.6/src
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/shim/gnu" && touch "$W/shim/gnu/stubs-32.h"
echo 'int pad_fn(int a){ volatile int x[64]; for(int i=0;i<64;i++) x[i]=a*i; return x[a&63]; } const char pad_data[3000] = {1};' > "$W/pad.c"
CORE="lapi lcode lctype ldebug ldo ldump lfunc lgc llex lmem lobject lopcodes lparser lstate lstring ltable ltm lundump lvm lzio"
for pair in aarch64-linux-android:arm64 armv7a-linux-androideabi:armv7; do
  t=${pair%%:*}; n=${pair##*:}; M=""; [ $n = armv7 ] && M=-mthumb
  objs=""; robjs=""
  for f in $CORE pad; do
    src=$L/$f.c; [ $f = pad ] && src=$W/pad.c
    clang --target=$t $M -O2 -fPIC -fvisibility=hidden -w -I"$W/shim" -I/usr/include -I/usr/include/x86_64-linux-gnu -c "$src" -o "$W/$n-$f.o"
  done
  for f in $CORE; do objs="$objs $W/$n-$f.o"; robjs="$W/$n-$f.o $robjs"; done
  clang --target=$t -shared -nostdlib -fuse-ld=lld -Wl,--build-id=sha1 -o "$W/lua546_${n}_ref.so" $objs
  clang --target=$t -shared -nostdlib -fuse-ld=lld -Wl,--build-id=sha1 -o "$W/tgt_full.so" "$W/$n-pad.o" $robjs
  # 期望值：目标 so 裁剪前的真实偏移，测试拿它核对特征码找到的地址
  llvm-nm --defined-only "$W/tgt_full.so" | awk '$3 ~ /^lua_/ {print $3"=0x"$1}' > "$OUT/lua546_${n}_target.expected"
  llvm-strip --strip-all -o "$W/lua546_${n}_target.so" "$W/tgt_full.so"
  xz -9 -c "$W/lua546_${n}_ref.so" > "$OUT/lua546_${n}_ref.so.xz"
  xz -9 -c "$W/lua546_${n}_target.so" > "$OUT/lua546_${n}_target.so.xz"
done
# MiniDebugInfo：参考 so 裁剪后附上 xz 压缩的 .symtab（和 Android 平台库的做法一样）
llvm-strip --strip-all -o "$W/s.so" "$W/lua546_arm64_ref.so"
llvm-objcopy --only-keep-debug "$W/lua546_arm64_ref.so" "$W/mini.debug"
xz -c "$W/mini.debug" > "$W/mini.xz"
llvm-objcopy --add-section .gnu_debugdata="$W/mini.xz" "$W/s.so" "$W/md.so"
xz -9 -c "$W/md.so" > "$OUT/lua546_arm64_minidebug.so.xz"
