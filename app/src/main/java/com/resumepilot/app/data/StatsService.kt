package com.resumepilot.app.data

import com.resumepilot.app.ResumePilotApp
import com.resumepilot.app.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统计服务——聚合执行数据，提供看板数据源
 *
 * 职责：
 * 1. 从 daily_stats 和 execution_logs 表聚合数据
 * 2. 提供按日/周/月/平台维度的统计数据
 * 3. 驱动 DashboardScreen 的数据展示
 */
class StatsService(private val app: ResumePilotApp) {

    /**
     * 获取仪表盘总览数据
     */
    suspend fun getDashboardData(): DashboardData = withContext(Dispatchers.IO) {
        val totalSummary = try {
            app.database.statsDao().getTotalSummary()
        } catch (_: Exception) { TotalSummary() }

        val platformSummary = try {
            app.database.statsDao().getPlatformSummary()
        } catch (_: Exception) { emptyList() }

        val recent30Days = try {
            app.database.statsDao().getRecent30Days()
        } catch (_: Exception) { emptyList() }

        val logs = try {
            app.database.scriptDao().getRecentLogs()
        } catch (_: Exception) { emptyList<ExecutionLogEntity>() }

        // 计算成功率
        val totalAttempts = totalSummary.totalSuccess + totalSummary.totalFail
        val successRate = if (totalAttempts > 0)
            (totalSummary.totalSuccess * 100 / totalAttempts) else 0

        // 最近7天趋势
        val dailyTrend = recent30Days
            .groupBy { it.date }
            .map { (date, entries) ->
                DailyTrend(
                    date = date,
                    success = entries.sumOf { it.apliedSuccess },
                    fail = entries.sumOf { it.apliedFail }
                )
            }
            .sortedBy { it.date }
            .takeLast(7)

        DashboardData(
            totalAttempts = totalAttempts,
            totalSuccess = totalSummary.totalSuccess,
            totalFail = totalSummary.totalFail,
            successRate = successRate,
            totalDuration = recent30Days.sumOf { it.totalDuration },
            platformSummary = platformSummary.map {
                PlatformStat(
                    platform = it.platformName,
                    success = it.totalSuccess,
                    fail = it.totalFail
                )
            },
            dailyTrend = dailyTrend,
            recentLogs = logs.map {
                StatsLog(
                    date = java.text.SimpleDateFormat("MM-dd HH:mm",
                        java.util.Locale.getDefault()).format(java.util.Date(it.startedAt)),
                    platform = it.scriptName,
                    success = it.success,
                    errorMessage = it.errorMessage
                )
            }
        )
    }

    /**
     * 获取今日统计
     */
    suspend fun getTodayStats(): TodayStats = withContext(Dispatchers.IO) {
        val today = java.time.LocalDate.now().toString()
        val todayStats = try {
            app.database.statsDao().getStatsByDate(today)
        } catch (_: Exception) { emptyList<StatsEntity>() }

        TodayStats(
            success = todayStats.sumOf { it.apliedSuccess },
            fail = todayStats.sumOf { it.apliedFail },
            skip = todayStats.sumOf { it.apliedSkip },
            total = todayStats.sumOf { it.apliedSuccess + it.apliedFail + it.apliedSkip }
        )
    }
}

// ====== 数据模型 ======

data class DashboardData(
    val totalAttempts: Int = 0,
    val totalSuccess: Int = 0,
    val totalFail: Int = 0,
    val successRate: Int = 0,
    val totalDuration: Long = 0,
    val platformSummary: List<PlatformStat> = emptyList(),
    val dailyTrend: List<DailyTrend> = emptyList(),
    val recentLogs: List<StatsLog> = emptyList()
)

data class PlatformStat(
    val platform: String,
    val success: Int,
    val fail: Int
)

data class DailyTrend(
    val date: String,
    val success: Int,
    val fail: Int
)

data class StatsLog(
    val date: String,
    val platform: String,
    val success: Boolean,
    val errorMessage: String? = null
)

data class TodayStats(
    val success: Int = 0,
    val fail: Int = 0,
    val skip: Int = 0,
    val total: Int = 0
)