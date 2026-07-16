package com.wearhealth.companion.shared

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出算法诊断包（纯文本）：把一次测量的三段对照数据合成一份文本，
 * 方便用户复制贴给助手，由助手精确定位是原始数据/本地算法/AI 哪一步出错。
 *
 * 三段：
 * 1. 原始 ECG 数据：mV×1000 整数数组，全量不降采样（30s×500Hz=15000 个点）
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
        if (transfer.tavilyStatus.isNotEmpty()) {
            sb.append("Tavily状态:${transfer.tavilyStatus}\n")
        }
        if (transfer.ppgReferenceHr > 0) {
            sb.append("PPG参考心率:${transfer.ppgReferenceHr} bpm\n")
        }
        sb.append("\n")

        // ===== 第 1 段：原始 ECG 数据 =====
        sb.append("[原始 ECG 数据]\n")
        sb.append("# 单位: mV×1000 整数（如 500 = 0.5mV，-1000 = -1.0mV）\n")
        sb.append("# 采样率: ${transfer.sampleRate} Hz，连续采样，无降采样无压缩\n")
        sb.append("# 用途: 助手据此复现本地算法行为（基线漂移/噪声段/R波检测）\n")
        sb.append("# 格式: 每行一个数值，行号=采样点索引（第1行=索引0，即 t=0.000s）\n")
        for (value in transfer.rawEcgData) {
            sb.append(value)
            sb.append("\n")
        }
        sb.append("\n")

        // ===== 第 2 段：算法提取后的结构化特征 =====
        sb.append("[算法提取后的结构化特征]\n")
        sb.append("# 由 EcgFeatureExtractor.extract() + toPromptText() 在手机端重建\n")
        sb.append("# 与手表端发给 DeepSeek 的提示词文本一致（DS 实际收到的数据）\n")
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
