package com.autorpa.app.resume

import android.content.Context
import android.net.Uri
import com.autorpa.app.data.db.AppDatabase
import com.autorpa.app.data.db.ScriptEntity
import com.autorpa.app.llm.LLMClient
import com.autorpa.app.llm.LLMConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 简历管理器
 * 负责简历的上传、解析、存储、匹配
 */
class ResumeManager(
    private val context: Context,
    private val db: AppDatabase? = null
) {
    private var _activeResume = MutableStateFlow<ResumeData?>(null)
    val activeResume: StateFlow<ResumeData?> = _activeResume

    private var _resumeHistory = MutableStateFlow<List<ResumeData>>(emptyList())
    val resumeHistory: StateFlow<List<ResumeData>> = _resumeHistory

    /**
     * 上传简历文件
     */
    suspend fun uploadResume(uri: Uri): Result<ResumeData> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri)
            val content = readTextFromUri(uri)

            // 基础解析（从文件名推断）
            val name = fileName?.substringBeforeLast(".")?.replace("_", " ") ?: "我的简历"

            val resume = ResumeData(
                name = name,
                sourceFile = fileName,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            // 如果有 LLM 配置，用 LLM 解析
            // parseWithLLM(content)?.let { resume = it }

            _activeResume.value = resume

            // 保存到历史
            val history = _resumeHistory.value.toMutableList()
            history.add(0, resume)
            _resumeHistory.value = history

            Result.success(resume)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 用 LLM 解析简历内容
     */
    suspend fun parseWithLLM(rawContent: String, llmConfig: LLMConfig): Result<ResumeData> = withContext(Dispatchers.IO) {
        try {
            val client = LLMClient(llmConfig)
            val prompt = buildString {
                append("你是一个简历解析专家。请解析以下简历文本，提取结构化信息并返回 JSON 格式。\n\n")
                append("简历内容：\n$rawContent\n\n")
                append("请返回以下 JSON 格式（不要返回其他内容）：\n")
                append("""{
  "name": "姓名",
  "title": "求职意向职位",
  "summary": "个人简介",
  "skills": ["技能1", "技能2"],
  "work_experience": [
    {"company": "公司", "title": "职位", "startDate": "开始时间", "endDate": "结束时间", "description": "工作描述", "highlights": ["亮点1"]}
  ],
  "education": [
    {"school": "学校", "degree": "学历", "major": "专业", "startDate": "开始", "endDate": "结束"}
  ],
  "projects": [
    {"name": "项目名", "role": "角色", "description": "描述", "techStack": ["技术1"]}
  ]
}""")
            }

            val response = client.callTextModelRaw(prompt)
            val json = extractJson(response)
            // 解析 JSON 为 ResumeData
            val parsed = parseResumeJson(json)
            parsed?.let { _activeResume.value = it }

            Result.success(parsed ?: _activeResume.value ?: ResumeData())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 设置简历
     */
    fun setResume(resume: ResumeData) {
        _activeResume.value = resume
    }

    /**
     * 获取当前简历
     */
    fun getActiveResume(): ResumeData? = _activeResume.value

    /**
     * 更新简历
     */
    fun updateResume(resume: ResumeData) {
        _activeResume.value = resume.copy(updatedAt = System.currentTimeMillis())
    }

    // ====== 工具方法 ======

    private fun getFileName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法打开文件")
        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "{}"
    }

    private fun parseResumeJson(json: String): ResumeData? {
        return try {
            val obj = org.json.JSONObject(json)
            ResumeData(
                name = obj.optString("name", ""),
                title = obj.optString("title", ""),
                summary = obj.optString("summary", ""),
                skills = obj.optJSONArray("skills")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
                workExperience = obj.optJSONArray("work_experience")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val exp = arr.getJSONObject(i)
                        WorkExperience(
                            company = exp.optString("company"),
                            title = exp.optString("title"),
                            startDate = exp.optString("startDate"),
                            endDate = exp.optString("endDate"),
                            description = exp.optString("description"),
                            highlights = exp.optJSONArray("highlights")?.let { h ->
                                (0 until h.length()).map { h.optString(it) }
                            } ?: emptyList()
                        )
                    }
                } ?: emptyList(),
                education = obj.optJSONArray("education")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val edu = arr.getJSONObject(i)
                        Education(
                            school = edu.optString("school"),
                            degree = edu.optString("degree"),
                            major = edu.optString("major"),
                            startDate = edu.optString("startDate"),
                            endDate = edu.optString("endDate")
                        )
                    }
                } ?: emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }
}