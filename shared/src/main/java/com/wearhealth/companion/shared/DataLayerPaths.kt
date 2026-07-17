package com.wearhealth.companion.shared

/**
 * Wearable Data Layer 路径常量
 *
 * 手表与手机之间所有通信的路径定义。
 * 修改路径时需同时确保手表和手机端的 WearableListenerService 同步更新。
 */
object DataLayerPaths {

    // ===== Capability names =====
    // Both APKs use the same applicationId and signing certificate. Capabilities identify
    // the compatible companion app/version among reachable Data Layer nodes.
    const val CAPABILITY_PHONE_SYNC = "wearhealth_phone_sync_v1"
    const val CAPABILITY_WATCH_SYNC = "wearhealth_watch_sync_v1"

    // ===== DataItem 路径 =====

    /** 手表 → 手机：发送 ECG 测量数据。完整路径: /ecg_measurement/{timestamp} */
    const val PATH_ECG_MEASUREMENT = "/ecg_measurement"

    /** 手机 → 手表：发送 API Key。完整路径: /api_key */
    const val PATH_API_KEY = "/api_key"

    /** 手机 → 手表：发送 DeepSeek 设置（Key + 默认模型 + 思考强度）。完整路径: /deepseek_settings */
    const val PATH_DEEPSEEK_SETTINGS = "/deepseek_settings"

    /** 手机 → 手表：发送 Tavily 搜索 API Key（供 DS 分析时联网检索医学文献）。完整路径: /tavily_settings */
    const val PATH_TAVILY_SETTINGS = "/tavily_settings"

    /** 手机 → 手表：请求同步未传送数据。完整路径: /sync_request */
    const val PATH_SYNC_REQUEST = "/sync_request"

    /** 手机 → 手表：Room 持久化成功回执。完整路径: /ecg_ack/{timestamp} */
    const val PATH_ECG_ACK = "/ecg_ack"

    // ===== DataMap 键名 =====

    // ECG 测量数据字段
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_DIAGNOSIS = "diagnosis"        // 逗号分隔的诊断标签
    const val KEY_POSSIBLE_DIAGNOSES = "possibleDiags"
    const val KEY_IS_REVERSE = "isReverse"
    const val KEY_AVG_HR = "avgHr"
    const val KEY_MIN_HR = "minHr"
    const val KEY_MAX_HR = "maxHr"
    const val KEY_SIGNAL_QUALITY = "sq"
    const val KEY_IS_ABNORMAL = "ab"
    const val KEY_AVG_QRS = "qrs"
    const val KEY_AVG_P = "avgP"
    const val KEY_PR_INTERVAL = "pr"
    const val KEY_AVG_QT = "qt"
    const val KEY_AVG_QTC = "qtc"
    const val KEY_PAC_COUNT = "pac"
    const val KEY_PVC_COUNT = "pvc"
    const val KEY_RAW_ECG = "rawEcg"             // 完整原始波形，ByteArray（二进制 int 数组）
    const val KEY_SAMPLE_RATE = "sampleRate"
    /** Non-secret per-submission value; makes an identical retry emit a new DataItem event. */
    const val KEY_TRANSFER_NONCE = "transferNonce"
    const val KEY_DOWNSAMPLED_ECG = "downsampledEcg"  // 降采样波形（用于列表缩略图）
    const val KEY_ANALYSIS_METHOD = "analysisMethod"   // 分析方式：heartvoice / ds_*
    const val KEY_AI_REPORT = "aiReport"               // DeepSeek JSON 报告
    const val KEY_TAVILY_STATUS = "tavilyStatus"       // Tavily 联网检索状态（DS 均衡/Max 档）
    const val KEY_PPG_HR = "ppgHr"                     // PPG 绿光参考心率
    const val KEY_PROCESSED_BY_ALGORITHM = "processedByAlgorithm"  // 是否经过本地算法处理（raw 模式为 false）

    // API Key 字段
    const val KEY_API_KEY = "apiKey"

    // DeepSeek 设置字段（手机 → 手表下发）
    const val KEY_DS_API_KEY = "dsApiKey"
    const val KEY_DS_DEFAULT_MODEL = "dsDefaultModel"       // FLASH / PRO
    const val KEY_DS_DEFAULT_THINKING = "dsDefaultThinking" // FAST / BALANCED / MAX
    const val KEY_DS_USER_AGE = "dsUserAge"                 // 用户年龄（0=未知）
    const val KEY_DS_USER_IS_MALE = "dsUserIsMale"          // 用户性别（null=未知用 false 占位 + 三态标志）
    const val KEY_DS_USER_GENDER_KNOWN = "dsUserGenderKnown" // 性别是否已知

    // Tavily 设置字段（手机 → 手表下发）
    const val KEY_TAVILY_API_KEY = "tavilyApiKey" // Tavily 搜索 API Key
}
