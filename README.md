# 简历投递助手 (ResumePilot)

> **免录制、LLM 驱动的自动化简历投递 Android App**

用户不需要录制操作，只需要截图。LLM 看懂截图后自动生成操作模板，遇到 UI 变化自动修复。

## 核心特性

- 🤖 **LLM 驱动** — 接入大语言模型，自动分析屏幕截图并生成操作脚本
- 📱 **零录制** — 无需手动录制操作流程，截屏即可自动生成模板
- 🔄 **自适应修复** — 遇到平台 UI 更新时，LLM 自动分析并修复操作模板
- 🎯 **多平台支持** — BOSS直聘、猎聘、51job 等平台适配
- ⏰ **定时投递** — 支持定时批量自动投递简历
- 🧩 **模板市场** — 导出/导入操作模板，社区共享

## 架构概览

```
用户操作
    │
    ▼
┌─────────────────────────────────────────┐
│            UI 层 (Jetpack Compose)        │
│  主界面 / 引导截图 / 执行监控 / 数据看板   │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│          Orchestrator 编排层              │
│   流程编排 / LLM 调度 / 状态管理          │
└──────┬──────────────────────┬───────────┘
       │                      │
┌──────▼──────┐    ┌─────────▼──────────┐
│  LLM 引擎   │    │    RPA 执行引擎      │
│  截图分析   │    │  无障碍服务手势操作   │
│  决策生成   │    │  动作录制与回放      │
│  脚本生成   │    │  控件树分析         │
└─────────────┘    └────────────────────┘
       │                      │
       └──────┬───────────────┘
              │
┌─────────────▼──────────────┐
│      平台适配器层           │
│  BOSS直聘 / 猎聘 / 51job   │
└────────────────────────────┘
```

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 架构 | MVVM + 编排器模式 |
| 数据库 | Room (SQLite) |
| 存储 | DataStore Preferences |
| 自动化 | Android AccessibilityService |
| LLM | OkHttp + 自定义 API 客户端 |
| 脚本 | YAML (kaml) |
| 构建 | Gradle + KSP |
| CI/CD | GitHub Actions |

## 快速开始

### 前置要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34

### 构建

```bash
# 克隆项目
git clone https://github.com/Docking666/ResumePilot.git
cd ResumePilot

# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease
```

APK 输出位置：`app/build/outputs/apk/debug/`

### 安装与使用

1. 安装 APK 到 Android 设备（需 Android 8.0+）
2. 在系统设置中开启 **无障碍服务** → **简历投递助手**
3. 打开 App，选择目标招聘平台
4. 按引导截图各页面，LLM 自动分析生成模板
5. 保存模板后即可一键执行自动投递

## 项目结构

```
app/src/main/java/com/resumepilot/app/
├── adapter/          # 平台适配器（BOSS直聘/猎聘/51job）
│   ├── boss/
│   ├── liepin/
│   └── job51/
├── autoscript/       # 脚本生成器
├── data/             # 数据层
│   ├── db/           # Room 数据库实体
│   └── PreferencesManager.kt
├── engine/           # 核心引擎（动作定义、编排器）
├── llm/              # LLM 客户端与 MCP 网关
│   └── mcp/tools/
├── resume/           # 简历数据与匹配
├── scheduler/        # 定时调度与批量投递
├── service/          # 无障碍服务与录制
└── ui/               # Compose UI 界面
    └── theme/
```

## 工作原理

### 首次使用：引导建模板

1. 选择招聘平台
2. 按照引导截图各个页面
3. LLM 分析截图 → 识别可交互元素
4. 生成操作模板 → 用户确认保存

### 日常使用：一键执行

1. 选择已有模板
2. 输入关键词
3. 一键执行 — LLM 在过程中实时分析屏幕
4. 遇到 UI 变化 → LLM 自动修复 → 继续执行

## License

MIT