#!/usr/bin/env bash
# 下载 emmylua_ls 各平台可执行文件到 <输出目录>/<平台>/emmylua_ls[.exe]，Gradle 打包时放进插件的 server/ 目录。
# 用法：fetch-emmylua-ls.sh <版本> <输出目录> [平台...]
#   版本留空读 gradle.properties 的 emmyLuaLsVersion；平台默认全部。
# 资产名按 Release 的实际列表匹配（不写死文件名），必需平台缺了直接失败，不出没有语言服务器的包。
set -euo pipefail

VERSION="${1:-}"
OUT="${2:-build/emmylua-ls}"
# 只给了一个参数时 shift 2 会失败且不移位，版本号就会被当成平台名，所以按实际个数移
if [ $# -ge 2 ]; then shift 2; else shift $#; fi
if [ -z "$VERSION" ]; then
  VERSION=$(grep -E '^emmyLuaLsVersion=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
fi
REPO="EmmyLuaLs/emmylua-analyzer-rust"
REQUIRED="win32-x64 darwin-x64 darwin-arm64 linux-x64"
PLATFORMS="${*:-win32-x64 win32-arm64 darwin-x64 darwin-arm64 linux-x64 linux-arm64}"

# 用 "$@" 带鉴权头：macOS 自带的 bash 3.2 在 set -u 下展开空数组会报 unbound variable
if [ -n "${GITHUB_TOKEN:-}" ]; then set -- -H "Authorization: Bearer $GITHUB_TOKEN"; else set --; fi
release=$(curl -fsSL "$@" "https://api.github.com/repos/$REPO/releases/tags/$VERSION")
assets=$(echo "$release" | jq -r '.assets[] | "\(.name) \(.browser_download_url)"')
echo "emmylua_ls $VERSION 的资产："
echo "$assets" | awk '{print "  " $1}'

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
missing_required=0

for platform in $PLATFORMS; do
  os=${platform%-*}
  arch=${platform#*-}
  case "$arch" in
    x64) arch_re='(x64|x86_64|amd64)' ;;
    arm64) arch_re='(arm64|aarch64)' ;;
    *) echo "未知架构 $arch"; exit 1 ;;
  esac
  case "$os" in
    win32) os_re='(win32|windows|win)' ;;
    darwin) os_re='(darwin|macos|mac)' ;;
    linux) os_re='linux' ;;
    *) echo "未知系统 $os"; exit 1 ;;
  esac
  # 同一平台有多个（如 glibc 版、musl 版）时取名字最短的那个：一般是默认构建
  line=$(echo "$assets" | grep -E "^emmylua_ls-${os_re}-${arch_re}(-glibc[.0-9]*)?\.(zip|tar\.gz) " \
    | awk '{print length($1) " " $0}' | sort -n | head -1 | cut -d' ' -f2- || true)
  if [ -z "$line" ]; then
    if echo " $REQUIRED " | grep -q " $platform "; then
      echo "::error::emmylua_ls $VERSION 没有 $platform 的构建"
      missing_required=1
    else
      echo "::warning::emmylua_ls $VERSION 没有 $platform 的构建，跳过"
    fi
    continue
  fi
  name=${line%% *}
  url=${line#* }
  echo "$platform ← $name"
  curl -fsSL "$@" -o "$tmp/$name" "$url"
  rm -rf "$tmp/x" && mkdir -p "$tmp/x"
  case "$name" in
    *.zip) unzip -q "$tmp/$name" -d "$tmp/x" ;;
    *.tar.gz) tar -xzf "$tmp/$name" -C "$tmp/x" ;;
  esac
  exe=emmylua_ls
  [ "$os" = win32 ] && exe=emmylua_ls.exe
  found=$(find "$tmp/x" -type f -name "$exe" | head -1)
  if [ -z "$found" ]; then
    echo "::error::$name 里没有 $exe"
    exit 1
  fi
  mkdir -p "$OUT/$platform"
  cp "$found" "$OUT/$platform/$exe"
  chmod +x "$OUT/$platform/$exe"
done

[ "$missing_required" = 0 ] || exit 1
find "$OUT" -type f -exec ls -l {} \;
