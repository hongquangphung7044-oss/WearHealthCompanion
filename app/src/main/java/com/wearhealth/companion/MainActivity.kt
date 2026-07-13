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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.PositionIndicator
import androidx.wear.compose.material3.Scaffold
import androidx.wear.compose.material3.TimeText
import com.wearhealth.companion.ui.HealthMonitorScreen
import com.wearhealth.companion.ui.HealthViewModel

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
            )
        )
    }
}
