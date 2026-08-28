package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.WorkspaceViewModel

@Composable
fun SettingsPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val isOffline by viewModel.isOfflineMode.collectAsState()
    var promptInjectionProtection by remember { mutableStateOf(true) }
    var etaParam by remember { mutableStateOf(0.30f) }
    var gammaParam by remember { mutableStateOf(0.90f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "إعدادات المنظومة ونموذج القرار (System & CBR Settings)",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "تهيئة بيئة التشغيل، ومعاملات التقييم الرياضي، وسياسات الأمان والحماية.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Offline Mode Card
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("فرض وضع عدم الاتصال (Enforce Offline Mode)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("تشغيل جميع النماذج محلياً (Native Heuristics & Local RAG) دون أي طلبات خارجية.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isOffline,
                        onCheckedChange = { viewModel.setOfflineMode(it) },
                        modifier = Modifier.testTag("offline_mode_switch")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Security Barrier Card
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("حاجز الحماية من حقن التوجيهات (Prompt Injection Guard)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("فحص النصوص باللغتين العربية والإنجليزية وعزل البيانات غير الموثوقة من الأدوات.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = promptInjectionProtection,
                        onCheckedChange = { promptInjectionProtection = it },
                        modifier = Modifier.testTag("security_guard_switch")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // CBR-MDP Mathematical Hyperparameters
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("معاملات نموذج القرار CBR-MDP", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text("عامل مسافة Wasserstein (\u03b7 = ${String.format("%.2f", etaParam)}):", fontSize = 11.sp)
                Slider(
                    value = etaParam,
                    onValueChange = { etaParam = it },
                    valueRange = 0.1f..1.0f,
                    steps = 9
                )

                Text("معامل الخصم الزمني للقرار (\u03b3 = ${String.format("%.2f", gammaParam)}):", fontSize = 11.sp)
                Slider(
                    value = gammaParam,
                    onValueChange = { gammaParam = it },
                    valueRange = 0.5f..0.99f,
                    steps = 10
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // System Architecture Info
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("معلومات المعمارية الأصلية (Native Architecture)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("• بيئة التشغيل: Native Kotlin (Android Studio Project)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• قاعدة البيانات المحلية: Room SQLite DB (Offline-First Schema)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• محرك المتجهات: Native Bag-of-Tokens Cosine Similarity Engine", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• واجهة المستخدم: Jetpack Compose Material Design 3 (Data-Driven Layout)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
