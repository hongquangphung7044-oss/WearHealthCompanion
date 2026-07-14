package com.wearhealth.companion.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wearhealth.companion.mobile.ui.HistoryListScreen
import com.wearhealth.companion.mobile.ui.MeasurementDetailScreen
import com.wearhealth.companion.mobile.ui.MobileViewModel
import com.wearhealth.companion.mobile.ui.SettingsScreen

/** Phone companion entry point. BLE advertising is active while this process is alive. */
class MainActivity : ComponentActivity() {

    private val viewModel by lazy { MobileViewModel(application) }
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.startBleSync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBluetoothPermissions()
        setContent {
            MobileAppTheme {
                MobileNavGraph(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Data Layer remains an optional transport. BLE is the GMS-free fallback for China ROMs.
        viewModel.refreshWatchConnection()
        viewModel.startBleSync()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
            )
        }
    }
}

/** App theme: Material 3 + dynamic colors where available. */
@Composable
private fun MobileAppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val light = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context)
    else androidx.compose.material3.lightColorScheme()
    val dark = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context)
    else androidx.compose.material3.darkColorScheme()
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) dark else light,
        content = { Surface(modifier = Modifier.fillMaxSize(), content = content) },
    )
}

@Composable
private fun MobileNavGraph(viewModel: MobileViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "history") {
        composable("history") {
            HistoryListScreen(
                viewModel = viewModel,
                onClickItem = { id -> navController.navigate("detail/$id") },
                onClickSettings = { navController.navigate("settings") },
            )
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            MeasurementDetailScreen(
                viewModel = viewModel,
                measurementId = backStackEntry.arguments?.getLong("id") ?: -1L,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
