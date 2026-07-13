package com.wearhealth.companion.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.wearhealth.companion.mobile.data.EcgStore
import com.wearhealth.companion.mobile.settings.SecureSettings
import com.wearhealth.companion.mobile.sync.WearProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { contentResolver.openOutputStream(it)?.use { stream ->
            // Basic valid PDF; detailed, multi-page ECG rendering will use the archived waveform in the next report pass.
            stream.write(PDF_BYTES)
        } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CompanionApp(onPushKey = ::saveAndPushKey, onExport = { exportPdf.launch("ECG-Health-Report.pdf") }) } }
    }

    private fun saveAndPushKey(value: String) {
        SecureSettings(this).saveApiKey(value)
        lifecycleScope.launch(Dispatchers.IO) {
            val request = PutDataMapRequest.create(WearProtocol.CONFIG_PATH).apply {
                dataMap.putString("apiKey", value.trim())
                dataMap.putLong("nonce", System.nanoTime())
            }.asPutDataRequest().setUrgent()
            runCatching { Tasks.await(Wearable.getDataClient(this@MainActivity).putDataItem(request)) }
        }
    }

    private companion object {
        val PDF_BYTES = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>endobj\n4 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n5 0 obj<</Length 93>>stream\nBT /F1 18 Tf 72 760 Td (WearHealth Companion ECG Health Reference Report) Tj 0 -30 Td /F1 11 Tf (Not a medical diagnosis.) Tj ET\nendstream\nendobj\nxref\n0 6\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \n0000000258 00000 n \n0000000328 00000 n \ntrailer<</Size 6/Root 1 0 R>>\nstartxref\n474\n%%EOF".toByteArray()
    }
}

@Composable
private fun CompanionApp(onPushKey: (String) -> Unit, onExport: () -> Unit) {
    val context = LocalContext.current
    val records by EcgStore.db(context).ecgDao().observeAll().collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var apiKey by remember { mutableStateOf(SecureSettings(context).apiKey()) }
    Scaffold(
        bottomBar = { NavigationBar { listOf("报告", "设置").forEachIndexed { index, label ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = {}, label = { Text(label) })
        } } }
    ) { contentPadding ->
        if (tab == 0) {
            LazyColumn(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("心电报告", style = MaterialTheme.typography.headlineSmall) }
                if (records.isEmpty()) item { Text("暂无从手表同步的记录") }
                items(records) { record -> ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.diagnosisCsv.ifBlank { "未检测到诊断标签" }, style = MaterialTheme.typography.titleMedium)
                        Text("心率 ${record.heartRate} bpm · 信号质量 ${(record.signalQuality * 100).toInt()}%")
                        Text("PR ${record.pr} ms · QRS ${record.qrs} ms · QTc ${record.qtc} ms")
                        Button(onClick = onExport) { Text("导出 PDF 健康参考报告") }
                    }
                } }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("设置", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("HeartVoice API Key") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onPushKey(apiKey) }) { Text("保存并自动推送到手表") }
                Text("用户资料字段（姓名、性别、出生日期、身高、体重）和完整波形 PDF 将在报告完善版本中提供。")
            }
        }
    }
}
