# Minis 手机操作技能包

给 OpenMinis（或任何能读 SKILL.md 的手机 Agent）用的操作规范，核心一条：**能走意图层就别点屏幕**。

## 包内容

```
minis-phone-ops/
├── SKILL.md                        主规范（四级降级、校验、红线）
├── references/intent-recipes.md    常用意图配方 + 探测流程
└── scripts/probe_app_intents.sh    探测 App 意图入口的脚本
```

## 安装

**方式一：装成技能（推荐）**

把整个 `minis-phone-ops` 文件夹放进 Minis 的技能目录——就是 App 里「技能 / Skills」页面列出来的那个位置。Minis 的技能格式就是一个带 `SKILL.md` 的文件夹，支持 `references/`、`scripts/`、`assets/` 子目录，和 Claude / Codex / OpenClaw 的技能通用。

放好之后重开 App，技能列表里应该能看到 `phone-intent-first`。

**方式二：当附件发过去**

不想动目录的话，把 `SKILL.md` 直接发给 Minis，说「按这个规范操作手机」，它读完就能照做。缺点是下次会话得再发一遍。

## 开场指令（每次新任务可以粘这段）

```
操作手机前先读 phone-intent-first 技能。按意图层优先降级：先探测再执行，
每步校验结果，碰支付/验证码/删除就停下来问我。失败如实说卡在哪一层。
```

## 建议的上手顺序

1. **先探路**：让它跑 `sh probe_app_intents.sh --send` 和 `--view`，看看系统里有哪些可用入口
2. **再小任务验证**：从「导航到某地」「发条短信」「打开 WiFi 设置」这类单步动作开始
3. **确认可靠后再放开**：多步流程、跨 App 传数据

## 注意事项

- 脚本里的 `dumpsys` / `cmd package` 需要 shell 级权限，**Shizuku 没起来的话只能干瞪眼**
- 第三方 App 的 scheme 会随版本失效，配方表里的「需探测」项不要直接照抄
- 中文输入 `input text` 不支持，走剪贴板 + `input keyevent 279`，或无障碍 `ACTION_SET_TEXT`
- 截图全黑说明是 FLAG_SECURE 窗口（银行/支付），不是 bug，别尝试绕过
