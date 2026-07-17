package com.wearhealth.companion.shared

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出算法诊断包（纯文本）：把一次测量的三段对照数据合成一份文本，
 * 方便用户复制贴给助手，由助手精确定位是原始数据/本地算法/AI 哪一步出错。
 *
 * 三段：
 * 1. 原始 ECG 数据：手表 SDK 返回的 ADC 原始整数值（约 14-17.5 万，含 DC 偏移），
 *    全量不降采样（30s×500Hz=15000 个点）。注意不是 mV×1000——R 波振幅在 ADC 中
 *    表现为相对基线的偏移（±100-500 范围），不是绝对值。EcgFeatureExtractor 在
 *    所有计算前先去 DC（减基线/中位数/滑动均值），所以算法工作在相对值上正确。
 * 2. 算法提取后的结构化特征：在手机端用 [EcgFeatureExtractor.extract] + [EcgFeatureExtractor.toPromptText]
 *    重建（与手表端发给 DS 的提示词文本一致）
 * 3. AI 给出的解读：[EcgMeasurementTransfer.aiReport] 原始 JSON
 *
 * 注意：transfer 不存用户 profile（年龄性别），手机端重建特征时用默认 profile（年龄0/性别null），
 * 导出文本明确标注"未知"。助手分析时如需年龄性别，需用户单独提供。
 *
 * 纯函数设计：[buildText] 不依赖 Android API，可在 CI JVM 单元测试。
 * Android 端写入 Downloads 的逻辑在 mobile 模块（不可单测，靠集成验证）。
 */
object DiagnosticExporter {

    /** 文件显示名（不含扩展名） */
    fun displayName(timestamp: Long): String {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
        return "ECG_diagnostic_$time.txt"
    }

    /**
     * 生成完整的诊断包纯文本。
     *
     * @param transfer 已加载的完整测量数据（含 rawEcgData / aiReport / 统计字段）
     * @return 三段对照文本，可直接写入 .txt 文件
     */
    fun buildText(transfer: EcgMeasurementTransfer): String {
        val sb = StringBuilder()

        // ===== 元信息头 =====
        sb.append("========== ECG 算法诊断包 ==========\n")
        val measuredAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(transfer.timestamp))
        sb.append("测量时间: $measuredAt\n")
        sb.append("测量时间戳:${transfer.timestamp}\n")
        sb.append("采样率:${transfer.sampleRate} Hz\n")
        sb.append("数据点数:${transfer.rawEcgData.size}\n")
        val durationSec = if (transfer.sampleRate > 0) {
            transfer.rawEcgData.size.toDouble() / transfer.sampleRate
        } else 0.0
        sb.append("时长:${"%.1f".format(durationSec)} 秒\n")
        sb.append("分析方法:${transfer.analysisMethod}\n")
        // 算法版本号自动注入：引用 EcgFeatureExtractor.ALGORITHM_VERSION，升级算法时改一处即可
        // heartvoice 路径走 HeartVoice API 不用本地算法，但导出仍标注版本，方便横向对照
        // （[算法提取后的结构化特征] 段会重建本地算法结果，版本号在此有意义）
        sb.append("算法版本:${EcgFeatureExtractor.ALGORITHM_VERSION}\n")
        if (transfer.tavilyStatus.isNotEmpty()) {
            sb.append("Tavily状态:${transfer.tavilyStatus}\n")
        }
        if (transfer.ppgReferenceHr > 0) {
            sb.append("PPG参考心率:${transfer.ppgReferenceHr} bpm\n")
        }
        sb.append("\n")

        // ===== 第 1 段：原始 ECG 数据 =====
        // 数据单位说明（重要）：手表 SDK 返回的是 ADC 原始整数值（约 14-17.5 万），不是 mV×1000。
        // 真实 R 波振幅在 ADC 中表现为"相对基线的偏移"（±100-500 范围），不是绝对值。
        // 助手复现算法时必须先去 DC（减中位数/滑动窗口均值），不能直接把这些数字当 mV 解读。
        // EcgFeatureExtractor 在所有计算前先做去 DC（detectRPeaks 减 1 秒窗口均值，
        // toRawEcgPromptText 用 detrendBySlidingWindow 2 秒窗口），所以算法工作在相对值上正确。
        sb.append("[原始 ECG 数据]\n")
        sb.append("# 单位: ADC 原始整数值（手表 Samsung ECG SDK 返回值，约 14-17.5 万，含 DC 偏移）\n")
        sb.append("# 重要: 不是 mV×1000！R 波振幅在 ADC 中表现为相对基线的偏移（±100-500），\n")
        sb.append("#       不是绝对值。助手复现算法时必须先去 DC（减中位数/滑动窗口均值），\n")
        sb.append("#       不能直接把这些数字当 mV 解读。\n")
        sb.append("# 采样率: ${transfer.sampleRate} Hz，连续采样，无降采样无压缩\n")
        sb.append("# 用途: 助手据此复现本地算法行为（基线漂移/噪声段/R波检测）\n")
        sb.append("# 格式: 每行一个数值，行号=采样点索引（第1行=索引0，即 t=0.000s）\n")
        sb.append("# 增益校准: 应用层未做 ADC→mV 转换；如需 mV，需自行用基线/中位数去 DC 后\n")
        sb.append("#           视为相对振幅（无绝对 mV 标定，因手表未公布增益系数）\n")
        for (value in transfer.rawEcgData) {
            sb.append(value)
            sb.append("\n")
        }
        sb.append("\n")

        // ===== 第 1.5 段：HeartVoice API 返回（仅 heartvoice 路径） =====
        // Bug 修复：旧实现不管 analysisMethod 都重建本地特征，HeartVoice 路径本地算法在真实
        // wrist ECG 上可能返回 0 个 R 波 → 导出 [全局指标] 全 0，但 HeartVoice API 实际返回
        // 了有效数据。修复：HeartVoice 路径加专门的 [HeartVoice API 返回] 段，输出 transfer
        // 已有的统计字段（API 解析后填入的），与本地算法段对照，方便定位差异。
        // DS 路径不加此段（避免误导，DS 路径无 API 返回）。
        if (transfer.analysisMethod == "heartvoice") {
            sb.append("[HeartVoice API 返回]\n")
            sb.append("# HeartVoice 专业 API 返回的聚合指标（不返回原始波形分析数据）\n")
            sb.append("# 与下方 [算法提取后的结构化特征] 对照可定位本地算法差异\n")
            sb.append("平均心率:${transfer.avgHeartRate}\n")
            sb.append("最低心率:${transfer.minHeartRate}\n")
            sb.append("最高心率:${transfer.maxHeartRate}\n")
            sb.append("诊断:${transfer.diagnosis.joinToString(",")}\n")
            if (transfer.possibleDiagnoses.isNotEmpty()) {
                sb.append("可能诊断:${transfer.possibleDiagnoses.joinToString(",")}\n")
            }
            sb.append("QRS宽度:${transfer.avgQrs}\n")
            if (transfer.avgP > 0) {
                sb.append("P波宽度:${transfer.avgP}\n")
            }
            sb.append("PR间期:${transfer.prInterval}\n")
            sb.append("QT间期:${transfer.avgQt}\n")
            sb.append("QTc:${transfer.avgQtc}\n")
            sb.append("房性早搏:${transfer.pacCount}\n")
            sb.append("室性早搏:${transfer.pvcCount}\n")
            sb.append("信号质量:${transfer.signalQuality}\n")
            sb.append("导联反接:${transfer.isReverse}\n")
            sb.append("是否异常:${transfer.isAbnormal}\n")
            sb.append("\n")
        }

        // ===== 第 2 段：算法提取后的结构化特征 =====
        // raw 模式虽不发给 DS，但导出时仍重建本地特征作为对照基线（方便以后算法优化对照）
        sb.append("[算法提取后的结构化特征]\n")
        sb.append("# 由 EcgFeatureExtractor.extract() + toPromptText() 在手机端重建\n")
        if (transfer.analysisMethod.endsWith("_raw") || transfer.analysisMethod == "ds_raw") {
            sb.append("# 注意: 本次测量为 raw 模式，本地算法未参与 DS 分析；此处重建仅作对照基线\n")
            sb.append("#       raw 模式实际发给 DS 的是去趋势原始波形（见下方 [raw 模式发给 DS 的波形]）\n")
        } else {
            sb.append("# 与手表端发给 DeepSeek 的提示词文本一致（DS 实际收到的数据）\n")
        }
        sb.append("# 注意: 用户 profile（年龄性别）未存入测量记录，手机端重建时为'未知'\n")
        sb.append("#       助手如需年龄性别做医学判断，需用户单独提供\n")
        sb.append("\n")
        // 用默认 profile 重建特征（年龄0/性别null=未知）
        // ppgReferenceHr 从 transfer 读取，保证 PPG 参考心率字段与测量时一致
        val bundle = EcgFeatureExtractor.extract(
            transfer.rawEcgData,
            sampleRateHz = transfer.sampleRate,
            profile = EcgFeatureExtractor.UserProfile(ageYears = 0, isMale = null),
            ppgReferenceHr = transfer.ppgReferenceHr,
        )
        sb.append(EcgFeatureExtractor.toPromptText(bundle))
        sb.append("\n")

        // ===== 第 3 段：AI 给出的解读 =====
        sb.append("[AI 解读]\n")
        sb.append("# DeepSeek 返回的原始 JSON（未清洗，可能含 markdown 代码块包裹）\n")
        if (transfer.aiReport.isBlank()) {
            sb.append("本次测量未使用 AI 分析（分析方法非 ds_*）\n")
        } else {
            sb.append(transfer.aiReport)
            sb.append("\n")
        }

        return sb.toString()
    }
}
