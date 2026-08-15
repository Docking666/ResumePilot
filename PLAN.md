# 简历投递助手 (ResumePilot) — 免录制自动化投递方案

## 一、核心理念

> **用户不需要录制操作，只需要截图。LLM 看懂截图后自动生成操作模板，遇到 UI 变化自动修复。**

### 用户使用流程

```
第一次使用                      第二次及以后
    │                              │
    ▼                              ▼
选择平台 (BOSS直聘)           选择已有脚本
    │                              │
    ▼                              ▼
按引导截图各页面             一键执行 (零录制)
    │                              │
    ▼                              ▼
LLM 分析截图 → 生成模板      模板执行 → 成功/失败
    │                              │
    ▼                        失败 → LLM 自动修复
用户确认模板                          │
    │                               ▼
    ▼                          模板更新
保存 → 后续可直接执行
```

---

## 二、整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                         UI 层                                 │
│  ┌──────────┐  ┌───────────┐  ┌──────────┐  ┌───────────┐  │
│  │ 引导截图  │  │ 模板预览   │  │ 执行看板  │  │ 数据统计   │  │
│  └────┬─────┘  └─────┬─────┘  └────┬─────┘  └─────┬─────┘  │
└───────┼───────────────┼─────────────┼───────────────┼────────┘
        │               │             │               │
┌───────▼───────────────▼─────────────▼───────────────▼────────┐
│                      业务逻辑层                                │
│                                                                │
│  ┌──────────────────────────────────────────────────────┐     │
│  │              平台适配器工厂 (PlatformAdapterFactory)   │     │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐              │     │
│  │  │ BOSS直聘 │ │ 猎聘     │ │ 51job    │  ...         │     │
│  │  │ 适配器   │ │ 适配器   │ │ 适配器   │              │     │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘              │     │
│  └───────┼────────────┼────────────┼────────────────────┘     │
│          │            │            │                          │
│  ┌───────▼────────────▼────────────▼────────────────────┐     │
│  │             模板生成引擎 (TemplateGenerator)           │     │
│  │  截图 → LLM 视觉分析 → 生成 YAML 模板 → 用户确认       │     │
│  └──────────────────────┬───────────────────────────────┘     │
│                         │                                     │
│  ┌──────────────────────▼───────────────────────────────┐     │
│  │             工作流引擎 (WorkflowEngine)                │     │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐    │     │
│  │  │ 模板执行  │  │ 状态验证  │  │ LLM 自适应修复    │    │     │
│  │  └──────────┘  └──────────┘  └──────────────────┘    │     │
│  └──────────────────────┬───────────────────────────────┘     │
│                         │                                     │
│  ┌──────────────────────▼───────────────────────────────┐     │
│  │             批量调度器 (BatchScheduler)                │     │
│  │  搜索 → 匹配 → 生成打招呼语 → 投递 → 记录 → 循环     │     │
│  └──────────────────────────────────────────────────────┘     │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│                      执行层 (已有)                             │
│  MCP 网关 → MCP 工具 → AccessibilityService → 手机操作       │
└──────────────────────────────────────────────────────────────┘
```

---

## 三、核心模块设计

### 模块 1：平台适配器工厂

```kotlin
interface PlatformAdapter {
    val platformName: String        // "BOSS直聘"
    val appPackage: String          // "com.hpbr.bosszhipin"
    val supportedWorkflows: List<String>  // ["search", "apply", "chat"]
    
    /** 生成模板：引导用户截图 → LLM 分析 → 生成 YAML */
    suspend fun generateTemplate(
        screenshotProvider: ScreenshotProvider,
        llmClient: LLMClient
    ): PlatformTemplate
    
    /** 用模板执行工作流 */
    suspend fun executeWorkflow(
        workflow: String,
        params: Map<String, String>,
        template: PlatformTemplate,
        engine: WorkflowEngine
    ): WorkflowResult
}
```

**关键设计**：每个适配器知道自己的工作流有哪些步骤，但**不知道具体怎么操作**——操作方式由 LLM 分析截图后生成的模板决定。

### 模块 2：模板生成引擎（核心创新）

```
用户点击"生成BOSS直聘模板"
         │
         ▼
模板引擎按预定义的"截图清单"引导用户
         │
         ├── 第1步: "请打开BOSS直聘，停留在首页，然后点'截图'"
         │     └── LLM 分析 → 识别首页元素: 搜索框、职位Tab、消息Tab
         │
         ├── 第2步: "请在搜索框输入'Java开发'，停留在搜索结果页，然后截图"
         │     └── LLM 分析 → 识别职位列表、投递按钮、筛选条件
         │
         ├── 第3步: "请点击任意一个职位，进入详情页，然后截图"
         │     └── LLM 分析 → 识别公司信息、薪资、投递按钮、打招呼入口
         │
         ├── 第4步: "请点击'投递'，进入打招呼页面，然后截图"
         │     └── LLM 分析 → 识别输入框、发送按钮、快捷打招呼选项
         │
         └── 生成 YAML 模板
               │
               ▼
         用户预览 → 确认 → 保存
```

**模板数据结构**：

```kotlin
data class PlatformTemplate(
    val id: String,
    val platformName: String,
    val appPackage: String,
    val version: Int,
    val screenshots: List<ScreenshotAnalysis>,  // 每张截图的分析结果
    val workflows: Map<String, WorkflowDef>,     // 工作流定义
    val createdAt: Long,
    val updatedAt: Long
)

data class ScreenshotAnalysis(
    val pageName: String,           // "首页", "搜索结果页", "职位详情页", "打招呼页"
    val description: String,        // LLM 对页面的描述
    val elements: List<UIElement>,  // 识别出的可交互元素
    val elementMapping: Map<String, String>  // {"搜索框": "text=搜索", "投递按钮": "text=投递"}
)

data class WorkflowDef(
    val name: String,               // "search_jobs"
    val description: String,
    val steps: List<WorkflowStep>,  // 操作步骤
    val requiredParams: List<String> // 需要的参数: ["keyword", "city"]
)

data class WorkflowStep(
    val action: String,             // "find_and_click", "type", "scroll", "wait"
    val target: String,             // 引用 elementMapping 的 key
    val params: Map<String, String> = emptyMap(),
    val waitAfter: Long = 500
)
```

### 模块 3：工作流引擎（自动修复核心）

```kotlin
class WorkflowEngine {
    /**
     * 执行工作流，支持自动修复
     */
    suspend fun execute(
        template: PlatformTemplate,
        workflow: String,
        params: Map<String, String>
    ): ExecutionResult {
        val steps = template.workflows[workflow]?.steps ?: return error
        
        for (step in steps) {
            val success = executeStep(step, params)
            if (!success) {
                // 自动修复流程
                val repaired = autoRepair(step, template)
                if (repaired) continue
                return ExecutionResult(success = false, failedStep = step)
            }
        }
        return ExecutionResult(success = true)
    }
    
    /**
     * LLM 自适应修复：当模板步骤失效时
     * 1. 截图当前屏幕
     * 2. 问 LLM "用户想执行'点击投递按钮'，当前屏幕长这样，应该点哪里？"
     * 3. LLM 返回新的操作指令
     * 4. 执行并更新模板
     */
    private suspend fun autoRepair(failedStep: WorkflowStep, template: PlatformTemplate): Boolean {
        val screenshot = captureScreenshot()
        val decision = llmClient.decideNextAction(
            taskDescription = "当前在执行${template.platformName}的${failedStep.action}操作，目标: ${failedStep.target}",
            screenshotBase64 = screenshot
        )
        // ... 执行 LLM 的决策并更新模板
    }
}
```

### 模块 4：批量调度器

```kotlin
class BatchScheduler {
    /**
     * 完整投递流水线
     */
    suspend fun runPipeline(platform: String, keyword: String) {
        // 1. 加载模板
        val template = templateRepository.load(platform)
        
        // 2. 搜索岗位
        val jobs = workflowEngine.execute(template, "search_jobs", mapOf("keyword" to keyword))
        
        // 3. 逐个匹配和投递
        for (job in jobs) {
            // 匹配度评分
            val match = jobMatcher.matchJob(resume, job)
            if (match.overallScore < 30) continue  // 低于阈值跳过
            
            // 生成打招呼语
            val greeting = jobMatcher.generateGreeting(resume, job)
            
            // 执行投递
            workflowEngine.execute(template, "apply_job", mapOf(
                "greeting" to greeting.content
            ))
            
            // 记录结果
            recordApplication(job, match, greeting)
        }
    }
}
```

---

## 四、用户操作流程（分步说明）

### 首次使用：引导截图建模板

```
1. 打开简历投递助手 App
2. 点击"添加平台"
3. 选择 "BOSS直聘"
4. App 弹出引导浮窗:
   ┌─────────────────────────────────────┐
   │  📸 第1步: 截图首页                  │
   │                                      │
   │  请打开 BOSS直聘，停留在首页          │
   │  确保可以看到: 搜索框、Tab栏         │
   │                                      │
   │     [打开 BOSS直聘]  [我已就绪]       │
   └─────────────────────────────────────┘
5. 用户打开 BOSS直聘 → 回到简历投递助手 → 点"我已就绪"
6. 简历投递助手自动截图（通过 MediaProjection）
7. 发送给 LLM 分析 → 显示分析结果:
   ┌─────────────────────────────────────┐
   │  ✅ 首页分析完成                     │
   │                                      │
   │  识别到以下元素:                     │
   │  • 搜索框 (点击后可输入关键词)        │
   │  • 职位推荐列表                      │
   │  • 底部Tab: 职位/消息/我的           │
   │                                      │
   │     [确认]  [重新截图]               │
   └─────────────────────────────────────┘
8. 重复步骤 2-4 步，完成所有页面截图
9. LLM 生成完整 YAML 模板
10. 用户预览 → 确认 → 保存
```

### 日常使用：一键执行

```
1. 打开简历投递助手
2. 选择 "BOSS直聘" 脚本
3. 输入关键词: "Java开发"
4. 点击 "开始执行"
5. 工作流引擎自动执行:
   ┌─────────────────────────────────────┐
   │  🚀 执行中 (3/15)                   │
   │                                      │
   │  ✅ 打开 BOSS直聘                    │
   │  ✅ 输入 "Java开发"                  │
   │  ✅ 进入搜索结果页                   │
   │  ⏳ 正在投递第1个岗位...              │
   │                                      │
   │     [暂停]  [停止]                   │
   └─────────────────────────────────────┘
6. 执行完成 → 显示结果摘要
```

---

## 五、数据流设计

```
截图引导
    │
    ▼
┌──────────────┐     ┌──────────────────┐
│  用户截图     │ ──→ │  LLM 视觉分析     │
│  (5-8张)     │     │  GPT-4o/千问VL    │
└──────────────┘     └────────┬─────────┘
                              │ 结构化输出
                              ▼
                    ┌──────────────────┐
                    │  YAML 模板        │
                    │  (元素映射+工作流)  │
                    └────────┬─────────┘
                              │ 保存
                              ▼
                    ┌──────────────────┐
                    │  Room 数据库      │
                    │  templates 表     │
                    └────────┬─────────┘
                              │ 加载
                              ▼
                    ┌──────────────────┐
                    │  工作流引擎执行    │
                    │  (步骤循环)       │
                    └────────┬─────────┘
                              │ 失败
                              ▼
                    ┌──────────────────┐
                    │  LLM 自适应修复   │
                    │  → 更新模板       │
                    └──────────────────┘
```

---

## 六、实施路线

### Phase 1：核心基建 (当前开始)

| 模块 | 文件 | 工作量 |
|------|------|--------|
| 平台适配器接口 + 工厂 | `adapter/PlatformAdapter.kt` | 小 |
| 模板数据模型 | `adapter/PlatformTemplate.kt` | 小 |
| 模板生成引擎 | `adapter/TemplateGenerator.kt` | 中 |
| 工作流引擎 | `adapter/WorkflowEngine.kt` | 中 |
| 引导截图 UI | `ui/SetupGuideScreen.kt` | 中 |
| BOSS直聘适配器实现 | `adapter/boss/BossAdapter.kt` | 中 |

### Phase 2：自动化闭环

| 模块 | 说明 |
|------|------|
| 批量调度器 | 搜索 → 匹配 → 投递 流水线 |
| 定时任务 | 每天自动执行 |
| 数据看板 | 投递统计图表 |

### Phase 3：多平台扩展

| 模块 | 说明 |
|------|------|
| 猎聘适配器 | 同 Phase 1 流程 |
| 51job/智联适配器 | 同 Phase 1 流程 |
| 模板市场 | 用户分享模板 |

---

## 七、技术关键点

### 1. 截图引导如何精准定位

引导文案不是写死的，而是由 LLM 根据上次截图分析结果动态生成：

```kotlin
// 第1步截图分析完成 → LLM 自动决定第2步引导什么
val nextStep = llmClient.generateNextStep(
    previousAnalysis = "首页有搜索框和职位列表",
    completedPages = ["首页"],
    targetPlatform = "BOSS直聘"
)
// LLM 输出: "请点击搜索框，输入任意关键词，进入搜索结果页后截图"
```

### 2. 模板如何抗 UI 变化

模板存储的是**语义映射**而非坐标：

```yaml
# 模板 (语义级)
steps:
  - action: find_and_click
    target: "搜索框"          # 语义层
    description: "点击搜索框进入搜索界面"

# 执行时解析为具体定位
# 1. 优先用映射: elementMapping["搜索框"] = "text=搜索" 
# 2. 映射失效 → OCR 找 "搜索" 文字
# 3. OCR 找不到 → LLM 视觉分析截图找搜索框
```

### 3. 模板更新策略

```
每次成功执行 → 记录元素的实际坐标和控件路径
多次执行后 → 统计最优定位方式 (控件ID > 文本 > OCR > 坐标)
生成模板的"经验版本" → 越用越稳定
```

---

## 八、文件清单 (新增)

```
app/src/main/java/com/resumepilot/app/adapter/
├── PlatformAdapter.kt           # 适配器接口
├── PlatformTemplate.kt          # 模板数据模型
├── TemplateGenerator.kt         # 模板生成引擎 (LLM 截图分析)
├── WorkflowEngine.kt            # 工作流引擎 (执行+自动修复)
├── BatchScheduler.kt            # 批量调度器
└── boss/
    ├── BossAdapter.kt           # BOSS直聘适配器
    └── boss_pages.yaml          # 预置的页面截图清单

app/src/main/java/com/resumepilot/app/ui/
├── SetupGuideScreen.kt          # 引导截图界面
├── TemplatePreviewScreen.kt     # 模板预览确认界面
└── ExecutionScreen.kt           # 执行过程界面

app/src/main/java/com/resumepilot/app/data/db/
├── TemplateEntity.kt            # 模板数据库实体
├── TemplateDao.kt               # 模板 DAO
└── ExecutionRecord.kt           # 执行记录实体
```

---

## 九、与现有架构的关系

```
现有模块:                         新增模块:
┌──────────┐                    ┌──────────────────┐
│  LLM     │ ←── MCP 工具 ──── │  PlatformAdapter  │
│  Client  │                    │  TemplateGenerator │
└──────────┘                    └────────┬─────────┘
                                         │ 调用
┌──────────┐                    ┌────────▼─────────┐
│  MCP     │ ←── 工具调用 ──── │  WorkflowEngine   │
│  Gateway │                    │  (执行+修复)      │
└──────────┘                    └────────┬─────────┘
                                         │ 执行
┌──────────┐                    ┌────────▼─────────┐
│  RPA     │ ←── 动作调用 ──── │  Accessibility    │
│  Service │                    │  Service          │
└──────────┘                    └──────────────────┘
```

**新增模块不替换现有模块，而是在上层编排**：
- 现有 `AccessibilityService` 仍是执行层
- 现有 `MCP Gateway` 仍是工具抽象层
- 新增的 `PlatformAdapter` + `WorkflowEngine` 是业务编排层

---

## 十、方案总结

| 维度 | 方案 |
|------|------|
| **用户门槛** | 从"录制坐标"降为"截图确认"，引导式操作 |
| **首次配置** | 5-8 张截图，约 3-5 分钟 |
| **日常使用** | 一键执行，无需任何操作 |
| **抗 UI 变化** | LLM 视觉自适应修复，无需用户介入 |
| **模板生成** | LLM 分析截图 → 语义级 YAML 模板 |
| **执行方式** | 工作流引擎逐步骤执行，失败自动修复 |
| **扩展性** | 新增平台只需实现适配器接口 + 截图引导流程 |