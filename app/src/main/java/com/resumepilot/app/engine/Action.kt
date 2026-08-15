package com.resumepilot.app.engine

import android.graphics.Rect
import com.google.gson.Gson

/**
 * 原子动作类型：简历投递助手的核心动作抽象
 * LLM 决策后输出 Action，RPA 引擎执行 Action
 */
sealed class Action {

    /** 点击坐标 */
    data class Click(
        val x: Int,
        val y: Int,
        val description: String = "",
        val randomOffset: Int = 5  // 随机偏移防检测
    ) : Action()

    /** 长按 */
    data class LongClick(
        val x: Int,
        val y: Int,
        val duration: Long = 1000,
        val description: String = ""
    ) : Action()

    /** 滑动 */
    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val duration: Long = 300,
        val description: String = ""
    ) : Action()

    /** 输入文本 */
    data class Type(
        val text: String,
        val description: String = ""
    ) : Action()

    /** 按返回键 */
    data object Back : Action()

    /** 按 Home 键 */
    data object Home : Action()

    /** 等待 */
    data class Wait(
        val millis: Long,
        val description: String = ""
    ) : Action()

    /** 查找控件并点击（语义定位） */
    data class FindAndClick(
        val text: String? = null,
        val id: String? = null,
        val description: String? = null,
        val fallbackOcr: Boolean = true  // 控件找不到时用 OCR 兜底
    ) : Action()

    /** 滚动列表 */
    data class Scroll(
        val direction: ScrollDirection,
        val times: Int = 1,
        val description: String = ""
    ) : Action()

    /** 打开应用 */
    data class LaunchApp(
        val packageName: String,
        val waitMillis: Long = 3000
    ) : Action()

    /** 运行子脚本（脚本嵌套调用） */
    data class RunScript(
        val scriptName: String,
        val params: Map<String, String> = emptyMap()
    ) : Action()

    /** LLM 自主决策（走视觉理解） */
    data class LLMDecision(
        val instruction: String,
        val maxSteps: Int = 10
    ) : Action()

    enum class ScrollDirection {
        DOWN, UP, LEFT, RIGHT
    }
}

/**
 * 执行轨迹中的一个步骤记录
 * 用于"探索 → 生成脚本"流程
 */
data class TrajectoryStep(
    val stepIndex: Int,
    val action: Action,
    val screenshotHash: String? = null,     // 截图指纹（去重用）
    val uiTreeSnapshot: String? = null,     // 操作前的控件树快照
    val success: Boolean = true,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 完整执行轨迹
 */
data class Trajectory(
    val id: String = java.util.UUID.randomUUID().toString(),
    val taskDescription: String,
    val steps: List<TrajectoryStep>,
    val appPackageName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val duration: Long = 0
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): Trajectory = Gson().fromJson(json, Trajectory::class.java)
    }
}

/**
 * RPA 脚本元数据
 */
data class RPAScript(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val yamlContent: String,           // YAML 格式的动作序列
    val sourceTrajectoryId: String? = null,  // 来自哪个轨迹
    val llmGenerated: Boolean = false,  // 是否由 LLM 自动生成
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val runCount: Int = 0,
    val successCount: Int = 0,
    val tags: List<String> = emptyList()
)