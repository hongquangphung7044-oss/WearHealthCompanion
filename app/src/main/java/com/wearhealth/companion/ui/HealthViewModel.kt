package com.wearhealth.companion.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.wearhealth.companion.ble.BleApiKeyFetcher
import com.wearhealth.companion.ble.BleEcgUploader
import com.wearhealth.companion.ble.BleUploadResult
import com.wearhealth.companion.data.ApiKeyManager
import com.wearhealth.companion.data.AutoSyncPreferences
import com.wearhealth.companion.data.DeepSeekSettings
import com.wearhealth.companion.data.EcgHistoryRepository
import com.wearhealth.companion.data.HistoryItem
import com.wearhealth.companion.model.EcgAnalysisResult
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.computeMinMaxHeartRate
import com.wearhealth.companion.model.localSignalQualityCheck
import com.wearhealth.companion.network.DeepSeekApiClient
import com.wearhealth.companion.network.HeartVoiceApiClient
import com.wearhealth.companion.sensor.EcgCollector
import com.wearhealth.companion.service.WatchWearableListenerService
import com.wearhealth.companion.shared.ApiKeyValidator
import com.wearhealth.companion.shared.DataLayerPaths
import com.wearhealth.companion.shared.EcgFeatureExtractor
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import com.wearhealth.companion.shared.MeasurementSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EcgUiState(
    val ecgState: EcgCollectionState = EcgCollectionState.Idle,
    val ecgResult: EcgAnalysisResult? = null,
    val sdkAvailable: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val liveSamples: List<Int> = emptyList(),    // 实时/最终 ECG 波形（用于 UI 绘制）
    val showHistory: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    val historyDetail: HistoryItem? = null,      // 点击进入的历史详情
    val syncingToPhone: Boolean = false,         // 是否正在传送到手机
    val syncMessage: String? = null,             // 传送状态消息
    val autoSyncEnabled: Boolean = true,           // 分析并保存后自动传送
    // 分析方式选择：heartvoice / ds_flash_fast / ds_flash_balanced（手表端只放 2 档 DS）
    val analysisMethod: String = "heartvoice",
    val dsApiKeyConfigured: Boolean = false,      // DeepSeek API Key 是否已配置
    val dsBalanceText: String? = null,           // DS 余额文本（如 "¥8.66"），null=未查询
    val dsBalanceLoading: Boolean = false,        // 正在查询余额
    val dsBalanceError: String? = null,          // 余额查询错误
    val showPreMeasureDialog: Boolean = false,   // 测量前年龄性别二级界面
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val ecgCollector = EcgCollector(app.applicationContext)
    private val apiKeyManager = ApiKeyManager(app.applicationContext)
    private var heartVoiceApi = HeartVoiceApiClient(apiKeyManager.getApiKey())
    private val historyRepo = EcgHistoryRepository(app.applicationContext)
    private val autoSyncPreferences = AutoSyncPreferences(app.applicationContext)
    private val bleUploader = BleEcgUploader(app.applicationContext)
    private val bleApiKeyFetcher = BleApiKeyFetcher(app.applicationContext)
    private val dsSettings = DeepSeekSettings(app.applicationContext)
    private var deepSeekApi = DeepSeekApiClient(dsSettings.getApiKey())

    private val _uiState = MutableStateFlow(EcgUiState())
    val uiState: StateFlow<EcgUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(
            sdkAvailable = ecgCollector.checkSdkAvailable(),
            apiKeyConfigured = apiKeyManager.isConfigured(),
            autoSyncEnabled = autoSyncPreferences.isEnabled(),
            dsApiKeyConfigured = dsSettings.isConfigured(),
        )
        // 监听 API Key 变更（手机端下发或本地保存后自动刷新）
        viewModelScope.launch {
            ApiKeyManager.refreshTrigger.collect {
                refreshApiKeyStatus()
            }
        }
        // SharedFlow has no initial event. The UI is updated only when the service has a real
        // submission result or when the phone sends a persistence ACK.
        viewModelScope.launch {
            WatchWearableListenerService.syncEvents.collect { message ->
                val records = historyRepo.getAll()
                _uiState.value = _uiState.value.copy(
                    history = records,
                    historyDetail = _uiState.value.historyDetail?.let { detail ->
                        records.find { it.timestamp == detail.timestamp } ?: detail
                    },
                    syncMessage = message,
                )
            }
        }
    }

    fun startEcgMeasurement(method: String = "heartvoice") {
        val current = _uiState.value.ecgState
        if (current is EcgCollectionState.Connecting ||
            current is EcgCollectionState.Collecting ||
            current is EcgCollectionState.Analyzing
        ) return

        viewModelScope.launch {
            // 同步实时状态到 UI
            val stateJob = launch {
                ecgCollector.state.collect { state ->
                    _uiState.value = _uiState.value.copy(ecgState = state)
                }
            }
            val sampleJob = launch {
                ecgCollector.liveSamples.collect { samples ->
                    _uiState.value = _uiState.value.copy(liveSamples = samples)
                }
            }

            val ecgData = ecgCollector.startEcgCollection(targetDurationSec = 30)

            stateJob.cancel()
            sampleJob.cancel()

            if (ecgData.isEmpty()) return@launch

            // 本地信号质量预检（不发 API，节省用量）
            val qualityError = localSignalQualityCheck(ecgData, sampleRateHz = 500)
            if (qualityError != null) {
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Error(qualityError)
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                ecgState = EcgCollectionState.Analyzing,
            )

            // 按分析方式分流
            val result = if (method.startsWith("ds_")) {
                analyzeWithDeepSeek(ecgData, method)
            } else {
                analyzeWithHeartVoice(ecgData)
            }

            result.onSuccess { finalResult ->
                // 保存到历史记录（同时保存完整原始波形 rawEcgData，不降采样）
                val savedItem = historyRepo.save(finalResult, rawEcgData = ecgData)
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Done(finalResult),
                    ecgResult = finalResult,
                    liveSamples = finalResult.ecgSamples,
                    history = historyRepo.getAll(),
                    syncMessage = if (autoSyncPreferences.isEnabled()) {
                        "分析完成并已保存；正在自动传送到手机…"
                    } else null,
                )
                if (autoSyncPreferences.isEnabled()) {
                    syncToPhone(savedItem, automatic = true)
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Error(error.message ?: "API 分析失败")
                )
            }
        }
    }

    /**
     * HeartVoice 专业 API 分析（原有流程）
     */
    private suspend fun analyzeWithHeartVoice(ecgData: List<Int>): Result<EcgAnalysisResult> {
        val result = heartVoiceApi.analyzeEcg(ecgData, sampleRate = 500)
        return result.map { analysis ->
            val waveform = downsample(ecgData, 400)
            val (minHr, maxHr) = computeMinMaxHeartRate(ecgData, sampleRateHz = 500)
            val filteredDiagnosis = filterWpwIfConflict(
                analysis.diagnosis, analysis.prInterval, analysis.avgQrs,
            )
            analysis.copy(
                ecgSamples = waveform,
                minHeartRate = minHr,
                maxHeartRate = maxHr,
                diagnosis = filteredDiagnosis,
                isAbnormal = analysis.isAbnormal,
                analysisMethod = "heartvoice",
            )
        }
    }

    /**
     * DeepSeek 分析流程：
     * 1. 用 EcgFeatureExtractor 本地提取统计特征（含 HRV + 间期估测）
     * 2. 调 DeepSeek API 生成 JSON 报告
     * 3. 把本地估算参数 + DS 报告合并到 EcgAnalysisResult
     *
     * [method] 取值：ds_flash_fast / ds_flash_balanced / ds_pro_max
     */
    private suspend fun analyzeWithDeepSeek(
        ecgData: List<Int>,
        method: String,
    ): Result<EcgAnalysisResult> {
        // 1. 提取本地特征（含用户年龄性别）
        val profile = EcgFeatureExtractor.UserProfile(
            ageYears = dsSettings.getUserAge(),
            isMale = dsSettings.getUserIsMale(),
        )
        val bundle = EcgFeatureExtractor.extract(ecgData, sampleRateHz = 500, profile = profile)
        val featureText = EcgFeatureExtractor.toPromptText(bundle)
        val g = bundle.global

        // 2. 解析 method → DeepSeekApiClient.Model + ThinkingMode
        val (model, thinking) = when (method) {
            "ds_flash_fast" -> DeepSeekApiClient.Model.FLASH to DeepSeekApiClient.ThinkingMode.FAST
            "ds_flash_balanced" -> DeepSeekApiClient.Model.FLASH to DeepSeekApiClient.ThinkingMode.BALANCED
            "ds_pro_max" -> DeepSeekApiClient.Model.PRO to DeepSeekApiClient.ThinkingMode.MAX
            else -> DeepSeekApiClient.Model.FLASH to DeepSeekApiClient.ThinkingMode.BALANCED
        }

        // 3. 调 DS
        val dsResult = deepSeekApi.analyzeEcg(featureText, model, thinking)
        return dsResult.mapCatching { report ->
            val waveform = downsample(ecgData, 400)

            // 4. DS 报告里的 JSON 字段尝试提取节律判断作为诊断标签（兜底用本地估算）
            val dsDiagnosis = extractDsDiagnosis(report.reportJson, g)

            EcgAnalysisResult(
                isAbnormal = dsDiagnosis.any { it !in listOf("SN", "SNT", "SNB") },
                signalQuality = g.signalQuality.toDouble(),
                diagnosis = dsDiagnosis,
                possibleDiagnoses = emptyList(),
                isReverse = false,
                avgHeartRate = g.avgHeartRate,
                minHeartRate = g.minHeartRate,
                maxHeartRate = g.maxHeartRate,
                avgQrs = g.qrsWidthMs,
                avgP = 0,
                prInterval = g.prIntervalMs,
                avgQt = g.qtIntervalMs,
                avgQtc = g.qtcMs,
                pacCount = 0,
                pvcCount = 0,
                rawData = "",  // DS 模式不保留原始 API 响应
                ecgSamples = waveform,
                analysisMethod = method,
                aiReport = report.reportJson,
            )
        }
    }

    /**
     * 从 DS JSON 报告里提取诊断标签
     *
     * 解析"节律分析.节律判断"字段，映射到项目诊断标签：
     * - 窦性心律 → SN
     * - 窦性心动过速 → SNT
     * - 窦性心动过缓 → SNB
     * - 房颤 → AF
     * - 房扑 → AFL
     * - 早搏 → PAC（本设备无法区分 PAC/PVC，统一标 PAC）
     * 解析失败时用本地特征兜底（RR 变异系数 > 0.15 标"疑似心律不齐"）
     */
    private fun extractDsDiagnosis(
        reportJson: String,
        g: EcgFeatureExtractor.GlobalFeatures,
    ): List<String> {
        return try {
            val json = org.json.JSONObject(reportJson)
            val rhythm = json.optJSONObject("节律分析")
                ?.optString("节律判断", "")
                ?: ""
            val labels = mutableListOf<String>()
            when {
                rhythm.contains("房颤") -> labels.add("AF")
                rhythm.contains("房扑") -> labels.add("AFL")
                rhythm.contains("心动过速") -> labels.add("SNT")
                rhythm.contains("心动过缓") -> labels.add("SNB")
                rhythm.contains("不齐") && !rhythm.contains("窦性") -> labels.add("ARR")
                rhythm.contains("窦性") -> labels.add("SN")
                else -> labels.add("SN")
            }
            // 早搏：DS 报告的"早搏提示"数组非空则标 PAC
            val earlyBeats = json.optJSONObject("节律分析")?.optJSONArray("早搏提示")
            if (earlyBeats != null && earlyBeats.length() > 0) {
                labels.add("PAC")
            }
            // 兜底：RR 变异系数 > 0.15 且未标 AF → 标疑似心律不齐
            if (g.rrIntervalsMs.size > 3 && g.avgHeartRate > 0) {
                val mean = g.rrIntervalsMs.average()
                val std = kotlin.math.sqrt(
                    g.rrIntervalsMs.map { (it - mean) * (it - mean) }.average()
                )
                val cv = std / mean
                if (cv > 0.15 && !labels.contains("AF") && !labels.contains("ARR")) {
                    labels.add("ARR")
                }
            }
            labels.ifEmpty { listOf("SN") }
        } catch (e: Exception) {
            Log.w(TAG, "解析 DS 报告诊断失败，用本地特征兜底", e)
            // 兜底：根据 RR 变异系数判断
            if (g.rrIntervalsMs.size > 3 && g.avgHeartRate > 0) {
                val mean = g.rrIntervalsMs.average()
                val std = kotlin.math.sqrt(
                    g.rrIntervalsMs.map { (it - mean) * (it - mean) }.average()
                )
                if (std / mean > 0.15) listOf("ARR") else listOf("SN")
            } else {
                listOf("SN")
            }
        }
    }

    // ========== API Key 管理 ==========

    /**
     * 保存 API Key（用户在手表端输入或由手机下发后调用）
     *
     * 保存前进行 NUL/控制字符校验；校验失败显示中文错误，不保存也不重建客户端。
     * 保存后会重建 HeartVoiceApiClient 并刷新 UI 状态。
     */
    fun saveApiKey(key: String) {
        val result = ApiKeyValidator.normalizeApiKey(key)
        val normalized = result.getOrNull()
        if (normalized == null) {
            _uiState.value = _uiState.value.copy(
                syncMessage = result.exceptionOrNull()?.message ?: "API Key 无效"
            )
            return
        }
        apiKeyManager.saveApiKey(normalized)
        // 用新的 API Key 重建客户端
        heartVoiceApi = HeartVoiceApiClient(normalized)
        _uiState.value = _uiState.value.copy(
            apiKeyConfigured = true,
            syncMessage = "API Key 已保存",
        )
    }

    /** Fetch a key from the paired phone over direct BLE; no ECG history is required. */
    fun fetchApiKeyFromPhone() {
        if (_uiState.value.syncingToPhone) return
        _uiState.value = _uiState.value.copy(
            syncingToPhone = true,
            syncMessage = "正在通过 BLE 查找手机上的 API Key…",
        )
        bleApiKeyFetcher.fetch { result ->
            viewModelScope.launch {
                result.onSuccess { key ->
                    apiKeyManager.saveApiKey(key)
                    heartVoiceApi = HeartVoiceApiClient(key)
                    _uiState.value = _uiState.value.copy(
                        apiKeyConfigured = true,
                        syncingToPhone = false,
                        syncMessage = "已从手机获取并保存 API Key ✓",
                    )
                }.onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        syncingToPhone = false,
                        syncMessage = "BLE 获取 Key 失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /**
     * 刷新 API Key 配置状态
     *
     * 在 onResume 或收到手机下发的新 Key 时调用。
     */
    fun refreshApiKeyStatus() {
        val apiKey = apiKeyManager.getApiKey()
        // 用最新的 API Key 重建客户端
        heartVoiceApi = HeartVoiceApiClient(apiKey)
        _uiState.value = _uiState.value.copy(
            apiKeyConfigured = apiKeyManager.isConfigured(),
        )
    }

    // ========== DeepSeek 设置管理 ==========

    /** 保存 DeepSeek API Key */
    fun saveDeepSeekApiKey(key: String) {
        dsSettings.saveApiKey(key)
        deepSeekApi = DeepSeekApiClient(key)
        _uiState.value = _uiState.value.copy(
            dsApiKeyConfigured = true,
            syncMessage = "DeepSeek API Key 已保存",
        )
    }

    /** 刷新 DS 设置状态（收到手机下发后调用） */
    fun refreshDeepSeekSettings() {
        deepSeekApi = DeepSeekApiClient(dsSettings.getApiKey())
        _uiState.value = _uiState.value.copy(
            dsApiKeyConfigured = dsSettings.isConfigured(),
        )
    }

    /** 设置分析方式选择 */
    fun setAnalysisMethod(method: String) {
        _uiState.value = _uiState.value.copy(analysisMethod = method)
    }

    /** 保存用户年龄 */
    fun saveUserAge(age: Int) {
        dsSettings.setUserAge(age)
    }

    /** 保存用户性别 */
    fun saveUserGender(isMale: Boolean?) {
        dsSettings.setUserIsMale(isMale)
    }

    /** 显示/隐藏测量前年龄性别二级界面 */
    fun setShowPreMeasureDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showPreMeasureDialog = show)
    }

    /** 查询 DeepSeek 账户余额 */
    fun queryDeepSeekBalance() {
        if (_uiState.value.dsBalanceLoading) return
        if (!dsSettings.isConfigured()) {
            _uiState.value = _uiState.value.copy(
                dsBalanceError = "未配置 DeepSeek API Key",
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            dsBalanceLoading = true,
            dsBalanceError = null,
        )
        viewModelScope.launch {
            val result = deepSeekApi.queryBalance()
            result.onSuccess { balance ->
                val symbol = if (balance.currency == "CNY") "¥" else "$"
                _uiState.value = _uiState.value.copy(
                    dsBalanceLoading = false,
                    dsBalanceText = "$symbol${balance.totalBalance}",
                    dsBalanceError = null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    dsBalanceLoading = false,
                    dsBalanceError = error.message ?: "余额查询失败",
                )
            }
        }
    }

    // ========== 传送到手机：Google Data Layer 优先，BLE GATT 回退 ==========

    /**
     * Sends a complete ECG record. Google Data Layer is used where it is available; China ROMs
     * that cannot discover a GMS node automatically fall back to the phone's direct BLE GATT
     * synchronizer. Both transports require a persistence ACK before [HistoryItem.syncedToPhone]
     * can become true.
     */
    fun syncToPhone(item: HistoryItem) = syncToPhone(item, automatic = false)

    private fun syncToPhone(item: HistoryItem, automatic: Boolean) {
        if (_uiState.value.syncingToPhone) return
        _uiState.value = _uiState.value.copy(
            syncingToPhone = true,
            syncMessage = if (automatic) "分析完成并已保存；正在自动检测手机同步通道…" else "正在检测手机同步通道…",
        )

        viewModelScope.launch {
            val transfer = item.toTransfer()
            val dataLayerSubmitted = try {
                val phoneNodes = withContext(Dispatchers.IO) {
                    Tasks.await(
                        Wearable.getCapabilityClient(getApplication()).getCapability(
                            DataLayerPaths.CAPABILITY_PHONE_SYNC,
                            CapabilityClient.FILTER_REACHABLE,
                        ),
                    ).nodes
                }
                if (phoneNodes.isEmpty()) false else {
                    val request = PutDataMapRequest.create(
                        "${DataLayerPaths.PATH_ECG_MEASUREMENT}/${item.timestamp}",
                    ).apply { dataMap.putAll(MeasurementSerializer.toDataMap(transfer)) }
                        .asPutDataRequest()
                        .setUrgent()
                    withContext(Dispatchers.IO) {
                        Tasks.await(Wearable.getDataClient(getApplication()).putDataItem(request))
                    }
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google Data Layer 不可用，改用 BLE", e)
                false
            }

            if (dataLayerSubmitted) {
                _uiState.value = _uiState.value.copy(
                    syncMessage = "Data Layer 已提交；最多等待 8 秒确认手机 Room 已保存…",
                )
                if (waitForPersistenceAck(item.timestamp)) {
                    refreshHistoryAfterSync(
                        if (automatic) "已自动保存到手机 ✓" else "手机已通过 Data Layer 保存 ECG ✓",
                    )
                    return@launch
                }
                // putDataItem() only confirms the local Google cache write. China ROMs can expose
                // a stale/reachable capability while never delivering the DataItem to the phone.
                // Fall through to BLE unless the matching Room ACK actually marked this record.
                _uiState.value = _uiState.value.copy(
                    syncMessage = "Data Layer 未收到手机保存确认，正在自动改用 BLE…",
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    syncMessage = "未发现 GMS 通道，正在搜索手机 BLE 同步器…",
                )
            }

            bleUploader.upload(transfer) { result ->
                viewModelScope.launch {
                    when (result) {
                        BleUploadResult.Success -> {
                            historyRepo.markSynced(item.timestamp)
                            refreshHistoryAfterSync(
                                if (automatic) "已自动保存到手机 ✓" else "手机已通过 BLE 保存 ECG ✓",
                            )
                        }
                        is BleUploadResult.Failure -> {
                            _uiState.value = _uiState.value.copy(
                                syncingToPhone = false,
                                syncMessage = if (automatic) {
                                    "自动传输失败，可在历史详情重试：${result.message}"
                                } else {
                                    "BLE 传送失败：${result.message}"
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Waits for the listener service to persist the matching Data Layer ACK into history. */
    private suspend fun waitForPersistenceAck(timestamp: Long): Boolean {
        repeat(DATA_LAYER_ACK_POLL_COUNT) {
            val acknowledged = historyRepo.getAll().any {
                it.timestamp == timestamp && it.syncedToPhone
            }
            if (acknowledged) return true
            delay(DATA_LAYER_ACK_POLL_MS)
        }
        return false
    }

    private fun HistoryItem.toTransfer() = EcgMeasurementTransfer(
        timestamp = timestamp,
        diagnosis = diagnosis,
        possibleDiagnoses = possibleDiagnoses,
        isReverse = isReverse,
        avgHeartRate = avgHeartRate,
        minHeartRate = minHeartRate,
        maxHeartRate = maxHeartRate,
        signalQuality = signalQuality,
        isAbnormal = isAbnormal,
        avgQrs = avgQrs,
        avgP = avgP,
        prInterval = prInterval,
        avgQt = avgQt,
        avgQtc = avgQtc,
        pacCount = pacCount,
        pvcCount = pvcCount,
        rawEcgData = rawEcgData,
        downsampledEcg = ecgSamples,
        sampleRate = 500,
        analysisMethod = analysisMethod,
        aiReport = aiReport,
    )

    private fun refreshHistoryAfterSync(message: String) {
        val records = historyRepo.getAll()
        _uiState.value = _uiState.value.copy(
            syncingToPhone = false,
            syncMessage = message,
            history = records,
            historyDetail = _uiState.value.historyDetail?.let { detail ->
                records.find { it.timestamp == detail.timestamp } ?: detail
            },
        )
    }

    /**
     * Data Layer initiated batch sync is retained for GMS-capable environments. Direct BLE is
     * remains a one-record-at-a-time BLE operation; the phone foreground service keeps the
     * advertiser available while background synchronization is enabled.
     */
    fun syncAllUnsynced() {
        val unsynced = historyRepo.getUnsynced()
        if (unsynced.isEmpty()) {
            _uiState.value = _uiState.value.copy(syncMessage = "没有未传送的记录")
            return
        }

        _uiState.value = _uiState.value.copy(
            syncingToPhone = true,
            syncMessage = "正在传送 ${unsynced.size} 条记录...",
        )

        viewModelScope.launch {
            var success = 0
            var failed = 0
            for (item in unsynced) {
                try {
                    val transfer = EcgMeasurementTransfer(
                        timestamp = item.timestamp,
                        diagnosis = item.diagnosis,
                        possibleDiagnoses = item.possibleDiagnoses,
                        isReverse = item.isReverse,
                        avgHeartRate = item.avgHeartRate,
                        minHeartRate = item.minHeartRate,
                        maxHeartRate = item.maxHeartRate,
                        signalQuality = item.signalQuality,
                        isAbnormal = item.isAbnormal,
                        avgQrs = item.avgQrs,
                        avgP = item.avgP,
                        prInterval = item.prInterval,
                        avgQt = item.avgQt,
                        avgQtc = item.avgQtc,
                        pacCount = item.pacCount,
                        pvcCount = item.pvcCount,
                        rawEcgData = item.rawEcgData,
                        downsampledEcg = item.ecgSamples,
                        sampleRate = 500,
                        analysisMethod = item.analysisMethod,
                        aiReport = item.aiReport,
                    )
                    val putDataMapReq = PutDataMapRequest.create(
                        "${DataLayerPaths.PATH_ECG_MEASUREMENT}/${item.timestamp}"
                    )
                    putDataMapReq.dataMap.putAll(MeasurementSerializer.toDataMap(transfer))
                    val putDataReq = putDataMapReq.asPutDataRequest()
                    putDataReq.setUrgent()

                    withContext(Dispatchers.IO) {
                        Tasks.await(Wearable.getDataClient(getApplication()).putDataItem(putDataReq))
                    }
                    // Do not mark synced here; /ecg_ack/{timestamp} is the source of truth.
                    success++
                } catch (e: Exception) {
                    Log.e(TAG, "传送记录 ${item.timestamp} 失败", e)
                    failed++
                }
            }

            _uiState.value = _uiState.value.copy(
                syncingToPhone = false,
                syncMessage = "已提交 $success 条，等待手机确认（批量请求仅走 Google Data Layer；国行 BLE 请逐条从手表详情传送）" + if (failed > 0) "；提交失败 $failed 条" else "",
                history = historyRepo.getAll(),
                historyDetail = _uiState.value.historyDetail?.let { detail ->
                    historyRepo.getAll().find { it.timestamp == detail.timestamp } ?: detail
                },
            )
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        autoSyncPreferences.setEnabled(enabled)
        _uiState.value = _uiState.value.copy(
            autoSyncEnabled = enabled,
            syncMessage = if (enabled) "测量完成后自动传输已开启" else "测量完成后自动传输已关闭",
        )
    }

    /** 清除同步状态消息 */
    fun clearSyncMessage() {
        _uiState.value = _uiState.value.copy(syncMessage = null)
    }

    // ========== 历史记录管理 ==========

    fun showHistory() {
        _uiState.value = _uiState.value.copy(
            showHistory = true,
            history = historyRepo.getAll(),
            historyDetail = null,
        )
    }

    fun hideHistory() {
        _uiState.value = _uiState.value.copy(showHistory = false, historyDetail = null)
    }

    fun showHistoryDetail(item: HistoryItem) {
        // 从仓库重新读取，确保 syncedToPhone 状态最新
        val latest = historyRepo.getAll().find { it.timestamp == item.timestamp } ?: item
        _uiState.value = _uiState.value.copy(historyDetail = latest)
    }

    fun hideHistoryDetail() {
        _uiState.value = _uiState.value.copy(historyDetail = null)
    }

    fun deleteHistory(timestamp: Long) {
        historyRepo.delete(timestamp)
        _uiState.value = _uiState.value.copy(
            history = historyRepo.getAll(),
            historyDetail = null,
        )
    }

    fun deleteAllHistory() {
        historyRepo.deleteAll()
        _uiState.value = _uiState.value.copy(history = emptyList(), historyDetail = null)
    }

    private fun downsample(data: List<Int>, targetSize: Int): List<Int> {
        if (data.size <= targetSize) return data
        val step = data.size.toFloat() / targetSize
        val result = mutableListOf<Int>()
        var idx = 0f
        while (idx < data.size && result.size < targetSize) {
            result.add(data[idx.toInt()])
            idx += step
        }
        return result
    }

    /**
     * WPW 误判过滤
     *
     * WPW 典型三联征：PR<120ms + QRS>120ms + delta 波
     * 如果 API 返回 WPW 但 PR 和 QRS 都在正常范围，很可能是误判 → 移除 WPW 诊断
     * 保留其他诊断标签
     */
    private fun filterWpwIfConflict(diagnosis: List<String>, prInterval: Int, avgQrs: Int): List<String> {
        if (!diagnosis.contains("WPW")) return diagnosis
        // PR 正常 (120-200) 且 QRS 正常 (80-120) → WPW 矛盾，移除
        val prNormal = prInterval in 120..200
        val qrsNormal = avgQrs in 80..120
        return if (prNormal && qrsNormal && prInterval > 0 && avgQrs > 0) {
            // 移除 WPW，如果没有其他诊断则标记为窦性心律
            val filtered = diagnosis.filter { it != "WPW" }
            if (filtered.isEmpty()) listOf("SN") else filtered
        } else {
            diagnosis  // 参数确实异常，保留 WPW 诊断
        }
    }

    fun resetEcg() {
        ecgCollector.disconnect()
        _uiState.value = _uiState.value.copy(
            ecgState = EcgCollectionState.Idle,
            ecgResult = null,
            liveSamples = emptyList(),
        )
    }

    override fun onCleared() {
        ecgCollector.disconnect()
        super.onCleared()
    }

    companion object {
        private const val DATA_LAYER_ACK_POLL_MS = 200L
        private const val DATA_LAYER_ACK_POLL_COUNT = 40
        private const val TAG = "HealthViewModel"
    }
}
