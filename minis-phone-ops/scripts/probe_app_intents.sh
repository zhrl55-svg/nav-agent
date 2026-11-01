#!/system/bin/sh
# 探测 Android App 的可用意图入口（scheme / action / 版本）
# 用法:
#   sh probe_app_intents.sh <包名> [包名2 ...]
#   sh probe_app_intents.sh --send              # 列出能处理"分享文本"的组件
#   sh probe_app_intents.sh --view              # 列出能处理"打开链接"的组件
# 需要 shell 级权限（Shizuku 或 adb shell）

probe_one() {
  PKG="$1"
  echo "==================== $PKG ===================="

  INFO=$(dumpsys package "$PKG" 2>/dev/null)
  if [ -z "$INFO" ]; then
    echo "!! 读不到 $PKG，可能包名错误或没有 shell 权限"
    echo
    return 1
  fi

  echo "--- 版本 ---"
  echo "$INFO" | grep -E "versionName=|targetSdk=" | head -3

  echo "--- 自定义 scheme ---"
  SCHEMES=$(echo "$INFO" | grep -oE 'scheme="[^"]+"' | sort -u)
  if [ -n "$SCHEMES" ]; then echo "$SCHEMES"; else echo "(无)"; fi

  echo "--- 注册的 action ---"
  ACTIONS=$(echo "$INFO" | grep -oE 'android\.intent\.action\.[A-Z_]+' | sort -u)
  if [ -n "$ACTIONS" ]; then echo "$ACTIONS"; else echo "(无)"; fi

  echo
}

case "$1" in
  --send)
    echo "== 能处理 ACTION_SEND(text/plain) 的组件 =="
    cmd package query-activities -a android.intent.action.SEND -t text/plain 2>/dev/null \
      | grep -E "^[[:space:]]+[a-zA-Z]" | sed 's/^[[:space:]]*//' | sort -u
    ;;
  --view)
    echo "== 能处理 ACTION_VIEW(https) 的组件 =="
    cmd package query-activities -a android.intent.action.VIEW -d "https://example.com" 2>/dev/null \
      | grep -E "^[[:space:]]+[a-zA-Z]" | sed 's/^[[:space:]]*//' | sort -u
    ;;
  "")
    echo "用法: sh probe_app_intents.sh <包名> [...] | --send | --view"
    exit 1
    ;;
  *)
    for p in "$@"; do probe_one "$p"; done
    ;;
esac
