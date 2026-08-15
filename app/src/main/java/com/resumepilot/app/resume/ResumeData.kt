package com.resumepilot.app.resume

/**
 * 简历数据模型
 */
data class ResumeData(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",                    // 姓名
    val title: String = "",                   // 求职意向职位
    val summary: String = "",                 // 个人简介
    val skills: List<String> = emptyList(),   // 技能列表
    val workExperience: List<WorkExperience> = emptyList(),
    val education: List<Education> = emptyList(),
    val projects: List<Project> = emptyList(),
    val certifications: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val contactInfo: ContactInfo = ContactInfo(),
    val sourceFile: String? = null,           // 原始简历文件路径
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "title" to title,
        "summary" to summary,
        "skills" to skills,
        "work_experience" to workExperience.map { it.toMap() },
        "education" to education.map { it.toMap() },
        "projects" to projects.map { it.toMap() },
        "certifications" to certifications,
        "languages" to languages,
        "contact_info" to contactInfo.toMap()
    )
}

data class WorkExperience(
    val company: String = "",
    val title: String = "",
    val startDate: String = "",
    val endDate: String = "",            // 空字符串表示至今
    val description: String = "",
    val highlights: List<String> = emptyList()
) {
    fun toMap() = mapOf(
        "company" to company,
        "title" to title,
        "period" to "$startDate - ${endDate.ifEmpty { "至今" }}",
        "description" to description,
        "highlights" to highlights
    )
}

data class Education(
    val school: String = "",
    val degree: String = "",
    val major: String = "",
    val startDate: String = "",
    val endDate: String = ""
) {
    fun toMap() = mapOf(
        "school" to school,
        "degree" to degree,
        "major" to major,
        "period" to "$startDate - ${endDate.ifEmpty { "至今" }}"
    )
}

data class Project(
    val name: String = "",
    val role: String = "",
    val description: String = "",
    val techStack: List<String> = emptyList(),
    val highlights: List<String> = emptyList()
) {
    fun toMap() = mapOf(
        "name" to name,
        "role" to role,
        "description" to description,
        "tech_stack" to techStack,
        "highlights" to highlights
    )
}

data class ContactInfo(
    val phone: String = "",
    val email: String = "",
    val wechat: String = "",
    val linkedin: String = ""
) {
    fun toMap() = mapOf(
        "phone" to phone,
        "email" to email,
        "wechat" to wechat
    )
}

/**
 * 岗位描述模型
 */
data class JobDescription(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",              // 职位名称
    val company: String = "",            // 公司名称
    val salary: String = "",             // 薪资范围
    val location: String = "",           // 工作地点
    val description: String = "",        // 职位描述
    val requirements: List<String> = emptyList(),  // 任职要求
    val responsibilities: List<String> = emptyList(), // 岗位职责
    val platform: String = "",           // 来源平台
    val url: String = "",                // 岗位链接
    val matchedScore: Int = 0,           // 匹配度评分（0-100）
    val matchedReasons: List<String> = emptyList(),  // 匹配原因
    val missingSkills: List<String> = emptyList()    // 缺失技能
)

/**
 * 打招呼语模板
 */
data class GreetingMessage(
    val content: String,                  // 打招呼内容
    val style: GreetingStyle,             // 风格
    val targetJobTitle: String,           // 目标职位
    val targetCompany: String,           // 目标公司
    val highlightsFromResume: List<String> = emptyList()  // 用到的简历亮点
)

enum class GreetingStyle(val displayName: String) {
    PROFESSIONAL("专业正式"),
    FRIENDLY("友好亲切"),
    CONFIDENT("自信展示"),
    BRIEF("简洁明了"),
    CUSTOM("自定义")
}

/**
 * 简历匹配结果
 */
data class MatchResult(
    val job: JobDescription,
    val overallScore: Int,                // 总体匹配度 0-100
    val skillMatch: Int,                  // 技能匹配度
    val experienceMatch: Int,             // 经验匹配度
    val educationMatch: Int,              // 学历匹配度
    val matchedSkills: List<String>,      // 匹配的技能
    val missingSkills: List<String>,      // 缺失的技能
    val suggestions: List<String>         // 改进建议
)