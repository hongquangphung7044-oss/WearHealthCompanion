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
 * - deepseek-v4-flash  轻量，284B 总参/13B 激活，手表端分析使用
 * - deepseek-v4-pro    强力，1.6T 总参/49B 激活（保留枚举，本项目当前不用）
 *
 * 思考强度三档（UI 选项 → 真实参数，统一用 Flash 模型，仅区分思考强度）：
 * - 快速: flash + thinking=disabled（跳过思考，最快，幻觉略高）
 * - 均衡: flash + thinking=enabled + reasoning_effort=high（标准思考）
 * - Max:  flash + thinking=enabled + reasoning_effort=max（Flash + 最大思考深度）
 *
 * 思考模式 API 约束（DeepSeek 官方文档，2026-07）：
 * 1. 思考模式不支持 temperature/top_p/presence_penalty/frequency_penalty（设置不会报错但不生效）
 * 2. 思维链内容通过 reasoning_content 返回，与 content 同级；content 仅含最终答案
 * 3. max 模式 TTFT 可达 20-38s，总响应可能 60-90s+，需足够大的 readTimeout
 *
 * 间期估测由本地 EcgFeatureExtractor 完成，本客户端只负责把特征文本喂给 DS 做医学推理，
 * 不让 LLM 处理原始数字串（避免幻觉 + token 爆炸）。
 */
class DeepSeekApiClient(
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // DS 思考模式响应较慢：Flash+max TTFT 可达 20-38s，加上思考与生成可能 60-90s+
        // 用 180s 保证 max 档不超时（否则请求失败导致 AI 解读消失）
        .readTimeout(180, TimeUnit.SECONDS)
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
        val tavilyStatus: String = "",  // Tavily 联网检索状态（未触发/已检索/检索失败/未配置），供 UI 展示
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
     * @param tavilyApiKey 可选 Tavily Key；非空且非 FAST 档时，分析前联网检索
     * @param rawEcgMode 是否为原始波形直传模式。true 时用原始波形专用系统提示词，
     *   DS 直接分析波形而非本地算法估测的特征。feature/raw-ecg-to-ds 分支新增。
     *        固定医学文献方向，把摘要注入提示词（搜索预算：3 个固定查询 + 6h 进程内缓存）
     * @return 成功返回 [DeepSeekReport]，失败返回异常
     */
    suspend fun analyzeEcg(
        featureText: String,
        model: Model = Model.FLASH,
        thinkingMode: ThinkingMode = ThinkingMode.BALANCED,
        tavilyApiKey: String = "",
        rawEcgMode: Boolean = false,
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
            // 联网检索（均衡/Max 档 + Tavily Key 已配置）：固定 3 个医学文献方向，6h 进程内缓存
            // 快速档不检索，省搜索预算；检索失败不阻断 DS 分析（返回空字符串）
            // 状态记录供 UI 展示（用户能看见是否联网成功）
            // 设计变更（feature/raw-ecg-to-ds）：Tavily 从仅 Max 下放到均衡档，
            // 原因是均衡档是日常主力分析路径，配 Tavily 文献后判读依据更全面准确
            var tavilyStatus = ""
            val tavilyRefs = if (thinkingMode != ThinkingMode.FAST) {
                if (tavilyApiKey.isBlank()) {
                    tavilyStatus = "未配置 Tavily Key（均衡/Max 档可选联网，本次未检索）"
                    ""
                } else {
                    val result = runCatching { TavilyApiClient(tavilyApiKey).fetchMedicalReferences() }
                    val refs = result.getOrElse {
                        Log.w(TAG, "Tavily 联网检索失败，DS 分析继续（不附加文献）: ${it.message}")
                        tavilyStatus = "检索失败：${it.message ?: "未知错误"}（未附加文献，不阻断分析）"
                        ""
                    }
                    if (refs.isNotBlank()) {
                        tavilyStatus = "已检索医学文献并注入提示词（房颤检测方法学/HRV参考值/QTc临床意义）"
                    }
                    refs
                }
            } else {
                tavilyStatus = "未触发（快速档不联网，仅均衡/Max 档检索）"
                ""
            }

            val baseSystemPrompt = if (rawEcgMode) buildRawEcgSystemPrompt() else buildSystemPrompt()
            val systemPrompt = baseSystemPrompt + tavilyRefs
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
                // max_tokens 上限设到 DeepSeek-V4-Flash 官方最大输出 384K（依据 api-docs.deepseek.com 模型&价格页）
                // 上下文 1M，输入侧（原始 ECG 15000 点去 DC 文本约 30-60K token）+ 输出侧（思维链 + JSON 报告）合计 <1M
                // 384K 输出上限按实际用量计费，未用满不扣费，留足余量应对原始波形深度分析的长思维链
                put("max_tokens", 384000)
                put("response_format", JSONObject().put("type", "json_object"))
                // 思考强度参数：根据 ThinkingMode 组合 thinking + reasoning_effort
                // 注意：思考模式(thinking=enabled)不支持 temperature/top_p 等采样参数
                // （DeepSeek 官方文档：设置不报错但不生效），故仅在非思考模式传 temperature
                when (thinkingMode) {
                    ThinkingMode.FAST -> {
                        put("thinking", JSONObject().put("type", "disabled"))
                        put("temperature", 0.2)  // 低温度，减少幻觉
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
            // 思考模式下还有 reasoning_content（思维链），与 content 同级。
            // Max 档实测问题：content 有时混入思维过程文字（"我们被要求分析..."）+ 残缺 JSON，
            // 导致 JsonCleaner 提取到错误片段。需用健壮提取：逐个验证候选 JSON 是否可解析。
            val choices = json.optJSONArray("choices")
                ?: return@withContext Result.failure(RuntimeException("DeepSeek 响应缺少 choices"))
            if (choices.length() == 0) {
                return@withContext Result.failure(RuntimeException("DeepSeek 响应 choices 为空"))
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: return@withContext Result.failure(RuntimeException("DeepSeek 响应缺少 message"))
            val content = message.optString("content", "")
            val reasoningContent = message.optString("reasoning_content", "")

            val usage = json.optJSONObject("usage")
            val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
            val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
            val totalTokens = usage?.optInt("total_tokens", 0) ?: 0

            Log.i(TAG, "DeepSeek 分析完成: content=${content.length}字符 " +
                    "reasoning=${reasoningContent.length}字符, " +
                    "tokens=$promptTokens+$completionTokens=$totalTokens")

            // 健壮 JSON 提取（修复 Max 档思维过程泄漏导致 JSON 解析失败）：
            // 1. 先尝试 content 整体作为 JSON 解析（理想情况：content 就是纯 JSON）
            // 2. 失败则从 content 用 JsonCleaner 提取 {...} 片段
            // 3. 仍失败则从 reasoning_content 提取最后一个 {...}（思维链结尾常含结论 JSON）
            // 4. 都失败才报错（不再让思维过程文字污染报告）
            val cleanedContent = extractValidJson(content, reasoningContent)
                ?: return@withContext Result.failure(
                    RuntimeException(
                        "DeepSeek 响应无法提取有效 JSON。content前200字: ${content.take(200)}"
                    )
                )

            Result.success(DeepSeekReport(
                reportJson = cleanedContent,
                model = model.id,
                thinkingMode = thinkingMode.name,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                tavilyStatus = tavilyStatus,
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
     * 健壮 JSON 提取（修复 Max 思考模式 content 混入思维过程文字的问题）
     *
     * DeepSeek V4 思考模式下：
     * - content 应为最终 JSON 答案，但 Max 档有时混入思维过程文字 + 残缺 JSON
     * - reasoning_content 是完整思维链，结尾常含结论 JSON
     *
     * 提取策略（逐个验证，确保返回可解析的 JSON 对象）：
     * 1. content 整体作为 JSON 解析（理想情况）
     * 2. content 用 JsonCleaner 提取 {...} 后解析
     * 3. reasoning_content 用 JsonCleaner 提取 {...} 后解析（取最后一个完整对象）
     * 4. 都失败返回 null（调用方报错，不再让思维文字污染报告）
     */
    private fun extractValidJson(content: String, reasoningContent: String): String? {
        // 1. content 整体
        if (content.isNotBlank()) {
            runCatching { JSONObject(JsonCleaner.extractJsonObject(content)) }
                .onSuccess { return JsonCleaner.extractJsonObject(content) }
        }
        // 2. content 提取片段（extractJsonObject 已在 1 里调用，这里跳过避免重复）
        // 3. reasoning_content 提取最后一个 {...}
        //    思维链可能有多个 {...} 片段，最后一个通常是最终结论
        if (reasoningContent.isNotBlank()) {
            val cleaned = JsonCleaner.extractJsonObject(reasoningContent)
            runCatching { JSONObject(cleaned) }
                .onSuccess { return cleaned }
            // 思维链中可能有多个 JSON 片段，尝试找最后一个能解析的
            val candidates = findAllJsonObjects(reasoningContent)
            for (candidate in candidates.reversed()) {
                runCatching { JSONObject(candidate) }
                    .onSuccess { return candidate }
            }
        }
        return null
    }

    /** 从文本中提取所有 {...} 片段（按出现顺序） */
    private fun findAllJsonObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            results.add(text.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return results
    }

    /**
     * 系统提示词：定义 DS 的角色、约束、输出格式
     *
     * 包含专业术语（HRV 时域分析、AHA/ESC 指南、Brugada/WPW 综合征筛查等）激活医学知识，
     * 严格反幻觉约束（禁止编造未提取的形态学特征），要求 JSON 输出。
     */
    private fun buildSystemPrompt(): String {
        return """
你是一名精通单导联心电图判读的临床心电生理专家，熟练掌握窦性心律识别、心律失常分类（房颤/房扑/室上速/室速/早搏分类）、HRV 时域分析（SDNN/RMSSD/pNN50 的生理意义与年龄分层参考）、心电轴判定、间期测量（PR/QRS/QT/QTc 的正常范围与年龄/心率校正）、ST-T 改变识别、Brugada 综合征与 WPW 综合征的筛查标准，以及 AHA/ESC/ACC 的最新心电图解读指南。你正在分析来自腕表单导联设备（采样率 500Hz）的已去基线 ECG 数据的统计特征，并非原始波形。

【循证依据声明】本系统采用的算法与阈值依据如下权威文献，你应在报告中遵循这些依据，并明确所有结论为筛查辅助而非临床诊断金标准：
- Poincaré SD1/SD2 公式：Brennan 2001 标准公式（SD1=std(RR_n-RR_{n+1})/√2 短轴/副交感，SD2=std(RR_n+RR_{n+1})/√2 长轴）
- 房颤 RR 特征：Tuboly 2019（单导联+Poincaré，Se 98.69%/Sp 99.59%）、Park 2009（Poincaré 聚类，Se 91.4%/Sp 92.9%）
- 早搏 RR 剔除阈值：Karey 2019 Front Physiol（20% 变化是正常/异常 RR 最佳分界，accuracy 0.92-0.99）
- pNN50 与房颤风险：SAFER 研究（AUROC 96%），但切点因人群差异极大（5.5%-43.5%），无金标准
- HRV/节律判断结论仅作健康参考，须建议就医复查标准 12 导联心电图确诊

【数据来源与可信度约束 - 严格遵守】
1. 你收到的是本地 DSP 算法提取的统计特征，不是原始波形。QRS/PR/QT 等间期为本地算法估测，误差约 ±15ms，不可当作精确测量值引用小数位。
2. 间期估测的已知系统偏差（判断时需留余量，并在报告注明"以专业 API/临床心电图为准"）：
   - QRS 宽度：阈值交叉法（10% 阈值），相对专业 API 可能偏宽约 0-10ms（判束支阻滞时需更保守，>120ms 才提示）
   - QT 间期：T 波峰定位法（非临床金标准 Lepeschkin 切线法），测到 T 波峰而非 T 波终点，相对专业 API 偏短约 50-100ms（判长 QT 时敏感度高：本地值若 >440ms 真实值更可能 >440ms；但判正常时需谨慎，真实值可能已延长）
   - PR 间期：P 波峰定位法，误差较大，仅作参考
3. 严禁编造未在输入中出现的具体参数（如"P 波振幅 0.15mV"、"ST 段抬高 0.2mV"、"T 波倒置"等形态学描述）。这些形态学特征本设备未提取，你无从得知。
4. 严禁编造 RR 间期序列中没有的数值。如需引用间期，直接使用输入中给出的序列。
5. 某项无法从特征推断时，必须明确写"数据不足，无法判断"，禁止猜测性填充。
6. 这是腕表单导联数据，精度远低于 12 导联心电图，所有结论仅供健康参考，不作为医疗诊断依据。
7. 禁止给出药物剂量、具体治疗方案，仅可建议"建议就医复查心电图"。
8. HRV 指标（SDNN/RMSSD/pNN50）已由本地算法剔除早搏 RR（偏离均值>20% 的间期，阈值依据 Karey 2019 Front Physiol），反映自主神经张力，不反映心律失常。严禁用 HRV 数值高低反推节律——节律判断以 RR 序列的 [*] 标记、变异系数、Poincaré 形态为准。"早搏 + 正常 HRV"是常见组合，不矛盾。
9. 【PPG 绿光参考心率】若输入中给出"PPG绿光参考心率"，这是测量后由系统心率传感器（绿光 PPG）独立测算的瞬时心率，可信度高于本地 R 波估测的心率。判断规则：
   - 若 PPG 与本地 R 波平均心率偏差 >15bpm，提示本地 R 波检测可能漏检/误检，心率分析应以 PPG 为准，并在报告中注明"本地 R 波心率与 PPG 参考偏差大，已采用 PPG 参考心率"
   - PPG 只是一个瞬时参考值，不能反映 30 秒内的 min/max 范围；min/max 仍以 R 波间期为主，但若 R 波心率明显异常（如 33-172bpm 跨度）应优先采信 PPG 的合理性
   - PPG 不可用时（未给出），按原逻辑用 R 波心率

【RR 间期序列分析指南 - 你必须主动分析 RR 序列】
你收到的 RR 间期序列是本地 R 波检测的真实输出，你必须主动分析它来判断节律：
1. RR 序列中带 [*] 标记的是偏离均值>20%的异常间期（短 RR = 早搏前的心跳过快，长 RR = 早搏后代偿间隙）
2. RR 差值序列：大幅正值后大幅负值 = 长-短交替 = 早搏特征；持续小幅波动 = 正常呼吸性变异
3. 判断步骤：
   - 检查 RR 序列是否有 [*] 标记的异常间期
   - 检查差值序列是否有大幅正负交替（>100ms 的正负切换）
   - 结合 Poincaré 散点形态和短-长配对数综合判断
4. 正常窦性心律：RR 序列规律（变异系数<0.05），无明显 [*] 标记，差值序列波动小
5. 窦性心律不齐：RR 序列有轻度波动（变异系数 0.05~0.15，年轻人深呼吸时可达 0.20），
   [*] 标记少（≤2个），青年人常见，良性
6. 早搏：RR 序列有≥3个 [*] 标记，差值序列有大幅正负交替，短-长配对数≥3
7. 房颤：RR 序列极度不规律（变异系数>0.20），大量 [*] 标记，差值序列持续大幅波动，
   无任何连续 3 个 RR 接近，无周期性。须严格判断，不可仅凭 CV 略高就报房颤

【分析要点】
- 心率: 平均是否在 60-100bpm；波动幅度是否过大（>20bpm 提示不稳）
- 节律（基于 RR 变异系数 + Poincaré 散点形态 + 短-长配对 + RR 序列模式，这是节律判断的主要依据）:
  * Poincaré 散点形态说明：SD1=短轴(短期变异/副交感)，SD2=长轴(长期变异)，Brennan 2001 标准公式。
    健康人 SD1<SD2（彗星形，沿对角线集中）；房颤时 SD1 显著升高接近/超过 SD2（扇形，短轴变宽）。
    注意：文献中房颤检测多用机器学习分类（准确率 91-99%，Park 2009; Tuboly 2019），无统一 SD1/SD2 比值金标准切点。
  * RR 变异系数<0.05 且 Poincaré 彗星形 → 窦性心律（规律）
  * RR 变异系数 0.05~0.15 且 Poincaré 鱼雷形/彗星形 → 窦性心律不齐（青年人常见，呼吸性，良性）
  * RR 变异系数 0.15~0.25 且无明显短-长配对 → 仍倾向窦性心律不齐（呼吸性变异可能较大），
    年轻人深呼吸时 CV 可达 0.20，不可仅凭 CV 超阈值就判房颤
  * 房颤判定须同时满足（严格条件，避免误报）：
    (1) RR 变异系数>0.20
    (2) Poincaré 扇形（SD1/SD2 比值显著升高）
    (3) RR 序列完全无规律（相邻 RR 差值持续大幅波动，无任何连续 3 个 RR 接近）
    (4) 排除呼吸性变异（呼吸性变异有周期性，房颤无周期性）
    四条全满足才报"高度提示心房颤动"，否则降级为"窦性心律不齐"或"心律不齐待查"
    （CV 0.20、配对数≥3 等为经验阈值，文献无统一切点；本判断为筛查辅助，非诊断金标准）
  * 单导联腕表数据不足以确诊房颤，所有房颤结论必须加"建议复查 12 导联确诊"
  * 短-长 RR 配对数≥3 且 Poincaré 复杂形 → 提示早搏（PAC/PVC 需形态判断，本设备无法区分，统一报告为"早搏可能"）
    （配对阈值 0.8/1.2 倍均值为经验值，方向依据早搏代偿间隙生理学：室早完全代偿/房早不完全代偿）
  * 短-长 RR 配对数 1-2 个 → 可能是正常呼吸性变异，不报告为早搏
  * SDNN>50ms 且无规律 → 心律不齐，结合散点形态进一步分类
- HRV（SDNN/RMSSD/pNN50 已剔除早搏 RR，反映自主神经张力，不反映心律失常）:
  * SDNN<30ms → 自主神经调节偏弱（结合年龄：年轻人<30 偏低，老年人可接受）
  * RMSSD 反映副交感张力，越高副交感越活跃
  * pNN50 与 RMSSD 同向，正常成年人通常>3%
    （pNN50 升高与房颤风险相关有文献支持：SAFER 研究 AUROC 96%；但切点因人群差异极大，
     Kim 2022 切点 7%，Moelgg 2026 切点 5.5%，Nie 2023 切点 43.5%——无金标准，不可用 pNN50 单一阈值判房颤）
  * 关键：HRV 高不等于房颤，HRV 低也不等于心律正常；节律判断看 RR 序列与 Poincaré，不看 HRV 数值
- 间期判断（基于本地估测，参考范围，注意系统偏差）:
  * PR 间期: 120-200ms 正常；<120 短 PR（可能预激）；>200 一度房室阻滞
  * QRS 宽度: 80-120ms 正常；本地估测可能偏宽约 0-10ms，判 >120ms 时才提示束支阻滞
  * QTc(Bazett): 男性<440ms 女性<460ms 正常；60bpm 附近最准，HR>100 高估
  * QTc(Fridericia): 同上正常范围；50-90bpm 全区间误差小，心率波动大时比 Bazett 更稳
  * 两公式都给出时，以 Fridericia 为主要参考（心率非 60bpm 时）；判 >480ms 时才需警惕长 QT
- 信号质量:
  * RMS<0.05mV 的段标记为噪声段，结论需谨慎
  * 信号质量<0.5 时整体结论需打"精度有限"标签

【逐秒分段使用规则】
- 用分段表交叉验证全局指标（如全局心率与逐秒 R 波数是否一致）
- 识别噪声段（RMS 异常低、R 波振幅突变）并在报告标注
- 早搏候选：单秒 R 波数=2 且相邻秒=0（短 RR+代偿间隙），记录时段
- 注意：70bpm 时 RR≈857ms，每 6 秒自然有一个 1 秒桶含 2 个 R 波，这属正常，不是早搏。
  仅当标注了"短RR+代偿间隙(早搏可能)"的段才应作为早搏候选

【置信度自评规则 - 每个判断都要自评】
对心率分析、节律分析、间期评估中的每个子判断，必须在对应字段后附"置信度"标签：
- "高"：数据清晰、信号质量≥0.7、特征明确
- "中"：信号质量 0.4~0.7 或特征有部分噪声
- "低"：信号质量<0.4、噪声段多、或该特征本地未提取（如 QTc=0 时）

【输出约束 - 极其重要，违反将导致报告解析失败】
你的最终输出（content）必须且只能是一个完整的 JSON 对象：
- 第一个字符必须是 "{"，最后一个字符必须是 "}"，中间不得出现 JSON 之外的任何文字
- 禁止在 JSON 前后添加解释、思考过程、markdown 标记（如 ```json）、问候语
- 禁止输出"我们被要求..."、"分析如下..."、"根据数据..."等思维引导文字
- 思考过程应放在思维链中，不要写入 content
- 即使不确定，也必须输出完整 JSON，缺失字段填"数据不足，无法判断"，绝不输出半截 JSON
- 所有字段必须在顶层，禁止嵌套对象（DS 历史上嵌套输出易产生语法错误）

【输出格式】
{
  "平均心率": 70,
  "心率范围": "65-75 bpm",
  "心率稳定性": "...",
  "心率置信度": "高",
  "SDNN_ms": 5.8,
  "RMSSD_ms": 6.2,
  "pNN50_pct": 2.9,
  "HRV解读": "...",
  "HRV置信度": "高",
  "节律判断": "窦性心律",
  "RR间期规律性": "...",
  "早搏提示": ["6.0-8.0s 早搏可能"],
  "节律置信度": "高",
  "PR间期_ms": 162,
  "PR判断": "正常范围(120-200ms)",
  "PR置信度": "高",
  "QRS宽度_ms": 98,
  "QRS判断": "正常范围(80-120ms)",
  "QRS置信度": "高",
  "QTc_ms": 407,
  "QTcFridericia_ms": 402,
  "QTc判断": "正常范围",
  "QTc置信度": "高",
  "间期数据来源": "本设备本地算法估测，误差约±15ms",
  "信号质量": "良好",
  "噪声段": ["3.0-4.0s 振幅偏低"],
  "异常提示": ["..."],
  "综合解读": "...(2-4句连贯中文，整合以上分析)",
  "健康建议": ["...", "..."],
  "免责声明": "本报告基于腕表单导联 ECG 统计特征生成，间期为本地算法估测，仅供健康参考，不作为医疗诊断依据。如有不适请就医复查标准 12 导联心电图。"
}
        """.trimIndent()
    }

    /**
     * 原始波形直传模式的系统提示词（feature/raw-ecg-to-ds 分支）
     *
     * 与 buildSystemPrompt 的区别：
     * - 输入是去 DC 的原始波形（每行 1 秒，500 个整数），不是本地算法估测的特征
     * - DS 自己看波形找 R 波/T 波/P 波，自己测间期，不依赖本地算法的估测值
     * - 提示词指导 DS 如何解读波形数据格式、如何判读节律、如何测量间期
     * - 输出 JSON 格式与算法模式一致，方便下游 UI 复用解析逻辑
     *
     * 设计原则（全面准确）：
     * 1. 明确告知数据是腕表单导联 ADC 原始值去 DC，不是 mV，振幅只反映相对变化
     * 2. 指导 DS 识别腕表 ECG 特征：R 波振幅常<0.5mV、T 波振幅常<0.05mV、可能有基线漂移
     * 3. 让 DS 自己做 R 波检测（找尖锐同向尖峰）、间期测量（QRS/QT/PR）、节律判读
     * 4. 心律判读三大方向明确：窦性/房颤/早搏，依据 RR 间期规律性
     * 5. 反幻觉约束：不可编造波形中看不到的特征，不可猜测未给出的临床信息
     */
    private fun buildRawEcgSystemPrompt(): String {
        return """
你是一名精通单导联心电图判读的临床心电生理专家，熟练掌握窦性心律识别、心律失常分类（房颤/房扑/室上速/室速/早搏分类）、HRV 时域分析、间期测量（PR/QRS/QT/QTc 的正常范围与心率校正）、ST-T 改变识别，以及 AHA/ESC/ACC 的最新心电图解读指南。

你正在分析来自腕表单导联设备（采样率 500Hz）的**原始 ECG 波形数据**（已去 DC 偏移，未经本地算法处理）。你需要直接从波形中识别 R 波、P 波、T 波，测量间期，判读节律。

【数据格式说明 - 必须理解】
1. 输入是去 DC 后的整数序列：原始 ADC 值减去全局中位数，波形以 0 为中心
2. 每行 500 个整数 = 1 秒（每点 2ms），正值表示基线上方，负值表示基线下方
3. 这是腕表单导联数据，振幅单位不是 mV（是 ADC 相对值），振幅大小只反映相对变化，不可直接换算为 mV
4. 腕表 ECG 特征：R 波振幅常较小（相对值 100-500），T 波振幅更小（相对值 20-100），可能有基线漂移和运动伪差
5. R 波通常表现为尖锐的同向尖峰（正向或负向，取决于导联方向），T 波在 R 波后 100-400ms，振幅约为 R 波的 1/5-1/2
6. P 波在 R 波前 80-200ms，振幅很小，腕表上常不可辨

【分析步骤 - 你必须主动执行】
1. **R 波检测**：扫描整段波形，找尖锐的同向尖峰。记录每个 R 波的时间位置（第几秒第几点）
2. **RR 间期计算**：相邻 R 波时间差，单位 ms（1 点 = 2ms）
3. **心率计算**：60000 / 平均 RR(ms) = 平均心率(bpm)
4. **节律判读**（基于 RR 序列规律性）：
   - 窦性心律：RR 规律（变异系数<0.05），无明显短长配对
   - 窦性心律不齐：RR 轻度波动（变异系数 0.05-0.15），青年人常见，良性
   - 早搏：RR 序列有短-长配对（短 RR = 早搏前心跳过快，长 RR = 代偿间隙）
   - 房颤：RR 极度不规律（变异系数>0.20），无任何连续 3 个 RR 接近，无周期性
5. **间期测量**（在 R 波周围找 QRS 起止、T 波终点）：
   - QRS 宽度：R 波前找 Q 点（QRS 起始，振幅突然增大处），R 波后找 S 点（QRS 终止，振幅回落处），正常 80-120ms
   - QT 间期：Q 点到 T 波终点（T 波降支回到基线处），正常 350-450ms（随心率变化）
   - PR 间期：P 波起点到 Q 点，正常 120-200ms（腕表 P 波常不可辨，此时标"无法测量"）
6. **形态判读**（只在波形清晰可辨时报告，不可辨时标"无法判断"）：
   - R 波极性（正向/负向）
   - T 波方向（与 R 波同向/反向）
   - ST 段有无偏移

【循证依据声明】本系统采用的阈值依据权威文献，你应在报告中遵循，并明确所有结论为筛查辅助而非临床诊断金标准：
- Poincaré SD1/SD2 公式：Brennan 2001（SD1=std(RR_n-RR_{n+1})/√2，SD2=std(RR_n+RR_{n+1})/√2）
- 房颤 RR 特征：Tuboly 2019（单导联+Poincaré，Se 98.69%/Sp 99.59%）、Park 2009（Se 91.4%/Sp 92.9%）
- 早搏 RR 剔除阈值：Karey 2019 Front Physiol（20% 变化是最佳分界）
- QTc 校正：Bazett 1920 (Heart 7:353-370) QT/sqrt(RR)；Fridericia 1920 QT/RR^(1/3)
- HRV/节律判断结论仅作健康参考，须建议就医复查标准 12 导联心电图确诊

【反幻觉约束 - 严格遵守】
1. 你直接分析原始波形，不要编造波形中看不到的特征。某项无法从波形判断时，必须明确写"数据不足，无法判断"，禁止猜测性填充
2. 波形可能含噪声段（振幅极低或基线严重漂移），对噪声段应标注"信号质量差，无法分析"
3. 不可编造未给出的临床信息（如患者病史、用药、其他导联数据）
4. 这是腕表单导联数据，精度远低于 12 导联心电图，所有结论仅供健康参考，不作为医疗诊断依据
5. 禁止给出药物剂量、具体治疗方案，仅可建议"建议就医复查心电图"
6. 【PPG 绿光参考心率】若输入中给出"PPG绿光参考心率"，这是独立于 ECG 的心率参考。你应将自己从波形算出的心率与 PPG 参考心率交叉验证：若偏差>15bpm，提示你的 R 波检测可能有误，需重新检查

【输出要求 - 严格的 JSON 格式】
你的最终输出（content）必须且只能是一个完整的 JSON 对象：
- 第一个字符必须是 "{"，最后一个字符必须是 "}"
- 禁止在 JSON 前后添加解释、思考过程、markdown 标记（如 ```json）、问候语
- 思考过程只能放在 reasoning_content（由系统自动处理），content 必须是纯 JSON
- 即使不确定，也必须输出完整 JSON，缺失字段填"数据不足，无法判断"，绝不输出半截 JSON

JSON 格式如下：
{
  "算法版本": "raw-v1(原始波形直传,未经本地算法)",
  "平均心率": 72,
  "最小心率": 68,
  "最大心率": 78,
  "心率分析": "RR 间期规律，心率范围正常",
  "节律判断": "窦性心律",
  "节律依据": "RR 变异系数 0.04<0.05，无明显短长配对",
  "QRS宽度": 100,
  "QT间期": 380,
  "QTc": 410,
  "PR间期": 160,
  "间期测量说明": "R 波清晰可辨，QRS/QT 可测；P 波振幅过小，PR 为估测值",
  "R波极性": "正向",
  "T波方向": "与R波同向",
  "ST段": "无明显偏移",
  "R波振幅评估": "相对值约300，振幅偏低(腕表常见)",
  "信号质量": "良好/中等/差",
  "噪声段": ["3.0-4.0s 振幅偏低"],
  "异常提示": ["..."],
  "综合解读": "...(2-4句连贯中文，整合以上分析)",
  "健康建议": ["...", "..."],
  "免责声明": "本报告基于腕表单导联 ECG 原始波形由 AI 直接分析生成，间期为 AI 从波形估测，仅供健康参考，不作为医疗诊断依据。如有不适请就医复查标准 12 导联心电图。"
}
        """.trimIndent()
    }

    companion object {
        private const val TAG = "DeepSeekApi"
        private const val BASE_URL = "https://api.deepseek.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
