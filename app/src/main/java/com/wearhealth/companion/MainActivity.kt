package com.wearhealth.companion

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.wearhealth.companion.ui.HealthMonitorScreen
import com.wearhealth.companion.ui.HealthViewModel

class MainActivity : ComponentActivity() {

    private val viewModel by lazy { HealthViewModel(application) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 忽略结果；用户拒绝时 UI 会显示无数据
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            MaterialTheme {
                val listState = rememberScalingLazyListState()
                Scaffold(
                    timeText = { TimeText() },
                    positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        HealthMonitorScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.BODY_SENSORS_BACKGROUND,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }
}
