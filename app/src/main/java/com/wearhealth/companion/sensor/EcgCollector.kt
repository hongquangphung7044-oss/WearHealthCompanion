package com.wearhealth.companion.sensor

import android.content.Context
import android.util.Log
import com.wearhealth.companion.model.EcgCollectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method

/**
 * ECG 采集器：通过 Samsung Health Sensor SDK 采集原始 ECG 波形
 *
 * 由于 Samsung SDK 是可选依赖（.aar 文件可能不存在），
 * 这里使用反射调用 SDK API，保证没有 SDK 时也能编译通过。
 *
 * SDK 工作流程（基于官方文档）：
 * 1. 创建 HealthTrackingService 连接 Health Platform
 * 2. 检查设备是否支持 ECG_ON_DEMAND
 * 3. 获取 HealthTracker 实例
 * 4. 设置 TrackerEventListener 接收 EcgSet 数据
 * 5. ECG 采样率 = 500Hz，每次回调返回一批 EcgSet 数据点
 *
 * 官方示例: https://developer.samsung.com/health/sensor/sample/ecg-monitor.html
 */
class EcgCollector(private val context: Context) {

    private val _state = MutableStateFlow<EcgCollectionState>(EcgCollectionState.Idle)
    val state: StateFlow<EcgCollectionState> = _state.asStateFlow()

    /** 采集到的 ECG 原始数据（ADC 值） */
    private val ecgData = mutableListOf<Int>()

    /** SDK 反射对象 */
    private var healthTrackingService: Any? = null
    private var healthTracker: Any? = null
    private var trackerEventListener: Any? = null
    private var isSdkAvailable = false

    /**
     * 检查 Samsung Health Sensor SDK 是否可用
     */
    fun checkSdkAvailable(): Boolean {
        return try {
            Class.forName("com.samsung.android.sdk.health.sensor.HealthTrackingService")
            isSdkAvailable = true
            Log.i(TAG, "Samsung Health Sensor SDK 可用")
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Samsung Health Sensor SDK 不可用，ECG 功能需安装 SDK")
            isSdkAvailable = false
            false
        }
    }

    /**
     * 启动 ECG 采集（按需模式，采集约 30 秒 = 15000 个采样点 @ 500Hz）
     */
    suspend fun startEcgCollection(targetDurationSec: Int = 30): List<Int> {
        if (!checkSdkAvailable()) {
            _state.value = EcgCollectionState.Error("Samsung SDK 不可用")
            return emptyList()
        }

        _state.value = EcgCollectionState.Connecting
        ecgData.clear()

        try {
            // 通过反射连接 Health Platform
            connectHealthPlatform()

            // 检查 ECG 能力
            if (!checkEcgCapability()) {
                _state.value = EcgCollectionState.Error("设备不支持 ECG 测量")
                return emptyList()
            }

            // 设置 ECG 追踪器
            setupEcgTracker()

            _state.value = EcgCollectionState.Collecting(0)

            // 等待采集完成（ECG 是 on-demand，SDK 会自动在测量完成后回调）
            val targetSamples = 500 * targetDurationSec
            var waitCount = 0
            while (ecgData.size < targetSamples && waitCount < targetDurationSec * 10) {
                kotlinx.coroutines.delay(100)
                _state.value = EcgCollectionState.Collecting(ecgData.size)
                waitCount++
            }

            // 停止采集
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
     * 通过反射连接 Health Platform
     */
    private fun connectHealthPlatform() {
        val serviceClass = Class.forName("com.samsung.android.sdk.health.sensor.HealthTrackingService")

        // 创建 ConnectionListener
        val connectionListenerClass = Class.forName(
            "com.samsung.android.sdk.health.sensor.HealthTrackingService\$ConnectionListener"
        )
        val connectionListener = java.lang.reflect.Proxy.newProxyInstance(
            connectionListenerClass.classLoader,
            arrayOf(connectionListenerClass)
        ) { _, method, args ->
            when (method.name) {
                "onConnectionSuccess" -> {
                    Log.i(TAG, "Health Platform 连接成功")
                }
                "onConnectionFailed" -> {
                    val exception = args?.getOrNull(0)
                    Log.e(TAG, "Health Platform 连接失败: $exception")
                    _state.value = EcgCollectionState.Error("Health Platform 连接失败")
                }
                "onConnectionEnded" -> {
                    Log.i(TAG, "Health Platform 连接断开")
                }
            }
            null
        }

        // 构造 HealthTrackingService(context, connectionListener)
        val constructor = serviceClass.getConstructor(Context::class.java, connectionListenerClass)
        healthTrackingService = constructor.newInstance(context, connectionListener)

        // 调用 connectService()
        val connectMethod = serviceClass.getMethod("connectService")
        connectMethod.invoke(healthTrackingService)

        // 等待连接
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(2000) }
    }

    /**
     * 检查设备是否支持 ECG
     */
    private fun checkEcgCapability(): Boolean {
        val service = healthTrackingService ?: return false
        val serviceClass = service.javaClass

        // 获取 trackingCapability
        val getCapabilityMethod = serviceClass.getMethod("getTrackingCapability")
        val capability = getCapabilityMethod.invoke(service)

        // 获取支持的 tracker 类型列表
        val capabilityClass = capability.javaClass
        val getSupportedMethod = capabilityClass.getMethod("getSupportHealthTrackerTypes")
        @Suppress("UNCHECKED_CAST")
        val supportedTypes = getSupportedMethod.invoke(capability) as? List<Enum<*>> ?: emptyList()

        // 检查是否包含 ECG_ON_DEMAND
        val hasEcg = supportedTypes.any { it.name == "ECG_ON_DEMAND" }
        Log.i(TAG, "设备支持 ECG: $hasEcg (支持类型: ${supportedTypes.map { it.name }})")
        return hasEcg
    }

    /**
     * 设置 ECG 追踪器并开始接收数据
     */
    private fun setupEcgTracker() {
        val service = healthTrackingService ?: throw IllegalStateException("未连接 Health Platform")

        // 获取 HealthTrackerType.ECG_ON_DEMAND 枚举
        val trackerTypeClass = Class.forName(
            "com.samsung.android.sdk.health.sensor.HealthTracker\$HealthTrackerType"
        )
        val ecgType = trackerTypeClass.enumConstants
            .filterIsInstance<Enum<*>>()
            .firstOrNull { it.name == "ECG_ON_DEMAND" }
            ?: throw IllegalStateException("找不到 ECG_ON_DEMAND 类型")

        // 获取 HealthTracker 实例
        val serviceClass = service.javaClass
        val getTrackerMethod = serviceClass.getMethod("getHealthTracker", trackerTypeClass)
        healthTracker = getTrackerMethod.invoke(service, ecgType)

        // 创建 TrackerEventListener（通过反射代理）
        val listenerClass = Class.forName(
            "com.samsung.android.sdk.health.sensor.HealthTracker\$TrackerEventListener"
        )
        trackerEventListener = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            when (method.name) {
                "onDataReceived" -> {
                    // args[0] 是 List<DataPoint>
                    val dataPoints = args?.getOrNull(0) as? List<*> ?: return@newProxyInstance null
                    for (dp in dataPoints) {
                        // 从 DataPoint 中提取 ECG 值
                        val ecgValue = extractEcgValue(dp)
                        if (ecgValue != null) {
                            ecgData.add(ecgValue)
                        }
                    }
                    Log.d(TAG, "收到 ECG 数据: +${dataPoints.size} 点 (总计 ${ecgData.size})")
                }
                "onFlushCompleted" -> Log.d(TAG, "ECG flush 完成")
                "onError" -> {
                    val error = args?.getOrNull(0)
                    Log.e(TAG, "ECG 追踪错误: $error")
                    _state.value = EcgCollectionState.Error("ECG 追踪错误: $error")
                }
            }
            null
        }

        // 调用 setTrackerEventListener(listener)
        val tracker = healthTracker ?: throw IllegalStateException("无法获取 ECG Tracker")
        val setListenerMethod = tracker.javaClass.getMethod("setTrackerEventListener", listenerClass)
        setListenerMethod.invoke(tracker, trackerEventListener)
    }

    /**
     * 从 DataPoint 提取 ECG 值
     * Samsung SDK 的 EcgSet 包含 ECG 波形数据
     */
    private fun extractEcgValue(dataPoint: Any?): Int? {
        if (dataPoint == null) return null
        return try {
            // 尝试调用 getData() 或直接获取 ECG 值
            val dpClass = dataPoint.javaClass
            // Samsung SDK DataPoint 有 getValue(HealthDataKey) 方法
            // ECG 数据键名可能因版本不同，尝试多种方式
            try {
                val getMethod = dpClass.getMethod("getData")
                val data = getMethod.invoke(dataPoint)
                // 如果 data 是数组或列表，取第一个值
                when (data) {
                    is IntArray -> data.firstOrNull() ?: 0
                    is List<*> -> (data.firstOrNull() as? Int) ?: 0
                    is Int -> data
                    is Number -> data.toInt()
                    else -> 0
                }
            } catch (e: NoSuchMethodException) {
                // 尝试直接作为 Number
                (dataPoint as? Number)?.toInt()
            }
        } catch (e: Exception) {
            Log.w(TAG, "提取 ECG 值失败: ${e.message}")
            null
        }
    }

    /**
     * 停止 ECG 采集
     */
    private fun stopEcgTracker() {
        try {
            val tracker = healthTracker ?: return
            val trackerClass = tracker.javaClass
            // 调用 unsetTrackerEventListener()
            try {
                val unsetMethod = trackerClass.getMethod("unsetTrackerEventListener")
                unsetMethod.invoke(tracker)
            } catch (e: NoSuchMethodException) {
                // 某些版本方法名不同
                val unsetMethod = trackerClass.methods.firstOrNull { it.name.startsWith("unset") }
                unsetMethod?.invoke(tracker)
            }
            Log.i(TAG, "ECG 追踪器已停止")
        } catch (e: Exception) {
            Log.w(TAG, "停止 ECG 追踪器时出错: ${e.message}")
        }
    }

    /**
     * 断开 Health Platform 连接
     */
    fun disconnect() {
        try {
            healthTrackingService?.let { service ->
                val disconnectMethod = service.javaClass.getMethod("disconnectService")
                disconnectMethod.invoke(service)
            }
        } catch (e: Exception) {
            Log.w(TAG, "断开连接时出错: ${e.message}")
        }
        healthTrackingService = null
        healthTracker = null
        trackerEventListener = null
        _state.value = EcgCollectionState.Idle
    }

    companion object {
        private const val TAG = "EcgCollector"
    }
}
