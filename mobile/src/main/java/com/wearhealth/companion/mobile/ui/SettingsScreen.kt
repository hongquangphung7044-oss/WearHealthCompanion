package com.wearhealth.companion.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * API Key 配置页
 *
 * - 输入 HeartVoice API Key 并发送到手表
 * - 显示当前已连接的手表信息
 * - 请求手表同步未传送数据
 * - API Key 使用说明（免费 100 次，不刷新）
 *
 * @param onBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MobileViewModel,
    onBack: () -> Unit,
    onSetBackgroundSync: (Boolean) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    val watchName by viewModel.connectedWatchName.collectAsState()
    val bleStatus by viewModel.bleSyncStatus.collectAsState()
    val bleConnected by viewModel.bleConnected.collectAsState()
    val apiKeyResult by viewModel.apiKeySendResult.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val backgroundSyncEnabled by viewModel.backgroundSyncEnabled.collectAsState()
    val backgroundSyncMessage by viewModel.backgroundSyncMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ===== 手表连接信息 =====
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Watch,
                        contentDescription = null,
                        tint = if (watchName != null) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            text = if (watchName != null) "Google 同步通道已连接" else "Google 同步通道未发现手表",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = watchName ?: "国行设备可直接使用下方 BLE 同步器；此处不代表 Galaxy Wearable 蓝牙配对状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (bleConnected) "BLE 状态：已连接" else "BLE 状态：待机，当前未连接",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (bleConnected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = bleStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.restartBleSync() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重新连接（重启 BLE 监听）") }
                    Text(
                        text = "手表不会常驻连接：获取 Key 或传 ECG 时才主动连接，完成后会断开。若传送失败，先点上方重新连接，再立即从手表详情重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("后台 BLE 同步", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "开启后手机会显示常驻通知，App 退到后台时仍等待手表主动连接。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = backgroundSyncEnabled,
                            onCheckedChange = onSetBackgroundSync,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "三星电池策略仍可能限制后台运行；若同步不稳定，请允许后台活动或取消电池优化。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    backgroundSyncMessage?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ===== API Key 输入与发送 =====
            Text(
                text = "HeartVoice API Key",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
            Button(
                onClick = { viewModel.sendApiKey(apiKey.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = apiKey.isNotBlank(),
            ) {
                Text("保存并提供给手表")
            }
            apiKeyResult?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ===== 请求同步 =====
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.requestSync() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("仅通过 Google 通道请求同步")
            }
            syncResult?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ===== API Key 说明 =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "API Key 说明",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "• BLE 直连支持手机向已配对手表提供 API Key，以及手表向手机传送 ECG\n" +
                                "• 手机保存 Key 后，手表可点“从手机 BLE 获取/更新”\n" +
                                "• 手机端不调用 ECG 分析 API；手表获取并保存 Key 后独立完成分析",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
