package com.example.presentation.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.core.model.TriStateCapability
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderConfiguration
import com.example.domain.core.provider.ProviderFlavor
import com.example.domain.core.task.AutonomyPolicy
import com.example.presentation.state.UiState
import com.example.presentation.viewmodel.MainViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsCapabilitiesScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("screen_models_capabilities"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "لوحة تحكم المزودين والموارد (Control Plane)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "إدارة حقيقية للمزودين، المفاتيح المشفرة، واختبار الاتصال في الوقت الفعلي",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.discoverModels() },
                        modifier = Modifier.testTag("btn_trigger_model_discovery")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "استكشاف النماذج")
                    }
                }
            }
        }

        // Section: Provider Management Control Plane
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "المزودون والموارد النشطة (${state.providerConfigurations.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = { viewModel.openAddProviderDialog() },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_add_provider")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("إضافة مزود")
                }
            }
        }

        // Provider Configurations List
        items(state.providerConfigurations, key = { it.id }) { provider ->
            ProviderConfigCard(
                provider = provider,
                isTesting = state.isTestingProvider && state.testingProviderId == provider.id,
                onToggle = { isEnabled -> viewModel.toggleProvider(provider.id, isEnabled) },
                onTest = { viewModel.testProviderConnection(provider.id) },
                onSetDefault = { viewModel.setAsDefaultProvider(provider.id, provider.category) },
                onEdit = { viewModel.openAddProviderDialog(provider) },
                onDelete = { viewModel.deleteProvider(provider.id) }
            )
        }

        // Section: Network & Offline Policy Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "سياسة الاتصال والعمل دون إنترنت (Network Policy)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NetworkPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = state.networkPolicy == policy,
                                onClick = { viewModel.setNetworkPolicy(policy) },
                                label = { Text(policy.displayName.split(" ")[0], style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("chip_net_policy_${policy.code}")
                            )
                        }
                    }
                }
            }
        }

        // Section: Autonomy Policy Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "سياسة استقلالية الوكيل (Autonomy Policy)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AutonomyPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = state.autonomyPolicy == policy,
                                onClick = { viewModel.setAutonomyPolicy(policy) },
                                label = { Text(policy.displayName.split(" ")[0], style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("chip_autonomy_policy_${policy.code}")
                            )
                        }
                    }
                }
            }
        }

        // Section: Discovered Models Matrix
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "مصفوفة النماذج المستكشفة (Capability Matrix - ${state.discoveredModels.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        items(state.discoveredModels) { model ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = model.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(
                                text = "المزود: ${model.providerId} | نافذة السياق: ${model.contextWindowTokens?.let { "$it tokens" } ?: "غير محدد"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (model.isLocalOnDevice) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = if (model.isLocalOnDevice) "محلي (Local Edge)" else "سحابي (Cloud)",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (model.supportsReasoning == TriStateCapability.SUPPORTED) {
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("تفكير متسلسل (Reasoning)", modifier = Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (model.supportsVision == TriStateCapability.SUPPORTED) {
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("رؤية بصرية (Vision)", modifier = Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (model.supportsToolCalling == TriStateCapability.SUPPORTED) {
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("استدعاء أدوات (Tools)", modifier = Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // Add / Edit Provider Dialog
    if (state.isAddProviderDialogOpen) {
        AddEditProviderDialog(
            editingProvider = state.editingProvider,
            onDismiss = { viewModel.closeAddProviderDialog() },
            onSave = { config, secretKey -> viewModel.saveProvider(config, secretKey) }
        )
    }
}

@Composable
private fun ProviderConfigCard(
    provider: ProviderConfiguration,
    isTesting: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("card_provider_${provider.id}"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Name, Category, Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (provider.isDefault) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "افتراضي",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        text = "${provider.category.displayName} • ${provider.flavor.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = provider.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("switch_provider_${provider.id}")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Details: Endpoint, Model, Secret status
            if (provider.endpointUrl.isNotBlank()) {
                Text(
                    text = "نقطة النهاية: ${provider.endpointUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (provider.defaultModelId.isNotBlank()) {
                Text(
                    text = "النموذج الافتراضي: ${provider.defaultModelId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status Badge & Latency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (badgeBg, badgeText) = when (provider.healthStatus) {
                        HealthStatus.HEALTHY -> MaterialTheme.colorScheme.primaryContainer to "جاهز ويعمل (Healthy)"
                        HealthStatus.DEGRADED -> MaterialTheme.colorScheme.tertiaryContainer to "أداء منخفض (Degraded)"
                        HealthStatus.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer to "غير متاح (Unavailable)"
                        HealthStatus.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to "غير مفحوص (Unknown)"
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeBg
                    ) {
                        Text(
                            text = badgeText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (provider.lastLatencyMs > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${provider.lastLatencyMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (provider.hasSecretKey) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("مفتاح محمي", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Interactive Action Buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!provider.isDefault) {
                        IconButton(
                            onClick = onSetDefault,
                            modifier = Modifier.size(36.dp).testTag("btn_set_default_${provider.id}")
                        ) {
                            Icon(Icons.Outlined.StarBorder, contentDescription = "تعيين كافتراضي", modifier = Modifier.size(20.dp))
                        }
                    }

                    OutlinedButton(
                        onClick = onTest,
                        enabled = !isTesting,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("btn_test_provider_${provider.id}")
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("اختبار الاتصال", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(36.dp).testTag("btn_edit_provider_${provider.id}")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "تعديل", modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp).testTag("btn_delete_provider_${provider.id}")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (provider.lastErrorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ملاحظة: ${provider.lastErrorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddEditProviderDialog(
    editingProvider: ProviderConfiguration?,
    onDismiss: () -> Unit,
    onSave: (ProviderConfiguration, String?) -> Unit
) {
    var name by remember { mutableStateOf(editingProvider?.name ?: "") }
    var category by remember { mutableStateOf(editingProvider?.category ?: ProviderCategory.LLM) }
    var flavor by remember { mutableStateOf(editingProvider?.flavor ?: ProviderFlavor.OPENAI_COMPATIBLE) }
    var endpointUrl by remember { mutableStateOf(editingProvider?.endpointUrl ?: flavor.defaultEndpoint) }
    var modelId by remember { mutableStateOf(editingProvider?.defaultModelId ?: flavor.defaultModel) }
    var apiKey by remember { mutableStateOf("") }
    var isApiKeyVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (editingProvider == null) "إضافة مزود ذكاء جديد" else "تعديل إعدادات المزود",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Category Selector
                Text("الفئة (Category):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ProviderCategory.LLM, ProviderCategory.SEARCH, ProviderCategory.EMBEDDING).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = {
                                category = cat
                                flavor = when (cat) {
                                    ProviderCategory.LLM -> ProviderFlavor.OPENAI_COMPATIBLE
                                    ProviderCategory.SEARCH -> ProviderFlavor.TAVILY
                                    ProviderCategory.EMBEDDING -> ProviderFlavor.LOCAL_EMBEDDING
                                    else -> ProviderFlavor.OPENAI_COMPATIBLE
                                }
                                endpointUrl = flavor.defaultEndpoint
                                modelId = flavor.defaultModel
                            },
                            label = { Text(cat.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // Flavor Selector
                Text("نوع البروتوكول (Flavor):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val availableFlavors = when (category) {
                        ProviderCategory.LLM -> listOf(ProviderFlavor.GEMINI, ProviderFlavor.OPENAI_COMPATIBLE, ProviderFlavor.OLLAMA)
                        ProviderCategory.SEARCH -> listOf(ProviderFlavor.TAVILY, ProviderFlavor.MULTI_SOURCE_SEARCH)
                        ProviderCategory.EMBEDDING -> listOf(ProviderFlavor.LOCAL_EMBEDDING)
                        else -> listOf(ProviderFlavor.OPENAI_COMPATIBLE)
                    }
                    availableFlavors.forEach { flv ->
                        FilterChip(
                            selected = flavor == flv,
                            onClick = {
                                flavor = flv
                                endpointUrl = flv.defaultEndpoint
                                modelId = flv.defaultModel
                            },
                            label = { Text(flv.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم المزود (Display Name)") },
                    modifier = Modifier.fillMaxWidth().testTag("input_provider_name")
                )

                OutlinedTextField(
                    value = endpointUrl,
                    onValueChange = { endpointUrl = it },
                    label = { Text("نقطة النهاية (Endpoint URL)") },
                    modifier = Modifier.fillMaxWidth().testTag("input_provider_endpoint")
                )

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("معرف النموذج (Default Model ID)") },
                    modifier = Modifier.fillMaxWidth().testTag("input_provider_model")
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("مفتاح API السري (API Key)") },
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                            Icon(
                                if (isApiKeyVisible) Icons.Default.Close else Icons.Default.Key,
                                contentDescription = "تبديل الرؤية"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("input_provider_key")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val id = editingProvider?.id ?: "${flavor.name.lowercase()}_${System.currentTimeMillis()}"
                    val config = ProviderConfiguration(
                        id = id,
                        name = name.ifBlank { flavor.displayName },
                        category = category,
                        flavor = flavor,
                        endpointUrl = endpointUrl,
                        defaultModelId = modelId,
                        isEnabled = editingProvider?.isEnabled ?: true,
                        isDefault = editingProvider?.isDefault ?: false,
                        healthStatus = editingProvider?.healthStatus ?: HealthStatus.UNKNOWN,
                        hasSecretKey = apiKey.isNotBlank() || (editingProvider?.hasSecretKey == true)
                    )
                    onSave(config, apiKey.ifBlank { null })
                },
                modifier = Modifier.testTag("btn_save_provider_config")
            ) {
                Text("حفظ التكوين")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_cancel_provider_config")
            ) {
                Text("إلغاء")
            }
        }
    )
}
