# 简历投递助手 (ResumePilot)

> **免录制、LLM 驱动的自动化简历投递 Android App**

用户不需要录制操作，只需要截图。LLM 看懂截图后自动生成操作模板，遇到 UI 变化自动修复。

## 核心特性

- 🤖 **LLM 驱动** — 接入大语言模型（GPT-4o / Claude / 千问VL / DeepSeek / Gemini），自动分析屏幕截图并生成操作脚本
- 📱 **零录制** — 无需手动录制操作流程，截屏即可自动生成模板
- 🔄 **自适应修复** — 遇到平台 UI 更新时，LLM 自动分析并修复操作模板
- 🎯 **多平台支持** — BOSS直聘、猎聘、前程无忧(51job) 平台适配
- ⏰ **定时投递** — 支持定时批量自动投递（精确闹钟 + 开机恢复）
- 🔍 **LLM 探索模式** — 输入任务描述，LLM 实时分析屏幕自主探索执行，一键生成可复用脚本
- 🔒 **安全** — API Key 使用 Android Keystore AES-GCM 加密存储，明文不落盘

## 架构概览

```
用户操作
    │
    ▼
┌─────────────────────────────────────────┐
│            UI 层 (Jetpack Compose)        │
│  主界面 / 引导截图 / 执行 / 看板 / 设置    │
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
│  脚本生成   │    │  控件树分析          │
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
| 语言 | Kotlin 1.9 |
| UI | Jetpack Compose + Material3 |
| 架构 | 分层架构 + 编排器模式 |
| 数据库 | Room (SQLite) |
| 存储 | DataStore Preferences |
| 自动化 | Android AccessibilityService + MediaProjection |
| LLM | OkHttp + 自定义多供应商 API 客户端（含指数退避重试） |
| 脚本 | YAML (kaml) |
| 安全 | Android Keystore AES-GCM 加密 |
| 构建 | Gradle 8.14 + KSP |
| CI/CD | GitHub Actions（构建 + Lint + 单元测试） |

## 快速开始

### 前置要求

- Android Studio（建议 2023.1.1 或更高）
- JDK 17（Release 构建）或 21
- Android SDK 34
- 国内网络环境已自动配置镜像（腾讯云 Gradle 镜像 + 阿里云 Maven 镜像）

### 构建

```bash
# 克隆项目
git clone https://github.com/Docking666/ResumePilot.git
cd ResumePilot

# Windows 使用 gradlew.bat；macOS/Linux 使用 ./gradlew
# Debug 构建
./gradlew assembleDebug

# Release 构建（R8 混淆）
./gradlew assembleRelease

# 运行单元测试（模板解析 / 脚本生成等 9 个用例）
./gradlew testDebugUnitTest
```

APK 输出位置：`app/build/outputs/apk/debug/`

> **国内网络提示**：项目默认使用官方 Gradle 发行版源（保证 GitHub Actions CI 可用）。
> 国内首次构建若 Gradle 下载慢，可手动把 `gradle/wrapper/gradle-wrapper.properties` 中
> `distributionUrl` 改为腾讯云镜像：
> `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.5-bin.zip`
> Maven 依赖已配置阿里云镜像，无需改动。

### 配置 LLM

1. 打开 App → **设置** Tab
2. 选择模型供应商（OpenAI / Claude / 千问VL / DeepSeek / Gemini）
3. 填写 API Key（**自动加密存储**，不会明文落盘）与模型名称
4. 保存配置

### 安装与使用（首次）

1. 安装 APK 到 Android 设备（需 Android 8.0+ / API 26+）
2. 在系统设置中开启 **无障碍服务** → **简历投递助手**
3. 打开 App，**设置** → 权限管理，按需开启：
   - **忽略电池优化**（确保后台自动投递持续运行）
   - **悬浮窗**（录制控制浮标，可选）
4. **引导** Tab：选择目标招聘平台
5. 按引导截图各页面（首次会请求"共享屏幕"授权，Android 14+ 由前台服务自动处理）
6. LLM 自动分析生成操作模板 → 保存
7. **执行** Tab：选择模板 → 输入关键词 → 一键执行自动投递

### 执行双模式

| 模式 | 说明 |
|------|------|
| 模板投递 | 使用已生成的平台模板执行「搜索 → 投递」流水线，失败自动 LLM 修复 |
| LLM 探索 | 输入任务描述，LLM 实时看屏幕自主操作，可一键生成可复用 YAML 脚本 |

## 项目结构

```
app/src/main/java/com/resumepilot/app/
├── adapter/          # 平台适配器 + 模板生成（BOSS直聘/猎聘/51job）
│   ├── boss/  liepin/  job51/
│   └── TemplateYamlParser.kt   # kaml YAML 模板解析
├── autoscript/       # 脚本生成器
├── data/             # 数据层（Room / DataStore / 统计）
├── engine/           # 核心引擎（动作定义、编排器）
├── llm/              # LLM 客户端（多供应商 + 重试）与 MCP 网关
│   └── mcp/tools/
├── resume/           # 简历数据与匹配
├── scheduler/        # 定时调度与批量投递（精确闹钟）
├── service/          # 无障碍服务 / 截图 / 前台服务
├── ui/               # Compose UI 界面
└── util/             # CryptoManager（Keystore 加解密）

app/src/test/         # JVM 单元测试（模板解析 / 脚本生成）
app/schemas/          # Room schema 导出（迁移参考）
```

## 工作原理

### 首次使用：引导建模板

1. 选择招聘平台
2. 按照引导截图各个页面
3. LLM 分析截图 → 识别可交互元素 → 生成语义化操作模板
4. 用户确认保存（解析失败可重试）

### 日常使用：一键执行

1. 选择已有模板，输入关键词/打招呼语
2. 一键执行 — 自动搜索岗位 → 逐个投递
3. 遇到 UI 变化 → LLM 自动修复模板 → 继续执行
4. 执行期间前台服务 + WakeLock 保活，切后台不中断

## 免责声明

本项目仅用于**个人学习与技术研究**。自动投递行为可能违反第三方招聘平台的用户协议，
使用本工具产生的一切后果由使用者自行承担。请合理控制使用频率，尊重平台规则。

## License

MIT
