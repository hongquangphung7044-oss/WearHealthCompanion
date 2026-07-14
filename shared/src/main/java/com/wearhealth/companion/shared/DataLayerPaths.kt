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

    /** 手机 → 手表：请求同步未传送数据。完整路径: /sync_request */
    const val PATH_SYNC_REQUEST = "/sync_request"

    /** 手机 → 手表：Room 持久化成功回执。完整路径: /ecg_ack/{timestamp} */
    const val PATH_ECG_ACK = "/ecg_ack"

    // ===== DataMap 键名 =====

    // ECG 测量数据字段
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_DIAGNOSIS = "diagnosis"        // 逗号分隔的诊断标签
    const val KEY_AVG_HR = "avgHr"
    const val KEY_MIN_HR = "minHr"
    const val KEY_MAX_HR = "maxHr"
    const val KEY_SIGNAL_QUALITY = "sq"
    const val KEY_IS_ABNORMAL = "ab"
    const val KEY_AVG_QRS = "qrs"
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

    // API Key 字段
    const val KEY_API_KEY = "apiKey"
}
