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
import kotlinx.coroutines.flow.map
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

    /**
     * 监听 ECG 采集状态，测量中（预热/采集/分析）自动保持屏幕常亮
     */
    private fun setupKeepScreenOnDuringMeasurement() {
        lifecycleScope.launch {
            viewModel.uiState.map { it.ecgState }
                .distinctUntilChanged()
                .collect { state ->
                    val keepOn = state is EcgCollectionState.Connecting ||
                            state is EcgCollectionState.Preheating ||
                            state is EcgCollectionState.Collecting ||
                            state is EcgCollectionState.Analyzing
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
