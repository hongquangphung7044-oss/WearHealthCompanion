package com.wearhealth.companion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * JsonCleaner 单元测试（由 CI `:shared:testReleaseUnitTest` 运行）。
 *
 * 验证：
 * - 剥离 Markdown ```json 包裹
 * - 转换全角引号/冒号/逗号为半角
 * - 定位最外层 { ... } 对象
 * - 清洗后能被 JSONObject 正确解析
 */
class JsonCleanerTest {

    @Test
    fun stripsMarkdownCodeFence() {
        val raw = "```json\n{\"平均心率\": 70}\n```"
        val cleaned = JsonCleaner.extractJsonObject(raw)
        assertEquals("{\"平均心率\": 70}", cleaned)
    }

    @Test
    fun convertsFullWidthQuotesAndColon() {
        // DS 有时输出全角引号 “ ” 和全角冒号 ：导致 JSON 解析失败
        val raw = "{“平均心率”：70}"
        val cleaned = JsonCleaner.extractJsonObject(raw)
        assertEquals("{\"平均心率\":70}", cleaned)
        // 清洗后应能被 JSONObject 解析
        val json = JSONObject(cleaned)
        assertEquals(70, json.optInt("平均心率"))
    }

    @Test
    fun convertsFullWidthComma() {
        val raw = "{\"平均心率”：70，“最低心率”：60}"
        val cleaned = JsonCleaner.extractJsonObject(raw)
        // 全角逗号 ， 应已转为半角 ,
        assertTrue("不应残留全角逗号", !cleaned.contains("，"))
        assertTrue("应包含半角逗号", cleaned.contains(","))
        // 清洗后应能被 JSONObject 解析
        val json = JSONObject(cleaned)
        assertEquals(70, json.optInt("平均心率"))
        assertEquals(60, json.optInt("最低心率"))
    }

    @Test
    fun extractsOutermostBraces() {
        val raw = "非JSON前缀 {\"节律判断\": \"窦性心律\"} 非JSON后缀"
        val cleaned = JsonCleaner.extractJsonObject(raw)
        assertEquals("{\"节律判断\": \"窦性心律\"}", cleaned)
    }

    @Test
    fun noBracesReturnsOriginal() {
        val raw = "纯文本无 JSON"
        val cleaned = JsonCleaner.extractJsonObject(raw)
        assertEquals(raw, cleaned)
    }
}
