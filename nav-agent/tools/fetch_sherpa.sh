#!/usr/bin/env bash
# 下载 sherpa-onnx 的 AAR（离线唤醒词 KWS + 声纹 encoder 的 Java 绑定）
#
# ★ 为什么不用 Maven：
#   sherpa-onnx 官方没上 Maven Central，AAR 只在 GitHub releases 上。
#   必须用它自带的 libonnxruntime.so —— 不能同时引 Maven 的 onnxruntime-android，
#   两个 AAR 里的同名 so 只能留一个，版本不一致时 Sherpa 在 JNI 初始化阶段直接失败。
#
# ★ 为什么不入库：
#   47MB，和高德 SDK 同样的理由 —— 大二进制进 Git 会把仓库撑爆。
#   libs/sherpa/ 进 .gitignore，构建前跑一次这个脚本。
#
# ★ KWS 模型（encoder/decoder/joiner，约 4.5MB）已经入库：
#   app/src/main/assets/sherpa-kws/。上游 kws-models release 里的 int8 encoder
#   和我们入库这份**不是同一个文件**（decoder/joiner 一致，只有 encoder 不同），
#   而入库这份是 2026-11-04 在 P40 Pro 上实测能命中的版本，所以以仓库为准。
#   tokens.txt / keywords.txt / keywords_test.txt 也和它配套，改模型必须一起改。
#
# 用法：bash tools/fetch_sherpa.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_DIR="$ROOT/android/libs/sherpa"
AAR_VERSION="2.0.1"
AAR_NAME="sherpa-onnx-${AAR_VERSION}.aar"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${AAR_VERSION}/${AAR_NAME}"
AAR_SHA256="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

mkdir -p "$LIB_DIR"

if [ -f "$LIB_DIR/sherpa-onnx.aar" ]; then
  echo "已存在：$LIB_DIR/sherpa-onnx.aar"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "下载 sherpa-onnx ${AAR_VERSION} AAR（约 47MB）…"
# ★ --speed-limit/--speed-time：卡住就报错退出，别干等。
#   这台机器上 Git Bash 的 curl 曾经卡了 10 分钟没动静（同一个文件
#   PowerShell 的 Invoke-WebRequest 35 秒就下完）。Windows 上如果 curl 不顺，
#   直接用 PowerShell：
#     Invoke-WebRequest -Uri <url> -OutFile android/libs/sherpa/sherpa-onnx.aar
curl -fL --max-time 900 --speed-limit 10240 --speed-time 60 -o "$TMP/sherpa.aar" "$AAR_URL"

if [ -n "$AAR_SHA256" ]; then
  echo "$AAR_SHA256  $TMP/sherpa.aar" | sha256sum -c -
fi

cp "$TMP/sherpa.aar" "$LIB_DIR/sherpa-onnx.aar"
echo "完成：$LIB_DIR/sherpa-onnx.aar"