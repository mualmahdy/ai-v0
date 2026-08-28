package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.db.entities.ModelProviderEntity
import com.example.data.local.db.entities.SearchProviderEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.viewmodel.WorkspaceViewModel

@Composable
fun ProvidersPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val modelProviders by viewModel.modelProviders.collectAsState()
    val searchProviders by viewModel.searchProviders.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "مزودو النماذج والبحث (Model & Search Providers)",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "تنسيق الأدوار وحماية قواطع الدائرة (Circuit Breaker) والتحويل التلقائي عند انقطاع الشبكة.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text("مزودو نماذج الذكاء الاصطناعي:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(modelProviders) { provider ->
                ModelProviderCard(provider)
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                Text("مزودو البحث والاسترجاع:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
            }

            items(searchProviders) { sp ->
                SearchProviderCard(sp)
            }
        }
    }
}

@Composable
fun ModelProviderCard(provider: ModelProviderEntity) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (provider.isOnlineOnly) Icons.Default.Cloud else Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(provider.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (provider.enabled) EmeraldSuccess.copy(alpha = 0.15f) else AmberWarning.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (provider.enabled) "نشط" else "معطل",
                        fontSize = 10.sp,
                        color = if (provider.enabled) EmeraldSuccess else AmberWarning,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("النوع: ${provider.providerType} | النموذج الافتراضي: ${provider.defaultModel ?: "Auto"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("حالة القاطع (Circuit Breaker): مغلق (طبيعي) | الأولوية: #${provider.priority}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SearchProviderCard(sp: SearchProviderEntity) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(sp.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (sp.isOnlineOnly) AmberWarning.copy(alpha = 0.15f) else EmeraldSuccess.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (sp.isOnlineOnly) "Online Web" else "Offline Native",
                        fontSize = 10.sp,
                        color = if (sp.isOnlineOnly) AmberWarning else EmeraldSuccess,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("استراتيجية البحث: أولوية رقم #${sp.priority} (${sp.providerType})", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
