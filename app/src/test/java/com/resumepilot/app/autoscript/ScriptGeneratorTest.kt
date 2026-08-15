package com.resumepilot.app.autoscript

import com.resumepilot.app.engine.Action
import com.resumepilot.app.llm.LLMConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScriptGenerator 单元测试——YAML 脚本解析与生成的纯逻辑
 */
class ScriptGeneratorTest {

    private val generator = ScriptGenerator(LLMConfig())

    @Test
    fun `parses click wait and back steps`() {
        val yaml = """
            name: "test"
            steps:
              - id: step_0
                action: click
                x: 100
                y: 200
              - id: step_1
                action: wait
                millis: 1500
              - id: step_2
                action: back
        """.trimIndent()

        val actions = generator.parseToActions(yaml)
        assertEquals(3, actions.size)

        val click = actions[0] as Action.Click
        assertEquals(100, click.x)
        assertEquals(200, click.y)

        val wait = actions[1] as Action.Wait
        assertEquals(1500L, wait.millis)

        assertTrue(actions[2] is Action.Back)
    }

    @Test
    fun `parses find_and_click with ocr fallback`() {
        val yaml = """
            steps:
              - id: step_0
                action: find_and_click
                text: "投递"
                fallback_ocr: true
        """.trimIndent()

        val actions = generator.parseToActions(yaml)
        assertEquals(1, actions.size)
        val fa = actions[0] as Action.FindAndClick
        assertEquals("投递", fa.text)
        assertTrue(fa.fallbackOcr)
    }

    @Test
    fun `generates yaml from actions`() {
        val yaml = generator.generateFromActions(
            name = "测试",
            description = "测试脚本",
            actions = listOf(
                Action.Click(10, 20, description = "点击"),
                Action.Type("hello")
            )
        )

        assertTrue(yaml.contains("action: click"))
        assertTrue(yaml.contains("x: 10"))
        assertTrue(yaml.contains("action: type"))
        assertTrue(yaml.contains("text: \"hello\""))
    }

    @Test
    fun `parsed yaml round-trips`() {
        val original = listOf<Action>(
            Action.Scroll(Action.ScrollDirection.DOWN, times = 2),
            Action.LaunchApp("com.hpbr.bosszhipin", 3000L),
            Action.FindAndClick("投递", fallbackOcr = true)
        )
        val yaml = generator.generateFromActions("往返", "测试", original)
        val parsed = generator.parseToActions(yaml)

        assertEquals(3, parsed.size)
        assertTrue(parsed[0] is Action.Scroll)
        assertEquals(Action.ScrollDirection.DOWN, (parsed[0] as Action.Scroll).direction)
        assertTrue(parsed[1] is Action.LaunchApp)
        assertTrue(parsed[2] is Action.FindAndClick)
    }
}
