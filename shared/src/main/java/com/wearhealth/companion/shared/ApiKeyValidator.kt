package com.wearhealth.companion.shared

/**
 * HeartVoice API Key 归一化与校验工具，供手机端与手表端共用同一套规则。
 *
 * 背景：BLE characteristic value 可能被协议栈在尾部填充 `0x00` NUL，普通 [String.trim] 无法
 * 清理 Key 中间的 NUL，最终进入 OkHttp 的 `Authorization: Bearer <key>` 头会触发：
 *
 * ```
 * Unexpected char 0x00 at ... in Authorization value
 * ```
 *
 * 规则（两端一致）：
 * - 自动去除尾部 `0x00` NUL padding（仅尾部， BLE 传输常见）；
 * - 去除首尾普通空白；
 * - 空 Key 拒绝；
 * - UTF-8 字节超过 [MAX_API_KEY_BYTES] 拒绝；
 * - 只允许适合 HTTP Header 的可见 ASCII（`0x21..0x7E`）；中间 NUL / 换行 / 回车 / tab
 *   等控制字符一律拒绝，不静默删除后继续；
 * - 校验失败不得覆盖手表/手机已有的有效 Key（由调用方在保存前调用本工具实现）。
 *
 * 不得在日志或 UI 中显示 Key 内容。
 */
object ApiKeyValidator {

    /** API Key 的最大 UTF-8 字节长度。 */
    const val MAX_API_KEY_BYTES = 512

    /**
     * 归一化并校验来自 BLE 的原始字节。
     *
     * 先去除尾部 `0x00` 字节 padding（BLE 协议栈常见），再按 [normalizeApiKey] 的字符串
     * 规则处理（可清理尾部 NUL 与普通空白交错的情况）；中间 NUL 或其它控制字符会被拒绝。
     */
    fun normalizeApiKeyBytes(bytes: ByteArray): Result<String> {
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) {
            end--
        }
        return normalizeApiKey(bytes.copyOf(end).toString(Charsets.UTF_8))
    }

    /**
     * 归一化并校验字符串形式的 Key（手机输入、Data Layer 下发、本地存储读取）。
     *
     * 同样去除尾部 NUL 与首尾空白；中间控制字符拒绝。
     */
    fun normalizeApiKey(text: String): Result<String> {
        var t = text.trim()
        while (t.endsWith('\u0000')) {
            t = t.dropLast(1).trim()
        }
        return validate(t)
    }

    private fun validate(text: String): Result<String> {
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("API Key 不能为空"))
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_API_KEY_BYTES) {
            return Result.failure(IllegalArgumentException("API Key 长度无效"))
        }
        if (text.any { it.code !in 0x21..0x7E }) {
            return Result.failure(
                IllegalArgumentException("API Key 含不可见或非法字符，请在手机端重新保存")
            )
        }
        return Result.success(text)
    }
}
