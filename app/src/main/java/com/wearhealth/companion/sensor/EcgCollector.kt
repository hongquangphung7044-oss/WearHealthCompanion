package com.wearhealth.companion.sensor

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.wearhealth.companion.model.EcgCollectionState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume

/**
 * ECG 采集器：通过 Samsung Health Sensor SDK 直接调用采集原始 ECG 波形
 *
 * 采集流程：
 * 1. 连接 Health Platform
 * 2. 等待电极接触：最多 30 秒（检测 leadOff != 5）
 * 3. 预热激活：5 秒信号稳定期（丢弃数据，类似 geminiman 激活预热）
 * 4. 正式采集：30 秒倒计时，实时输出波形数据
 * 5. 采样率 = 500Hz，30 秒 ≈ 15000 个采样点
 *
 * 电极说明（Galaxy Watch 4/5/6/7/Ultra）：
 * - 上方按键（Home 键）= ECG 电极，手指轻触即可（不要按下去）
 * - 手表背面 = 另一电极，需紧贴手腕皮肤
 * - 下方按键（Back 键）不是电极
 */
class EcgCollector(private val context: Context) {

    private val _state = MutableStateFlow<EcgCollectionState>(EcgCollectionState.Idle)
    val state: StateFlow<EcgCollectionState> = _state.asStateFlow()

    /** 实时 ECG 波形数据（仅保留最近 1000 点用于滚动显示） */
    private val _liveSamples = MutableStateFlow<List<Int>>(emptyList())
    val liveSamples: StateFlow<List<Int>> = _liveSamples.asStateFlow()

    /** 采集到的完整 ECG 数据（mV × 1000，整数）。SDK 回调和采集协程运行在线程间。 */
    private val ecgData = mutableListOf<Int>()
    private val ecgDataLock = Any()

    private fun clearEcgData() = synchronized(ecgDataLock) { ecgData.clear() }
    private fun ecgDataSize(): Int = synchronized(ecgDataLock) { ecgData.size }
    private fun isEcgDataEmpty(): Boolean = synchronized(ecgDataLock) { ecgData.isEmpty() }
    private fun snapshotEcgData(): List<Int> = synchronized(ecgDataLock) { ecgData.toList() }
    private fun appendEcgSample(sample: Int) = synchronized(ecgDataLock) { ecgData.add(sample) }
    private fun recentEcgSnapshot(maxSamples: Int): List<Int> = synchronized(ecgDataLock) {
        ecgData.takeLast(maxSamples)
    }

    private var healthTrackingService: HealthTrackingService? = null
    private var healthTracker: HealthTracker? = null
    @Volatile private var isConnected = false
    @Volatile private var hasContact = false  // 接触是否稳定（持续 1 秒以上）
    @Volatile private var leadLost = false    // 采集中电极是否断开
    @Volatile private var firstContactTime = 0L  // 首次接触时间戳（0=未接触）
    @Volatile private var contactStable = false  // 接触稳定标志（持续 1 秒）

    fun checkSdkAvailable(): Boolean {
        return try {
            Class.forName("com.samsung.android.service.health.tracking.HealthTrackingService")
            Log.i(TAG, "Samsung Health Sensor SDK 可用")
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Samsung Health Sensor SDK 不可用")
            false
        }
    }

    /**
     * 启动 ECG 采集
     * 阶段 1: 连接 + 预热（等待电极接触）
     * 阶段 2: 30 秒正式采集（带倒计时）
     */
    suspend fun startEcgCollection(targetDurationSec: Int = 30): List<Int> {
        if (!checkSdkAvailable()) {
            _state.value = EcgCollectionState.Error("Samsung SDK 不可用")
            return emptyList()
        }

        _state.value = EcgCollectionState.Connecting  // 预热中
        clearEcgData()
        hasContact = false
        _liveSamples.value = emptyList()
        isConnected = false

        try {
            // 0. 每次新连接前，彻底释放上次的资源（防止"第二次连不上"）
            fullyReleaseService()
            delay(300)  // 给系统时间清理

            // 1. 连接 Health Platform（带重试，最多 3 次）
            var connected = false
            for (attempt in 1..3) {
                Log.i(TAG, "连接 Health Platform 第 $attempt 次尝试")
                connectHealthPlatform()
                if (isConnected) {
                    connected = true
                    break
                }
                // 失败：彻底释放后重试
                fullyReleaseService()
                delay(500)
            }
            if (!connected) {
                _state.value = EcgCollectionState.Error(
                    "无法连接传感器服务，请重试。如多次失败请重启手表后重试"
                )
                return emptyList()
            }

            // 2. 检查 ECG 能力
            if (!checkEcgCapability()) {
                _state.value = EcgCollectionState.Error("设备不支持 ECG 测量")
                fullyReleaseService()
                return emptyList()
            }

            // 3. 启动 ECG 追踪器
            setupEcgTracker()

            // 4. 等待电极接触：最多 30 秒（给用户充分时间调整姿势）
            Log.i(TAG, "预热中：等待电极接触...")
            val preheatTicks = 300  // 30 秒 × 10 次/秒
            var preheatCount = 0
            while (!hasContact && preheatCount < preheatTicks) {
                delay(100)
                preheatCount++
            }
            if (!hasContact || isEcgDataEmpty()) {
                _state.value = EcgCollectionState.Error(
                    "30 秒内未检测到电极接触。可能原因：\n" +
                    "1. 手指太干 → 涂点护手霜\n" +
                    "2. 手指没完全覆盖上方按键\n" +
                    "3. 手表戴太松 → 戴紧一点\n" +
                    "4. 手指太冷 → 搓热手指再试\n" +
                    "5. 手腕有汗水/毛发 → 清洁后重试"
                )
                fullyReleaseService()
                return emptyList()
            }
            Log.i(TAG, "电极接触检测成功")

            // 5. 预热激活阶段：5 秒信号稳定期（类似 geminiman 的激活预热）
            // 电极刚接触时信号会有建立过程，前几秒数据不稳定，丢弃这段数据
            // 期间显示倒计时让用户知道在激活
            Log.i(TAG, "预热激活中：5 秒信号稳定期")
            val activateSec = 5
            for (sec in activateSec downTo 1) {
                _state.value = EcgCollectionState.Preheating(sec)
                delay(1000)
            }
            // 激活完成，清空激活期间的数据，确保 30 秒采集从稳定状态开始
            clearEcgData()
            _liveSamples.value = emptyList()
            Log.i(TAG, "预热激活完成，开始 30 秒正式采集")

            // 6. 正式采集：30 秒倒计时
            // 注意：不在采集中检测"接触断开"——Samsung SDK 的 leadOff 值在测量过程中
            // 可能短暂波动，误判会导致测量失败。数据质量交给本地预检 + API sqGrade 判断
            val totalTicks = targetDurationSec * 10  // 100ms 一次
            var tick = 0
            while (tick < totalTicks) {
                delay(100)
                tick++
                val countdown = targetDurationSec - (tick / 10)
                val sampleCount = ecgDataSize()
                _state.value = EcgCollectionState.Collecting(sampleCount, countdown)
                // SDK callback writes on another thread. Publish an immutable snapshot so a
                // concurrent sample cannot invalidate subList iteration and abort collection.
                _liveSamples.value = recentEcgSnapshot(250)
            }

            // 6. 停止追踪器但保持 service（下次测量复用）
            stopEcgTracker()

            val completedData = snapshotEcgData()
            // 最终波形（降采样到 300 点用于结果页全局缩略图）
            val finalWaveform = downsample(completedData, 300)
            _liveSamples.value = finalWaveform

            Log.i(TAG, "ECG 采集完成: ${completedData.size} 个采样点")
            return completedData
        } catch (e: Exception) {
            Log.e(TAG, "ECG 采集失败", e)
            val detail = e.message?.takeIf { it.isNotBlank() }
                ?: e.javaClass.simpleName.takeIf { it.isNotBlank() }
                ?: "未知异常"
            _state.value = EcgCollectionState.Error("采集失败：$detail")
            fullyReleaseService()
            return emptyList()
        }
    }

    /**
     * 彻底释放 Samsung SDK 资源
     * 这是解决"测完一次后第二次连不上"的关键：
     * 1. 停止 tracker listener
     * 2. 断开 service
     * 3. 置 null 让 GC 回收
     * 4. 给系统时间清理
     */
    private fun fullyReleaseService() {
        try {
            healthTracker?.unsetEventListener()
        } catch (_: Exception) {}
        try {
            healthTrackingService?.disconnectService()
        } catch (_: Exception) {}
        healthTrackingService = null
        healthTracker = null
        isConnected = false
        hasContact = false
    }

    /** 降采样：从原数据中均匀取样 */
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

    private suspend fun connectHealthPlatform() = suspendCancellableCoroutine { cont ->
        try {
            val connectionListener = object : ConnectionListener {
                override fun onConnectionSuccess() {
                    Log.i(TAG, "Health Platform 连接成功")
                    isConnected = true
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onConnectionEnded() {
                    Log.i(TAG, "Health Platform 连接断开")
                    isConnected = false
                }

                override fun onConnectionFailed(exception: HealthTrackerException) {
                    Log.e(TAG, "Health Platform 连接失败: $exception")
                    isConnected = false
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            val service = HealthTrackingService(connectionListener, context)
            healthTrackingService = service
            service.connectService()

            // 超时保护：10 秒
            Thread {
                try {
                    Thread.sleep(10000)
                    if (cont.isActive) cont.resume(Unit)
                } catch (_: InterruptedException) {}
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "创建 HealthTrackingService 失败", e)
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private fun checkEcgCapability(): Boolean {
        val service = healthTrackingService ?: return false
        return try {
            val capability = service.trackingCapability ?: return false
            val supportedTypes = capability.supportHealthTrackerTypes
            val hasEcg = supportedTypes.any {
                it == HealthTrackerType.ECG_ON_DEMAND || it == HealthTrackerType.ECG
            }
            Log.i(TAG, "设备支持 ECG: $hasEcg")
            hasEcg
        } catch (e: Exception) {
            Log.e(TAG, "检查 ECG 能力失败", e)
            false
        }
    }

    private fun setupEcgTracker() {
        val service = healthTrackingService
            ?: throw IllegalStateException("未连接 Health Platform")

        val trackerType = try {
            HealthTrackerType.ECG_ON_DEMAND
        } catch (e: Exception) {
            HealthTrackerType.ECG
        }
        healthTracker = service.getHealthTracker(trackerType)
        val tracker = healthTracker
            ?: throw IllegalStateException("无法获取 ECG 追踪器")

        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    try {
                        val leadOff = dp.getValue(ValueKey.EcgSet.LEAD_OFF)
                        val ecgMv = dp.getValue(ValueKey.EcgSet.ECG_MV)
                        if (ecgMv != null && leadOff != null) {
                            // Samsung SDK: leadOff=0 表示接触，leadOff=5 表示未接触
                            // 只要不是 5 就算接触（避免中间值误判）
                            val isContact = (leadOff != 5)
                            if (!hasContact && isContact) {
                                // 首次检测到接触，清空预热噪声
                                hasContact = true
                                clearEcgData()
                                leadLost = false
                                Log.i(TAG, "检测到电极接触 (leadOff=$leadOff)，清空预热数据，开始正式记录")
                            }
                            // 关键：接触断开时不记录数据（避免噪声污染缩略图）
                            if (hasContact) {
                                if (isContact) {
                                    leadLost = false
                                    appendEcgSample((ecgMv * 1000).toInt())
                                } else {
                                    leadLost = true
                                    // 不记录断开期间的噪声数据
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "提取 ECG 值失败: ${e.message}")
                    }
                }
            }

            override fun onFlushCompleted() {}

            override fun onError(error: HealthTracker.TrackerError) {
                // 只记录日志，不中断采集——某些 TrackerError 是可恢复的
                // 采集循环会继续，最终数据质量交给本地预检 + API 判断
                Log.w(TAG, "ECG 追踪警告（不中断采集）: $error")
            }
        }

        tracker.setEventListener(listener)
        Log.i(TAG, "ECG 追踪器已启动，等待数据...")
    }

    private fun stopEcgTracker() {
        try {
            healthTracker?.unsetEventListener()
            Log.i(TAG, "ECG 追踪器已停止")
        } catch (e: Exception) {
            Log.w(TAG, "停止 ECG 追踪器时出错: ${e.message}")
        }
    }

    fun disconnect() {
        fullyReleaseService()
        clearEcgData()
        _liveSamples.value = emptyList()
        _state.value = EcgCollectionState.Idle
    }

    companion object {
        private const val TAG = "EcgCollector"
    }
}
