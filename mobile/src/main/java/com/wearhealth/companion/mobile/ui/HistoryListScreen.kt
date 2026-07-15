package com.wearhealth.companion.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wearhealth.companion.mobile.data.EcgMeasurementEntity
import com.wearhealth.companion.shared.EcgBinaryCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录列表页
 *
 * - 顶部 TopAppBar + 设置图标
 * - LazyColumn 显示所有测量记录卡片（时间、诊断、心率、信号质量、缩略波形）
 * - 空状态提示
 * - 底部手表连接状态
 *
 * @param viewModel 主 ViewModel
 * @param onClickItem 点击记录卡片，传入记录 id 进入详情
 * @param onClickSettings 点击设置图标
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryListScreen(
    viewModel: MobileViewModel,
    onClickItem: (Long) -> Unit,
    onClickSettings: () -> Unit,
) {
    val measurements by viewModel.measurements.collectAsState()
    val watchName by viewModel.connectedWatchName.collectAsState()
    val bleStatus by viewModel.bleSyncStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ECG 历史记录") },
                actions = {
                    IconButton(onClick = onClickSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (measurements.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(measurements, key = { it.id }) { item ->
                        HistoryItemCard(item = item, onClick = { onClickItem(item.id) })
                    }
                }
            }
            // 底部手表连接状态
            WatchConnectionBar(
                watchName = watchName,
                bleStatus = bleStatus,
                onRefresh = { viewModel.refreshWatchConnection() },
            )
        }
    }
}

/** 空状态提示 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "暂无测量记录",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "请用手表测量心电图后\n点击右上角设置同步数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 单条历史记录卡片 */
@Composable
private fun HistoryItemCard(
    item: EcgMeasurementEntity,
    onClick: () -> Unit,
) {
    val diagnosisList = remember(item.diagnosis) {
        if (item.diagnosis.isBlank()) emptyList() else item.diagnosis.split(",")
    }
    val samples = remember(item.downsampledEcgBytes) {
        try { EcgBinaryCodec.decode(item.downsampledEcgBytes) } catch (e: Exception) { emptyList() }
    }
    val cardColor = if (item.isAbnormal)
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    else CardDefaults.cardColors()

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = cardColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(item.timestamp),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${item.avgHeartRate} bpm",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.isAbnormal) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = diagnosisToText(diagnosisList),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // 缩略波形
            if (samples.isNotEmpty()) {
                MobileEcgChart(
                    samples = samples,
                    modifier = Modifier.fillMaxWidth(),
                    interactive = false,
                    chartHeight = 100.dp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "信号质量 ${(item.signalQuality * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.signalQuality >= 0.7) Color(0xFF388E3C)
                            else MaterialTheme.colorScheme.error,
                )
                if (item.pacCount + item.pvcCount > 0) {
                    Text(
                        text = "早搏 ${item.pacCount + item.pvcCount} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 底部手表连接状态条 */
@Composable
private fun WatchConnectionBar(watchName: String?, bleStatus: String, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Watch,
            contentDescription = null,
            tint = if (watchName != null) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = if (watchName != null) "GMS 已连接：$watchName" else bleStatus,
            style = MaterialTheme.typography.bodySmall,
            color = if (watchName != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "刷新连接状态",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 格式化时间戳为可读字符串 */
private fun formatTime(ts: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ts))
}
