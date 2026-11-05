# nav-agent

**含高德地图导航能力的 Android 语音智能体。**

开车时不用看屏幕 —— 说一句话就能算路、查天气、问新闻，回答直接念给你听。

```
说「小猪」唤醒  →  「导航去张掖西站」  →  地图上开始导航
                        ↓
              「今天张掖有什么新闻」  →  检索 →  读原文  →  播报
```

## 项目在哪

源码在 **[`nav-agent/`](nav-agent/)**，那才是仓库正主 —— 完整说明见
**[`nav-agent/README.md`](nav-agent/README.md)**。

```
nav-agent/
├── android/         Android 工程（app + core 两个模块）
├── DESIGN.md        产品选型与里程碑
└── HANDOFF.md       状态快照与下一步
```

## 三分钟了解

- **纯语音**：主界面就是导航页，不显示聊天记录，回答只用 TTS 播报
- **离线唤醒**：关键词检测不联网，导航播报中也能被唤醒词打断
- **联网自己接**：模型没有内置联网，用 Function Calling + Tavily / 高德 API 补齐，
  并支持最多 3 轮检索（搜 → 读原文 → 换词再搜）
- **arm64 only**：高德 SDK 只提供 arm 的 so，模拟器跑不了导航主链路

## 构建

详见 [`nav-agent/README.md`](nav-agent/README.md) 的「快速开始」。
两件容易踩的事：

1. 首次构建前先跑 `tools/fetch_amap_sdk.sh` 和 `tools/fetch_sherpa.sh` 拉二进制
2. `GRADLE_OPTS` 的超时**不能省**，否则从 `services.gradle.org` 下依赖会卡死

## 文档

| 文件 | 内容 |
|---|---|
| [`nav-agent/README.md`](nav-agent/README.md) | 项目总览、快速开始、架构 |
| [`nav-agent/android/README.md`](nav-agent/android/README.md) | **工程主文档**：环境、构建、模块、关键设计决策、踩过的坑 |
| [`nav-agent/DESIGN.md`](nav-agent/DESIGN.md) | 产品设计 |
| [`nav-agent/HANDOFF.md`](nav-agent/HANDOFF.md) | 状态快照与下一步 |

---

密钥一律放在 `nav-agent/android/local.properties`（已被 `.gitignore` 排除），**不要提交**。
