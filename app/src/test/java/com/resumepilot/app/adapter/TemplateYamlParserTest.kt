package com.resumepilot.app.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TemplateYamlParser 单元测试——覆盖引导截图→模板生成主链路的关键解析逻辑
 */
class TemplateYamlParserTest {

    private val sampleYaml = """
        element_mapping:
          搜索框:
            text: "搜索"
            fallback_ocr: true
          投递按钮:
            text: "投递"
        workflows:
          search_jobs:
            description: "搜索岗位"
            params: [keyword, city]
            steps:
              - id: step_1
                action: find_and_click
                target: "搜索框"
                wait_after: 1000
              - id: step_2
                action: type
                text: "{keyword}"
                wait: 500
          apply_job:
            description: "投递职位"
            steps:
              - id: step_1
                action: find_and_click
                target: "投递按钮"
                max_retries: 2
    """.trimIndent()

    @Test
    fun `parses element mapping with text locators`() {
        val result = TemplateYamlParser.parse(sampleYaml)
        assertEquals("text=搜索", result.elementMapping["搜索框"])
        assertEquals("text=投递", result.elementMapping["投递按钮"])
    }

    @Test
    fun `parses workflows with steps`() {
        val result = TemplateYamlParser.parse(sampleYaml)
        assertEquals(2, result.workflows.size)

        val search = result.workflows["search_jobs"]
        assertNotNull(search)
        assertEquals(listOf("keyword", "city"), search!!.requiredParams)
        assertEquals(2, search.steps.size)
        assertEquals("find_and_click", search.steps[0].action)
        assertEquals("搜索框", search.steps[0].target)
        assertEquals(1000L, search.steps[0].waitAfter)
        assertEquals("{keyword}", search.steps[1].text)
        assertEquals(500L, search.steps[1].millis)

        val apply = result.workflows["apply_job"]
        assertNotNull(apply)
        assertEquals(2, apply!!.steps[0].maxRetries)
    }

    @Test
    fun `handles markdown code fences`() {
        val wrapped = "```yaml\n$sampleYaml\n```"
        val result = TemplateYamlParser.parse(wrapped)
        assertTrue(result.workflows.isNotEmpty())
        assertTrue(result.elementMapping.isNotEmpty())
    }

    @Test
    fun `returns empty on invalid yaml`() {
        val result = TemplateYamlParser.parse("not: [valid: yaml")
        assertTrue(result.workflows.isEmpty())
        assertTrue(result.elementMapping.isEmpty())
    }

    @Test
    fun `drops workflows without any steps`() {
        val yaml = """
            workflows:
              empty_flow:
                description: "无步骤"
                steps: []
              valid_flow:
                steps:
                  - id: s1
                    action: back
        """.trimIndent()
        val result = TemplateYamlParser.parse(yaml)
        assertEquals(1, result.workflows.size)
        assertNotNull(result.workflows["valid_flow"])
    }
}
