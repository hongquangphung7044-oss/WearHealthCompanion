package com.wearhealth.companion

import android.Manifest
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.MaterialTheme
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.ui.HealthMonitorScreen
import com.wearhealth.companion.ui.HealthViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel by lazy { HealthViewModel(application) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            MaterialTheme {
                HealthMonitorScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 测量时保持屏幕常亮（防止熄屏退出）
        setupKeepScreenOnDuringMeasurement()
    }

    override fun onResume() {
        super.onResume()
        // 刷新 API Key 状态（手机端可能在 App 后台期间下发了新 Key）
        viewModel.refreshApiKeyStatus()
    }

    /**
     * 监听测量与 BLE 同步状态；采集、分析、Key 获取或 ECG 上传期间保持屏幕常亮。
     * 无过滤 BLE 扫描在 Android 熄屏后会被系统暂停，因此同步阶段也不能熄屏。
     */
    private fun setupKeepScreenOnDuringMeasurement() {
        lifecycleScope.launch {
            viewModel.uiState
                .distinctUntilChanged { old, new ->
                    old.ecgState == new.ecgState && old.syncingToPhone == new.syncingToPhone
                }
                .collect { state ->
                    val keepOn = state.ecgState is EcgCollectionState.Connecting ||
                            state.ecgState is EcgCollectionState.Preheating ||
                            state.ecgState is EcgCollectionState.Collecting ||
                            state.ecgState is EcgCollectionState.Analyzing ||
                            state.syncingToPhone
                    runOnUiThread {
                        if (keepOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BODY_SENSORS,
                *if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray(),
            )
        )
    }
}
