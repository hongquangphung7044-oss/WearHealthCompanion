package com.wearhealth.companion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ApiKeyValidator 单元测试（纯 JVM，不依赖 Android SDK，由 CI `:shared:testReleaseUnitTest` 运行）。
 *
 * 不包含任何真实 HeartVoice Key；全部使用明显的测试占位串。
 */
class ApiKeyValidatorTest {

    private val validKey = "hv_test_0123456789abcdefABCDEF.-_~+"

    // ---- 正常 Key ----

    @Test
    fun normalKeyStringIsValid() {
        val result = ApiKeyValidator.normalizeApiKey(validKey)
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    @Test
    fun normalKeyBytesIsValid() {
        val result = ApiKeyValidator.normalizeApiKeyBytes(validKey.toByteArray(Charsets.UTF_8))
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    // ---- 首尾普通空白 ----

    @Test
    fun leadingAndTrailingSpacesAreTrimmed() {
        val result = ApiKeyValidator.normalizeApiKey("   $validKey\t ")
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    @Test
    fun bytesWithLeadingTrailingSpacesAreTrimmed() {
        val result = ApiKeyValidator.normalizeApiKeyBytes(" \t$validKey ".toByteArray(Charsets.UTF_8))
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    // ---- 尾部 NUL padding（BLE 常见）----

    @Test
    fun singleTrailingNulIsStripped() {
        val bytes = validKey.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val result = ApiKeyValidator.normalizeApiKeyBytes(bytes)
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    @Test
    fun multipleTrailingNulAreStripped() {
        val bytes = validKey.toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0, 0)
        val result = ApiKeyValidator.normalizeApiKeyBytes(bytes)
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    @Test
    fun trailingNulFollowedBySpaceIsStripped() {
        val bytes = validKey.toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0) + " ".toByteArray(Charsets.UTF_8)
        val result = ApiKeyValidator.normalizeApiKeyBytes(bytes)
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    @Test
    fun stringTrailingNulIsStripped() {
        val result = ApiKeyValidator.normalizeApiKey("$validKey\u0000\u0000")
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    // ---- 中间 NUL 必须拒绝 ----

    @Test
    fun middleNulInBytesIsRejected() {
        val prefix = validKey.substring(0, 10)
        val suffix = validKey.substring(10)
        val bytes = prefix.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + suffix.toByteArray(Charsets.UTF_8)
        val result = ApiKeyValidator.normalizeApiKeyBytes(bytes)
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun middleNulInStringIsRejected() {
        val corrupt = validKey.substring(0, 10) + '\u0000' + validKey.substring(10)
        val result = ApiKeyValidator.normalizeApiKey(corrupt)
        assertTrue(result.isFailure)
    }

    @Test
    fun middleNulIsNotSilentlyDeleted() {
        // 关键：中间 NUL 不能被静默删除后继续使用，必须整体拒绝。
        val corrupt = validKey.substring(0, 10) + '\u0000' + validKey.substring(10)
        val result = ApiKeyValidator.normalizeApiKey(corrupt)
        assertFalse(result.isSuccess)
        // 校验失败时 getOrNull() 必须为 null，绝不能返回"静默删 NUL"后的串
        assertNull(result.getOrNull())
        val cleaned = corrupt.replace('\u0000'.toString(), "").trim()
        assertFalse("不得返回静默清理后的串", cleaned == result.getOrNull())
    }

    // ---- 换行 / 回车 / tab ----

    @Test
    fun trailingNewlineIsTrimmedAndAccepted() {
        // 尾部换行（0x0A）属于普通空白，由 .trim() 清理后接受；输出仍只含可见 ASCII。
        val result = ApiKeyValidator.normalizeApiKey("$validKey\n")
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    @Test
    fun middleNewlineIsRejected() {
        val result = ApiKeyValidator.normalizeApiKey(validKey.substring(0, 5) + "\n" + validKey.substring(5))
        assertTrue(result.isFailure)
    }

    @Test
    fun trailingCarriageReturnIsTrimmedAndAccepted() {
        // 尾部回车（0x0D）属于普通空白，由 .trim() 清理后接受。
        val result = ApiKeyValidator.normalizeApiKey("$validKey\r")
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    @Test
    fun trailingTabIsTrimmedAndAccepted() {
        // 尾部 tab（0x09）属于普通空白，由 .trim() 清理后接受。
        val result = ApiKeyValidator.normalizeApiKey("$validKey\t")
        assertTrue(result.isSuccess)
        assertEquals(validKey, result.getOrNull())
    }

    @Test
    fun middleTabIsRejected() {
        val result = ApiKeyValidator.normalizeApiKey(validKey.substring(0, 5) + "\t" + validKey.substring(5))
        assertTrue(result.isFailure)
    }

    @Test
    fun middleControlCharsAreRejected() {
        // 中间任何控制字符（含 NUL/换行/回车/tab 等）都必须拒绝，不静默删除。
        for (code in 0..0x1F) {
            val ch = code.toChar()
            val result = ApiKeyValidator.normalizeApiKey(validKey.substring(0, 5) + ch + validKey.substring(5))
            assertTrue("中间控制字符 0x${code.toString(16)} 应被拒绝", result.isFailure)
        }
        val delMiddle = ApiKeyValidator.normalizeApiKey(
            validKey.substring(0, 5) + 0x7F.toChar() + validKey.substring(5)
        )
        assertTrue("中间 DEL(0x7F) 应被拒绝", delMiddle.isFailure)
    }

    @Test
    fun trailingWhitespaceControlCharsAreTrimmedAndAccepted() {
        // 尾部的普通空白类控制字符（0x09/0x0A/0x0B/0x0C/0x0D/0x1C-0x1F/0x20）
        // 由 Kotlin .trim() 清理后接受；归一化输出仍只含 0x21..0x7E。
        val trailingWhitespaceCodes = listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20)
        for (code in trailingWhitespaceCodes) {
            val result = ApiKeyValidator.normalizeApiKey(validKey + code.toChar())
            assertTrue("尾部空白字符 0x${code.toString(16)} 应被清理后接受", result.isSuccess)
            assertEquals(validKey, result.getOrNull())
        }
    }

    @Test
    fun trailingNonWhitespaceControlCharsAreRejected() {
        // 尾部非空白控制字符（0x01-0x08, 0x0E-0x1B）不会被 .trim() 清理，必须拒绝。
        val nonWhitespaceCodes = (1..8) + (0x0E..0x1B)
        for (code in nonWhitespaceCodes) {
            val result = ApiKeyValidator.normalizeApiKey(validKey + code.toChar())
            assertTrue("尾部非空白控制字符 0x${code.toString(16)} 应被拒绝", result.isFailure)
        }
    }

    @Test
    fun trailingDelIsRejected() {
        // DEL(0x7F) 不属于空白，不会被 .trim() 清理，必须拒绝。
        val result = ApiKeyValidator.normalizeApiKey(validKey + 0x7F.toChar())
        assertTrue(result.isFailure)
    }

    // ---- 空 Key ----

    @Test
    fun emptyStringIsRejected() {
        val result = ApiKeyValidator.normalizeApiKey("")
        assertTrue(result.isFailure)
    }

    @Test
    fun blankStringIsRejected() {
        val result = ApiKeyValidator.normalizeApiKey("   \t  ")
        assertTrue(result.isFailure)
    }

    @Test
    fun emptyBytesIsRejected() {
        val result = ApiKeyValidator.normalizeApiKeyBytes(ByteArray(0))
        assertTrue(result.isFailure)
    }

    @Test
    fun onlyNulBytesIsRejected() {
        val result = ApiKeyValidator.normalizeApiKeyBytes(byteArrayOf(0, 0, 0))
        assertTrue(result.isFailure)
    }

    // ---- 超长 Key ----

    @Test
    fun overlongKeyIsRejected() {
        val tooLong = "a".repeat(ApiKeyValidator.MAX_API_KEY_BYTES + 1)
        val result = ApiKeyValidator.normalizeApiKey(tooLong)
        assertTrue(result.isFailure)
    }

    @Test
    fun overlongBytesIsRejected() {
        val tooLong = "a".repeat(ApiKeyValidator.MAX_API_KEY_BYTES + 1).toByteArray(Charsets.UTF_8)
        val result = ApiKeyValidator.normalizeApiKeyBytes(tooLong)
        assertTrue(result.isFailure)
    }

    @Test
    fun exactlyMaxLengthIsAccepted() {
        val atLimit = "a".repeat(ApiKeyValidator.MAX_API_KEY_BYTES)
        val result = ApiKeyValidator.normalizeApiKey(atLimit)
        assertTrue(result.isSuccess)
    }

    // ---- 验证失败不能覆盖旧有效 Key（纯 JVM 模拟存储契约）----

    @Test
    fun validationFailureDoesNotOverwriteOldKey() {
        // 模拟手机/手表存储层使用的 "getOrNull() ?: return" 契约：非法输入不写入。
        var stored: String? = validKey
        fun save(input: String) {
            val normalized = ApiKeyValidator.normalizeApiKey(input).getOrNull() ?: return
            stored = normalized
        }
        // 尝试用中间含 NUL 的非法 Key 覆盖
        save(validKey.substring(0, 5) + '\u0000' + validKey.substring(5))
        assertEquals("旧有效 Key 必须保留", validKey, stored)
        // 空 Key 也不覆盖
        save("")
        assertEquals(validKey, stored)
        // 中间含换行的非法 Key 也不覆盖
        save(validKey.substring(0, 5) + "\n" + validKey.substring(5))
        assertEquals(validKey, stored)
        // 合法 Key 才覆盖
        val newValid = "hv_new_valid_key_123456"
        save(newValid)
        assertEquals(newValid, stored)
    }

    @Test
    fun validationFailureDoesNotOverwriteOldKeyBytes() {
        var stored: String? = validKey
        fun save(input: ByteArray) {
            val normalized = ApiKeyValidator.normalizeApiKeyBytes(input).getOrNull() ?: return
            stored = normalized
        }
        // 尾部 NUL 合法 → 覆盖为清理后的值
        save((validKey + "\u0000\u0000").toByteArray(Charsets.UTF_8))
        assertEquals(validKey, stored)
        // 中间 NUL 非法 → 不覆盖
        val corrupt = validKey.substring(0, 8).toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            validKey.substring(8).toByteArray(Charsets.UTF_8)
        save(corrupt)
        assertEquals("中间 NUL 不能覆盖旧 Key", validKey, stored)
    }

    // ---- 最终 Authorization value 不含控制字符 ----

    @Test
    fun normalizedOutputContainsOnlyVisibleAscii() {
        // 所有成功归一化的输出必须只含 0x21..0x7E，保证 Authorization 头不会触发 OkHttp 异常。
        val inputs = listOf(
            validKey,
            "  $validKey  ",
            "$validKey\u0000\u0000",
            "$validKey\t ",
        )
        for (input in inputs) {
            val out = ApiKeyValidator.normalizeApiKey(input).getOrNull()
            assertNotNull("输入应校验成功: ${input.length}", out)
            out!!.forEach { ch ->
                assertTrue(
                    "归一化后字符 0x${ch.code.toString(16)} 必须在可见 ASCII 范围",
                    ch.code in 0x21..0x7E,
                )
            }
        }
    }

    @Test
    fun normalizedBytesOutputContainsNoControlChars() {
        val bytes = (validKey + "\u0000\u0000\u0000").toByteArray(Charsets.UTF_8)
        val out = ApiKeyValidator.normalizeApiKeyBytes(bytes).getOrNull()
        assertNotNull(out)
        for (ch in out!!) {
            assertFalse("归一化后不得含 NUL", ch.code == 0x00)
            assertFalse("归一化后不得含 DEL", ch.code == 0x7F)
            assertTrue(ch.code in 0x21..0x7E)
        }
    }

    @Test
    fun authorizationHeaderValueWouldBeSafe() {
        // 模拟 HeartVoiceApiClient 构造 "Bearer $key" 前的最终校验：非法 Key 返回 failure。
        val safe = ApiKeyValidator.normalizeApiKey(validKey).getOrThrow()
        val header = "Bearer $safe"
        // 头值不得含 0x00 或任何 < 0x20 的控制字符
        assertTrue(header.none { it.code == 0x00 || (it.code in 0x01..0x1F) || it.code == 0x7F })
    }
}
