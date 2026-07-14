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
) {
    var apiKey by remember { mutableStateOf("") }
    val watchName by viewModel.connectedWatchName.collectAsState()
    val bleStatus by viewModel.bleSyncStatus.collectAsState()
    val apiKeyResult by viewModel.apiKeySendResult.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()

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
                    Text("国行 BLE 直连同步", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = bleStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.restartBleSync() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重启 BLE 同步器") }
                    Text(
                        text = "使用方法：先在下方保存 API Key。手表无 Key 时点“从手机 BLE 获取”；测量完成后在手表“历史记录 > 详情”点“传送到手机”。请保持手机 App 前台。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
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
                Text("请求手表同步数据")
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
                                "• 手机保存 Key 后，手表在缺 Key 页面点“从手机 BLE 获取”\n" +
                                "• 手机端不调用 ECG 分析 API；手表获取并保存 Key 后独立完成分析",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
