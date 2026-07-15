package com.wearhealth.companion.shared

/**
 * JSON 字符串清洗工具
 *
 * DeepSeek 等大模型有时会在 JSON 外面包一层 Markdown 代码块（```json ... ```），
 * 或混入非 JSON 文本。本工具剥离这些包裹，提取最外层 { ... } 对象。
 */
object JsonCleaner {

    /**
     * 剥离 Markdown 代码块包裹，提取最外层 JSON 对象。
     *
     * 处理顺序：
     * 1. 去除首尾空白
     * 2. 去除 ```json / ``` 开头的代码块标记
     * 3. 定位第一个 '{' 和最后一个 '}'，截取中间内容
     *
     * @return 清洗后的 JSON 字符串；若不含 '{' 则返回原字符串
     */
    fun extractJsonObject(raw: String): String {
        var s = raw.trim()

        // 去除 Markdown 代码块包裹
        if (s.startsWith("```")) {
            // 去掉首行 ```json 或 ```
            val firstNewline = s.indexOf('\n')
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1).trimStart()
            }
            // 去掉末尾 ```
            if (s.endsWith("```")) {
                s = s.removeSuffix("```").trimEnd()
            }
        }

        // 定位最外层 { ... }
        val firstBrace = s.indexOf('{')
        val lastBrace = s.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1)
        }

        return s
    }
}
