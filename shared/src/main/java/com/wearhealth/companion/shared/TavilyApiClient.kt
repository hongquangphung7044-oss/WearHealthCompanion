package com.wearhealth.companion.shared

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * 1. 仅在 ThinkingMode != FAST（即 BALANCED / MAX 档）+ Tavily Key 已配置时触发
 * 2. 固定 3 个查询方向（见 [SEARCH_QUERIES]），不让 DS 自由发挥
 * 3. 进程内缓存（[CACHE_TTL_MS]），同一查询在缓存有效期内不重复调用
 *    → 同一次 App 会话内多次均衡/Max 分析，每个查询最多调用 1 次 Tavily
 * 4. 并行执行（async + awaitAll）：3 个查询同时发出，省 6-10s（vs 串行）
 *    Tavily 无明确公开速率限制（basic 档按 credit 计费），3 并发安全
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
     * 执行一次完整检索：对 [SEARCH_QUERIES] 的每个查询**并行**调用 Tavily，合并结果。
     *
     * 并行实现（feature/raw-ecg-to-ds）：3 个查询用 async 同时发出，awaitAll 等齐后合并。
     * 单查询约 3-5s，并行后总耗时≈最慢查询（4-6s），比串行（10-15s）省 6-10s。
     *
     * 缓存策略：命中缓存的查询直接复用，不参与并发请求；未命中的才发起网络调用。
     * 失败的查询返回空字符串，不阻断其他查询和后续 DS 分析。
     *
     * @return 合并后的医学参考文献文本，可直接拼入 DS 提示词。失败返回空字符串（不阻断 DS 分析）
     */
    suspend fun fetchMedicalReferences(): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ""

        val now = System.currentTimeMillis()

        // 并行发起所有查询：命中缓存的同步返回文本，未命中的 async 调用 Tavily
        val deferreds = SEARCH_QUERIES.map { query ->
            async {
                val cached = cache[query]
                if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
                    cached.text  // 命中缓存，不发网络请求
                } else {
                    val result = runCatching { searchOnce(query) }
                    result.getOrElse {
                        Log.w(TAG, "Tavily 查询失败 [$query]: ${it.message}")
                        ""
                    }
                }
            }
        }
        val texts = deferreds.awaitAll()

        // 写入缓存（未命中的查询成功后）+ 合并文本
        val sb = StringBuilder()
        var anySuccess = false
        for ((query, text) in SEARCH_QUERIES.zip(texts)) {
            if (text.isNotBlank()) {
                // 只在缓存未命中且本次有结果时写入（避免覆盖已有缓存）
                val existing = cache[query]
                if (existing == null || now - existing.timestamp >= CACHE_TTL_MS) {
                    cache[query] = CacheEntry(text, now)
                }
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

        /** 进程内缓存有效期：6 小时（同会话内多次均衡/Max 分析不重复搜同一查询） */
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        /** 进程内查询缓存（query → 清洗后文本 + 时间戳） */
        private val cache = mutableMapOf<String, CacheEntry>()

        /**
         * 固定医学参考检索方向（3 个核心，覆盖节律检测 + HRV + 间期校正）。
         *
         * 演进历史：
         * - v1：3 个查询（房颤/早搏/QTc），仅 Max 档
         * - v2：7 个查询，均衡+Max 档（覆盖太广，串行 30s+）
         * - v3：4 个查询（房颤/早搏/HRV/QTc），均衡+Max 档，串行 12-20s
         * - v4（当前）：3 个查询并行，合并房颤+早搏为"心律失常检测"
         *   原因：房颤与早搏检测方法学高度相关（都看 RR 变异性 + Poincaré），
         *   合并检索能命中同时覆盖两者的综述/指南文献，token 利用率更高
         *   并行执行总耗时≈最慢查询（4-6s），比 v3 串行省 6-10s
         *
         * 选择依据：raw 模式 DS 自己看波形，prompt 已内置 PR/QRS/ST-T/腕表局限性等约束，
         * Tavily 只补 DS 训练数据可能过时的部分——节律分类、HRV 分层、QTc 阈值。
         * 查询用英文（医学文献库以英文为主，命中更优），年份锚定最新指南周期。
         */
        private val SEARCH_QUERIES = listOf(
            // 查询 1：心律失常检测（房颤 + 早搏合并）——方法学相关，命中综述文献
            "arrhythmia detection single lead ECG atrial fibrillation PAC PVC RR variability Poincare wearable 2024 guidelines",
            // 查询 2：HRV 时域指标分层（年龄相关的正常参考值）
            "HRV SDNN RMSSD pNN50 normal reference values by age clinical interpretation wearable",
            // 查询 3：QTc 校正公式与临床阈值（Bazett/Fridericia + ESC 2024 指南）
            "QTc prolongation Fridericia Bazett clinical significance threshold ESC guidelines 2024",
        )
    }
}
