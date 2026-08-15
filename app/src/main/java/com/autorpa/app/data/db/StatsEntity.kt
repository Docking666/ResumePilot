package com.autorpa.app.data.db

import androidx.room.*

/**
 * 每日统计数据
 */
@Entity(tableName = "daily_stats")
data class StatsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "date") val date: String,                     // "2026-08-14"
    @ColumnInfo(name = "platform_name") val platformName: String,     // "BOSS直聘"
    @ColumnInfo(name = "applied_success") val apliedSuccess: Int = 0,
    @ColumnInfo(name = "applied_fail") val apliedFail: Int = 0,
    @ColumnInfo(name = "applied_skip") val apliedSkip: Int = 0,
    @ColumnInfo(name = "total_duration") val totalDuration: Long = 0
)

@Dao
interface StatsDao {
    @Query("SELECT * FROM daily_stats ORDER BY date DESC")
    suspend fun getAllStats(): List<StatsEntity>

    @Query("SELECT * FROM daily_stats WHERE date = :date ORDER BY platform_name")
    suspend fun getStatsByDate(date: String): List<StatsEntity>

    @Query("SELECT * FROM daily_stats WHERE date = :date AND platform_name = :platform LIMIT 1")
    suspend fun getStatsByDateAndPlatform(date: String, platform: String): StatsEntity?

    @Query("SELECT * FROM daily_stats ORDER BY date DESC LIMIT 30")
    suspend fun getRecent30Days(): List<StatsEntity>

    @Query("SELECT platform_name, SUM(applied_success) as total_success, SUM(applied_fail) as total_fail FROM daily_stats GROUP BY platform_name ORDER BY total_success DESC")
    suspend fun getPlatformSummary(): List<PlatformSummary>

    @Query("SELECT SUM(applied_success) as total_success, SUM(applied_fail) as total_fail FROM daily_stats")
    suspend fun getTotalSummary(): TotalSummary

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(stats: StatsEntity)

    @Query("UPDATE daily_stats SET applied_success = :success, applied_fail = :fail, applied_skip = :skip, total_duration = :duration WHERE id = :id")
    suspend fun updateStats(id: Long, success: Int, fail: Int, skip: Int, duration: Long)
}

/** 平台汇总 */
data class PlatformSummary(
    @ColumnInfo(name = "platform_name") val platformName: String = "",
    @ColumnInfo(name = "total_success") val totalSuccess: Int = 0,
    @ColumnInfo(name = "total_fail") val totalFail: Int = 0
)

/** 总计汇总 */
data class TotalSummary(
    @ColumnInfo(name = "total_success") val totalSuccess: Int = 0,
    @ColumnInfo(name = "total_fail") val totalFail: Int = 0
)

/**
 * 执行日志扩展 DAO——在 ScriptDao 中补充
 */
@Dao
interface ExecutionLogDao {
    @Query("""
        SELECT strftime('%Y-%m-%d', started_at / 1000, 'unixepoch') as date,
               COUNT(*) as total,
               SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as success_count,
               SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) as fail_count
        FROM execution_logs
        WHERE started_at > :since
        GROUP BY date
        ORDER BY date DESC
    """)
    suspend fun getDailyExecutionStats(since: Long): List<DailyExecutionStat>

    @Query("""
        SELECT script_name as platform,
               COUNT(*) as total,
               SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as success_count
        FROM execution_logs
        GROUP BY script_name
        ORDER BY total DESC
    """)
    suspend fun getPlatformExecutionStats(): List<PlatformExecutionStat>
}

data class DailyExecutionStat(
    val date: String = "",
    val total: Int = 0,
    @ColumnInfo(name = "success_count") val successCount: Int = 0,
    @ColumnInfo(name = "fail_count") val failCount: Int = 0
)

data class PlatformExecutionStat(
    val platform: String = "",
    val total: Int = 0,
    @ColumnInfo(name = "success_count") val successCount: Int = 0
)