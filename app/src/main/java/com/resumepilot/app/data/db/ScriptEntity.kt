package com.resumepilot.app.data.db

import androidx.room.*

@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "yaml_content") val yamlContent: String,
    @ColumnInfo(name = "source_trajectory_id") val sourceTrajectoryId: String?,
    @ColumnInfo(name = "llm_generated") val llmGenerated: Boolean,
    @ColumnInfo(name = "app_package") val appPackage: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "run_count") val runCount: Int,
    @ColumnInfo(name = "success_count") val successCount: Int,
    @ColumnInfo(name = "tags") val tags: String  // JSON array
)

@Entity(tableName = "trajectories")
data class TrajectoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_description") val taskDescription: String,
    @ColumnInfo(name = "steps_json") val stepsJson: String,  // 完整轨迹 JSON
    @ColumnInfo(name = "app_package") val appPackage: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "duration") val duration: Long,
    @ColumnInfo(name = "step_count") val stepCount: Int
)

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "script_id") val scriptId: String,
    @ColumnInfo(name = "script_name") val scriptName: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "success") val success: Boolean,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "steps_completed") val stepsCompleted: Int,
    @ColumnInfo(name = "steps_total") val stepsTotal: Int
)

@Dao
interface ScriptDao {
    @Query("SELECT * FROM scripts ORDER BY updated_at DESC")
    suspend fun getAllScripts(): List<ScriptEntity>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun getScriptById(id: String): ScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: ScriptEntity)

    @Delete
    suspend fun deleteScript(script: ScriptEntity)

    @Query("UPDATE scripts SET run_count = run_count + 1, success_count = success_count + :success WHERE id = :id")
    suspend fun updateRunCount(id: String, success: Int)

    @Query("SELECT * FROM trajectories ORDER BY created_at DESC")
    suspend fun getAllTrajectories(): List<TrajectoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrajectory(trajectory: TrajectoryEntity)

    @Delete
    suspend fun deleteTrajectory(trajectory: TrajectoryEntity)

    @Query("SELECT * FROM execution_logs ORDER BY started_at DESC LIMIT 50")
    suspend fun getRecentLogs(): List<ExecutionLogEntity>

    @Insert
    suspend fun insertLog(log: ExecutionLogEntity): Long

    @Query("UPDATE execution_logs SET completed_at = :completedAt, success = :success, error_message = :error, steps_completed = :stepsCompleted WHERE id = :id")
    suspend fun updateLog(id: Long, completedAt: Long, success: Boolean, error: String?, stepsCompleted: Int)
}

@Database(
    entities = [
        ScriptEntity::class,
        TrajectoryEntity::class,
        ExecutionLogEntity::class,
        TemplateEntity::class,
        TemplateGenerationLogEntity::class,
        StatsEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun templateDao(): TemplateDao
    abstract fun templateGenerationLogDao(): TemplateGenerationLogDao
    abstract fun statsDao(): StatsDao
    abstract fun executionLogDao(): ExecutionLogDao
}