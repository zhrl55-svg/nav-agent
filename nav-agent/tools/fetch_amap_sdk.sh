#!/usr/bin/env bash
# 下载高德 Android 导航 SDK（官方合包）到 android/libs/amap/
#
# ★ 为什么不用 Maven：
#   Maven 上 com.amap.api:navi 最新只到 1.9.4（2020 年），
#   新版（V11.x）**没有上 Maven**，只能从官网下载 ZIP 手动引入。
#
# ★ 为什么不入库：
#   jar 36MB + 两个 ABI 的 so 共 187MB，Git 仓库会爆。
#   所以走「构建前自动下载」，libs/amap/ 进 .gitignore。
#
# 用法：bash tools/fetch_amap_sdk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/android/libs/amap"
ZIP_URL="https://amappc.cn-hangzhou.oss-pub.aliyun-inc.com/lbs/static/zip/AMap_Android_Navi_SDK_All.zip"
JAR_NAME="AMap3DMap_11.3.100_AMapNavi_11.3.100_AMapSearch_9.8.1_AMapLocation_11.3.101_20260924.jar"

if [ -f "$OUT/$JAR_NAME" ]; then
  echo "已存在：$OUT/$JAR_NAME"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "下载高德导航SDK（约 225MB）…"
curl -sL --max-time 600 -o "$TMP/sdk.zip" "$ZIP_URL"

echo "解压…"
unzip -o -q "$TMP/sdk.zip" -d "$TMP"
unzip -o -q "$TMP/AMap3DMap_AMapNavi_AMapSearch_AMapLocation.zip" -d "$TMP"

mkdir -p "$OUT/arm64-v8a" "$OUT/armeabi-v7a"
cp "$TMP/$JAR_NAME" "$OUT/"
cp "$TMP"/arm64-v8a/*.so "$OUT/arm64-v8a/"
cp "$TMP"/armeabi-v7a/*.so "$OUT/armeabi-v7a/"

echo "完成："
ls -la "$OUT/$JAR_NAME"
echo "  so: $(ls "$OUT"/arm64-v8a/*.so | wc -l) 个 (arm64-v8a) + $(ls "$OUT"/armeabi-v7a/*.so | wc -l) 个 (armeabi-v7a)"
echo
echo "下次构建直接跑 gradle 即可（core/build.gradle.kts 会引用这些文件）。"
