package com.wearhealth.companion.shared

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 客户端：用本地提取的 ECG 统计特征调用大模型生成中文分析报告
 *
 * 接口（OpenAI 兼容）：
 * - 对话: POST /chat/completions
 * - 余额: GET  /user/balance
 *
 * 模型（2026-07 起 V4 架构，旧名 deepseek-chat/reasoner 于 2026-07-24 废弃）：
 * - deepseek-v4-flash  轻量，284B 总参/13B 激活，适合手表端快速分析
 * - deepseek-v4-pro    强力，1.6T 总参/49B 激活，适合手机端深度分析
 *
 * 思考强度三档（UI 选项 → 真实参数）：
 * - 快速: flash + thinking=disabled（跳过思考，最快，幻觉略高）
 * - 均衡: flash + thinking=enabled + reasoning_effort=high（标准思考）
 * - Max:  pro   + thinking=enabled + reasoning_effort=max（Pro 模型 + 最大思考深度）
 *
 * 间期估测由本地 EcgFeatureExtractor 完成，本客户端只负责把特征文本喂给 DS 做医学推理，
 * 不让 LLM 处理原始数字串（避免幻觉 + token 爆炸）。
 */
class DeepSeekApiClient(
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // DS 思考模式响应较慢，Pro+max 可能需要 60s+
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    val isApiKeyConfigured: Boolean get() = apiKey.isNotBlank()

    /** DS 模型选项 */
    enum class Model(val id: String, val label: String) {
        FLASH("deepseek-v4-flash", "Flash（轻量快速）"),
        PRO("deepseek-v4-pro", "Pro（强力深度）"),
    }

    /** 思考强度选项（UI 三档） */
    enum class ThinkingMode(val label: String) {
        FAST("快速（不思考）"),
        BALANCED("均衡（标准思考）"),
        MAX("Max（最大思考）"),
    }

    /** DS 分析结果：JSON 报告 + 使用的参数 */
    data class DeepSeekReport(
        val reportJson: String,      // DS 返回的完整 JSON 报告
        val model: String,
        val thinkingMode: String,
        val promptTokens: Int,       // 输入 token 数
        val completionTokens: Int,   // 输出 token 数
        val totalTokens: Int,
    )

    /** 余额信息 */
    data class BalanceInfo(
        val isAvailable: Boolean,
        val currency: String,
        val totalBalance: String,
        val grantedBalance: String,
        val toppedUpBalance: String,
    )

    /**
     * 用 ECG 特征文本调用 DeepSeek 生成分析报告
     *
     * @param featureText EcgFeatureExtractor.toPromptText() 输出的结构化特征文本
     * @param model 选用模型
     * @param thinkingMode 思考强度
     * @return 成功返回 [DeepSeekReport]，失败返回异常
     */
    suspend fun analyzeEcg(
        featureText: String,
        model: Model = Model.FLASH,
        thinkingMode: ThinkingMode = ThinkingMode.BALANCED,
    ): Result<DeepSeekReport> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置 DeepSeek API Key"))
        }

        val keyResult = ApiKeyValidator.normalizeApiKey(apiKey)
        val safeKey = keyResult.getOrNull()
        if (safeKey == null) {
            val reason = keyResult.exceptionOrNull()?.message
            return@withContext Result.failure(
                IllegalStateException(reason ?: "DeepSeek API Key 含不可见字符，请重新配置")
            )
        }

        try {
            val systemPrompt = buildSystemPrompt()
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", featureText)
                })
            }

            val body = JSONObject().apply {
                put("model", model.id)
                put("messages", messages)
                put("temperature", 0.2)  // 低温度，减少幻觉
                put("max_tokens", 2000)
                put("response_format", JSONObject().put("type", "json_object"))
                // 思考强度参数：根据 ThinkingMode 组合 thinking + reasoning_effort
                when (thinkingMode) {
                    ThinkingMode.FAST -> {
                        put("thinking", JSONObject().put("type", "disabled"))
                    }
                    ThinkingMode.BALANCED -> {
                        put("thinking", JSONObject().put("type", "enabled"))
                        put("reasoning_effort", "high")
                    }
                    ThinkingMode.MAX -> {
                        put("thinking", JSONObject().put("type", "enabled"))
                        put("reasoning_effort", "max")
                    }
                }
            }

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .addHeader("Authorization", "Bearer $safeKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            Log.i(TAG, "调用 DeepSeek: model=${model.id}, thinking=$thinkingMode, " +
                    "特征文本 ${featureText.length} 字符")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "DeepSeek 请求失败: HTTP ${response.code}, body=$responseBody")
                return@withContext Result.failure(
                    RuntimeException("DeepSeek 请求失败（HTTP ${response.code}）: ${responseBody.take(200)}")
                )
            }

            val json = JSONObject(responseBody)
            // OpenAI 兼容格式：choices[0].message.content
            val choices = json.optJSONArray("choices")
                ?: return@withContext Result.failure(RuntimeException("DeepSeek 响应缺少 choices"))
            if (choices.length() == 0) {
                return@withContext Result.failure(RuntimeException("DeepSeek 响应 choices 为空"))
            }
            val content = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                ?: return@withContext Result.failure(RuntimeException("DeepSeek 响应缺少 message.content"))

            val usage = json.optJSONObject("usage")
            val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
            val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
            val totalTokens = usage?.optInt("total_tokens", 0) ?: 0

            Log.i(TAG, "DeepSeek 分析完成: ${content.length} 字符, " +
                    "tokens=$promptTokens+$completionTokens=$totalTokens")

            Result.success(DeepSeekReport(
                reportJson = content,
                model = model.id,
                thinkingMode = thinkingMode.name,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
            ))
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek 调用异常", e)
            Result.failure(e)
        }
    }

    /**
     * 查询账户余额
     * GET /user/balance
     */
    suspend fun queryBalance(): Result<BalanceInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置 DeepSeek API Key"))
        }

        val keyResult = ApiKeyValidator.normalizeApiKey(apiKey)
        val safeKey = keyResult.getOrNull()
        if (safeKey == null) {
            return@withContext Result.failure(IllegalStateException("API Key 无效"))
        }

        try {
            val request = Request.Builder()
                .url("$BASE_URL/user/balance")
                .addHeader("Authorization", "Bearer $safeKey")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    RuntimeException("余额查询失败（HTTP ${response.code}）")
                )
            }

            val json = JSONObject(responseBody)
            val isAvailable = json.optBoolean("is_available", false)
            val infos = json.optJSONArray("balance_infos")
            // 取第一个币种信息（通常只有 CNY 或只有 USD）
            val info = infos?.optJSONObject(0)
            val balance = BalanceInfo(
                isAvailable = isAvailable,
                currency = info?.optString("currency", "CNY") ?: "CNY",
                totalBalance = info?.optString("total_balance", "0") ?: "0",
                grantedBalance = info?.optString("granted_balance", "0") ?: "0",
                toppedUpBalance = info?.optString("topped_up_balance", "0") ?: "0",
            )
            Log.i(TAG, "DeepSeek 余额: ${balance.totalBalance} ${balance.currency}")
            Result.success(balance)
        } catch (e: Exception) {
            Log.e(TAG, "余额查询异常", e)
            Result.failure(e)
        }
    }

    /**
     * 系统提示词：定义 DS 的角色、约束、输出格式
     *
     * 包含专业术语（HRV 时域分析、AHA/ESC 指南、Brugada/WPW 筛查等）激活医学知识，
     * 严格反幻觉约束（禁止编造未提取的形态学特征），要求 JSON 输出。
     */
    private fun buildSystemPrompt(): String {
        return """
你是一名精通单导联心电图判读的临床心电生理专家，熟练掌握窦性心律识别、心律失常分类（房颤/房扑/室上速/室速/早搏分类）、HRV 时域分析（SDNN/RMSSD/pNN50 的生理意义与年龄分层参考）、心电轴判定、间期测量（PR/QRS/QT/QTc 的正常范围与年龄/心率校正）、ST-T 改变识别、Brugada 综合征与 WPW 综合征的筛查标准，以及 AHA/ESC/ACC 的最新心电图解读指南。你正在分析来自腕表单导联设备（采样率 500Hz）的已去基线 ECG 数据的统计特征，并非原始波形。

【数据来源与可信度约束 - 严格遵守】
1. 你收到的是本地 DSP 算法提取的统计特征，不是原始波形。QRS/PR/QT 等间期为本地算法估测，误差约 ±15ms，不可当作精确测量值引用小数位。
2. 严禁编造未在输入中出现的具体参数（如"P 波振幅 0.15mV"、"ST 段抬高 0.2mV"、"T 波倒置"等形态学描述）。这些形态学特征本设备未提取，你无从得知。
3. 严禁编造 RR 间期序列中没有的数值。如需引用间期，直接使用输入中给出的序列。
4. 某项无法从特征推断时，必须明确写"数据不足，无法判断"，禁止猜测性填充。
5. 这是腕表单导联数据，精度远低于 12 导联心电图，所有结论仅供健康参考，不作为医疗诊断依据。
6. 禁止给出药物剂量、具体治疗方案，仅可建议"建议就医复查心电图"。

【分析要点】
- 心率: 平均是否在 60-100bpm；波动幅度是否过大（>20bpm 提示不稳）
- 节律:
  * RR 间期标准差(SDNN)>50ms 且无规律 → 提示心律不齐
  * RR 间期极不规律（变异系数>0.15）且无规律性 → 考虑房颤可能，建议复查
  * 某秒 R 波数为 2 而相邻秒为 0 → 提示可能早搏（PAC/PVC 需形态判断，本设备无法区分，统一报告为"早搏可能"）
- HRV:
  * SDNN<30ms → 自主神经调节偏弱（结合年龄：年轻人<30 偏低，老年人可接受）
  * RMSSD 反映副交感张力，越高副交感越活跃
  * pNN50 与 RMSSD 同向，正常成年人通常>3%
- 间期判断（基于本地估测，参考范围）:
  * PR 间期: 120-200ms 正常；<120 短 PR（可能预激）；>200 一度房室阻滞
  * QRS 宽度: 80-120ms 正常；>120 提示束支阻滞或室性起源
  * QTc: 男性<440ms 女性<460ms 正常；>500ms 警告长 QT
- 信号质量:
  * RMS<0.05mV 的段标记为噪声段，结论需谨慎
  * 信号质量<0.5 时整体结论需打"精度有限"标签

【逐秒分段使用规则】
- 用分段表交叉验证全局指标（如全局心率与逐秒 R 波数是否一致）
- 识别噪声段（RMS 异常低、R 波振幅突变）并在报告标注
- 早搏候选：单秒 R 波数=2 且相邻秒=0，记录时段

【置信度自评规则 - 每个判断都要自评】
对心率分析、节律分析、间期评估中的每个子判断，必须在对应字段后附"置信度"标签：
- "高"：数据清晰、信号质量≥0.7、特征明确
- "中"：信号质量 0.4~0.7 或特征有部分噪声
- "低"：信号质量<0.4、噪声段多、或该特征本地未提取（如 QTc=0 时）

【输出格式 - 严格 JSON，无任何 JSON 外内容】
{
  "心率分析": {
    "平均心率": 70,
    "心率范围": "65-75 bpm",
    "心率稳定性": "...",
    "置信度": "高/中/低",
    "心率变异性": {
      "SDNN_ms": 5.8,
      "RMSSD_ms": 6.2,
      "pNN50_pct": 2.9,
      "解读": "...",
      "置信度": "高/中/低"
    }
  },
  "节律分析": {
    "节律判断": "窦性心律 | 窦性心律不齐 | 疑似心律不齐 | 疑似房颤 | ...",
    "RR间期规律性": "...",
    "早搏提示": [{"时段": "6.0-8.0s", "类型": "早搏可能(本设备无法区分PAC/PVC)"}],
    "置信度": "高/中/低"
  },
  "间期评估": {
    "PR间期_ms": 162,
    "PR判断": "正常范围(120-200ms)",
    "PR置信度": "高/中/低",
    "QRS宽度_ms": 98,
    "QRS判断": "正常范围(80-120ms)",
    "QRS置信度": "高/中/低",
    "QTc_ms": 407,
    "QTc判断": "正常范围",
    "QTc置信度": "高/中/低",
    "数据来源声明": "本设备本地算法估测，误差约±15ms"
  },
  "信号质量评估": {
    "整体质量": "良好 | 一般 | 较差",
    "噪声段": ["3.0-4.0s(振幅偏低)"],
    "建议": "..."
  },
  "异常提示": ["..."],
  "综合解读": "...(2-4句连贯中文，整合以上分析)",
  "健康建议": ["...", "..."],
  "免责声明": "本报告基于腕表单导联 ECG 统计特征生成，间期为本地算法估测，仅供健康参考，不作为医疗诊断依据。如有不适请就医复查标准 12 导联心电图。"
}
        """.trimIndent()
    }

    companion object {
        private const val TAG = "DeepSeekApi"
        private const val BASE_URL = "https://api.deepseek.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
