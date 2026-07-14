package com.wearhealth.companion.mobile.service

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.wearhealth.companion.shared.DataLayerPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Wearable Data Layer 管理器
 *
 * 封装手机 → 手表的通信：
 * - [sendApiKeyToWatch]：通过 PutDataMapRequest 发送 HeartVoice API Key
 * - [requestSyncFromWatch]：通过 MessageClient 请求手表同步未传送数据
 * - [getConnectedNodes]：查询已连接的手表节点
 *
 * 所有调用都运行在 IO 调度器，使用挂起函数包装 GoogleAPIClient 回调。
 */
class DataLayerManager(private val context: Context) {

    private val messageClient: MessageClient get() = Wearable.getMessageClient(context)
    private val dataClient get() = Wearable.getDataClient(context)

    /**
     * 发送 API Key 到手表
     *
     * 通过 PutDataMapRequest 写入 /api_key 数据项，[setUrgent] 确保立即同步。
     * @return true 表示请求已提交（不代表手表已接收）
     */
    suspend fun sendApiKeyToWatch(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext false
        try {
            val putDataReq = PutDataMapRequest.create(DataLayerPaths.PATH_API_KEY).apply {
                dataMap.putString(DataLayerPaths.KEY_API_KEY, apiKey)
            }.asPutDataRequest().setUrgent()
            awaitTask { dataClient.putDataItem(putDataReq) }
            Log.i(TAG, "API Key 已发送到手表")
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送 API Key 失败: ${e.message}", e)
            false
        }
    }

    /**
     * 请求手表同步未传送的数据
     *
     * 向所有已连接的手表发送 /sync_request 消息（无 payload）。
     * @return 成功发送消息的节点数
     */
    suspend fun requestSyncFromWatch(): Int = withContext(Dispatchers.IO) {
        val nodes = getConnectedNodes()
        if (nodes.isEmpty()) {
            Log.w(TAG, "没有已连接的手表节点")
            return@withContext 0
        }
        var sent = 0
        for (node in nodes) {
            try {
                awaitTask {
                    messageClient.sendMessage(node.id, DataLayerPaths.PATH_SYNC_REQUEST, ByteArray(0))
                }
                sent++
                Log.i(TAG, "已向手表 ${node.displayName} 请求同步")
            } catch (e: Exception) {
                Log.e(TAG, "向手表 ${node.displayName} 请求同步失败: ${e.message}", e)
            }
        }
        sent
    }

    /**
     * 获取可达且声明了 WearHealth 同步协议的手表节点。
     * Do not use generic connectedNodes: it can include a node without this companion app.
     */
    suspend fun getConnectedNodes(): List<Node> = withContext(Dispatchers.IO) {
        try {
            awaitTask {
                Wearable.getCapabilityClient(context).getCapability(
                    DataLayerPaths.CAPABILITY_WATCH_SYNC,
                    CapabilityClient.FILTER_REACHABLE,
                )
            }.nodes.toList()
        } catch (e: Exception) {
            Log.e(TAG, "获取兼容手表节点失败: ${e.message}", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "DataLayerManager"
    }
}

/**
 * 将 Google Play Services Task 转为挂起函数
 */
private suspend fun <T> awaitTask(block: () -> com.google.android.gms.tasks.Task<T>): T =
    suspendCancellableCoroutine { cont ->
        val task = block()
        task.addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        task.addOnFailureListener { e ->
            if (cont.isActive) cont.cancel(e)
        }
    }
