---
name: phone-intent-first
description: 在安卓手机上操作其他 App 时强制执行「意图层 → 无障碍 → 视觉 → 人工」四级降级，禁止一上来就截图点坐标。包含能力探测、结果校验、中文输入、红线清单。触发场景：打开某 App 做某事、发消息、导航、改设置、批量操作、跨 App 传数据、任何"帮我操作手机"的请求。
---

# 手机操作：意图层优先

## 0. 开工前三件事（每次任务都要做，30 秒）

```bash
android-shizuku-cli service status      # 必须 ready，否则停下来让用户激活 Shizuku
android-shizuku-cli device info         # 记下 Android 版本，决定哪些 API 可用
android-shizuku-cli --help              # 列出真实存在的子命令
```

**硬规则：只调用 `--help` 里真实存在的子命令。** 不要凭记忆编造 CLI 参数；不确定就先 `--help`，再 `--help` 到二级子命令。

如果 Shizuku 不可用，直接用无障碍也能干，但要主动告诉用户「现在只有 L1 能力，复杂操作会失败」。

## 1. 决策顺序（不可跳级）

| 层 | 什么时候用 | 失败信号 | 代价 |
|---|---|---|---|
| **L0 意图层** | 永远先试 | `ActivityNotFoundException`、`Status: timeout` | 毫秒，免费 |
| **L1 无障碍** | 意图层确认不存在 | 控件树为空、找不到目标节点 | 百毫秒 |
| **L2 截图+视觉** | 控件树为空（Flutter/Unity/游戏/自绘） | 截图全黑（FLAG_SECURE） | 秒级，贵 |
| **L3 人工** | 触碰红线，或三层都失败 | — | 停下来问 |

**跳级 = 违规。** 不许因为「点坐标更直观」就直接跳到 L1。

## 2. L0 意图层怎么做

### 2.1 先探测，别猜

```bash
# 目标 App 注册了哪些 scheme 和 action
sh -c 'dumpsys package <包名> | grep -oE "scheme=\"[^\"]+\"" | sort -u'
sh -c 'dumpsys package <包名> | grep -oE "android\.intent\.action\.[A-Z_]+" | sort -u'

# 谁能处理某个 intent（系统级查询，最可靠）
sh -c 'cmd package query-activities -a android.intent.action.SEND -t text/plain'
```

探测不到就说明这条路不通，直接降级，不要硬试。

### 2.2 执行

```bash
# 通用启动（不知道 Activity 名时最稳的一招）
sh -c 'monkey -p <包名> -c android.intent.category.LAUNCHER 1'

# 指定 action + 数据
sh -c 'am start -W -a android.intent.action.VIEW -d "geo:38.93,100.45?q=张掖西站"'

# 点名组件（探测到之后才用）
sh -c 'am start -W -n <包名>/<Activity全名>'

# 带参数：--es 字符串 --ei 整数 --ez 布尔 --eu URI
sh -c 'am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "内容"'
```

`-W` 必须带：它会让 `am` 等启动完成并打印 `Status: ok` / `LaunchState`，这是你唯一可靠的成败判据。

### 2.3 结果判定（不许含糊）

| 输出 | 结论 | 下一步 |
|---|---|---|
| `Status: ok` + `LaunchState: COLD/WARM/HOT` | 成功 | 继续 |
| `Error: Activity not started` | 组件存在但拒绝启动 | 降级 |
| `ActivityNotFoundException` | 意图不存在 | 降级 |
| `Warning: Activity not started, intent has been delivered to currently running top-most instance` | 已在前台，没切换 | 当作成功但需确认界面 |

## 3. L1 无障碍怎么点

- **按 text / content-desc / resource-id 定位，绝不写死坐标。** 写死坐标等于给自己埋雷。
- 每次点击后必须校验：界面是否变化（`dumpsys window | grep mCurrentFocus` 或控件树 diff）。**没校验的点击不算成功。**
- 需要等待时，轮询目标节点是否出现，而不是 `sleep 3` 硬等。
- 找不到节点时先滚动一次再找，仍找不到就降级，不要反复乱点。

## 4. L2 视觉兜底

只在前两层都失败时用：

1. `screencap -p /sdcard/shot.png` 截图
2. 描述界面 → 定位目标元素 → 给出**相对位置**（如「右上角第二个图标」）
3. 换算成像素坐标 → 点击
4. 再截一张图确认变化

**截图全黑 = FLAG_SECURE**（银行、支付、DRM 视频）。这种情况直接报 L3，不要尝试绕过。

## 5. 中文输入（高频坑）

`input text` **只支持 ASCII**，中文会静默失败或变成问号。正确做法按优先级：

1. **剪贴板 + 粘贴**：先写入剪贴板，再 `sh -c 'input keyevent 279'`（KEYCODE_PASTE）
2. **直接 setText**：无障碍找到输入框后调用 `ACTION_SET_TEXT`，这是最干净的方式
3. **ADBKeyboard 输入法**：装了才可用，走 broadcast 注入

空格用 `%s`，特殊字符要转义：`input text "a%sb"`。

## 6. 红线（不商量，碰到就停）

- **支付 / 转账 / 密码 / 验证码**：最多把界面打开，最后一步必须人来点
- **删除、卸载、扣费、把数据发给第三方**：执行前复述一遍要做什么，等确认
- **批量加好友、群发、刷单、养号**：不做
- **改 Secure Settings、停用系统应用、清数据**：除非用户当次明确要求
- **绕过风控**：不改设备指纹、不 hook、不伪装模拟器
- 用户主力机上的**无人值守**操作：不做

## 7. 怎么报结果

三段式，不许只说「已完成」：

```
做了什么：am start 打开高德并传入了目的地
结果：Status: ok，LaunchState: COLD，当前界面 com.autonavi.minimap
卡点：无
```

失败时：

```
做了什么：试了 3 个意图（taobao://item、openapp.jdmobile、https 商品页）
结果：前两个 ActivityNotFoundException，第三个跳到了浏览器
卡点：L0 无可用入口，L1 控件树里商品列表为空（Flutter 渲染）
建议：改用 L2 截图定位，或由你手动确认是否继续
```

**证据规则**：说「成功」必须有 `Status: ok`、界面变化、或可读的返回值。三者都没有，就是没成功。

## 8. 交付前自检

- [ ] 先试了 L0，而不是直接点
- [ ] 所有坐标都是算出来的，不是猜的
- [ ] 每一步都有校验，不是点完就往下走
- [ ] 没碰红线；碰了就停下来问了
- [ ] 失败如实报告，没说「已完成」
- [ ] 调用的 CLI 子命令都真实存在（`--help` 验证过）

## 参考

- 常用意图配方：`references/intent-recipes.md`
- 探测脚本：`scripts/probe_app_intents.sh`
