# 常用意图配方

> 标注「稳定」的是 Android 标准 API，长期不会变。
> 标注「需探测」的来自第三方 App，随版本变化，**用前必须先跑探测脚本确认**。

## 系统级（稳定）

| 动作 | 命令 |
|---|---|
| 打开应用（最通用） | `monkey -p <包名> -c android.intent.category.LAUNCHER 1` |
| 拨号盘（不拨出） | `am start -a android.intent.action.DIAL -d tel:10086` |
| 短信编辑 | `am start -a android.intent.action.SENDTO -d smsto:10086 --es sms_body "内容"` |
| 邮件 | `am start -a android.intent.action.SENDTO -d mailto:a@b.com --es android.intent.extra.SUBJECT "主题" --es android.intent.extra.TEXT "正文"` |
| 地图坐标 | `am start -a android.intent.action.VIEW -d "geo:38.93,100.45?q=38.93,100.45(张掖西站)"` |
| 网页 | `am start -a android.intent.action.VIEW -d "https://example.com"` |
| 分享文本 | `am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "内容"` |
| 分享图片 | `am start -a android.intent.action.SEND -t image/* --eu android.intent.extra.STREAM content://...` |
| 应用详情页 | `am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:<包名>` |
| WiFi 设置 | `am start -a android.settings.WIFI_SETTINGS` |
| 蓝牙设置 | `am start -a android.settings.BLUETOOTH_SETTINGS` |
| 强制停止 | `am force-stop <包名>` |
| 截图 | `screencap -p /sdcard/shot.png` |
| 当前界面 | `dumpsys window \| grep mCurrentFocus` |
| 当前 Activity | `dumpsys activity activities \| grep mResumedActivity` |
| 粘贴 | `input keyevent 279` |
| 返回 / 主页 | `input keyevent 4` / `input keyevent 3` |

## 第三方 App（需探测）

| App | scheme 示例 | 备注 |
|---|---|---|
| 高德地图 | `androidamap://route?sourceApplication=x&dlat=38.93&dlon=100.45&dname=目的地&dev=0&t=0` | 导航类里最稳 |
| 百度地图 | `baidumap://map/direction?destination=38.93,100.45&mode=driving&src=agent` | 需探测 |
| 腾讯地图 | `qqmap://map/routeplan?type=drive&to=目的地&tocoord=38.93,100.45&referer=x` | 需探测 |
| 淘宝 | `taobao://item.taobao.com/item.htm?id=<商品ID>` | 需探测，部分版本失效 |
| 京东 | `openapp.jdmobile://virtual?params=...` | 参数结构复杂，易变 |
| 抖音 | `snssdk1128://` | 需探测 |
| B 站 | `bilibili://video/<BV号>` | 需探测 |
| 支付宝 | `alipays://platformapi/startapp?appId=<id>` | **风控严，触碰红线** |
| 微信 | `weixin://` | **大部分已失效**，用系统分享面板代替 |

**微信这类封闭 App 的正确做法**：不要找 scheme，用 `ACTION_SEND` 调起系统分享面板，让用户或自动化点一下目标。

## 探测流程（模板）

```bash
PKG=com.autonavi.minimap

# 1. 版本与 targetSdk（决定可用 API）
sh -c "dumpsys package $PKG | grep -E 'versionName|targetSdk' | head -3"

# 2. 注册的 scheme
sh -c "dumpsys package $PKG | grep -oE 'scheme=\"[^\"]+\"' | sort -u"

# 3. 注册的 action
sh -c "dumpsys package $PKG | grep -oE 'android\.intent\.action\.[A-Z_]+' | sort -u"

# 4. 实际试一次，带 -W 看返回值
sh -c "am start -W -a android.intent.action.VIEW -d 'androidamap://route?...'"
```

探测结果存成一张表（App → 动作 → intent 模板），下次直接用。表要标日期，App 大版本升级后重新探。

## 判断失败原因

| 现象 | 原因 | 对策 |
|---|---|---|
| `ActivityNotFoundException` | scheme 或组件不存在 | 换下一个候选，或降级 |
| `Permission Denial` | 目标 Activity 未导出 | 无解，降级 |
| `Status: timeout` | App 启动超时 | 重试一次，仍超时降级 |
| 启动了但停在首页 | 参数没被接收 | 检查参数名拼写、是否需要 URL 编码 |
| 弹了选择器 | 有多个 App 能处理 | 加 `-n <包名/组件>` 指定 |
