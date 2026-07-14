package com.wearhealth.companion.mobile.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wearhealth.companion.mobile.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the one process-wide BLE GATT server alive while background synchronization is enabled. */
class BleSyncForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BleSyncPreferences.isEnabled(this)) {
            BleSyncRuntime.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasBluetoothPermissions()) {
            BleSyncStatusStore.setStartError("后台同步需要附近设备权限")
            BleSyncRuntime.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasNotificationPermission()) {
            BleSyncStatusStore.setStartError("后台同步需要通知权限才能显示常驻通知")
            BleSyncRuntime.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.wearhealth.companion.mobile.R.drawable.ic_sync_notification)
            .setContentTitle("ECG 后台同步已开启")
            .setContentText("正在等待已配对手表连接")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else 0,
            )
            BleSyncRuntime.start(this)
            BleSyncStatusStore.setMessage("后台 BLE 同步正在运行；等待已配对手表连接")
        } catch (error: Exception) {
            BleSyncStatusStore.setStartError("后台同步启动失败：${error.message ?: "系统限制"}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BleSyncRuntime.stop(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ECG 后台同步", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "保持 BLE 同步器等待已配对手表连接"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    } else true

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "ble_ecg_sync"
        private const val NOTIFICATION_ID = 4101
    }
}

/** Non-secret diagnostic surfaced by the UI when platform foreground-service startup is rejected. */
object BleSyncStatusStore {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun setMessage(message: String?) { _message.value = message }
    fun setStartError(message: String) { _message.value = message }
}
