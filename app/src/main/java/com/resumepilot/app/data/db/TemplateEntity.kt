package com.resumepilot.app.data.db

import androidx.room.*

/**
 * 平台模板数据库实体
 * 存储 LLM 生成的 PlatformTemplate 持久化数据
 */
@Entity(tableName = "platform_templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "platform_name") val platformName: String,
    @ColumnInfo(name = "app_package") val appPackage: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "template_json") val templateJson: String,  // PlatformTemplate 完整 JSON
    @ColumnInfo(name = "workflow_count") val workflowCount: Int,
    @ColumnInfo(name = "screenshot_count") val screenshotCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "run_count") val runCount: Int,
    @ColumnInfo(name = "repair_count") val repairCount: Int,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true
)

@Dao
interface TemplateDao {
    @Query("SELECT * FROM platform_templates ORDER BY updated_at DESC")
    suspend fun getAllTemplates(): List<TemplateEntity>

    @Query("SELECT * FROM platform_templates WHERE platform_name = :name ORDER BY version DESC LIMIT 1")
    suspend fun getLatestTemplate(name: String): TemplateEntity?

    @Query("SELECT * FROM platform_templates WHERE id = :id")
    suspend fun getTemplateById(id: String): TemplateEntity?

    @Query("SELECT * FROM platform_templates WHERE is_active = 1")
    suspend fun getActiveTemplates(): List<TemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: TemplateEntity)

    @Delete
    suspend fun deleteTemplate(template: TemplateEntity)

    @Query("UPDATE platform_templates SET is_active = 0 WHERE id = :id")
    suspend fun deactivateTemplate(id: String)

    @Query("UPDATE platform_templates SET run_count = run_count + 1, repair_count = repair_count + :repairs WHERE id = :id")
    suspend fun updateRunStats(id: String, repairs: Int = 0)
}

/**
 * 模板生成记录——记录每次用户通过截图创建模板的过程
 */
@Entity(tableName = "template_generation_logs")
data class TemplateGenerationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "platform_name") val platformName: String,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "success") val success: Boolean,
    @ColumnInfo(name = "screenshot_count") val screenshotCount: Int,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TemplateGenerationLogDao {
    @Query("SELECT * FROM template_generation_logs ORDER BY created_at DESC LIMIT 20")
    suspend fun getRecentLogs(): List<TemplateGenerationLogEntity>

    @Insert
    suspend fun insertLog(log: TemplateGenerationLogEntity)
}