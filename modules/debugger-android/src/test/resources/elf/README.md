# ELF 测试夹具

`fakelua.c` 只有和 Lua API 同名的空函数，用来验证符号提取，不是真 Lua。重新生成：

```bash
gcc -shared -fPIC -O0 -fvisibility=hidden -Wl,--build-id=sha1 -o fake_lua54_x64.so fakelua.c
cp fakelua.c /tmp/fakelua.cpp && g++ -shared -fPIC -O0 -fvisibility=hidden -Wl,--build-id=sha1 -o fake_lua54_cpp_x64.so /tmp/fakelua.cpp
strip -o fake_lua54_x64_stripped.so fake_lua54_x64.so
clang --target=armv7a-linux-androideabi -shared -fPIC -nostdlib -fuse-ld=lld -mthumb -Wl,--build-id=sha1 -o fake_lua54_armv7.so fakelua.c
clang --target=aarch64-linux-android -shared -fPIC -nostdlib -fuse-ld=lld -Wl,--build-id=sha1 -o fake_lua54_arm64.so fakelua.c
```

重新生成后 Build ID 和偏移会变，要同步更新 `LuaSymbolResolverTest` 里的常量（`readelf -n`、`nm` 查看）。

## 特征码 / MiniDebugInfo 夹具（`lua546_*`）

真 Lua 5.4.6 核心（`native/third-party/lua-5.4.6`）用 clang 交叉编译成 arm64 和 armv7（Thumb）各两次：
参考（`*_ref.so`，未裁剪）和目标（`*_target.so`，链接顺序反过来、前面多一段代码和数据、裁剪过），
`*_target.expected` 是目标裁剪前的真实偏移，`SymbolFallbackTest` 拿它核对特征码找到的每个地址。
`lua546_arm64_minidebug.so` 是参考 so 裁剪后附上 `.gnu_debugdata`。都用 xz 压缩存放，测试里解压。
`fake_lua54_arm64_stripped.so` 是 `llvm-strip --strip-all fake_lua54_arm64.so`（`lua_*` 只剩 `.dynsym`）。

重新生成（需要 clang 18+、lld、llvm-strip / llvm-objcopy / llvm-nm、xz；不需要 NDK）：

```bash
modules/debugger-android/src/test/resources/elf/make_lua546_fixtures.sh . modules/debugger-android/src/test/resources/elf
```
