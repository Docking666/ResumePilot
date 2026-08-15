package com.autorpa.app.scheduler

import com.autorpa.app.AutoRPAApp
import com.autorpa.app.adapter.PlatformTemplate
import com.autorpa.app.adapter.WorkflowEngine
import com.autorpa.app.adapter.WorkflowResult
import com.autorpa.app.data.db.ExecutionLogEntity
import com.autorpa.app.data.db.StatsEntity
import com.autorpa.app.llm.LLMClient
import com.autorpa.app.resume.GreetingStyle
import com.autorpa.app.resume.JobDescription
import com.autorpa.app.resume.JobMatcher
import com.autorpa.app.resume.ResumeData
import com.autorpa.app.service.RPAAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

/**
 * 批量调度器——完整投递流水线
 *
 * 流程：
 *  加载模板 → 搜索岗位 → 匹配评分 → 生成打招呼语 → 投递 → 记录 → 循环
 *
 * 设计原则：
 * - 每个步骤独立可观察（通过 onEvent 回调）
 * - 支持暂停/继续（通过协程取消）
 * - 错误容错（单个投递失败不影响后续）
 */
class BatchScheduler(
    private val accessibilityService: RPAAccessibilityService?,
    private val llmClient: LLMClient?
) {

    /** 调度事件回调 */
    var onEvent: ((BatchEvent) -> Unit)? = null

    /** 是否暂停 */
    @Volatile
    var paused = false

    /** 是否取消 */
    @Volatile
    var cancelled = false

    /** 统计信息 */
    @Volatile
    var stats = BatchStats()

    private val engine = WorkflowEngine(accessibilityService, llmClient)

    /**
     * 执行完整投递流水线
     */
    suspend fun runPipeline(
        template: PlatformTemplate,
        config: PipelineConfig
    ): PipelineResult = withContext(Dispatchers.IO) {
        val app = AutoRPAApp.instance
        val startTime = System.currentTimeMillis()
        stats = BatchStats(templateName = template.platformName)
        cancelled = false
        paused = false

        emitEvent(BatchEvent.PipelineStarted(template.platformName, config))

        // 1. 加载简历
        val resume = app.resumeManager.getActiveResume()
        if (resume == null) {
            emitEvent(BatchEvent.PipelineError("未设置简历，请先在简历Tab上传"))
            return@withContext PipelineResult(
                success = false, templateName = template.platformName,
                error = "未设置简历"
            )
        }

        // 2. 搜索岗位
        emitEvent(BatchEvent.Searching(config.keyword, config.city))
        val searchResult = engine.execute(
            template = template,
            workflowName = "search_jobs",
            params = mapOf("keyword" to config.keyword, "city" to config.city)
        )

        if (!searchResult.success) {
            emitEvent(BatchEvent.PipelineError("搜索失败: ${searchResult.errorMessage}"))
            return@withContext PipelineResult(
                success = false, templateName = template.platformName,
                error = searchResult.errorMessage
            )
        }
        emitEvent(BatchEvent.SearchComplete)

        // 3. 初始化匹配器
        val matcher = JobMatcher(llmClient?.config ?: return@withContext PipelineResult(
            success = false, templateName = template.platformName, error = "LLM 未配置"
        ))

        // 4. 投递循环
        val appliedJobs = mutableListOf<AppliedJobRecord>()
        var successCount = 0
        var failCount = 0
        var skipCount = 0

        for (i in 0 until config.maxApplications) {
            if (cancelled) {
                emitEvent(BatchEvent.PipelineCancelled)
                break
            }

            while (paused && !cancelled) {
                delay(500)
            }

            if (cancelled) break

            emitEvent(BatchEvent.Applying(i + 1, config.maxApplications))

            // 4a. 匹配评分（先从搜索结果中获取当前岗位信息）
            val currentJob = JobDescription(
                title = config.keyword,
                company = "",
                requirements = parseKeywordToRequirements(config.keyword)
            )
            val matchResult = matcher.matchJob(resume, currentJob)

            // 4b. 低于阈值跳过
            if (matchResult.overallScore < config.minMatchScore) {
                emitEvent(BatchEvent.Skipped(currentJob.title, matchResult.overallScore))
                skipCount++
                // 跳到下一个岗位
                val nextResult = engine.execute(template, "next_job", emptyMap())
                if (!nextResult.success) {
                    emitEvent(BatchEvent.PipelineError("无法获取下一个岗位"))
                    break
                }
                delay(Random.nextLong(2000, 5000))
                continue
            }

            // 4c. 生成打招呼语
            val greeting = if (config.customGreeting.isNotBlank()) {
                com.autorpa.app.resume.GreetingMessage(
                    content = config.customGreeting,
                    style = GreetingStyle.PROFESSIONAL,
                    targetJobTitle = currentJob.title,
                    targetCompany = currentJob.company
                )
            } else {
                matcher.generateGreeting(resume, currentJob, GreetingStyle.PROFESSIONAL)
            }
            emitEvent(BatchEvent.GreetingGenerated(greeting.content))

            // 4d. 执行投递
            val applyResult = engine.execute(
                template = template,
                workflowName = "apply_job",
                params = mapOf("greeting_text" to greeting.content)
            )

            if (applyResult.success) {
                successCount++
                emitEvent(BatchEvent.Applied(currentJob.title, matchResult.overallScore))
            } else {
                failCount++
                emitEvent(BatchEvent.ApplyFailed(currentJob.title, applyResult.errorMessage ?: "未知错误"))
            }

            // 记录投递结果
            val record = AppliedJobRecord(
                jobTitle = currentJob.title,
                matchScore = matchResult.overallScore,
                greetingUsed = greeting.content,
                success = applyResult.success,
                errorMessage = if (!applyResult.success) applyResult.errorMessage else null
            )
            appliedJobs.add(record)

            // 4e. 投递间隔防检测
            if (i < config.maxApplications - 1 && !cancelled) {
                val delayMs = Random.nextLong(
                    config.minIntervalMs.coerceAtLeast(30_000),
                    config.maxIntervalMs.coerceAtMost(180_000)
                )
                emitEvent(BatchEvent.Waiting(delayMs))
                delay(delayMs)
            }
        }

        // 5. 保存执行记录
        val totalDuration = System.currentTimeMillis() - startTime
        val logEntity = ExecutionLogEntity(
            scriptId = template.id,
            scriptName = template.platformName,
            startedAt = startTime,
            completedAt = System.currentTimeMillis(),
            success = cancelled || successCount > 0,
            errorMessage = if (cancelled) "用户取消" else null,
            stepsCompleted = successCount,
            stepsTotal = config.maxApplications
        )
        app.database.scriptDao().insertLog(logEntity)

        // 6. 更新日统计
        updateDailyStats(app, template.platformName, successCount, failCount, skipCount, totalDuration)

        // 更新模板统计
        app.database.templateDao().updateRunStats(template.id, 0)

        stats = stats.copy(
            successCount = successCount,
            failCount = failCount,
            skipCount = skipCount,
            totalDuration = totalDuration
        )

        emitEvent(BatchEvent.PipelineCompleted(
            successCount = successCount,
            failCount = failCount,
            skipCount = skipCount,
            totalDuration = totalDuration
        ))

        PipelineResult(
            success = cancelled || successCount > 0,
            templateName = template.platformName,
            appliedCount = successCount,
            failedCount = failCount,
            skippedCount = skipCount,
            totalDuration = totalDuration,
            records = appliedJobs,
            wasCancelled = cancelled
        )
    }

    /** 暂停 */
    fun pause() { paused = true }

    /** 继续 */
    fun resume() { paused = false }

    /** 取消 */
    fun cancel() { cancelled = true }

    private fun emitEvent(event: BatchEvent) {
        onEvent?.invoke(event)
    }

    private fun parseKeywordToRequirements(keyword: String): List<String> {
        return keyword.split(Regex("[ ,，/、]")).filter { it.isNotBlank() }
    }

    private suspend fun updateDailyStats(
        app: AutoRPAApp,
        platformName: String,
        success: Int,
        fail: Int,
        skip: Int,
        duration: Long
    ) {
        try {
            val today = java.time.LocalDate.now().toString()
            val existing = app.database.statsDao().getStatsByDate(today)

            if (existing != null) {
                app.database.statsDao().updateStats(
                    id = existing.id,
                    success = existing.apliedSuccess + success,
                    fail = existing.apliedFail + fail,
                    skip = existing.apliedSkip + skip,
                    duration = existing.totalDuration + duration
                )
            } else {
                app.database.statsDao().insertStats(
                    StatsEntity(
                        id = 0,
                        date = today,
                        platformName = platformName,
                        apliedSuccess = success,
                        apliedFail = fail,
                        apliedSkip = skip,
                        totalDuration = duration
                    )
                )
            }
        } catch (_: Exception) { }
    }
}

// ====== 数据类 ======

/** 流水线配置 */
data class PipelineConfig(
    val keyword: String,
    val city: String = "",
    val customGreeting: String = "",
    val maxApplications: Int = 30,
    val minMatchScore: Int = 30,
    val minIntervalMs: Long = 30_000,
    val maxIntervalMs: Long = 90_000
)

/** 流水线执行结果 */
data class PipelineResult(
    val success: Boolean,
    val templateName: String,
    val appliedCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val totalDuration: Long = 0,
    val error: String? = null,
    val records: List<AppliedJobRecord> = emptyList(),
    val wasCancelled: Boolean = false
)

/** 单次投递记录 */
data class AppliedJobRecord(
    val jobTitle: String,
    val matchScore: Int,
    val greetingUsed: String,
    val success: Boolean,
    val errorMessage: String? = null
)

/** 调度统计 */
data class BatchStats(
    val templateName: String = "",
    val successCount: Int = 0,
    val failCount: Int = 0,
    val skipCount: Int = 0,
    val totalDuration: Long = 0
)

/** 调度事件 */
sealed class BatchEvent {
    data class PipelineStarted(val platform: String, val config: PipelineConfig) : BatchEvent()
    data class Searching(val keyword: String, val city: String) : BatchEvent()
    data object SearchComplete : BatchEvent()
    data class Applying(val current: Int, val total: Int) : BatchEvent()
    data class Skipped(val title: String, val score: Int) : BatchEvent()
    data class GreetingGenerated(val greeting: String) : BatchEvent()
    data class Applied(val title: String, val score: Int) : BatchEvent()
    data class ApplyFailed(val title: String, val error: String) : BatchEvent()
    data class Waiting(val millis: Long) : BatchEvent()
    data class PipelineError(val error: String) : BatchEvent()
    data object PipelineCancelled : BatchEvent()
    data class PipelineCompleted(
        val successCount: Int, val failCount: Int,
        val skipCount: Int, val totalDuration: Long
    ) : BatchEvent()
}