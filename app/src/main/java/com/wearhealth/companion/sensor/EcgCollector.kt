package com.wearhealth.companion.sensor

import android.content.Context
import android.util.Log
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
 * 工作流程（基于 Samsung SDK v1.4.1 官方示例）：
 * 1. 创建 HealthTrackingService(ConnectionListener, Context) 并连接
 * 2. 检查设备是否支持 ECG_ON_DEMAND
 * 3. 通过 getHealthTracker(HealthTrackerType.ECG_ON_DEMAND) 获取追踪器
 * 4. 注册 TrackerEventListener，接收 DataPoint 列表
 * 5. 通过 DataPoint.getValue(ValueKey.EcgSet.ECG_MV) 提取 ECG 毫伏值
 * 6. ECG 采样率 = 500Hz
 *
 * 编译依赖：samsung-health-sensor-api.aar（位于 app/libs/）
 * 官方 SDK 包名：com.samsung.android.service.health.tracking
 */
class EcgCollector(private val context: Context) {

    private val _state = MutableStateFlow<EcgCollectionState>(EcgCollectionState.Idle)
    val state: StateFlow<EcgCollectionState> = _state.asStateFlow()

    /** 采集到的 ECG 原始数据（毫伏值，Int 表示） */
    private val ecgData = mutableListOf<Int>()

    /** SDK 相关实例（直接调用，无反射） */
    private var healthTrackingService: HealthTrackingService? = null
    private var healthTracker: HealthTracker? = null
    @Volatile private var isConnected = false

    /**
     * 检查 Samsung Health Sensor SDK 是否可用
     */
    fun checkSdkAvailable(): Boolean {
        return try {
            Class.forName("com.samsung.android.service.health.tracking.HealthTrackingService")
            Log.i(TAG, "Samsung Health Sensor SDK 可用（直接调用 v1.4.1）")
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Samsung Health Sensor SDK 不可用，ECG 功能需安装 SDK")
            false
        }
    }

    /**
     * 启动 ECG 采集（按需模式，采集约 30 秒 ≈ 15000 个采样点 @ 500Hz）
     */
    suspend fun startEcgCollection(targetDurationSec: Int = 30): List<Int> {
        if (!checkSdkAvailable()) {
            _state.value = EcgCollectionState.Error("Samsung SDK 不可用")
            return emptyList()
        }

        _state.value = EcgCollectionState.Connecting
        ecgData.clear()
        isConnected = false

        try {
            // 1. 连接 Health Platform
            connectHealthPlatform()
            if (!isConnected) {
                _state.value = EcgCollectionState.Error("Health Platform 连接失败")
                return emptyList()
            }

            // 2. 检查设备是否支持 ECG
            if (!checkEcgCapability()) {
                _state.value = EcgCollectionState.Error("设备不支持 ECG 测量")
                disconnect()
                return emptyList()
            }

            // 3. 设置 ECG 追踪器并开始监听
            setupEcgTracker()

            _state.value = EcgCollectionState.Collecting(0)

            // 4. 等待采集足够数据或超时
            val targetSamples = 500 * targetDurationSec
            var waitCount = 0
            while (ecgData.size < targetSamples && waitCount < targetDurationSec * 10) {
                delay(100)
                _state.value = EcgCollectionState.Collecting(ecgData.size)
                waitCount++
            }

            // 5. 停止追踪器
            stopEcgTracker()

            Log.i(TAG, "ECG 采集完成: ${ecgData.size} 个采样点")
            return ecgData.toList()
        } catch (e: Exception) {
            Log.e(TAG, "ECG 采集失败", e)
            _state.value = EcgCollectionState.Error(e.message ?: "采集失败")
            return emptyList()
        }
    }

    /**
     * 连接 Health Platform
     *
     * 官方 API：HealthTrackingService(ConnectionListener, Context)
     * ConnectionListener 是独立接口，三个回调：onConnectionSuccess/onConnectionEnded/onConnectionFailed
     */
    private suspend fun connectHealthPlatform() = suspendCancellableCoroutine { cont ->
        try {
            val connectionListener = object : HealthTrackingService.ConnectionListener {
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

            // 额外超时保护：10 秒还没连接成功就继续
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

    /**
     * 检查设备是否支持 ECG 测量
     */
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

    /**
     * 获取 ECG 追踪器并注册监听器
     *
     * 官方示例：setEventListener(TrackerEventListener)
     * ECG DataPoint 通过 ValueKey.EcgSet.ECG_MV 获取毫伏值
     */
    private fun setupEcgTracker() {
        val service = healthTrackingService
            ?: throw IllegalStateException("未连接 Health Platform")

        // 获取 ECG 追踪器（优先 ECG_ON_DEMAND，回退 ECG）
        val trackerType = try {
            HealthTrackerType.ECG_ON_DEMAND
        } catch (e: Exception) {
            HealthTrackerType.ECG
        }
        healthTracker = service.getHealthTracker(trackerType)
        val tracker = healthTracker
            ?: throw IllegalStateException("无法获取 ECG 追踪器")

        // 注册数据监听器（官方 API）
        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    try {
                        // 先检查 LEAD_OFF 状态（电极是否接触良好）
                        val leadOff = dp.getValue(ValueKey.EcgSet.LEAD_OFF)
                        if (leadOff != null && leadOff == 5) {
                            // LEAD_OFF == 5 表示电极未接触，跳过无效数据
                            continue
                        }
                        // 提取 ECG 毫伏值
                        val ecgMv = dp.getValue(ValueKey.EcgSet.ECG_MV)
                        if (ecgMv != null) {
                            // 转换为整数（放大 1000 倍保留精度，用于 API 传输）
                            ecgData.add((ecgMv * 1000).toInt())
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "提取 ECG 值失败: ${e.message}")
                    }
                }
                if (dataPoints.isNotEmpty()) {
                    Log.d(TAG, "收到 ECG 数据: +${dataPoints.size} 点（总计 ${ecgData.size}）")
                }
            }

            override fun onFlushCompleted() {
                Log.d(TAG, "ECG flush 完成")
            }

            override fun onError(error: HealthTracker.TrackerError) {
                Log.e(TAG, "ECG 追踪错误: $error")
                _state.value = EcgCollectionState.Error("ECG 追踪错误: $error")
            }
        }

        tracker.setEventListener(listener)
        Log.i(TAG, "ECG 追踪器已启动，等待数据...")
    }

    /**
     * 停止 ECG 追踪器
     */
    private fun stopEcgTracker() {
        try {
            val tracker = healthTracker
            if (tracker != null) {
                tracker.unsetEventListener()
                Log.i(TAG, "ECG 追踪器已停止")
            }
        } catch (e: Exception) {
            Log.w(TAG, "停止 ECG 追踪器时出错: ${e.message}")
        }
    }

    /**
     * 断开 Health Platform 连接并清理资源
     */
    fun disconnect() {
        try {
            stopEcgTracker()
            healthTrackingService?.disconnectService()
        } catch (e: Exception) {
            Log.w(TAG, "断开连接时出错: ${e.message}")
        }
        healthTrackingService = null
        healthTracker = null
        isConnected = false
        ecgData.clear()
        _state.value = EcgCollectionState.Idle
    }

    companion object {
        private const val TAG = "EcgCollector"
    }
}
