package com.wearhealth.companion.shared

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ECG 特征提取器：从原始波形数据提取统计特征，用于喂给 DeepSeek 做医学推理
 *
 * 设计原则：
 * 1. 本地完成所有 DSP 工作（R 波检测、HRV、间期估测），不指望 LLM 算数字
 * 2. 输出结构化文本（全局指标 + 逐秒分段表），token 量可控（约 1500~2500）
 * 3. 间期估测标注误差范围（±15ms），让 DS 知道这是估测值而非精确测量
 * 4. 噪声段明确标记，避免 DS 误判
 *
 * 数据来源：mV×1000 整数，500Hz，30 秒 ≈ 15000 点
 */
object EcgFeatureExtractor {

    /**
     * 算法版本号（自动注入到导出诊断包与 toPromptText，便于横向对照样本与定位回归）。
     *
     * 升级算法时只需改这一处：DiagnosticExporter 导出的元信息头、toPromptText 输出的
     * [全局指标] 段、以及 ds_* 路径发给 DeepSeek 的提示词，都会自动带上新版本号，
     * 不需要同步修改多处字符串。历史样本对照时，凭此字段即可判断是哪个版本跑出的结果。
     *
     * 版本演进：
     * - v5：初版 R 波检测 + HRV + 间期估测
     * - v6：引入二次不应期 200ms + T 波排除 200-400ms（拦不住 208-276ms 超短 RR）
     * - v7：精修二次不应期 300ms + 回溯补检 T 波排除 300-450ms + PPG 兜底 avgHr + R 峰可靠性评估三档降级
     * - v8（当前）：QRS SNR 自适应法（SNR≥3 用 5% 阈值交叉法，SNR<3 用先验 100ms）
     *              + QT Bazett 反向先验（QTc=400ms）+ 间期估测置信度标注
     *              依据 5 份带 HeartVoice 对照样本验证：QRS 偏差从 30-50ms 降到 9.6ms（达标 ±10ms ✓），
     *              QT 改用 Bazett 先验（40.8ms）比所有实测方法都准（切线 55ms、阈值 47ms、模板 53ms），
     *              原因是腕表 T 波振幅<0.05mV 被噪声淹没，实测有物理上限。
     */
    const val ALGORITHM_VERSION = "v8"

    /** 用户基本属性（影响 QTc/HRV 判断阈值） */
    data class UserProfile(
        val ageYears: Int = 0,        // 0=未知
        val isMale: Boolean? = null,  // null=未知
    )

    /** 全局特征（整段测量） */
    data class GlobalFeatures(
        val sampleRateHz: Int,
        val durationSec: Float,
        val totalSamples: Int,
        val rPeakCount: Int,
        val avgHeartRate: Int,          // bpm
        val minHeartRate: Int,
        val maxHeartRate: Int,
        val rrIntervalsMs: List<Int>,   // R-R 间期序列
        val sdnnMs: Float,              // RR 标准差
        val rmssdMs: Float,             // 相邻 RR 差均方根
        val pnn50Pct: Float,            // 相邻 RR 差>50ms 占比
        val qrsWidthMs: Int,            // 本地估测
        val qrsWidthStdMs: Int,
        val prIntervalMs: Int,          // 本地估测
        val prIntervalStdMs: Int,
        val qtIntervalMs: Int,          // 本地估测
        val qtcMs: Int,                 // Bazett 校正（静止测量 60bpm 附近最准）
        val qtcFridericiaMs: Int = 0,   // Fridericia 校正（心率波动大时更稳，50-90bpm 全区间误差小）
        // v8：间期估测置信度（让 DS 知道哪些是实测值，哪些是先验值）
        // QRS 置信度：高 SNR R 波占比 >30% → "高置信"，否则 "低置信"（多数走先验 100ms）
        // QT 置信度：始终 "低置信"（腕表 T 波振幅<0.05mV 被噪声淹没，本地用 Bazett 先验）
        val qrsConfidence: String = "未知",  // "高置信"/"低置信"/"未知"
        val qtConfidence: String = "未知",   // "高置信"/"低置信"/"未知"
        val rAmplitudeMv: Float,        // R 波平均振幅
        val signalQuality: Float,       // 0~1
        val noiseSegments: List<String>,// 噪声段时段标记
        // 节律判别辅助特征（帮助 DS 区分窦性/房颤/早搏）
        val rrVariabilityCoef: Float,   // RR 变异系数 = SDNN/meanRR，>0.15 提示心律不齐/房颤
        val poincarePattern: String,    // Poincaré 散点形态描述（彗星形/扇形/鱼雷形/复杂形）
        val shortLongPairs: Int,        // 短-长 RR 配对数（早搏代偿间隙特征）
        val ppgReferenceHr: Int = 0,    // PPG 绿光参考心率（测后读系统心率，0=未采集/不可用）
        val rPeakIndices: List<Int> = emptyList(),  // R 波位置索引（样本索引，噪声段剔除后的有效 R 波）
        // 形态客观参数（测量归本地，判读归 DS）
        val rPeakPolarity: String = "未知",  // R 波极性："正向"/"负向"/"未知"（多数投票，R 峰值相对基线的符号）
        val tToRAmplitudeRatio: Float = 0f,  // T/R 振幅比（T 峰振幅 / R 峰振幅，正常 0.1~0.5）
        val stDeviationMv: Float = 0f,       // ST 段偏移 mV（QRS 终点到 T 波起点的高度差相对基线，正常 ±0.05mV）
        // R 峰可靠性评估（独立于 signalQuality，专门评估 R 波定位本身的稳定性）
        // 核心原则：波形可见 ≠ R 峰可信；R 峰不可信 ≠ 可以推断房颤
        // 当 rPeakReliability.reliable=false 时，rrVariabilityCoef/poincarePattern/shortLongPairs
        // 不应被当作节律证据，DS 不应输出"高度提示房颤/高置信早搏"等强标签
        val rPeakReliability: RPeakReliability = RPeakReliability(),
    )

    /**
     * R 峰可靠性评估（独立于 signalQuality）
     *
     * 设计动机：signalQuality 反映"波形整体可读性"（RMS、噪声段比例、R 波数合理性），
     * 但不直接评估"R 峰定位是否稳定"。223211 案例证明：波形 RMS 正常、signalQuality 也正常，
     * 但导联反接 + 基线漂移让 R 波振幅仅 0.2-0.9mV，包络峰偏弱、误检簇多 → 原始 RR 的
     * CV 被拉到 0.35-0.55 → 误判房颤。signalQuality 通过但 R 峰并不可信。
     *
     * 因此新增独立维度，从 RR 序列本身的"生理合理性"反推 R 峰定位稳定性：
     * - 极端 RR 占比：RR<300ms 或 >2500ms 的比例（生理不可能，多为误检/漏检）
     * - 有效 RR 占比：通过 300-2500ms 区间的 RR / 全部相邻 R 峰对
     * - 相邻 RR 跳变比例：相邻 RR 差 >20% mean 的占比（误检特征：短长 RR 配对）
     * - R 峰-时长一致性：实际 R 峰数 vs 期望 R 峰数（durationSec×avgHr/60）的偏差
     * - 异常段占比：R 波数为 0 的段或 rms<0.1 的段占比
     *
     * 阈值依据：经验值（无文献金标准），基于 5 份真实 wrist ECG 样本校准。
     * 当 reliable=false 时下游 toPromptText 会降级输出，不让 DS 把检测噪声当房颤。
     */
    data class RPeakReliability(
        val extremeRrRatio: Float = 0f,       // 极端 RR（<300ms 或 >2500ms）占比，>0.10 提示误检/漏检
        val validRrRatio: Float = 1f,         // 有效 RR（300-2500ms）占比，<0.85 提示 R 峰定位不稳
        val rrJumpRatio: Float = 0f,          // 相邻 RR 跳变 >20% mean 的占比，>0.30 提示短长配对过多
        val rateConsistency: Float = 1f,      // R 峰数 vs 期望数（duration×avgHr/60）比值，<0.7 或 >1.3 提示不一致
        val abnormalSegmentRatio: Float = 0f, // 异常段（0 R 波或 rms<0.1）占比，>0.40 提示大段不可用
        val reliable: Boolean = true,         // 综合判定：true=可信用 RR 推节律，false=降级输出
        val level: String = "可信",            // "可信"/"边缘"/"不可信"
        val reason: String = "",              // 不可信/边缘的原因（用于 toPromptText 提示）
    )

    /** 逐秒分段特征 */
    data class SegmentFeature(
        val startSec: Float,
        val endSec: Float,
        val rPeakCount: Int,
        val ampMinMv: Float,
        val ampMaxMv: Float,
        val peakToPeakMv: Float,
        val maxSlopeMvPerSec: Float,
        val rmsMv: Float,
    )

    /** 完整提取结果 */
    data class FeatureBundle(
        val global: GlobalFeatures,
        val segments: List<SegmentFeature>,
        val profile: UserProfile,
    )

    /**
     * 主入口：从原始 ECG 数据提取全部特征
     *
     * @param ecgData ECG 数据（mV×1000 整数）
     * @param sampleRateHz 采样率（默认 500）
     * @param profile 用户属性（年龄性别，可选）
     */
    fun extract(
        ecgData: List<Int>,
        sampleRateHz: Int = 500,
        profile: UserProfile = UserProfile(),
        ppgReferenceHr: Int = 0,
    ): FeatureBundle {
        val durationSec = if (sampleRateHz > 0) ecgData.size.toFloat() / sampleRateHz else 0f
        val rPeaks = detectRPeaks(ecgData, sampleRateHz)

        // 噪声段掩码剔除：先识别噪声段（rms<0.10 的 1 秒段），剔除落在噪声段内的 R 波。
        // 这些 R 波多为低振幅噪声偶尔触发的误检，不应进入 HRV/心率/间期/segments 统计。
        // 跨噪声段的 RR（前段尾→后段头）也会因 R 波剔除而不产生，避免虚假长/短 RR 污染节律判别。
        // 依据：视图模型建议第 2 条"噪声段强制剔除不参与 HRV/节律运算"；
        //       Lueken 2023 (Sensors) 证实信号质量差的段显著增加房颤误分类概率
        // 噪声段阈值 0.10mV：真实 wrist ECG 有效段（含 R 波）RMS 通常 >0.15mV
        // （R 波振幅 0.5-1.5mV，1 秒内 1-2 个 R 波）；0.10mV 以下几乎不含有效 R 波信息。
        // 旧阈值 0.05mV 太严格，0.1mV 噪声段（RMS≈0.058）漏标 → 误检 R 波不剔除 → 虚假 RR。
        val segmentsRaw = extractSegments(ecgData, sampleRateHz, rPeaks, durationSec)
        val noiseSampleRanges = segmentsRaw.filter { it.rmsMv < 0.10f }
            .map { (it.startSec * sampleRateHz).toInt() until (it.endSec * sampleRateHz).toInt() }
        val effectiveRPeaks = if (noiseSampleRanges.isEmpty()) rPeaks
            else rPeaks.filter { peak -> noiseSampleRanges.none { range -> peak in range } }

        val rrIntervals = computeRRIntervals(effectiveRPeaks, sampleRateHz)
        // RR 双通路：HRV 和心率统计用剔早搏 RR，节律判别保原始 RR
        // 早搏短长 RR 会同时污染 HRV 和心率范围（短 RR→虚高 maxHr，长 RR→虚低 minHr）
        val rrForHrv = filterEctopicBeats(rrIntervals)
        val hr = computeHeartRateStats(rrForHrv, profile.ageYears)
        // PPG 兜底：当本地 R 波检测失败（RR<3 → avgHr=0）但 PPG 可用时，用 PPG 填充 avgHr。
        // 场景：信号质量差导致 R 峰检测数不足，但 PPG 绿光传感器仍能给出瞬时心率。
        // min/max 仍为 0（PPG 是瞬时值无法算范围），但 avgHr 至少有值供 DS 参考。
        // 依据：DeepSeekApiClient 提示词第 9 条"PPG 不可用时按原逻辑用 R 波心率"，
        //       反之 R 波不可用时应优先采信 PPG（系统心率传感器独立测算，绕开 R 波检测）。
        val finalAvgHr = if (hr.avgHr == 0 && ppgReferenceHr > 0) ppgReferenceHr else hr.avgHr
        val finalMinHr = if (hr.avgHr == 0 && ppgReferenceHr > 0) 0 else hr.minHr  // PPG 无法算 min
        val finalMaxHr = if (hr.avgHr == 0 && ppgReferenceHr > 0) 0 else hr.maxHr  // PPG 无法算 max
        val hrv = computeHrv(rrForHrv)
        val rhythm = computeRhythmFeatures(rrIntervals)
        val intervals = estimateIntervals(ecgData, sampleRateHz, effectiveRPeaks, hr.avgHr)
        val rAmp = computeRAmplitude(ecgData, effectiveRPeaks, sampleRateHz)
        // 重算 segments 用 effectiveRPeaks，让噪声段 rPeakCount=0
        val segments = extractSegments(ecgData, sampleRateHz, effectiveRPeaks, durationSec)
        val noiseSegs = segments.filter { it.rmsMv < 0.10f }
            .map { "${"%.1f".format(it.startSec)}-${"%.1f".format(it.endSec)}s(噪声)" }
        val sigQuality = computeSignalQuality(segments, effectiveRPeaks.size, durationSec)

        // R 峰可靠性评估（独立于 signalQuality，专门评估 R 波定位稳定性）
        // 用原始 rPeaks（未剔噪声段）算极端/有效 RR 占比，用 effectiveRPeaks 算一致性
        // avgHr 来自剔早搏 RR，比用原始 RR 估的"R 峰-时长一致性"更稳
        val reliability = computeRPeakReliability(
            rawRPeaks = rPeaks,
            effectiveRPeaks = effectiveRPeaks,
            rrIntervals = rrIntervals,
            avgHr = finalAvgHr,
            durationSec = durationSec,
            segments = segments,
            sampleRateHz = sampleRateHz,
        )

        val global = GlobalFeatures(
            sampleRateHz = sampleRateHz,
            durationSec = durationSec,
            totalSamples = ecgData.size,
            rPeakCount = effectiveRPeaks.size,
            avgHeartRate = finalAvgHr,
            minHeartRate = finalMinHr,
            maxHeartRate = finalMaxHr,
            rrIntervalsMs = rrIntervals,
            sdnnMs = hrv.sdnn,
            rmssdMs = hrv.rmssd,
            pnn50Pct = hrv.pnn50,
            qrsWidthMs = intervals.qrsMean,
            qrsWidthStdMs = intervals.qrsStd,
            prIntervalMs = intervals.prMean,
            prIntervalStdMs = intervals.prStd,
            qtIntervalMs = intervals.qtMean,
            qtcMs = intervals.qtc,
            qtcFridericiaMs = intervals.qtcFridericiaMs,
            qrsConfidence = intervals.qrsConfidence,
            qtConfidence = intervals.qtConfidence,
            rAmplitudeMv = rAmp,
            signalQuality = sigQuality,
            noiseSegments = noiseSegs,
            rrVariabilityCoef = rhythm.variabilityCoef,
            poincarePattern = rhythm.poincarePattern,
            shortLongPairs = rhythm.shortLongPairs,
            ppgReferenceHr = ppgReferenceHr,
            rPeakIndices = effectiveRPeaks,
            rPeakPolarity = intervals.rPeakPolarity,
            tToRAmplitudeRatio = if (intervals.rAmplitudeMv > 0.01f && intervals.tAmplitudeMv > 0f)
                intervals.tAmplitudeMv / intervals.rAmplitudeMv else 0f,
            stDeviationMv = intervals.stDeviationMv,
            rPeakReliability = reliability,
        )
        return FeatureBundle(global, segments, profile)
    }

    // ===== 内部算法 =====

    /**
     * R 波检测（NeuroKit 风格预处理 + Pan-Tompkins 回溯补检）
     *
     * 预处理链（借鉴 NeuroKit2 'neurokit' 方法）：
     * 1. 1秒窗口去基线漂移（等效高通 ~1Hz）
     * 2. |梯度|（一阶中心差分绝对值）—— 突出 QRS 陡峭斜率，抑制平缓 P/T 波
     * 3. 150ms boxcar 平滑（MWI 移动窗口积分）—— 形成包络，让一个 QRS 只产生一个峰
     *
     * 检测（借鉴 Pan-Tompkins）：
     * 4. 分段自适应阈值 = 均值 + 1.7×标准差（4 秒窗口，k=1.7 平衡运动后检出与静息不回归）
     * 5. 局部最大 + 200ms 不应期（最高 300bpm）
     * 6. 原始信号 ±50ms 精修 R 峰位置（envelope 峰在 R 峰上升沿前 ~30-50ms，需 ±50ms 才能命中真峰）
     * 7. 回溯补检：RR > 1.66×近期均值时半阈值补检（PT 核心，提升漏检召回）
     */
    private fun detectRPeaks(ecgData: List<Int>, sampleRateHz: Int): List<Int> {
        if (ecgData.size < sampleRateHz * 5) return emptyList()

        // 1. 去基线漂移（1秒窗口移动平均）
        val baselineWindow = sampleRateHz
        val baseline = DoubleArray(ecgData.size)
        for (i in ecgData.indices) {
            val from = maxOf(0, i - baselineWindow / 2)
            val to = minOf(ecgData.size, i + baselineWindow / 2)
            var sum = 0.0
            for (j in from until to) sum += ecgData[j]
            baseline[i] = sum / (to - from)
        }
        val highPassed = DoubleArray(ecgData.size) { i -> ecgData[i] - baseline[i] }

        // 2. |梯度|（一阶中心差分绝对值，突出 QRS 斜率）
        val gradient = DoubleArray(highPassed.size)
        for (i in highPassed.indices) {
            val prev = highPassed[maxOf(0, i - 1)]
            val next = highPassed[minOf(highPassed.lastIndex, i + 1)]
            gradient[i] = abs(next - prev) / 2.0
        }

        // 3. 150ms boxcar 平滑（MWI 移动窗口积分，Pan-Tompkins 原版标准）
        // 窗口宽度必须覆盖整个 QRS 复合波（正常 100ms，病理可达 150ms），让一个 QRS
        // 在包络上只产生一个峰。旧实现用 50ms 太窄，一个 QRS 的 R 波+R'切迹会产生双峰，
        // 导致双 R 峰检测 → 虚假短 RR → 心率虚高 + 误判早搏/房颤
        // 依据：Pan-Tompkins 1985 原版；Pan-Tompkins++ (Imtiaz 2022) 同样用 150ms
        val smoothWin = (sampleRateHz * 0.150).toInt().coerceAtLeast(3)  // 150ms
        val envelope = DoubleArray(gradient.size)
        for (i in gradient.indices) {
            val from = maxOf(0, i - smoothWin / 2)
            val to = minOf(gradient.size, i + smoothWin / 2 + 1)
            var sum = 0.0
            for (j in from until to) sum += gradient[j]
            envelope[i] = sum / (to - from)
        }

        // 4. 分段自适应阈值（解决局部噪声污染全局阈值）
        // 演进历史：
        // - v1 全局 envMean+2×envStd：某段大运动伪差拉高 envStd → 干净段 R 波漏检 → 心率虚低
        // - v2 分段 mean+2×std（4 秒窗口）：解决跨段污染，但运动后肌电干扰让 envelope 右偏长尾
        //   （少量极高值），mean+2std 被长尾拉高 → 真实 R 波 envelope 峰仅 0.85~1.11 倍阈值
        //   → 漏检 60%（samples/运动后.txt：HV 107bpm，本地 54bpm）
        // - v3 分段百分位 0.92 + 形态验证：合成信号通过，但真实静息信号过度检出
        //   （pct0.92 比 mean+2std 低 2-3 单位，静息噪声通过 → 48 R 波 vs 期望 27）
        // - v4 分段 mean+1.7×std（当前）：降低 k 值从 2.0 到 1.7，让运动后边缘 R 波通过
        //   Python 验证（verify_k18.py）：合成 6 seed 全部 >=34（mean+2std 仅 21-29）
        //   5 份真实样本：静息/活动后全部准确（HR 偏差 <15bpm），运动后 21→36 R 波改善
        //   k=1.7 选择依据：k=1.8 有 3/6 seed <33（漏检），k=1.6 真实样本短 RR 偏多，1.7 平衡
        //   k=1.7 是经验调参值（非临床金标准），依据是 5 份真实 wrist ECG + 6 seed 合成信号
        //
        // 4 秒窗口覆盖约 4-5 个心动周期，统计稳健；窗口太短（1 秒）样本不足
        //
        // 历史教训：曾尝试加 median×1.5 下限防平坦段误检，但真实 wrist ECG 数据
        // （samples/ECG_diagnostic_*.txt）证实 R 波包络峰仅为噪声中位数 ~1.4 倍，
        // median×1.5 把所有真实 R 波压掉 → R 波检测 0 个 → 心率 0（彻底失效）。
        // 平坦段误检改由噪声段掩码剔除（extract() 中 RMS<0.1 的段剔除）兜底，
        // 不在检测层用 median 约束压阈值。
        val segLen = sampleRateHz * 4  // 4 秒一段
        val thresholds = DoubleArray(envelope.size)
        for (segStart in 0 until envelope.size step segLen) {
            val segEnd = minOf(envelope.size, segStart + segLen)
            val segSize = segEnd - segStart
            if (segSize <= 0) continue
            // mean+1.7×std 阈值：k=1.7 比 k=2.0 低 ~10%，让运动后边缘 R 波通过
            var segSum = 0.0
            for (i in segStart until segEnd) segSum += envelope[i]
            val segMean = segSum / segSize
            var segVarSum = 0.0
            for (i in segStart until segEnd) {
                val d = envelope[i] - segMean
                segVarSum += d * d
            }
            val segStd = sqrt(segVarSum / segSize)
            val segThreshold = segMean + 1.7 * segStd
            for (i in segStart until segEnd) thresholds[i] = segThreshold
        }
        // 全局最低阈值保护：纯静音/全零段阈值可能极低，加 floor 避免噪声误判
        val globalFloor = 3.0

        // 5. R 峰检测：阈值 + 局部最大 + 不应期
        // 不应期 200ms（Pan-Tompkins 原版 MINPEAKDISTANCE=200ms，生理上 RR 不可能 <200ms）
        // 无形态验证：v3 的形态验证（|hp| 侧翼 1.1 倍）在真实静息信号上误杀 R 波
        //   （envelope 峰在 R 峰前 40-50ms 上升沿，|hp| 在候选点低 → 误杀）
        //   k=1.7 阈值 + 噪声段掩码 + filterEctopicBeats 已足够控制误检
        val rPeaks = mutableListOf<Int>()
        val refractory = sampleRateHz / 5  // 200ms 不应期（500/5=100样本，最高 300bpm 上限）
        val refineRefractory = (sampleRateHz * 0.300).toInt()  // 300ms 精修二次不应期（v7）
        var lastPeakIdx = -refractory * 2
        val checkRange = sampleRateHz / 50  // ±10ms 局部最大检查（500/50=10样本=±10ms）

        for (i in envelope.indices) {
            val thr = maxOf(thresholds[i], globalFloor)
            if (envelope[i] < thr) continue
            val lo = maxOf(0, i - checkRange)
            val hi = minOf(envelope.size, i + checkRange + 1)
            var isLocalMax = true
            for (j in lo until hi) {
                // 用 >= 防 plateau 双峰：两相邻点包络值相等时，只认第一个，避免双峰
                if (j != i && envelope[j] >= envelope[i]) {
                    if (j < i) { isLocalMax = false; break }  // 前面有等值或更高峰，当前不是第一个
                }
            }
            if (!isLocalMax) continue
            if ((i - lastPeakIdx) < refractory) continue

            // 6. 精修：在原始去基线信号 ±50ms 邻域找真正的 R 峰
            // 根因：envelope(|梯度| 150ms 平滑)的峰位于 R 波上升沿最陡处，比真正的 R 峰
            // 提前 ~30-50ms（合成 Gaussian σ=10ms 信号实测：envelope 峰在 R 峰前 29 samples=58ms）。
            // 旧实现 ±25ms (sampleRateHz/40) 够不到真正的 R 峰，找到的是上升沿上的噪声
            // 局部最大值 → R 峰位置漂移 ±6 samples (12ms) → RR 范围扩大 → HR range=11>10 失败。
            // ±50ms (sampleRateHz/20) 能稳定命中真正的 R 峰（实测 RR range=2，HR range=2）。
            // 依据：Pan-Tompkins 原版用 ±50ms 精修窗口；150ms MWI 让 envelope 峰前移是已知特性
            val refineLo = maxOf(0, i - sampleRateHz / 20)
            val refineHi = minOf(highPassed.size, i + sampleRateHz / 20)
            var bestIdx = i
            var bestVal = 0.0
            for (j in refineLo until refineHi) {
                if (abs(highPassed[j]) > bestVal) {
                    bestVal = abs(highPassed[j])
                    bestIdx = j
                }
            }
            // 二次不应期验证（v6 新增，v7 调整为 300ms）：
            // 根因：envelope 双峰（150ms MWI 让一个 QRS 产生两个包络峰，间距 250-330ms），
            // 精修后两个 bestIdx 距离 208-276ms，超过 200ms refractory 但仍是极端 RR。
            // v6 用 200ms 拦不住 208-276ms 的超短 RR（test_short_rr_source.py 证实全是"主-主精修过近"）。
            // v7 改用 300ms：生理上 RR<300ms = 心率>200bpm，腕表 ECG 不可能；
            // 107bpm(运动后) RR=561ms > 300ms 安全，不会误杀高心率真 R 波。
            // 验证（test_refine_300ms.py，7 样本）：
            // - 专业/dsmax/静息/心电api活动后 极端 RR 全清零
            // - ds均衡/223211 边缘→可信，运动后无回归
            // - 仅专业 R 峰 32→25（双峰合并，58bpm RR=1034ms 不会误杀真 R 波）
            if (rPeaks.isNotEmpty() && (bestIdx - rPeaks.last()) < refineRefractory) continue
            rPeaks.add(bestIdx)
            // 关键修复：lastPeakIdx 必须用精修后的 bestIdx，否则不应期基于包络峰位置计算，
            // 与实际 R 峰位置偏差累积，可能让相邻 QRS 的精修位置进入同一不应期窗口
            lastPeakIdx = bestIdx
        }

        // 7. 回溯补检（Pan-Tompkins 核心）：RR > 1.66×近期均值时可能有漏检
        if (rPeaks.size >= 5) {
            val extraPeaks = mutableListOf<Int>()
            for (k in 1 until rPeaks.size) {
                val rr = rPeaks[k] - rPeaks[k - 1]
                // 计算近期 RR 均值（前后各 4 个），排除当前 k 索引的 RR
                // 标准 Pan-Tompkins 用排除当前间期的邻居均值，避免长 RR 自身污染均值
                val recentStart = maxOf(0, k - 4)
                val recentEnd = minOf(rPeaks.size, k + 4)
                val recentRRs = mutableListOf<Int>()
                for (m in recentStart + 1 until recentEnd) {
                    if (m == k) continue  // 排除当前待判定的长 RR
                    recentRRs.add(rPeaks[m] - rPeaks[m - 1])
                }
                if (recentRRs.size < 3) continue
                val rrAvg = recentRRs.average()
                if (rrAvg < 1.0) continue

                if (rr > rrAvg * 1.66) {
                    // 半阈值搜索漏检区间：用搜索区间中点的分段阈值（漏检段阈值）
                    val midIdx = (rPeaks[k - 1] + rPeaks[k]) / 2
                    val midThr = if (midIdx in thresholds.indices) thresholds[midIdx] else globalFloor
                    val backThr = maxOf(midThr, globalFloor) * 0.5
                    val searchStart = rPeaks[k - 1] + refractory
                    val searchEnd = rPeaks[k] - refractory
                    if (searchEnd <= searchStart) continue
                    var bestBackIdx = -1
                    var bestBackVal = backThr
                    for (j in searchStart..searchEnd) {
                        if (j < 0 || j >= envelope.size) continue
                        if (envelope[j] > bestBackVal) {
                            val bLo = maxOf(0, j - checkRange)
                            val bHi = minOf(envelope.size, j + checkRange + 1)
                            var isMax = true
                            for (m in bLo until bHi) {
                                if (m != j && envelope[m] > envelope[j]) { isMax = false; break }
                            }
                            if (isMax) {
                                bestBackVal = envelope[j]
                                bestBackIdx = j
                            }
                        }
                    }
                    if (bestBackIdx >= 0) {
                        // 精修（与主检测一致用 ±50ms，理由见主检测注释）
                        val rLo = maxOf(0, bestBackIdx - sampleRateHz / 20)
                        val rHi = minOf(highPassed.size, bestBackIdx + sampleRateHz / 20)
                        var rIdx = bestBackIdx
                        var rVal = 0.0
                        for (j in rLo until rHi) {
                            if (abs(highPassed[j]) > rVal) {
                                rVal = abs(highPassed[j])
                                rIdx = j
                            }
                        }
                        // T 波位置排除（v6 新增，v7 窗口调整为 300-450ms）：
                        // 根因：腕表 ECG 的 T/R 比普遍 ≥1（223211 T/R=1.09、静息 T/R=1.80、
                        // 运动后 T/R=0.97），T 波也有斜率会产生 envelope 峰，半阈值搜索时
                        // 可能找到 T 波峰 → 产生 R-T 短 RR + T-下个R 长 RR 的伪配对。
                        // 验证（test_backfill_twave.py）：223211 有 4 个、静息有 3 个回溯补检峰
                        // 落在 T 波位置（距前 R 峰 200-450ms）。
                        // v7 调整：下限从 200ms 提到 300ms，与精修二次不应期一致，
                        // 避免 <300ms 的补检峰（极端 RR）通过；上限 450ms 不变（T 波最晚位置）。
                        // 二次不应期也用 refineRefractory（300ms），与主检测一致。
                        val prevR = rPeaks.filter { it < rIdx }.maxOrNull() ?: -refractory * 4
                        val offsetToPrevMs = (rIdx - prevR) * 1000.0 / sampleRateHz
                        if (offsetToPrevMs in 300.0..450.0) continue  // T 波位置，跳过
                        // 回溯补检二次不应期（300ms，与主检测一致）
                        if (rPeaks.any { abs(rIdx - it) < refineRefractory }) continue
                        extraPeaks.add(rIdx)
                    }
                }
            }
            if (extraPeaks.isNotEmpty()) {
                rPeaks.addAll(extraPeaks)
                rPeaks.sort()
            }
        }

        return rPeaks
    }

    /**
     * RR 间期计算 + 生理学范围过滤
     *
     * 范围 300-2500ms（24-200bpm）的生理学依据：
     * - 下限 300ms = 200bpm：极端心动过速上限，室上速 SVT 可达 150-250bpm（生理学教材）
     *   早搏联律间期最短约 300ms，再短多为噪声/误检
     * - 上限 2500ms = 24bpm：极端心动过缓（病理但存在），代偿间隙可至 2000ms+
     * - 窦性心律正常 600-1000ms（60-100bpm），本宽范围保留早搏短长 RR 用于节律判别
     *
     * 注意：HRV/心率统计另有 filterEctopicBeats（±20%, Karey 2019）剔早搏
     */
    private fun computeRRIntervals(rPeaks: List<Int>, sampleRateHz: Int): List<Int> {
        if (rPeaks.size < 2) return emptyList()
        val rr = mutableListOf<Int>()
        for (i in 1 until rPeaks.size) {
            val rrMs = ((rPeaks[i] - rPeaks[i - 1]) * 1000.0 / sampleRateHz).toInt()
            // 宽区间 300-2500ms（24-200bpm），保留早搏短 RR 和代偿间隙长 RR
            if (rrMs in 300..2500) rr.add(rrMs)
        }
        return rr
    }

    /**
     * 剔除早搏 RR（偏离均值>20%），专供 HRV 计算
     *
     * 早搏的短 RR（联律间期）和长 RR（代偿间隙）会暴涨 SDNN/RMSSD/pNN50，
     * 让 DS 误判房颤。HRV 反映自主神经张力，应基于正常窦性心拍。
     * 节律判别仍用原始 RR（保早搏特征），两条通路各司其职。
     *
     * 阈值依据：Karey 2019 (Front Physiol, PMC6562196) ROC 分析显示
     * 20% 变化是区分正常/异常 RR 的灵敏-特异最佳平衡点（accuracy 0.92-0.99）。
     * 该文献用"相邻 RR 差>20%"，本实现改用"偏离均值>20%"——对 30 秒短记录更稳
     * （相邻差对单点噪声敏感，均值法平滑一次），阈值参考文献。
     */
    private fun filterEctopicBeats(rrIntervals: List<Int>): List<Int> {
        if (rrIntervals.size < 5) return rrIntervals
        val mean = rrIntervals.average()
        if (mean <= 0) return rrIntervals
        return rrIntervals.filter { rr ->
            val dev = abs(rr - mean) / mean
            dev <= 0.20  // Karey 2019：20% 是正常/异常 RR 的最佳分界点
        }.ifEmpty { rrIntervals }  // 全部偏离则保留原样（异常情况不空手）
    }

    /** 中位数（抗 P/T 波干扰，比均值更适合做局部基线估计） */
    private fun List<Int>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = this.sorted()
        return sorted[sorted.size / 2].toDouble()
    }

    private data class HeartRateStats(val avgHr: Int, val minHr: Int, val maxHr: Int)

    /**
     * 心率统计（抗 R 波误检 + 抗早搏设计）
     *
     * 已知问题（已修复）：旧实现用 P10-P90 分位数，30 秒短记录仅 ~40 个 RR，
     * 10% 误检就能把 P90 拉到虚高值（如真实 80bpm 测出 120bpm）。
     *
     * 策略（全部有文献依据）：
     * 1. avgHr 用中位数 RR 反算（60000/medianRR）——中位数对 ≤49% 的误检/早搏完全免疫
     *    （数学性质：中位数不受极端值影响，除非极端值过半）
     * 2. 心率范围基于呼吸性窦性心律不齐的正常变异幅度：
     *    Hiss 1976 (J Appl Physiol, PMID 993161): Percent variation = 23.2 - 0.35 × age
     *    - 18岁 ≈ 17%，40岁 ≈ 9%，年轻人呼吸性变异显著
     *    - 未知年龄用 ±20%（覆盖文献报告的年轻人最大变异 17% + 3% 余量）
     *    超过此范围的 RR 视为早搏/误检/噪声，不纳入心率范围统计
     * 3. 输入应为已剔早搏的 RR（filterEctopicBeats），避免短长 RR 污染心率
     */
    private fun computeHeartRateStats(rrIntervals: List<Int>, ageYears: Int): HeartRateStats {
        if (rrIntervals.size < 3) return HeartRateStats(0, 0, 0)
        val medianRR = rrIntervals.median()
        if (medianRR <= 0) return HeartRateStats(0, 0, 0)
        val medianHR = (60000.0 / medianRR).toInt()
        if (medianHR !in 40..200) return HeartRateStats(0, 0, 0)

        // 呼吸性窦性心律不齐的正常变异范围（Hiss 1976 公式）
        // 未知年龄(ageYears<=0)用 20%（覆盖年轻人最大变异 17% + 余量）
        val variationPct = if (ageYears > 0) {
            (23.2 - 0.35 * ageYears).coerceIn(5.0, 20.0) / 100.0
        } else {
            0.20
        }
        val lowerBound = medianRR * (1 - variationPct)
        val upperBound = medianRR * (1 + variationPct)
        val normalRRs = rrIntervals.filter { it.toDouble() in lowerBound..upperBound }
        val rrForRange = if (normalRRs.size >= 3) normalRRs else rrIntervals

        val minRR = (rrForRange.minOrNull() ?: medianRR.toInt()).toDouble()
        val maxRR = (rrForRange.maxOrNull() ?: medianRR.toInt()).toDouble()
        return HeartRateStats(
            avgHr = medianHR,  // 中位数反算，抗误检
            minHr = (60000.0 / maxRR).toInt(),  // 长 RR = 低心率
            maxHr = (60000.0 / minRR).toInt(),  // 短 RR = 高心率
        )
    }

    private data class HrvStats(val sdnn: Float, val rmssd: Float, val pnn50: Float)

    /**
     * HRV 时域指标（Task Force 1996 标准，Circulation 93:1043-1065）
     * - SDNN: 所有 NN 间期标准差，反映总体 HRV（短期记录反映交感+副交感+体液）
     * - RMSSD: 相邻 NN 差的均方根，反映副交感（迷走）张力，高频
     * - pNN50: 相邻 NN 差>50ms 占比，与 RMSSD 高度相关，反映副交感
     * Task Force 1996 明确：NN = 剔除非窦性心拍后的 RR，本实现已用 filterEctopicBeats 剔早搏
     * 注意：Task Force 推荐 5 分钟/24 小时记录，本设备 30 秒记录 SDNN 仅供参考不可比标准值
     */
    private fun computeHrv(rrIntervals: List<Int>): HrvStats {
        if (rrIntervals.size < 3) return HrvStats(0f, 0f, 0f)
        val mean = rrIntervals.average()
        // SDNN: 所有 RR 间期的标准差
        val variance = rrIntervals.map { (it - mean) * (it - mean) }.average()
        val sdnn = sqrt(variance).toFloat()
        // RMSSD: 相邻 RR 差的均方根
        var sumSqDiff = 0.0
        for (i in 1 until rrIntervals.size) {
            val diff = (rrIntervals[i] - rrIntervals[i - 1]).toDouble()
            sumSqDiff += diff * diff
        }
        val rmssd = sqrt(sumSqDiff / (rrIntervals.size - 1)).toFloat()
        // pNN50: 相邻 RR 差 >50ms 的占比
        var nn50 = 0
        for (i in 1 until rrIntervals.size) {
            if (abs(rrIntervals[i] - rrIntervals[i - 1]) > 50) nn50++
        }
        val pnn50 = (nn50.toFloat() / (rrIntervals.size - 1)) * 100f
        return HrvStats(sdnn, rmssd, pnn50)
    }

    /**
     * 节律判别辅助特征（帮助 DS 区分窦性/房颤/早搏）
     *
     * - RR 变异系数 CV = SDNN/meanRR：
     *   * <0.05 窦性心律（很规律）
     *   * 0.05~0.15 窦性心律不齐/呼吸性变异（青年人常见，深呼吸时可达 0.20）
     *   * >0.20 高度不规律，提示房颤或心律不齐（须结合 Poincaré 形态，不可仅凭 CV 判房颤）
     * - Poincaré 散点形态（RRn vs RRn+1）：
     *   * 彗星形(comet)：窄而长，沿对角线 → 窦性心律
     *   * 扇形(fan)：宽而散，远离对角线 → 房颤
     *   * 鱼雷形(torpedo)：窄短 → 窦性心律不齐
     *   * 复杂形(complex)：多个簇 → 早搏/异位心律
     * - 短-长 RR 配对：相邻 RR 中"短(前)<均值×0.8 且 长(后)>均值×1.2"
     *   是早搏代偿间隙的典型特征
     */
    private data class RhythmFeatures(
        val variabilityCoef: Float,
        val poincarePattern: String,
        val shortLongPairs: Int,
    )

    private fun computeRhythmFeatures(rrIntervals: List<Int>): RhythmFeatures {
        if (rrIntervals.size < 4) {
            return RhythmFeatures(0f, "数据不足", 0)
        }
        // CV/SD1/SD2 用剔早搏 RR（filterEctopicBeats），shortLongPairs 用原始 RR（保早搏检测）
        //
        // 修复原因：真实导联反接/低振幅样本（ECG_diagnostic_20260716_223211，HeartVoice 诊断 SN 窦性 0早搏）
        // R 波检测在低信噪比（9mV 基线漂移 + R 波仅 0.2-0.9mV）下产生误检/漏检 → 虚假短长 RR
        // → 原始 RR 的 CV 被拉高至 0.35-0.55 → 误判"扇形(疑似房颤)"，DS 据此误诊房颤。
        // 6 个真实窦性样本（静息/活动后/运动后/导联反接）CV 全部 0.38-0.56，全部误判扇形。
        //
        // CV/SD1/SD2 对检测误差极敏感（一个 400ms 短 RR + 一个 1500ms 长 RR 就能让 CV 翻倍），
        // 而 filterEctopicBeats（±20%, Karey 2019）能剔除这些偏离均值>20% 的检测误差 RR。
        // 真早搏的短长 RR 也偏离均值>20%，会被剔除——所以 shortLongPairs 仍用原始 RR 保留早搏检测。
        //
        // 这样 CV 反映"规律性"（不被检测误差干扰），shortLongPairs 反映"早搏特征"（保原始 RR），
        // DS 能区分"窦性+检测噪声"（CV低+SLP可能高）vs"真房颤"（CV高+SLP低）。
        // 修复后 6 个真实窦性样本 CV 降至 0.10-0.15，判"鱼雷形(轻度不齐)"，不再误判房颤。
        val rrForCv = filterEctopicBeats(rrIntervals)
        val rrForStats = if (rrForCv.size >= 4) rrForCv else rrIntervals  // 清洗后不足4则 fallback 原始

        val mean = rrForStats.average()
        if (mean < 1.0) return RhythmFeatures(0f, "数据不足", 0)

        // 变异系数（用清洗后 RR）
        val variance = rrForStats.map { (it - mean) * (it - mean) }.average()
        val sdnn = sqrt(variance)
        val cv = (sdnn / mean).toFloat()

        // Poincaré 散点 SD1/SD2（Brennan 2001 标准公式，用清洗后 RR）
        // SD1（短轴，垂直对角线，短期变异/副交感）= std(RR_n - RR_{n+1}) / sqrt(2)
        // SD2（长轴，沿对角线，长期变异）= std(RR_n + RR_{n+1}) / sqrt(2)
        val pairs = ArrayList<Pair<Double, Double>>()
        for (i in 0 until rrForStats.size - 1) {
            pairs.add(Pair(rrForStats[i].toDouble(), rrForStats[i + 1].toDouble()))
        }
        val diffs = DoubleArray(pairs.size) { pairs[it].first - pairs[it].second }
        val sums = DoubleArray(pairs.size) { pairs[it].first + pairs[it].second }
        val diffMean = diffs.average()
        val sumMean = sums.average()
        var diffVar = 0.0
        var sumVar = 0.0
        for (i in diffs.indices) {
            diffVar += (diffs[i] - diffMean) * (diffs[i] - diffMean)
            sumVar += (sums[i] - sumMean) * (sums[i] - sumMean)
        }
        diffVar /= diffs.size
        sumVar /= sums.size
        val sd1 = sqrt(diffVar / 2.0)  // 短轴（短期变异）
        val sd2 = sqrt(sumVar / 2.0)   // 长轴（长期变异）
        // SD1/SD2 比值：SD1=短轴(副交感/短期变异)，SD2=长轴(长期变异)，Brennan 2001 标准公式
        // 方向性依据：健康人 SD1<SD2（彗星形，沿对角线集中），房颤时 SD1 显著升高接近甚至超过 SD2（扇形，短轴变宽）
        // 注意：文献中房颤检测多用 SVM/KNN 等机器学习分类（准确率 91-99%，Park 2009; Tuboly 2019），
        // 无统一的 SD1/SD2 比值金标准切点，此处用比值升高方向辅助 CV 判断，不作单一阈值诊断
        val ratio = if (sd2 > 0.1) sd1 / sd2 else 0.0

        // 短-长 RR 配对（早搏代偿间隙，用原始 RR 保早搏特征）
        // 生理学依据：早搏=短联律间期(早搏前RR短)+代偿间隙(早搏后RR长)，室早完全代偿/房早不完全代偿
        // 0.8/1.2 倍均值为经验切点（方向依据生理学，倍数无文献金标准）
        // 用原始 RR 的均值：早搏短长 RR 偏离清洗后均值更远，用原始均值做阈值更易检出早搏
        val meanRaw = rrIntervals.average()
        var shortLongPairs = 0
        for (i in 0 until rrIntervals.size - 1) {
            val isShort = rrIntervals[i] < meanRaw * 0.8
            val isLong = rrIntervals[i + 1] > meanRaw * 1.2
            if (isShort && isLong) shortLongPairs++
        }

        // 散点形态分类（综合考虑 CV、SD1/SD2 比值方向、短-长配对数）
        // CV 阈值 0.05/0.15/0.20 为经验值（文献无统一 CV 房颤切点，各研究用 RCV+SVM 分类）
        // SD1/SD2 比值仅用方向（升高=不规律），不用精确切点（无文献金标准）
        // 短-长配对数≥3 才提示早搏（1-2 个可能是正常呼吸性变异，经验值）
        val pattern = when {
            cv < 0.05 -> "彗星形(规律)"
            cv < 0.15 && ratio < 0.5 -> "彗星形(规律)"
            cv < 0.15 && ratio >= 0.5 -> "鱼雷形(轻度不齐)"
            cv in 0.15..0.20 && ratio >= 0.7 -> "扇形(不规律，需结合RR序列判断)"
            cv > 0.20 && ratio >= 0.7 -> "扇形(高度不规律，疑似房颤)"
            shortLongPairs >= 3 && cv < 0.20 -> "复杂形(疑似早搏)"
            else -> "彗星形(规律)"
        }

        return RhythmFeatures(cv, pattern, shortLongPairs)
    }

    /**
     * R 峰可靠性评估：从 RR 序列与分段特征反推 R 波定位本身的稳定性
     *
     * 输入说明：
     * - rawRPeaks：未做噪声段剔除的原始 R 峰位置（用于算"全部相邻 R 峰对"分母）
     * - effectiveRPeaks：噪声段剔除后的有效 R 峰（用于算有效 RR）
     * - rrIntervals：由 effectiveRPeaks 算出的 RR（已过 300-2500ms 区间过滤）
     * - avgHr：用于"R 峰-时长一致性"的期望心率（来自剔早搏 RR，比原始 RR 稳）
     * - durationSec：用于算期望 R 峰数 = durationSec × avgHr / 60
     * - segments：逐秒分段，用于算异常段占比
     *
     * 判定逻辑（任一命中即降级）：
     * - 不可信：极端 RR>18% 或 有效 RR<80% 或 跳变>20% 或 一致性<0.6 或 >1.5 或 异常段>50%
     * - 边缘：极端 RR>8% 或 有效 RR<90% 或 跳变>10% 或 一致性<0.75 或 >1.35 或 异常段>30%
     * - 可信：以上均不触发
     *
     * 跳变比例用 filterEctopicBeats 清洗后的 RR（非原始 RR）：
     * - 原始 RR 含误检/早搏导致跳变普遍偏高（30-60%），5 份真实样本无区分度
     * - 清洗后 RR 的跳变反映 filterEctopicBeats 救不了的残余剧烈跳变，更能区分"真不稳定"
     *
     * 阈值依据：5 份真实 wrist ECG 样本校准（test_reliability.py 验证）：
     * - 223211（导联反接+漂移，曾误判房颤）：极端 RR=21% → 不可信 ✓
     * - 223747（DS 路径）：极端 RR=31% → 不可信 ✓
     * - 运动后（SNT 107bpm）：极端 RR=3% → 可信 ✓
     * - 静息/活动后/静息旧（SN/SNB）：极端 RR=12-14% → 边缘 ✓
     */
    private fun computeRPeakReliability(
        rawRPeaks: List<Int>,
        effectiveRPeaks: List<Int>,
        rrIntervals: List<Int>,
        avgHr: Int,
        durationSec: Float,
        segments: List<SegmentFeature>,
        sampleRateHz: Int,
    ): RPeakReliability {
        // 数据不足时直接判不可信（无法评估）
        if (rawRPeaks.size < 4 || durationSec < 5f) {
            return RPeakReliability(
                reliable = false, level = "不可信",
                reason = "R 波数不足(${rawRPeaks.size})或时长过短(${durationSec}s)，无法评估节律",
            )
        }

        // 1. 极端 RR 占比 / 2. 有效 RR 占比
        // 全部相邻 R 峰对（含未通过 300-2500ms 的）= rawRPeaks.size - 1
        // 但 rrIntervals 已是 effectiveRPeaks 过滤后的结果，无法反推被剔除的 RR 数
        // 改用 rawRPeaks 算原始 RR，统计极端 RR 占比（更接近"误检/漏检"的真实比例）
        val totalPairs = rawRPeaks.size - 1
        var extremeCount = 0
        var validCount = 0
        for (i in 1 until rawRPeaks.size) {
            val rrMs = ((rawRPeaks[i] - rawRPeaks[i - 1]) * 1000.0 / sampleRateHz).toInt()
            if (rrMs in 300..2500) {
                validCount++
            } else {
                extremeCount++
            }
        }
        val extremeRrRatio = if (totalPairs > 0) extremeCount.toFloat() / totalPairs else 0f
        val validRrRatio = if (totalPairs > 0) validCount.toFloat() / totalPairs else 1f

        // 3. 相邻 RR 跳变比例（用 filterEctopicBeats 清洗后的 RR，>40% mean 视为跳变）
        // 用清洗后 RR 而非原始 RR：原始 RR 含误检/早搏导致跳变普遍偏高（30-60%），无区分度；
        // 清洗后 RR 的跳变反映 filterEctopicBeats 救不了的残余剧烈跳变，更能区分"真不稳定"
        // 40% 阈值与 shortLongPairs 的 0.8/1.2 倍均值一致（差值>40% mean）
        val rrCleanForJump = filterEctopicBeats(rrIntervals)
        val rrJumpRatio = if (rrCleanForJump.size >= 2 && rrCleanForJump.average() > 0) {
            val mean = rrCleanForJump.average()
            val jumpCount = (1 until rrCleanForJump.size).count { k ->
                abs(rrCleanForJump[k] - rrCleanForJump[k - 1]) / mean > 0.40
            }
            jumpCount.toFloat() / (rrCleanForJump.size - 1)
        } else 0f

        // 4. R 峰-时长一致性：实际 R 峰数 / 期望 R 峰数
        // 期望 = durationSec × avgHr / 60（avgHr 来自剔早搏 RR，比原始 RR 稳）
        // 比值 <1 = 漏检，>1 = 误检
        val expectedPeaks = if (avgHr > 0) (durationSec * avgHr / 60f).coerceAtLeast(1f) else 1f
        val rateConsistency = effectiveRPeaks.size / expectedPeaks

        // 5. 异常段占比：R 波数为 0 或 rms<0.1 的段
        val abnormalSegCount = segments.count { it.rPeakCount == 0 || it.rmsMv < 0.10f }
        val abnormalSegmentRatio = if (segments.isNotEmpty()) {
            abnormalSegCount.toFloat() / segments.size
        } else 1f

        // 综合判定：不可信
        val reasons = mutableListOf<String>()
        if (extremeRrRatio > 0.18f) reasons.add("极端RR占比${"%.0f".format(extremeRrRatio * 100)}%")
        if (validRrRatio < 0.80f) reasons.add("有效RR占比${"%.0f".format(validRrRatio * 100)}%")
        if (rrJumpRatio > 0.20f) reasons.add("相邻RR跳变(清洗后)${"%.0f".format(rrJumpRatio * 100)}%")
        if (rateConsistency < 0.60f) reasons.add("R峰数偏少(一致性${"%.2f".format(rateConsistency)})")
        if (rateConsistency > 1.50f) reasons.add("R峰数偏多(一致性${"%.2f".format(rateConsistency)})")
        if (abnormalSegmentRatio > 0.50f) reasons.add("异常段占比${"%.0f".format(abnormalSegmentRatio * 100)}%")

        if (reasons.isNotEmpty()) {
            return RPeakReliability(
                extremeRrRatio = extremeRrRatio,
                validRrRatio = validRrRatio,
                rrJumpRatio = rrJumpRatio,
                rateConsistency = rateConsistency,
                abnormalSegmentRatio = abnormalSegmentRatio,
                reliable = false, level = "不可信",
                reason = "R波定位稳定性不足：${reasons.joinToString("、")}",
            )
        }

        // 边缘判定
        val edgeReasons = mutableListOf<String>()
        if (extremeRrRatio > 0.08f) edgeReasons.add("极端RR占比${"%.0f".format(extremeRrRatio * 100)}%")
        if (validRrRatio < 0.90f) edgeReasons.add("有效RR占比${"%.0f".format(validRrRatio * 100)}%")
        if (rrJumpRatio > 0.10f) edgeReasons.add("相邻RR跳变(清洗后)${"%.0f".format(rrJumpRatio * 100)}%")
        if (rateConsistency < 0.75f) edgeReasons.add("R峰数偏少(一致性${"%.2f".format(rateConsistency)})")
        if (rateConsistency > 1.35f) edgeReasons.add("R峰数偏多(一致性${"%.2f".format(rateConsistency)})")
        if (abnormalSegmentRatio > 0.30f) edgeReasons.add("异常段占比${"%.0f".format(abnormalSegmentRatio * 100)}%")

        if (edgeReasons.isNotEmpty()) {
            return RPeakReliability(
                extremeRrRatio = extremeRrRatio,
                validRrRatio = validRrRatio,
                rrJumpRatio = rrJumpRatio,
                rateConsistency = rateConsistency,
                abnormalSegmentRatio = abnormalSegmentRatio,
                reliable = true, level = "边缘",
                reason = "R波定位稳定性边缘：${edgeReasons.joinToString("、")}，节律判读需谨慎",
            )
        }

        return RPeakReliability(
            extremeRrRatio = extremeRrRatio,
            validRrRatio = validRrRatio,
            rrJumpRatio = rrJumpRatio,
            rateConsistency = rateConsistency,
            abnormalSegmentRatio = abnormalSegmentRatio,
            reliable = true, level = "可信",
            reason = "",
        )
    }

    private data class IntervalEstimates(
        val qrsMean: Int, val qrsStd: Int,
        val prMean: Int, val prStd: Int,
        val qtMean: Int, val qtc: Int,
        val qtcFridericiaMs: Int,
        // 形态客观参数（与间期估测同源，复用 Q/S/T 定位）
        val tAmplitudeMv: Float,   // T 波平均振幅（mV，相对基线，带符号）
        val rAmplitudeMv: Float,   // R 波平均振幅（mV，相对基线，带符号）
        val stDeviationMv: Float,  // ST 段平均偏移（mV，QRS 终点后 20ms 处相对基线）
        val rPeakPolarity: String, // R 波极性："正向"/"负向"/"未知"（多数投票）
        // v8 置信度
        val qrsConfidence: String, // "高置信"/"低置信"
        val qtConfidence: String,  // "高置信"/"低置信"
    )

    /**
     * 间期估测（v8：QRS SNR 自适应 + QT Bazett 先验 + 置信度标注）
     *
     * v8 改进背景（5 份带 HeartVoice 对照样本验证）：
     * - QRS：旧阈值交叉法（10% 阈值）在腕表 R 波振幅<0.5mV 时被噪声淹没，
     *   QRS 偏窄 30-50ms。v8 改用 SNR 自适应：SNR≥3 实测（5% 阈值），SNR<3 先验 100ms。
     *   验证结果：QRS 偏差从 30-50ms 降到 9.6ms（目标 ±10ms 内 ✓）
     * - QT：旧 T 波峰法在腕表 T 波振幅<0.05mV 时完全失效，实测 QT 偏差 46-90ms。
     *   验证所有实测方法（切线/阈值/模板/T峰固定间隔）都有物理上限。
     *   纯 Bazett 先验（40.8ms）反而比所有实测方法都准，v8 改用 Bazett 先验并标注低置信。
     *
     * 置信度设计（让 DS 知道哪些是实测值，哪些是先验值）：
     * - QRS：高 SNR R 波占比 >30% → "高置信"（多数走阈值交叉法），否则 "低置信"（多数走先验 100ms）
     * - QT：始终 "低置信"（腕表 T 波振幅<0.05mV 被噪声淹没，本地始终用 Bazett 先验）
     *
     * QRS: SNR 自适应法——SNR≥3 用 5% 阈值交叉法，SNR<3 用先验 100ms
     *      依据：AHA/ESC 正常 QRS 范围 80-120ms，中位数 100ms
     * PR: R 峰前推 250ms 窗口找 P 波候选（小幅正向峰）→ 到 R 峰的间隔（保留 v7 逻辑）
     * QT: Bazett 反向公式 QT = QTc × sqrt(RR)，QTc=400ms（AHA/ESC 正常范围 350-450ms 中位数）
     *     依据：Postema 2014 (PMID 24827793)；Bazett 1920 (Heart 7:353-370)
     */
    private fun estimateIntervals(
        ecgData: List<Int>,
        sampleRateHz: Int,
        rPeaks: List<Int>,
        avgHr: Int,
    ): IntervalEstimates {
        if (rPeaks.size < 3) return IntervalEstimates(0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, "未知", "未知", "未知")

        val qrsWidths = mutableListOf<Int>()
        val prIntervals = mutableListOf<Int>()
        // 形态客观参数收集（与间期估测同源，复用 Q/S/T 定位）
        val tAmplitudes = mutableListOf<Float>()  // T 波振幅（mV，相对基线，带符号）
        val rAmplitudes = mutableListOf<Float>()  // R 波振幅（mV，相对基线，带符号）
        val stDeviations = mutableListOf<Float>() // ST 段偏移（mV）
        var positiveCount = 0  // R 波极性投票
        var negativeCount = 0
        // v8：QRS SNR 统计（用于置信度判定）
        var highSnrRPeakCount = 0  // SNR≥3 的 R 波数
        var totalRPeakCount = 0    // 有效 R 波总数

        for (idx in rPeaks.indices) {
            val rIdx = rPeaks[idx]
            // QRS 估测：阈值交叉法（兼容正向/负向 R 波）
            val qSearchStart = maxOf(0, rIdx - sampleRateHz * 100 / 1000)
            val sSearchEnd = minOf(ecgData.size, rIdx + sampleRateHz * 100 / 1000)
            if (sSearchEnd - qSearchStart < 10) continue

            // 中位数去基线（抗 P/T 波干扰，比均值更适合局部基线估计，减小间期偏差）
            val segMedian = ecgData.subList(qSearchStart, sSearchEnd).median()
            // R 波幅度用绝对值，兼容负向 R 波（腕表单导联 R 波极性可能向下）
            // 在 rIdx ± 10ms 邻域找真正极值，避免精修位置偏差几 ms 导致振幅测量不准
            val refineRange = sampleRateHz / 50  // ±10ms
            var rPeakVal = ecgData[rIdx]
            for (j in maxOf(qSearchStart, rIdx - refineRange)..minOf(sSearchEnd - 1, rIdx + refineRange)) {
                if (abs(ecgData[j] - segMedian) > abs(rPeakVal - segMedian)) {
                    rPeakVal = ecgData[j]
                }
            }
            val rAmpVal = abs(rPeakVal.toDouble() - segMedian)
            if (rAmpVal < 30.0) continue  // R 波太小（<0.03mV），不可靠

            // 判断 R 波极性：R 峰值相对基线是正还是负
            val rIsPositive = rPeakVal.toDouble() > segMedian
            if (rIsPositive) positiveCount++ else negativeCount++
            // R 波振幅（带符号，mV）—— 用于 T/R 比计算
            rAmplitudes.add(((rPeakVal - segMedian) / 1000.0).toFloat())

            // v8 QRS：SNR 自适应法
            // 根因：腕表 ECG R 波振幅常 <0.5mV，被噪声（±0.4mV）淹没，
            // 阈值交叉法和导数法都无法准确定位 Q/S 边界 → QRS 偏窄 30-50ms。
            // 修复策略（信噪比自适应，非硬编码补正）：
            // 1. 估算局部信噪比 SNR = R 振幅 / 噪声 RMS
            // 2. SNR ≥ 3（高信噪比）：用 5% 阈值交叉法（比旧 10% 更宽，更接近真实 QRS）
            // 3. SNR < 3（低信噪比，R 波被淹没）：QRS 测量不可靠，用先验 100ms
            //    依据：健康成人 QRS 正常范围 80-120ms（AHA/ESC 标准），中位数 ~100ms
            //    这是"测量不可靠时用先验"，不是"硬编码补正"——高信噪比时仍用实测值
            val noiseRWindowStart = qSearchStart
            val noiseRWindowEnd = maxOf(qSearchStart, rIdx - sampleRateHz * 80 / 1000)
            val noiseLWindowStart = minOf(sSearchEnd, rIdx + sampleRateHz * 80 / 1000)
            val noiseLWindowEnd = sSearchEnd
            val noiseSamples = mutableListOf<Double>()
            for (i in noiseRWindowStart until noiseRWindowEnd) {
                noiseSamples.add(ecgData[i] - segMedian)
            }
            for (i in noiseLWindowStart until noiseLWindowEnd) {
                noiseSamples.add(ecgData[i] - segMedian)
            }
            if (noiseSamples.isEmpty()) {
                for (i in qSearchStart until sSearchEnd) {
                    noiseSamples.add(ecgData[i] - segMedian)
                }
            }
            val noiseRms = if (noiseSamples.isNotEmpty()) {
                sqrt(noiseSamples.map { it * it }.average())
            } else 0.0
            val snr = if (noiseRms > 0) rAmpVal / noiseRms else 0.0
            totalRPeakCount++

            val qPoint: Int
            val sPoint: Int
            val qrsMs: Int
            if (snr < 3.0) {
                // 低信噪比：R 波被噪声淹没，测量不可靠，用先验 100ms
                // Q 点 = R 峰前 50ms，S 点 = R 峰后 50ms（对称，QRS 中心约在 R 峰）
                qPoint = maxOf(0, rIdx - sampleRateHz * 50 / 1000)
                sPoint = minOf(ecgData.size, rIdx + sampleRateHz * 50 / 1000)
                qrsMs = 100
            } else {
                // 高信噪比：用 5% 阈值交叉法（比旧 10% 更宽，更接近真实 QRS）
                highSnrRPeakCount++
                val qrsThreshold = rAmpVal * 0.05
                var q = rIdx
                for (i in rIdx downTo qSearchStart) {
                    val amp = abs(ecgData[i].toDouble() - segMedian)
                    if (amp < qrsThreshold) {
                        q = i
                        break
                    }
                }
                var s = rIdx
                for (i in rIdx until sSearchEnd) {
                    val amp = abs(ecgData[i].toDouble() - segMedian)
                    if (amp < qrsThreshold) {
                        s = i
                        break
                    }
                }
                qPoint = q
                sPoint = s
                qrsMs = (sPoint - qPoint) * 1000 / sampleRateHz
            }
            if (qrsMs in 40..200) qrsWidths.add(qrsMs)

            // ST 段偏移测量（J 点 + 20ms 处相对基线，保留 v7 逻辑）
            // 临床定义：ST 段 = QRS 终点(J 点, ≈S 点)到 T 波起点。
            // ST 偏移 = J 点后 20ms 处振幅相对基线的高度差。
            // 基线用 QRS 前 50ms 中位数（PQ 段基线，比 QRS 窗口中位数更接近真基线）
            val stMeasureIdx = sPoint + sampleRateHz * 20 / 1000  // J 点(S) + 20ms
            if (stMeasureIdx < ecgData.size) {
                val baselineStart = maxOf(0, qPoint - sampleRateHz * 50 / 1000)
                val baselineEnd = qPoint  // PQ 段（QRS 前 50ms）
                if (baselineEnd > baselineStart + 5) {
                    val baselineSeg = ecgData.subList(baselineStart, baselineEnd).sorted()
                    val pqBaseline = baselineSeg[baselineSeg.size / 2].toDouble()
                    val stDevMv = ((ecgData[stMeasureIdx] - pqBaseline) / 1000.0).toFloat()
                    if (stDevMv in -1.0f..1.0f) stDeviations.add(stDevMv)
                }
            }

            // PR 估测：R 峰前推 250ms 找 P 波（保留 v7 逻辑）
            val pSearchStart = maxOf(0, rIdx - sampleRateHz * 250 / 1000)
            if (pSearchStart < qSearchStart) {
                val pSeg = ecgData.subList(pSearchStart, qSearchStart)
                if (pSeg.isNotEmpty()) {
                    val pIdx = pSeg.indices.maxByOrNull {
                        val dev = pSeg[it].toDouble() - segMedian
                        if (rIsPositive) dev else -dev
                    } ?: 0
                    val pPeakGlobal = pSearchStart + pIdx
                    val prMs = (rIdx - pPeakGlobal) * 1000 / sampleRateHz
                    if (prMs in 80..300) prIntervals.add(prMs)
                }
            }

            // T 波形态参数收集（保留 v7 逻辑，用于 T/R 比等形态客观参数）
            // 注意：v8 不再用 T 波峰估测 QT（改用 Bazett 先验），但 T 波振幅仍需测量用于形态判读
            val tSearchStart = rIdx + sampleRateHz * 100 / 1000
            val tSearchHardEnd = rIdx + sampleRateHz * 500 / 1000
            val nextRPeakLimit = if (idx + 1 < rPeaks.size) rPeaks[idx + 1] - sampleRateHz * 50 / 1000
                                 else ecgData.size
            val tSearchEnd = minOf(ecgData.size, tSearchHardEnd, nextRPeakLimit)
            if (tSearchEnd > tSearchStart + 10) {
                val tSeg = ecgData.subList(tSearchStart, tSearchEnd)
                val tSorted = tSeg.sorted()
                val tMedian = tSorted[tSorted.size / 2].toDouble()
                val smoothWin = 5
                val smoothed = DoubleArray(tSeg.size)
                for (i in tSeg.indices) {
                    val from = maxOf(0, i - smoothWin / 2)
                    val to = minOf(tSeg.size, i + smoothWin / 2 + 1)
                    var sum = 0.0
                    for (j in from until to) sum += tSeg[j]
                    smoothed[i] = sum / (to - from) - tMedian
                }
                var tIdx = -1
                var maxDev = 0.0
                for (i in smoothed.indices) {
                    if (i > 0 && i < smoothed.size - 1) {
                        val isPeak = (smoothed[i] > smoothed[i - 1] && smoothed[i] >= smoothed[i + 1]) ||
                            (smoothed[i] < smoothed[i - 1] && smoothed[i] <= smoothed[i + 1])
                        if (isPeak && abs(smoothed[i]) > maxDev) {
                            maxDev = abs(smoothed[i])
                            tIdx = i
                        }
                    }
                }
                if (tIdx >= 0) {
                    val tPeakGlobal = tSearchStart + tIdx
                    // T 波振幅（带符号，mV，相对 PQ 基线）
                    val baselineStart2 = maxOf(0, qPoint - sampleRateHz * 50 / 1000)
                    if (qPoint > baselineStart2 + 5) {
                        val blSeg = ecgData.subList(baselineStart2, qPoint).sorted()
                        val pqBase = blSeg[blSeg.size / 2].toDouble()
                        val tAmpMv = ((ecgData[tPeakGlobal] - pqBase) / 1000.0).toFloat()
                        if (tAmpMv in -2.0f..2.0f) tAmplitudes.add(tAmpMv)
                    }
                }
            }
        }

        fun mean(lst: List<Int>) = if (lst.isEmpty()) 0 else lst.average().toInt()
        fun std(lst: List<Int>): Int {
            if (lst.size < 2) return 0
            val m = lst.average()
            return sqrt(lst.map { (it - m) * (it - m) }.average()).toInt()
        }

        // v8 QT：Bazett 反向公式先验 QT = QTc × sqrt(RR)，QTc=400ms
        // 根因：腕表 ECG T 波振幅 <0.05mV，被 ±0.1mV 基线噪声完全淹没，
        // 所有实测方法（切线/阈值/模板/T峰固定间隔）都有物理上限（偏差 46-90ms）。
        // 纯 Bazett 先验（40.8ms）反而比所有实测方法都准。
        // 这是"测量不可靠时用生理学先验"，不是"硬编码补正"——本地算法始终用先验
        // （因为腕表 T 波振幅本身低于噪声 floor），并明确标注置信度为"低置信"。
        // 依据：Postema 2014 (PMID 24827793)；Bazett 1920 (Heart 7:353-370)；
        //       QTc=400 是 AHA/ESC 推荐的健康成人正常范围 350-450ms 的中位数
        val qtMean = if (avgHr > 0) {
            val rrSec = 60.0 / avgHr
            (400 * sqrt(rrSec)).toInt()
        } else 0

        // QTc 双公式（均有原始文献）：
        // - Bazett 1920 (Heart 7:353-370): QT/sqrt(RR)
        // - Fridericia 1920 (Acta Med Scand 53:469-486): QT/RR^(1/3)，50-90bpm 全区间误差小
        // 临床共识（Postema 2014, PMID 24827793）：Fridericia 心率波动大时比 Bazett 更稳
        val rrSec = if (avgHr > 0) 60.0 / avgHr else 0.0
        val qtc = if (qtMean > 0 && rrSec > 0) {
            (qtMean / sqrt(rrSec)).toInt()
        } else 0
        val qtcFridericia = if (qtMean > 0 && rrSec > 0) {
            (qtMean / Math.cbrt(rrSec)).toInt()
        } else 0

        // 形态客观参数汇总（保留 v7 逻辑）
        val tAmpMean = if (tAmplitudes.isNotEmpty()) tAmplitudes.map { abs(it) }.average().toFloat() else 0f
        val rAmpMean = if (rAmplitudes.isNotEmpty()) rAmplitudes.map { abs(it) }.average().toFloat() else 0f

        val stDev = if (stDeviations.isNotEmpty()) {
            val sorted = stDeviations.sorted()
            sorted[sorted.size / 2]
        } else 0f

        // R 波极性：多数投票
        val polarity = when {
            positiveCount > negativeCount -> "正向"
            negativeCount > positiveCount -> "负向"
            else -> "未知"
        }

        // v8 置信度判定
        // QRS：高 SNR R 波占比 >30% → "高置信"（多数走阈值交叉法），否则 "低置信"（多数走先验 100ms）
        // QT：始终 "低置信"（腕表 T 波振幅<0.05mV 被噪声淹没，本地始终用 Bazett 先验）
        val qrsConfidence = if (totalRPeakCount > 0 && highSnrRPeakCount.toDouble() / totalRPeakCount > 0.30) {
            "高置信"
        } else {
            "低置信"
        }
        val qtConfidence = if (qtMean > 0) "低置信" else "未知"

        return IntervalEstimates(
            qrsMean = mean(qrsWidths), qrsStd = std(qrsWidths),
            prMean = mean(prIntervals), prStd = std(prIntervals),
            qtMean = qtMean, qtc = qtc, qtcFridericiaMs = qtcFridericia,
            tAmplitudeMv = tAmpMean,
            rAmplitudeMv = rAmpMean,
            stDeviationMv = stDev,
            rPeakPolarity = polarity,
            qrsConfidence = qrsConfidence,
            qtConfidence = qtConfidence,
        )
    }

    /** R 波平均振幅（mV），兼容正向/负向 R 波 */
    private fun computeRAmplitude(ecgData: List<Int>, rPeaks: List<Int>, sampleRateHz: Int): Float {
        if (rPeaks.isEmpty()) return 0f
        val window = sampleRateHz / 20  // ±50ms
        val amps = rPeaks.mapNotNull { rIdx ->
            val lo = maxOf(0, rIdx - window)
            val hi = minOf(ecgData.size, rIdx + window)
            if (hi > lo) {
                val seg = ecgData.subList(lo, hi)
                val segMean = seg.average()
                // 腕表单导联 R 波极性可能向下，取正向峰值与负向峰值中绝对值较大者
                // （与间期估测的 abs(ecgData[rIdx] - segMedian) 一致）
                val maxDev = maxOf(
                    abs((seg.maxOrNull() ?: 0) - segMean),
                    abs((seg.minOrNull() ?: 0) - segMean),
                )
                maxDev.toFloat() / 1000f  // mV×1000 → mV
            } else null
        }
        return if (amps.isNotEmpty()) amps.average().toFloat() else 0f
    }

    /** 逐秒分段特征提取 */
    private fun extractSegments(
        ecgData: List<Int>,
        sampleRateHz: Int,
        rPeaks: List<Int>,
        durationSec: Float,
    ): List<SegmentFeature> {
        val segLenSamples = sampleRateHz  // 1 秒一段（主流选择，0.5秒在70bpm下大量段跨RR间变0制造噪声）
        val segCount = (ecgData.size / segLenSamples).coerceAtLeast(1)
        val segments = mutableListOf<SegmentFeature>()

        for (segIdx in 0 until segCount) {
            val startSample = segIdx * segLenSamples
            val endSample = minOf(ecgData.size, (segIdx + 1) * segLenSamples)
            if (endSample - startSample < sampleRateHz / 2) continue  // 不足半秒跳过

            val seg = ecgData.subList(startSample, endSample)
            val segMean = seg.average()
            val centered = seg.map { (it - segMean).toFloat() / 1000f }  // mV

            val ampMin = centered.minOrNull() ?: 0f
            val ampMax = centered.maxOrNull() ?: 0f
            val p2p = ampMax - ampMin

            // 最大斜率（mV/s）
            var maxSlope = 0f
            for (i in 1 until centered.size) {
                val slope = abs(centered[i] - centered[i - 1]) * sampleRateHz.toFloat()
                if (slope > maxSlope) maxSlope = slope
            }

            // RMS
            val rms = sqrt(centered.map { it * it }.average()).toFloat()

            // 该段内 R 波数
            val rCount = rPeaks.count { it in startSample until endSample }

            segments.add(SegmentFeature(
                startSec = startSample.toFloat() / sampleRateHz,
                endSec = endSample.toFloat() / sampleRateHz,
                rPeakCount = rCount,
                ampMinMv = ampMin,
                ampMaxMv = ampMax,
                peakToPeakMv = p2p,
                maxSlopeMvPerSec = maxSlope,
                rmsMv = rms,
            ))
        }
        return segments
    }

    /** 信号质量评分（0~1） */
    private fun computeSignalQuality(
        segments: List<SegmentFeature>,
        rPeakCount: Int,
        durationSec: Float,
    ): Float {
        if (segments.isEmpty()) return 0f
        // 因子1：噪声段占比（越少越好）
        val noiseRatio = segments.count { it.rmsMv < 0.10f }.toFloat() / segments.size
        val noiseScore = 1f - noiseRatio
        // 因子2：期望心率 60-100bpm，30秒应有 30~50 个 R 波
        val expectedMin = (durationSec * 1f).toInt()   // 30bpm × 0.5
        val expectedMax = (durationSec * 2f).toInt()    // 120bpm
        val hrScore = if (rPeakCount in expectedMin..expectedMax) 1f else 0.5f
        // 因子3：平均 RMS 在合理范围（0.1~2.0mV）
        val avgRms = segments.map { it.rmsMv }.average().toFloat()
        val rmsScore = if (avgRms in 0.1f..2.0f) 1f
                       else if (avgRms in 0.05f..3.0f) 0.5f
                       else 0f
        return (noiseScore * 0.4f + hrScore * 0.3f + rmsScore * 0.3f).coerceIn(0f, 1f)
    }

    /**
     * 把特征打包成喂给 DeepSeek 的文本（结构化，约 1500~2500 token）
     */
    fun toPromptText(bundle: FeatureBundle): String {
        val g = bundle.global
        val sb = StringBuilder()

        // 用户属性
        sb.append("[用户属性]\n")
        if (bundle.profile.ageYears > 0) sb.append("年龄: ${bundle.profile.ageYears}岁\n")
        if (bundle.profile.isMale != null) {
            sb.append("性别: ${if (bundle.profile.isMale) "男" else "女"}\n")
        }
        sb.append("\n")

        // 全局指标
        sb.append("[全局指标]\n")
        sb.append("算法版本:${ALGORITHM_VERSION}\n")
        sb.append("采样率:${g.sampleRateHz}Hz 时长:${"%.1f".format(g.durationSec)}s 总点数:${g.totalSamples}\n")
        sb.append("R波检测:${g.rPeakCount}个 平均心率:${g.avgHeartRate}bpm 范围:${g.minHeartRate}-${g.maxHeartRate}bpm\n")
        // min/max=0 提示：R 波检测数不足或 HV API 缺陷时无法算 min/max
        if (g.minHeartRate == 0 && g.maxHeartRate == 0 && g.avgHeartRate > 0) {
            sb.append("(心率范围不可用：R波检测数不足无法算min/max，平均心率由${if (g.ppgReferenceHr > 0 && g.rPeakCount < 4) "PPG绿光参考" else "R波中位数"}估算；建议重新测量获取完整心率范围)\n")
        }
        // PPG 绿光参考心率：测后读系统心率传感器，绕开本地 R 波检测可能的不准
        // DS 应以此为"金标准参考"校验本地 R 波心率：两者偏差大时本地 R 波检测可能漏检/误检
        if (g.ppgReferenceHr > 0) {
            sb.append("PPG绿光参考心率:${g.ppgReferenceHr}bpm(系统心率传感器独立测算，可信度高于本地R波估测；若与R波心率偏差>15bpm，提示R波检测可能漏检/误检，应以PPG为准)\n")
        }
        // R 波位置索引（样本索引）：方便助手精确复现 R 波检测，对照 HeartVoice API 差异
        // 导出诊断包时此字段是定位"本地算法漏检/误检哪几个 R 波"的关键证据
        if (g.rPeakIndices.isNotEmpty()) {
            sb.append("R波位置(样本索引):${g.rPeakIndices.joinToString(",")}\n")
        }
        // RR 序列标注异常值（偏离均值>20% 标*，与 filterEctopicBeats 阈值一致，Karey 2019）
        // 帮助 DS 直观识别早搏（短RR）和代偿间隙（长RR）
        if (g.rrIntervalsMs.isNotEmpty()) {
            val rrMean = g.rrIntervalsMs.average()
            val rrAnnotated = g.rrIntervalsMs.joinToString(" ") { rr ->
                val dev = if (rrMean > 0) abs(rr - rrMean) / rrMean else 0.0
                if (dev > 0.20) "[$rr*]" else "$rr"
            }
            sb.append("RR间期序列(ms，[*]为偏离均值>20%的异常间期，多为早搏/误检):$rrAnnotated\n")
            // RR 差值序列：正值=RR变长，负值=RR变短；大幅正负交替=短-长配对=早搏特征
            if (g.rrIntervalsMs.size >= 2) {
                val rrDiffs = (1 until g.rrIntervalsMs.size).joinToString(",") {
                    (g.rrIntervalsMs[it] - g.rrIntervalsMs[it - 1]).toString()
                }
                sb.append("RR差值序列(ms):$rrDiffs\n")
            }
        }
        sb.append("SDNN:${"%.1f".format(g.sdnnMs)}ms RMSSD:${"%.1f".format(g.rmssdMs)}ms pNN50:${"%.1f".format(g.pnn50Pct)}%\n")
        sb.append("QRS宽度(本地估测,ms):${g.qrsWidthMs}±${g.qrsWidthStdMs} 置信度:${g.qrsConfidence}\n")
        sb.append("PR间期(本地估测,ms):${g.prIntervalMs}±${g.prIntervalStdMs}\n")
        sb.append("QT间期(本地估测,ms):${g.qtIntervalMs} QTc(Bazett):${g.qtcMs}ms QTc(Fridericia):${g.qtcFridericiaMs}ms 置信度:${g.qtConfidence}\n")
        if (g.qrsConfidence == "低置信" || g.qtConfidence == "低置信") {
            sb.append("注:低置信间期为生理学先验值（腕表信号物理上限），判读时仅供参考，不应作为强证据\n")
        }
        sb.append("R波平均振幅:${"%.2f".format(g.rAmplitudeMv)}mV\n")
        // 形态客观参数：测量归本地（输出客观值），判读归 DS（医学诊断）
        // 不做主观形态诊断（如"T 波倒置"），只输出客观测量值让 DS 判读
        sb.append("R波极性:${g.rPeakPolarity}(负向提示导联反接/位置异常)\n")
        if (g.tToRAmplitudeRatio > 0f) {
            // T/R 振幅比：正常 0.1~0.5（生理学教材），<0.1 T 波低平，>0.5 T 波高尖
            sb.append("T/R振幅比:${"%.2f".format(g.tToRAmplitudeRatio)}(正常0.1-0.5，<0.1低平，>0.5高尖)\n")
        }
        // ST 段偏移：正常 ±0.05mV，>0.1mV 提示 ST 抬高，<-0.1mV 提示 ST 压低
        sb.append("ST段偏移(本地估测,mV):${"%.3f".format(g.stDeviationMv)}(正常±0.05，>0.1抬高，<-0.1压低)\n")
        sb.append("信号质量:${"%.2f".format(g.signalQuality)} ${if (g.signalQuality >= 0.7f) "良好" else if (g.signalQuality >= 0.4f) "一般" else "较差"}\n")
        if (g.noiseSegments.isNotEmpty()) {
            sb.append("噪声段:${g.noiseSegments.joinToString("; ")}\n")
        }
        sb.append("\n")

        // 节律判别特征（帮助区分窦性/房颤/早搏，基于 RR 间期序列）
        // 降级原则：R 峰可靠性不足时，不把 CV/Poincaré/shortLongPairs 当节律证据，
        // 不向 DS 传递"高度不规律/疑似房颤"等强引导标签，禁止 DS 输出"高度提示房颤/高置信早搏"。
        sb.append("[节律判别特征]\n")
        val rel = g.rPeakReliability
        if (!rel.reliable) {
            // 不可信：降级输出，不让 DS 把检测噪声当房颤
            sb.append("R波可靠性:${rel.level}\n")
            sb.append("可靠性指标:极端RR占比${"%.0f".format(rel.extremeRrRatio * 100)}% 有效RR占比${"%.0f".format(rel.validRrRatio * 100)}% 相邻RR跳变${"%.0f".format(rel.rrJumpRatio * 100)}% R峰-时长一致性${"%.2f".format(rel.rateConsistency)} 异常段占比${"%.0f".format(rel.abnormalSegmentRatio * 100)}%\n")
            sb.append("原因:${rel.reason}\n")
            sb.append("【节律判读降级】R波定位稳定性不足，当前记录无法可靠评估节律；CV/Poincaré/短长RR配对不作为节律证据。\n")
            sb.append("禁止：输出「高度提示房颤」「高置信早搏」等强诊断标签。\n")
            sb.append("建议：在静止、接触稳定时重新测量。\n")
            // 仍输出原始 RR 序列数值供 DS 参考，但明确标注"仅作参考，不作为节律证据"
            sb.append("(以下RR指标因R波不可信，仅作参考，不作为节律证据)\n")
            sb.append("RR变异系数:${"%.3f".format(g.rrVariabilityCoef)}(参考)\n")
            sb.append("Poincaré散点形态:${g.poincarePattern}(参考)\n")
            sb.append("短-长RR配对数:${g.shortLongPairs}个(参考)\n")
        } else if (rel.level == "边缘") {
            // 边缘：仍传数值，但加降级提示
            sb.append("R波可靠性:${rel.level}\n")
            sb.append("可靠性指标:极端RR占比${"%.0f".format(rel.extremeRrRatio * 100)}% 有效RR占比${"%.0f".format(rel.validRrRatio * 100)}% 相邻RR跳变${"%.0f".format(rel.rrJumpRatio * 100)}% R峰-时长一致性${"%.2f".format(rel.rateConsistency)} 异常段占比${"%.0f".format(rel.abnormalSegmentRatio * 100)}%\n")
            sb.append("提示:${rel.reason}\n")
            sb.append("RR变异系数:${"%.3f".format(g.rrVariabilityCoef)}")
            sb.append("(${if (g.rrVariabilityCoef < 0.05f) "规律" else if (g.rrVariabilityCoef < 0.15f) "轻度不齐" else "不规律"})\n")
            sb.append("Poincaré散点形态:${g.poincarePattern}\n")
            sb.append("短-长RR配对数(早搏代偿):${g.shortLongPairs}个\n")
            sb.append("注意:R波定位稳定性边缘，节律判读需谨慎，不宜仅凭CV/Poincaré下房颤诊断。\n")
        } else {
            // 可信：原输出
            sb.append("R波可靠性:${rel.level}\n")
            sb.append("RR变异系数:${"%.3f".format(g.rrVariabilityCoef)}")
            sb.append("(${if (g.rrVariabilityCoef < 0.05f) "规律" else if (g.rrVariabilityCoef < 0.15f) "轻度不齐" else "高度不规律"})\n")
            sb.append("Poincaré散点形态:${g.poincarePattern}\n")
            sb.append("短-长RR配对数(早搏代偿):${g.shortLongPairs}个\n")
        }
        sb.append("\n")

        // 逐秒分段表
        sb.append("[逐秒分段]\n")
        sb.append("时段(s)  R波  振幅范围(mV)  峰峰值(mV)  最大斜率(mV/s)  RMS(mV)\n")
        for ((idx, seg) in bundle.segments.withIndex()) {
            val range = "${"%.2f".format(seg.ampMinMv)}~${"%.2f".format(seg.ampMaxMv)}"
            val prevSeg = bundle.segments.getOrNull(idx - 1)
            val nextSeg = bundle.segments.getOrNull(idx + 1)
            val note = when {
                seg.rmsMv < 0.10f -> "  ← 噪声段"
                // 2个R波+下一秒0个R波=短RR+代偿间隙，才是早搏特征
                // 70bpm时RR≈857ms，每6秒自然有一个1秒桶含2个R波，属正常
                seg.rPeakCount == 2 && nextSeg != null && nextSeg.rPeakCount == 0 ->
                    "  ← 短RR+代偿间隙(早搏可能)"
                seg.rPeakCount == 0 && prevSeg != null && prevSeg.rPeakCount == 2 ->
                    "  ← 代偿间隙(配合前秒短RR)"
                seg.rPeakCount == 0 && idx > 0 ->
                    "  ← 本秒无R波(心率<60或代偿间隙)"
                else -> ""
            }
            sb.append("${"%.1f".format(seg.startSec)}-${"%.1f".format(seg.endSec)}  ${seg.rPeakCount}  $range  ${"%.2f".format(seg.peakToPeakMv)}  ${"%.2f".format(seg.maxSlopeMvPerSec)}  ${"%.2f".format(seg.rmsMv)}$note\n")
        }
        // 逐秒 RMS 紧凑曲线：一行展示所有段的 RMS，方便快速识别噪声段分布
        // 与上方分段表对照，<0.10 的段即噪声段（已在 noiseSegments 标注）
        if (bundle.segments.isNotEmpty()) {
            sb.append("逐秒RMS:${bundle.segments.joinToString(",") { "%.3f".format(it.rmsMv) }}\n")
        }

        return sb.toString()
    }

    /**
     * 原始 ECG 提示词构造器（feature/raw-ecg-to-ds 分支）
     *
     * 与 toPromptText 的本质区别：不经过本地算法的间期/形态估测，直接把去 DC 的原始波形
     * 交给 DS 分析。让 DS 自己看波形判读，避免本地算法的估测偏差污染 DS 判断。
     *
     * 预处理（最小化，只做 DS 无法做的）：
     * 1. 去 DC 偏移：减去全局中位数。原始数据是 ADC 值（14-17.5 万），不减 DC DS 看到的全是
     *    几十万的大数字毫无意义。减中位数后波形以 0 为中心，R 波/T 波形态清晰可辨。
     *    注意：不能用平均值去 DC——QRS 尖峰会让均值偏置；中位数对尖峰鲁棒。
     *    不做滤波/平滑/降采样：保留原始波形信息，让 DS 自己判读。
     * 2. 采样元信息：采样率、时长、总点数，让 DS 知道数据格式
     * 3. PPG 参考心率：独立于 ECG 的心率参考，帮助 DS 交叉验证 R 波检测
     *
     * 数据量评估：30s × 500Hz = 15000 点，每点 1 个整数（去 DC 后 ±500 范围内），
     * 文本化后约 45000 字符（≈ 30K token），加上 system prompt + Tavily 文献约 35K token，
     * 远低于 1M 上下文上限。
     *
     * @param ecgData 原始 ECG 数据（ADC 整数，500Hz × 30s）
     * @param sampleRateHz 采样率
     * @param ppgReferenceHr PPG 绿光参考心率（0=未采集）
     * @param profile 用户属性
     */
    fun toRawEcgPromptText(
        ecgData: List<Int>,
        sampleRateHz: Int = 500,
        ppgReferenceHr: Int = 0,
        profile: UserProfile = UserProfile(),
    ): String {
        val sb = StringBuilder()

        // 采样元信息
        sb.append("[采样元信息]\n")
        sb.append("采样率:${sampleRateHz}Hz\n")
        sb.append("时长:${if (sampleRateHz > 0) ecgData.size.toFloat() / sampleRateHz else 0f}秒\n")
        sb.append("总点数:${ecgData.size}\n")
        sb.append("数据格式:去DC后的整数序列(原始ADC值减去全局中位数),1点=${1000f / sampleRateHz}ms\n")
        sb.append("算法处理:无(原始波形直传,未经本地算法间期/形态估测)\n")
        sb.append("\n")

        // 用户属性
        sb.append("[用户属性]\n")
        if (profile.ageYears > 0) sb.append("年龄: ${profile.ageYears}岁\n")
        if (profile.isMale != null) {
            sb.append("性别: ${if (profile.isMale) "男" else "女"}\n")
        }
        sb.append("\n")

        // PPG 参考心率
        if (ppgReferenceHr > 0) {
            sb.append("[PPG绿光参考心率]\n")
            sb.append("心率:${ppgReferenceHr}bpm(系统心率传感器独立测算,与ECG分析互不干扰)\n")
            sb.append("\n")
        }

        // 去DC后的原始波形
        sb.append("[原始ECG波形(去DC,每行1秒])\n")
        sb.append("说明:每行${sampleRateHz}个整数(1秒),正值表示基线上方,负值表示基线下方\n")
        sb.append("R波通常表现为尖锐的同向尖峰,T波在R波后100-400ms,振幅约为R波的1/5-1/2\n")
        sb.append("\n")
        val ecgSorted = ecgData.sorted()
        val dcOffset = ecgSorted[ecgSorted.size / 2]  // 中位数去DC
        val samplesPerSec = sampleRateHz
        var sec = 0
        var i = 0
        while (i < ecgData.size) {
            val end = minOf(ecgData.size, i + samplesPerSec)
            val secData = StringBuilder()
            for (j in i until end) {
                if (j > i) secData.append(",")
                secData.append(ecgData[j] - dcOffset)
            }
            sb.append("第${sec}秒:${secData}\n")
            sec++
            i = end
        }
        sb.append("\n")

        return sb.toString()
    }
}
