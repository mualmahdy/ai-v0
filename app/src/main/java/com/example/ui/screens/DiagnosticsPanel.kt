package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runtime.events.SystemEvent
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.Slate850
import com.example.ui.theme.Slate950
import com.example.ui.viewmodel.WorkspaceViewModel

@Composable
fun DiagnosticsPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val eventsList = remember { mutableStateListOf<SystemEvent>() }

    LaunchedEffect(Unit) {
        viewModel.systemEvents.collect { event ->
            eventsList.add(0, event)
            if (eventsList.size > 100) eventsList.removeLast()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "تشخيص النظام وسجل الأحداث (EventBus Stream)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "بث فوري لجميع أحداث الوكلاء، واستدعاء الأدوات، ونماذج التقييم.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { eventsList.clear() }) {
                Icon(Icons.Default.Clear, contentDescription = "مسح السجل")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // System Resource State Card
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricColumn("حالة المنظومة", "STABLE", EmeraldSuccess)
                MetricColumn("حالة الشبكة", if (viewModel.isOfflineMode.collectAsState().value) "OFFLINE" else "ONLINE", CyanPrimary)
                MetricColumn("الأحداث المسجلة", "${eventsList.size}", MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("سجل الأحداث التفاعلي الحي (Live Streaming):", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(Slate950, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(eventsList) { event ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[${event.timestamp}]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Slate850
                    ) {
                        Text(
                            text = event.topic,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = CyanPrimary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = event.summary,
                        fontSize = 11.sp,
                        color = Color(0xFFE2E8F0),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun MetricColumn(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
