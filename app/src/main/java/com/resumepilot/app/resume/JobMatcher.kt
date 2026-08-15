package com.resumepilot.app.resume

import com.resumepilot.app.llm.LLMClient
import com.resumepilot.app.llm.LLMConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 简历匹配 + 智能打招呼语生成器
 * 核心功能：
 *   1. 简历与岗位匹配度评分
 *   2. 智能生成个性化打招呼语
 *   3. 批量岗位匹配排序
 */
class JobMatcher(private val llmConfig: LLMConfig) {

    private val llmClient = LLMClient(llmConfig)

    /**
     * 匹配岗位：计算简历与岗位的匹配度
     */
    suspend fun matchJob(resume: ResumeData, job: JobDescription): MatchResult = withContext(Dispatchers.IO) {
        // 1. 技能匹配
        val resumeSkills = resume.skills.map { it.lowercase() }
        val jobRequirements = job.requirements.map { it.lowercase() }

        val matchedSkills = jobRequirements.filter { req ->
            resumeSkills.any { skill -> req.contains(skill) || skill.contains(req) }
        }
        val missingSkills = jobRequirements.filter { req ->
            resumeSkills.none { skill -> req.contains(skill) || skill.contains(req) }
        }

        val skillMatch = if (jobRequirements.isEmpty()) 0
            else (matchedSkills.size * 100 / jobRequirements.size).coerceIn(0, 100)

        // 2. 经验匹配（粗略估算）
        val totalYears = resume.workExperience.sumOf { exp ->
            val years = extractYears(exp.startDate, exp.endDate)
            years
        }
        val experienceMatch = (totalYears * 10).coerceIn(0, 100)

        // 3. 学历匹配
        val degreeLevel = mapOf(
            "博士" to 5, "博士后" to 5,
            "硕士" to 4, "研究生" to 4,
            "本科" to 3, "学士" to 3,
            "大专" to 2, "专科" to 2,
            "高中" to 1
        )
        val resumeDegree = resume.education.maxOfOrNull {
            degreeLevel.entries.firstOrNull { (key, _) ->
                it.degree.contains(key)
            }?.value ?: 0
        } ?: 0
        val educationMatch = (resumeDegree * 20).coerceIn(0, 100)

        // 4. 总体评分
        val overallScore = (skillMatch * 0.5 + experienceMatch * 0.3 + educationMatch * 0.2).toInt()

        // 5. 改进建议
        val suggestions = mutableListOf<String>()
        if (missingSkills.isNotEmpty()) {
            suggestions.add("简历中缺少以下技能: ${missingSkills.take(5).joinToString(", ")}")
        }
        if (experienceMatch < 50) {
            suggestions.add("工作经验年限与岗位要求有差距")
        }

        MatchResult(
            job = job,
            overallScore = overallScore,
            skillMatch = skillMatch,
            experienceMatch = experienceMatch,
            educationMatch = educationMatch,
            matchedSkills = matchedSkills,
            missingSkills = missingSkills,
            suggestions = suggestions
        )
    }

    /**
     * 生成智能打招呼语（核心功能）
     * 结合简历 + 岗位描述，LLM 生成个性化问候
     */
    suspend fun generateGreeting(
        resume: ResumeData,
        job: JobDescription,
        style: GreetingStyle = GreetingStyle.PROFESSIONAL
    ): GreetingMessage = withContext(Dispatchers.IO) {

        val prompt = buildString {
            append("你是一个求职助手，正在帮用户向招聘方发出打招呼/自荐消息。\n\n")
            append("用户简历信息：\n")
            append("姓名: ${resume.name}\n")
            append("求职意向: ${resume.title}\n")
            append("个人简介: ${resume.summary}\n")
            append("技能: ${resume.skills.joinToString(", ")}\n")
            append("工作经历:\n")
            resume.workExperience.forEach { exp ->
                append("  - ${exp.company} | ${exp.title} | ${exp.startDate}-${exp.endDate}\n")
                append("    ${exp.description}\n")
            }
            append("项目经验:\n")
            resume.projects.forEach { proj ->
                append("  - ${proj.name} (${proj.role}): ${proj.description}\n")
            }
            append("\n目标岗位信息：\n")
            append("公司: ${job.company}\n")
            append("职位: ${job.title}\n")
            append("薪资: ${job.salary}\n")
            append("职位描述: ${job.description}\n")
            append("任职要求: ${job.requirements.joinToString("; ")}\n")
            append("\n要求：\n")
            append("1. 生成一段50-150字的打招呼/自荐消息\n")
            append("2. 风格要求: ${style.displayName}\n")
            append("3. 突出简历中与岗位最匹配的2-3个亮点\n")
            append("4. 表达对该岗位的兴趣\n")
            append("5. 不要过于冗长，适合在BOSS直聘/猎聘等平台发送\n")
            append("6. 语气要自信但不傲慢\n\n")
            append("请直接返回打招呼内容，不要包含其他解释。")
        }

        val response = llmClient.callTextModelRaw(prompt)

        // 提取简历亮点用于展示
        val highlights = resume.skills.filter { skill ->
            job.requirements.any { it.contains(skill, ignoreCase = true) }
        }.take(3)

        GreetingMessage(
            content = response.trim(),
            style = style,
            targetJobTitle = job.title,
            targetCompany = job.company,
            highlightsFromResume = highlights
        )
    }

    /**
     * 批量生成打招呼语（缓存相同岗位的打招呼语）
     */
    suspend fun batchGenerateGreetings(
        resume: ResumeData,
        jobs: List<JobDescription>,
        style: GreetingStyle = GreetingStyle.PROFESSIONAL
    ): List<Pair<JobDescription, GreetingMessage>> = withContext(Dispatchers.IO) {
        jobs.map { job ->
            job to generateGreeting(resume, job, style)
        }
    }

    /**
     * 对岗位列表按匹配度排序
     */
    suspend fun rankJobs(
        resume: ResumeData,
        jobs: List<JobDescription>,
        minScore: Int = 30
    ): List<Pair<JobDescription, MatchResult>> = withContext(Dispatchers.IO) {
        jobs.map { it to matchJob(resume, it) }
            .filter { it.second.overallScore >= minScore }
            .sortedByDescending { it.second.overallScore }
    }

    private fun extractYears(start: String, end: String): Int {
        val s = start.take(4).toIntOrNull() ?: return 0
        val e = end.take(4).toIntOrNull() ?: 2026
        return (e - s).coerceIn(0, 10)
    }
}

/**
 * 打招呼语模板库（不依赖 LLM 的快速选项）
 */
object GreetingTemplates {

    fun getTemplate(
        style: GreetingStyle,
        resume: ResumeData,
        job: JobDescription
    ): String {
        val name = resume.name.ifEmpty { "求职者" }
        val topSkills = resume.skills.take(3).joinToString("、")
        val company = job.company
        val title = job.title

        return when (style) {
            GreetingStyle.PROFESSIONAL ->
                "您好，我是$name，看到贵公司在招聘$title 岗位，非常感兴趣。" +
                "我具备$topSkills 等方面的技能和${resume.workExperience.size}年相关工作经验。" +
                "希望能有机会进一步沟通，谢谢！"

            GreetingStyle.FRIENDLY ->
                "Hi~ 我是$name，看到$company 在招$title，感觉和我的背景很匹配。" +
                "我熟悉$topSkills，之前做过一些相关项目。期待能聊聊看~"

            GreetingStyle.CONFIDENT ->
                "您好！我是$name，$title 方向的专业开发者。" +
                "精通$topSkills，有${resume.workExperience.size}年实战经验。" +
                "相信我的能力能为$company 带来价值，期待面试机会。"

            GreetingStyle.BRIEF ->
                "您好，我对$title 岗位感兴趣。我熟悉$topSkills，有相关经验，期待沟通。"

            GreetingStyle.CUSTOM -> ""
        }
    }
}