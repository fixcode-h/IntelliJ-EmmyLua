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
