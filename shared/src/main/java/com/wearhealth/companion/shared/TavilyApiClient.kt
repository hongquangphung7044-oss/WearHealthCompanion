package com.wearhealth.companion.shared

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Tavily 搜索 API 客户端：DS 分析前联网检索最新医学文献，注入提示词增强循证依据
 *
 * 设计动机：DeepSeek chat/completions 接口无内置联网能力。本客户端用 Tavily 搜索固定
 * 医学参考方向，把结果摘要喂给 DS，使其基于最新文献推理（而非仅依赖训练数据）。
 *
 * 搜索预算控制：
 * 1. 仅在 ThinkingMode.MAX + Tavily Key 已配置时触发（均衡/快速不搜，省预算）
 * 2. 固定 3 个查询方向（见 [SEARCH_QUERIES]），不让 DS 自由发挥
 * 3. 进程内缓存（[CACHE_TTL_MS]），同一查询在缓存有效期内不重复调用
 *    → 同一次 App 会话内多次 Max 分析，每个查询最多调用 1 次 Tavily
 *
 * Tavily API（2026-07）：POST https://api.tavily.com/search
 * - search_depth=basic（轻量，省 credit）
 * - include_answer=true（返回 AI 摘要 + 源列表）
 * - max_results=3（每查询 3 条来源，控制 token 量）
 */
class TavilyApiClient(
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val isApiKeyConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * 执行一次完整检索：对 [SEARCH_QUERIES] 的每个查询调用 Tavily，合并结果。
     *
     * @return 合并后的医学参考文献文本，可直接拼入 DS 提示词。失败返回空字符串（不阻断 DS 分析）
     */
    suspend fun fetchMedicalReferences(): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ""

        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        var anySuccess = false

        for ((index, query) in SEARCH_QUERIES.withIndex()) {
            // 命中缓存则直接复用
            val cached = cache[query]
            if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
                sb.append(cached.text)
                anySuccess = true
                continue
            }
            val result = runCatching { searchOnce(query) }
            val text = result.getOrElse {
                Log.w(TAG, "Tavily 查询失败（$index）: ${it.message}")
                ""
            }
            if (text.isNotBlank()) {
                cache[query] = CacheEntry(text, now)
                sb.append(text)
                anySuccess = true
            }
        }

        if (!anySuccess) {
            Log.w(TAG, "所有 Tavily 查询均失败，DS 分析将不附加联网文献")
            return@withContext ""
        }
        buildReferencesBlock(sb.toString())
    }

    /** 单次 Tavily 搜索调用，返回清洗后的文本（摘要 + 来源标题/URL） */
    private suspend fun searchOnce(query: String): String {
        val keyResult = ApiKeyValidator.normalizeApiKey(apiKey)
        val safeKey = keyResult.getOrNull()
            ?: throw IllegalStateException("Tavily API Key 无效")

        val body = JSONObject().apply {
            put("api_key", safeKey)
            put("query", query)
            put("search_depth", "basic")
            put("include_answer", true)
            put("max_results", 3)
        }

        val request = Request.Builder()
            .url("$BASE_URL/search")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Tavily HTTP ${response.code}")
            }
            val raw = response.body?.string() ?: ""
            val json = JSONObject(raw)
            val answer = json.optString("answer", "").trim()
            val results = json.optJSONArray("results")
            val sb = StringBuilder()
            if (answer.isNotBlank()) {
                sb.append("【查询】").append(query).append('\n')
                sb.append("摘要：").append(truncate(answer, 600)).append('\n')
            }
            // 源列表（标题 + URL），不引全文，控制 token
            if (results != null && results.length() > 0) {
                sb.append("来源：")
                val count = minOf(results.length(), 3)
                for (i in 0 until count) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title", "").trim()
                    if (title.isNotBlank()) {
                        sb.append("\n  - ").append(truncate(title, 120))
                    }
                }
                sb.append('\n')
            }
            return sb.toString()
        }
    }

    /** 把多段检索结果包装为提示词可引用的参考文献块 */
    private fun buildReferencesBlock(rawText: String): String {
        if (rawText.isBlank()) return ""
        return buildString {
            append("\n【联网检索的最新医学参考 - Tavily】\n")
            append("以下为本次分析前实时检索的文献摘要（来源已列明），你应优先遵循上述已校准的循证依据，")
            append("这些联网结果仅作补充参考，不可覆盖本地算法提取的实测特征与已标注的文献阈值。\n")
            append(rawText.trim())
            append('\n')
        }
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    private data class CacheEntry(val text: String, val timestamp: Long)

    companion object {
        private const val TAG = "TavilyApiClient"
        private const val BASE_URL = "https://api.tavily.com"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** 进程内缓存有效期：6 小时（同会话内多次 Max 分析不重复搜同一查询） */
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        /** 进程内查询缓存（query → 清洗后文本 + 时间戳） */
        private val cache = mutableMapOf<String, CacheEntry>()

        /**
         * 固定医学参考检索方向（7 个，覆盖本设备涉及的主要判读方向）。
         *
         * 选择依据：覆盖腕表单导联 ECG 判读的核心方向——节律分类（房颤/早搏/房扑）、
         * HRV 时域分析、间期测量与心率校正（QTc/PR/QRS）、腕表 ECG 算法性能、ST-T 改变、
         * 筛查型综合征（长 QT/Brugada/WPW），让 DS 能引用最新指南/研究而非仅训练数据。
         * 查询用英文（医学文献库以英文为主，命中更优），年份锚定最新指南周期。
         */
        private val SEARCH_QUERIES = listOf(
            "atrial fibrillation detection single lead ECG RR variability Poincare 2024 guidelines",
            "premature atrial ventricular contraction PAC PVC detection wearable ECG RR interval short-long pairing",
            "HRV SDNN RMSSD pNN50 normal reference values by age clinical interpretation",
            "QTc prolongation Fridericia Bazett clinical significance threshold ESC guidelines 2024",
            "PR interval QRS duration measurement single lead ECG normal range bundle branch block",
            "smartwatch wearable single lead ECG accuracy validation clinical study Apple Watch Samsung",
            "ST segment T wave morphology interpretation single lead ECG ischemia detection limitations",
        )
    }
}
