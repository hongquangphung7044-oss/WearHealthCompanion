package com.wearhealth.companion.shared

/**
 * JSON 字符串清洗工具
 *
 * DeepSeek 等大模型有时会输出非标准 JSON：
 * 1. 外面包一层 Markdown 代码块（```json ... ```）
 * 2. 混入非 JSON 文本
 * 3. 使用中文全角引号（" " U+201C/U+201D）和全角冒号（：U+FF1A）而非标准 ASCII
 *
 * 本工具剥离这些包裹、转换全角字符，提取最外层 { ... } 对象。
 */
object JsonCleaner {

    /**
     * 剥离 Markdown 代码块包裹，转换全角引号/冒号，提取最外层 JSON 对象。
     *
     * 处理顺序：
     * 1. 去除首尾空白
     * 2. 去除 ```json / ``` 开头的代码块标记
     * 3. 全角引号 " " → 半角 "，全角冒号 ： → 半角 :
     * 4. 定位第一个 '{' 和最后一个 '}'，截取中间内容
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

        // 全角引号/冒号转半角（DS 有时输出中文标点导致 JSON 解析失败）
        s = s.replace('\u201C', '"')   // " 左双引号
            .replace('\u201D', '"')    // " 右双引号
            .replace('\u2018', '\'')   // ' 左单引号
            .replace('\u2019', '\'')   // ' 右单引号
            .replace('\uFF1A', ':')    // ：全角冒号
            .replace('\uFF0C', ',')    // ，全角逗号

        // 定位最外层 { ... }
        val firstBrace = s.indexOf('{')
        val lastBrace = s.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1)
        }

        return s
    }
}
