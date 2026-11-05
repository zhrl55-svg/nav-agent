#!/usr/bin/env bash
#
# 一键：编译 → 安装 → 启动 → 截图 → 抓日志。
#
# 为什么要有这个脚本：这个项目每次改动都要走同五步，手打命令容易忘参数
# （尤其设备不止一台时必须 -s，忘了就报 "more than one device"）。
#
# 用法：
#   tools/deploy.sh                        # 编译+安装+启动导航页
#   tools/deploy.sh --say "导航去张掖西站"   # 启动后直接喂一句话（调试链路）
#   tools/deploy.sh --no-build             # 跳过编译，只装现有的包
#   tools/deploy.sh -s 192.168.2.21:5555   # 指定设备（无线调试的地址）
#   tools/deploy.sh --chat                 # 启动对话页而不是导航页
#
# 产物：shots/<时间戳>.png 截图；日志从手机 nav.log 尾部打印。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
GRADLE="/d/Android/gradle/gradle-8.14.6/bin/gradle.bat"
PKG="com.navagent"
LOG_PATH="/sdcard/Android/data/$PKG/files/nav.log"

DEVICE=""
DO_BUILD=1
ACTIVITY="NavActivity"
SAY=""

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--serial) DEVICE="$2"; shift 2 ;;
    --no-build)  DO_BUILD=0; shift ;;
    --chat)      ACTIVITY="ChatActivity"; shift ;;
    --say)       SAY="$2"; shift 2 ;;
    -h|--help)   sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1"; exit 1 ;;
  esac
done

# ---- 选设备 ------------------------------------------------------------
# 没指定就自动挑：只有一台在线时直接用它；多台就要求显式指定，
# 免得装到别人手机上（之前同时连了华为和 vivo，差点装错）。
if [ -z "$DEVICE" ]; then
  mapfile -t ONLINE < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ ${#ONLINE[@]} -eq 0 ]; then
    echo "没有在线设备。插 USB，或者先 adb connect <手机IP>:5555"
    exit 1
  elif [ ${#ONLINE[@]} -eq 1 ]; then
    DEVICE="${ONLINE[0]}"
  else
    echo "有多台设备，请用 -s 指定："
    printf '  -s %s\n' "${ONLINE[@]}"
    exit 1
  fi
fi
echo "设备：$DEVICE"

# 无线设备（IP:端口）先确保连上。
# ★ 必须做：adb daemon 只要重启一次（换 shell、升级、杀进程都会），
#   无线连接就掉了，后面每条命令都报 device not found，看着像手机问题。
case "$DEVICE" in
  *:*)
    adb connect "$DEVICE" >/dev/null 2>&1 || true
    if ! adb devices | awk '{print $1}' | grep -qx "$DEVICE"; then
      echo "连不上 $DEVICE。检查：手机和电脑是否同一 Wi-Fi、手机是否锁屏"
      exit 1
    fi
    ;;
esac

# ---- 编译 --------------------------------------------------------------
if [ "$DO_BUILD" -eq 1 ]; then
  echo "编译中…"
  "$GRADLE" -p "$ROOT/android" assembleDebug 2>&1 | grep -E "^e:|error:|BUILD" | head -20
  [ -f "$APK" ] || { echo "编译失败，没有产物"; exit 1; }
fi

# ---- 安装 --------------------------------------------------------------
# ★ 不能直接用 `adb install`：vivo 上会随机 `Failure [-200]`（HANDOFF 第 3 节记过）。
#   走 pm install-create / install-write / install-commit 三段式，华为和 vivo 都稳。
# ★ MSYS_NO_PATHCONV=1 必加：Git Bash 会把 /data/local/tmp 转成 Windows 路径。
echo "安装中…"
export MSYS_NO_PATHCONV=1
adb -s "$DEVICE" push "$APK" /data/local/tmp/nav.apk >/dev/null 2>&1
SIZE=$(adb -s "$DEVICE" shell stat -c %s /data/local/tmp/nav.apk | tr -d '\r')
SESSION=$(adb -s "$DEVICE" shell "pm install-create -r -t -S $SIZE" | grep -oE '[0-9]+' | head -1)
if [ -z "$SIZE" ] || [ -z "$SESSION" ]; then
  echo "安装会话创建失败（size=$SIZE session=$SESSION）"
  exit 1
fi
adb -s "$DEVICE" shell "pm install-write -S $SIZE $SESSION base /data/local/tmp/nav.apk" >/dev/null
RESULT=$(adb -s "$DEVICE" shell "pm install-commit $SESSION" 2>&1)
echo "$RESULT" | tail -2
if ! echo "$RESULT" | grep -q "Success"; then
  echo "安装失败。手机上可能弹了确认框没点允许，或者签名不一致（先 adb uninstall $PKG）"
  exit 1
fi

# ---- 启动 --------------------------------------------------------------
adb -s "$DEVICE" shell am force-stop "$PKG"
# 清日志缓冲，保证这次跑的日志是干净的
adb -s "$DEVICE" logcat -c 2>/dev/null
if [ -n "$SAY" ]; then
  adb -s "$DEVICE" shell am start -n "$PKG/$PKG.app.$ACTIVITY" --es say "$SAY" >/dev/null
else
  adb -s "$DEVICE" shell am start -n "$PKG/$PKG.app.$ACTIVITY" >/dev/null
fi

# ---- 截图 --------------------------------------------------------------
mkdir -p "$ROOT/shots"
SHOT="$ROOT/shots/$(date +%H%M%S).png"
sleep 6
adb -s "$DEVICE" exec-out screencap -p > "$SHOT"
echo "截图：$SHOT"

# ---- 日志 --------------------------------------------------------------
echo "---- nav.log 尾部 ----"
MSYS_NO_PATHCONV=1 adb -s "$DEVICE" shell tail -n 20 "$LOG_PATH" 2>/dev/null \
  || echo "（读不到 nav.log：设置里「文件日志」可能没开）"
