package com.wearhealth.companion.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

/**
 * 手机端入口 Activity
 *
 * 使用 Material 3 + 动态取色（Dynamic Colors），
 * 通过 Navigation Compose 在三个页面间导航：
 * - history：历史记录列表
 * - detail/{id}：测量详情
 * - settings：API Key 配置
 */
class MainActivity : ComponentActivity() {

    private val viewModel by lazy { MobileViewModel(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MobileAppTheme {
                MobileNavGraph(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次 resume 时刷新手表连接状态（用户可能刚打开蓝牙/配对手表）
        viewModel.refreshWatchConnection()
    }
}

/** 应用主题：Material 3 + 动态取色（Android 12+），低版本回退默认配色 */
@Composable
private fun MobileAppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        androidx.compose.material3.lightColorScheme()
    }
    val darkColorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        androidx.compose.material3.darkColorScheme()
    }
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme else colorScheme,
        content = {
            Surface(modifier = Modifier.fillMaxSize(), content = content)
        },
    )
}

/** 导航图 */
@Composable
private fun MobileNavGraph(
    viewModel: MobileViewModel,
) {
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
            val id = backStackEntry.arguments?.getLong("id") ?: -1L
            MeasurementDetailScreen(
                viewModel = viewModel,
                measurementId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
