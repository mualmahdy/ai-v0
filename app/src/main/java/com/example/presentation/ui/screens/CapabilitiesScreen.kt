package com.example.presentation.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.capability.CapabilityState
import com.example.domain.core.capability.CapabilityType
import com.example.presentation.state.UiState
import com.example.presentation.viewmodel.MainViewModel

@Composable
fun CapabilitiesScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("capabilities_screen")
    ) {
        Text(
            text = "سجل الإمكانيات والأدوات (Capabilities Registry)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "فهرس الإمكانيات والأدوات المسجلة ديناميكياً والمتاحة للوكلاء وفق سياسات الأمان.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (state.capabilities.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "جاري استرجاع سجل الإمكانيات...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.capabilities) { cap ->
                    CapabilityItemCard(capability = cap)
                }
            }
        }
    }
}

@Composable
private fun CapabilityItemCard(capability: CapabilityDescriptor) {
    val stateColor = when (capability.state) {
        CapabilityState.AVAILABLE -> Color(0xFF2E7D32)
        CapabilityState.DEGRADED -> Color(0xFFE65100)
        CapabilityState.UNAVAILABLE -> Color(0xFFC62828)
    }

    val stateLabel = when (capability.state) {
        CapabilityState.AVAILABLE -> "متاح (Available)"
        CapabilityState.DEGRADED -> "تراجع تشغيلي (Degraded)"
        CapabilityState.UNAVAILABLE -> "غير متاح (Unavailable)"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("capability_card_${capability.type.code}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (capability.type) {
                                CapabilityType.LLM_GENERATION -> Icons.Default.Psychology
                                CapabilityType.STREAMING -> Icons.Default.Bolt
                                CapabilityType.VECTOR_STORE, CapabilityType.MEMORY_RETRIEVAL -> Icons.Default.Memory
                                CapabilityType.TOOL_EXECUTION -> Icons.Default.Build
                                CapabilityType.FILE_STORAGE -> Icons.Default.Folder
                                else -> Icons.Default.DataObject
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = capability.type.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "الرمز: ${capability.type.code} | المزود: ${capability.providerId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = stateColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = stateColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (capability.degradedReason != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "سبب التراجع: ${capability.degradedReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
