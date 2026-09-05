package com.example.presentation.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.presentation.state.UiState
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * ProviderServiceManagerScreen — smart-workspace control room
 * ============================================================================
 *
 * Complete rebuild (user feedback: "add/edit providers existed but I could
 * never add a provider I can actually use"):
 *
 *   1. Status dashboard — providers / active resources / health at a glance.
 *   2. "Connect Provider" wizard — one guided flow that ends with a USABLE
 *      enabled resource (provider→service→config+key→offering→materialize→
 *      validate→enable) instead of a dead provider row.
 *   3. Action labels in clear Arabic + honest state badges (lifecycle &
 *      health color-coded), no pipeline jargon.
 */
@Composable
fun ProviderServiceManagerScreen(state: UiState, viewModel: MainViewModel) {
    val enabledResources = state.materializedResources.count {
        it.lifecycleState == ResourceLifecycleState.ENABLED
    }
    val healthyCount = state.materializedResources.count {
        it.healthStatus == HealthStatus.HEALTHY
    }
    val downCount = state.materializedResources.count {
        it.healthStatus == HealthStatus.UNAVAILABLE
    }
    val hasActiveLlm = state.materializedResources.any {
        it.resourceType == com.example.domain.core.resource.ResourceType.LLM &&
            it.lifecycleState == ResourceLifecycleState.ENABLED
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("providers_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- Header ----------
        item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "المزوّدون والموارد",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "اربط مزودي الذكاء، أدخل مفاتيحك المشفّرة، وتحكم في الموارد المفعّلة",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- Status dashboard ----------
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    label = "المزوّدون",
                    value = state.generalizedProviders.size.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "موارد مفعّلة",
                    value = enabledResources.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "سليمة / متعطّلة",
                    value = "$healthyCount/$downCount",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ---------- No-LLM guidance banner ----------
        if (!hasActiveLlm) {
            item {
                GuidanceBanner(onConnect = viewModel::openConnectWizard)
            }
        }

        // ---------- Connect button ----------
        item {
            Button(
                onClick = viewModel::openConnectWizard,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_connect_provider"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ربط مزوّد جديد (خبراء + مفتاح واحد)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ---------- Providers ----------
        item {
            Text(
                text = "قائمة المزوّدين (${state.generalizedProviders.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (state.generalizedProviders.isEmpty()) {
            item { EmptyProvidersCard() }
        }
        items(state.generalizedProviders, key = { it.id }) { provider ->
            ProviderCard(
                provider = provider,
                services = state.generalizedServices.filter { it.providerId == provider.id },
                configurations = state.generalizedConfigurations,
                offerings = state.discoveredOfferings,
                resources = state.materializedResources,
                isTesting = state.isTestingProvider,
                testingId = state.testingProviderId,
                isDiscovering = state.isDiscoveringModels,
                viewModel = viewModel
            )
        }

        // ---------- Resources section ----------
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "الموارد التشغيلية (${state.materializedResources.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        items(state.materializedResources, key = { it.resourceId.value }) { resource ->
            ResourceRecordCard(resource = resource, viewModel = viewModel)
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    // Credential dialog (API key entry for an existing service)
    if (state.credentialDialogServiceId != null) {
        CredentialInputDialog(
            serviceName = state.credentialDialogServiceName,
            authAlias = state.credentialDialogAuthAlias,
            inputValue = state.credentialInput,
            isSaving = state.isSavingCredential,
            onValueChange = viewModel::updateCredentialInput,
            onConfirm = viewModel::submitCredential,
            onDismiss = viewModel::closeCredentialDialog
        )
    }

    // Connect wizard
    if (state.isConnectWizardOpen) {
        ConnectProviderWizardDialog(state = state, viewModel = viewModel)
    }
}

/* ==========================================================================
 * Dashboard pieces
 * ========================================================================== */

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GuidanceBanner(onConnect: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "لا يوجد نموذج ذكاء مفعّل",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "الاستوديو يحتاج نموذجاً لغوياً مفعّلاً للعمل. اربط Google Gemini أو Groq أو OpenRouter بمفتاح واحد وسيُفعّل تلقائياً بعد التحقق.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = onConnect) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("اربط الآن")
            }
        }
    }
}

@Composable
private fun EmptyProvidersCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "لا يوجد مزوّدون بعد",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "ابدأ بزر «ربط مزوّد جديد» بالأعلى",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ==========================================================================
 * Provider / service / offering cards
 * ========================================================================== */

@Composable
private fun ProviderCard(
    provider: com.example.domain.core.provider.Provider,
    services: List<com.example.domain.core.provider.ProviderService>,
    configurations: List<com.example.domain.core.provider.ServiceConfiguration>,
    offerings: List<com.example.domain.core.provider.offering.ServiceOffering>,
    resources: List<com.example.domain.core.resource.ResourceRecord>,
    isTesting: Boolean,
    testingId: String?,
    isDiscovering: Boolean,
    viewModel: MainViewModel
) {
    var expanded by remember { mutableStateOf(true) }
    val providerResources = resources.filter { it.providerId == provider.id }
    val enabledCount = providerResources.count { it.lifecycleState == ResourceLifecycleState.ENABLED }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderAvatar(provider = provider)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (providerResources.isEmpty()) {
                            if (provider.isLocal) "مزوّد محلي" else "غير مربوط بموارد بعد"
                        } else {
                            "$enabledCount من ${providerResources.size} موارد مفعّلة"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = provider.isEnabled,
                    onCheckedChange = { viewModel.toggleProvider(provider.id, it) },
                    modifier = Modifier.testTag("switch_provider_" + provider.id)
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "إخفاء" else "توسيع"
                    )
                }
                IconButton(
                    onClick = { viewModel.deleteProvider(provider.id) },
                    modifier = Modifier.testTag("btn_delete_provider_" + provider.id)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    if (provider.description.isNotBlank()) {
                        Text(
                            text = provider.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (services.isEmpty()) {
                        Text(
                            text = "لا توجد خدمات لهذا المزوّد — استخدم «ربط مزوّد جديد» لإضافة خدمة كاملة",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    services.forEach { service ->
                        ServiceRow(
                            provider = provider,
                            service = service,
                            config = configurations.firstOrNull { it.serviceId == service.id },
                            offerings = offerings.filter { it.serviceId == service.id },
                            resources = resources.filter { it.providerId == provider.id && it.serviceId == service.id },
                            isTesting = isTesting,
                            testingId = testingId,
                            isDiscovering = isDiscovering,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderAvatar(provider: com.example.domain.core.provider.Provider) {
    val initial = provider.name.trim().take(1).ifBlank { "؟" }
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (provider.isLocal) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        }
    ) {
        Box(
            modifier = Modifier.size(38.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (provider.isLocal) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ServiceRow(
    provider: com.example.domain.core.provider.Provider,
    service: com.example.domain.core.provider.ProviderService,
    config: com.example.domain.core.provider.ServiceConfiguration?,
    offerings: List<com.example.domain.core.provider.offering.ServiceOffering>,
    resources: List<com.example.domain.core.resource.ResourceRecord>,
    isTesting: Boolean,
    testingId: String?,
    isDiscovering: Boolean,
    viewModel: MainViewModel
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${service.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TypeChip(label = service.serviceType.displayName)
            }
            config?.let {
                Text(
                    text = it.endpointUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (config != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionChip(
                        text = "افحص الاتصال",
                        icon = Icons.Default.CheckCircle,
                        loading = isTesting,
                        enabled = !isTesting && !isDiscovering,
                        onClick = { viewModel.testServiceConnection(config.id) }
                    )
                    ActionChip(
                        text = "اكتشف النماذج",
                        icon = Icons.Default.Search,
                        loading = isDiscovering,
                        enabled = !isTesting && !isDiscovering,
                        onClick = { viewModel.discoverOfferings(service.id) }
                    )
                    ActionChip(
                        text = "مفتاح API",
                        icon = Icons.Default.Key,
                        loading = false,
                        enabled = true,
                        onClick = {
                            viewModel.openCredentialDialog(
                                serviceId = service.id,
                                serviceName = service.name,
                                authAlias = config.authAlias ?: service.id
                            )
                        },
                        tag = "btn_enter_api_key_" + service.id
                    )
                }
            }

            offerings.forEach { offering ->
                OfferingRow(
                    provider = provider,
                    offering = offering,
                    resources = resources,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun TypeChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ActionChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .height(34.dp)
            .let { m -> tag?.let { m.testTag(it) } ?: m }
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun OfferingRow(
    provider: com.example.domain.core.provider.Provider,
    offering: com.example.domain.core.provider.offering.ServiceOffering,
    resources: List<com.example.domain.core.resource.ResourceRecord>,
    viewModel: MainViewModel
) {
    val matching = resources.firstOrNull { it.metadata["offeringId"] == offering.id }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = offering.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (matching == null) {
            Button(
                onClick = {
                    viewModel.materializeResource(
                        providerId = provider.id,
                        serviceId = offering.serviceId,
                        offeringId = offering.id
                    )
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("btn_materialize_" + offering.id)
            ) {
                Text("تهيئة للاستخدام", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            HealthBadge(health = matching.healthStatus)
            Spacer(modifier = Modifier.width(6.dp))
            LifecycleBadge(state = matching.lifecycleState)
        }
    }
}

@Composable
private fun HealthBadge(health: HealthStatus) {
    val (label, color) = when (health) {
        HealthStatus.HEALTHY -> "سليم" to MaterialTheme.colorScheme.tertiary
        HealthStatus.DEGRADED -> "متراجع" to Color(0xFFF59E0B)
        HealthStatus.UNAVAILABLE -> "متعطّل" to MaterialTheme.colorScheme.error
        else -> "غير معروف" to MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, androidx.compose.foundation.shape.CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
private fun LifecycleBadge(state: ResourceLifecycleState) {
    val (label, color) = when (state) {
        ResourceLifecycleState.ENABLED, ResourceLifecycleState.ACTIVE ->
            "مفعّل" to MaterialTheme.colorScheme.primary
        ResourceLifecycleState.DISABLED -> "معطّل" to MaterialTheme.colorScheme.outline
        ResourceLifecycleState.REGISTERED -> "بانتظار التحقق" to Color(0xFFF59E0B)
        else -> state.name.lowercase() to MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ResourceRecordCard(
    resource: com.example.domain.core.resource.ResourceRecord,
    viewModel: MainViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resource.metadata["offeringId"]?.toString() ?: resource.resourceId.value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${resource.resourceType} • إصدار الإعداد ${resource.configurationVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HealthBadge(health = resource.healthStatus)
            Spacer(modifier = Modifier.width(6.dp))
            LifecycleBadge(state = resource.lifecycleState)

            Spacer(modifier = Modifier.width(8.dp))
            if (resource.lifecycleState == ResourceLifecycleState.ENABLED) {
                ActionChip(
                    text = "إيقاف",
                    icon = Icons.Default.Api,
                    loading = false,
                    enabled = true,
                    onClick = { viewModel.disableResource(resource.resourceId.value) }
                )
            } else if (resource.runtimeSupported) {
                ActionChip(
                    text = "تفعيل",
                    icon = Icons.Default.CheckCircle,
                    loading = false,
                    enabled = true,
                    onClick = { viewModel.enableResource(resource.resourceId.value) }
                )
            }
            ActionChip(
                text = "إعادة التحقق",
                icon = Icons.Default.Refresh,
                loading = false,
                enabled = true,
                onClick = { viewModel.validateResource(resource.resourceId.value) }
            )
        }
    }
}

/* ==========================================================================
 * Credential dialog (existing services)
 * ========================================================================== */

@Composable
private fun CredentialInputDialog(
    serviceName: String,
    authAlias: String?,
    inputValue: String,
    isSaving: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        shape = RoundedCornerShape(20.dp),
        title = { Text("مفتاح API — $serviceName") },
        text = {
            Column {
                Text(
                    text = "يُحفظ المفتاح مشفراً في قبو الاعتمادات" +
                        (if (authAlias != null) " (المعرّف: $authAlias)" else "") +
                        " ثم يُختبر الاتصال فوراً.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = onValueChange,
                    label = { Text("مفتاح API") },
                    visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (visible) "إخفاء" else "إظهار"
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("credential_input_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isSaving && inputValue.isNotBlank(),
                modifier = Modifier.testTag("credential_confirm_button")
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("حفظ واختبار")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("إلغاء") }
        }
    )
}

/* ==========================================================================
 * Connect Provider wizard — the guided full-chain dialog
 * ==========================================================================

   Three visual phases:
     A. Preset selection  — grid of known providers (Gemini/OpenAI/Groq/…)
     B. Configuration     — name / endpoint / model / API key (+ hints)
     C. Progress/Result   — 6 honest steps with live status, then success or
                            a precise failure diagnostic with next actions.
*/
@Composable
private fun ConnectProviderWizardDialog(state: UiState, viewModel: MainViewModel) {
    var selectedPreset by remember { mutableStateOf<ProviderPreset?>(null) }
    var providerName by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { viewModel.closeConnectWizard() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        state.wizardRunning -> "جاري الربط…"
                        selectedPreset == null -> "اختر المزوّد"
                        else -> "إعداد ${selectedPreset?.displayName ?: ""}"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when {
                    // ---------- Phase C: running ----------
                    state.wizardRunning -> WizardProgressPanel(
                        currentStep = state.wizardStep,
                        stepLabel = state.wizardStepLabel
                    )

                    // ---------- Phase C: result ----------
                    state.wizardResult != null -> WizardResultPanel(
                        isSuccess = state.wizardResultIsSuccess,
                        message = state.wizardResult ?: "",
                        onDone = viewModel::closeConnectWizard,
                        onRetry = {
                            selectedPreset = null
                            viewModel.closeConnectWizard()
                            viewModel.openConnectWizard()
                        }
                    )

                    // ---------- Phase A: preset grid ----------
                    selectedPreset == null -> PresetSelectionPanel { preset ->
                        selectedPreset = preset
                        providerName = preset.displayName
                        endpointUrl = preset.defaultEndpoint
                        modelName = preset.defaultModel
                        apiKey = ""
                    }

                    // ---------- Phase B: configuration form ----------
                    else -> WizardConfigForm(
                        preset = selectedPreset!!,
                        providerName = providerName,
                        onProviderName = { providerName = it },
                        endpointUrl = endpointUrl,
                        onEndpointUrl = { endpointUrl = it },
                        modelName = modelName,
                        onModelName = { modelName = it },
                        apiKey = apiKey,
                        onApiKey = { apiKey = it },
                        keyVisible = keyVisible,
                        onToggleKey = { keyVisible = it },
                        onBack = { selectedPreset = null },
                        onConfirm = {
                            viewModel.connectProviderFullChain(
                                preset = selectedPreset!!,
                                providerName = providerName.trim().ifBlank { selectedPreset!!.displayName },
                                endpointUrl = endpointUrl.trim(),
                                modelName = modelName.trim(),
                                apiKey = apiKey.trim().ifBlank { null }
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun PresetSelectionPanel(onSelect: (ProviderPreset) -> Unit) {
    Column {
        Text(
            text = "اختر خدمة الذكاء التي تريد ربطها. كل مزوّد يحتاج مفتاحاً واحداً فقط، وسيُفعّل تلقائياً بعد التحقق الفعلي من الاتصال.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        PROVIDER_PRESETS.forEach { preset ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelect(preset) }
                    .testTag("preset_" + preset.id),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = preset.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (preset.isLocal) {
                                Spacer(modifier = Modifier.width(6.dp))
                                TypeChip(label = "محلي")
                            }
                        }
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardConfigForm(
    preset: ProviderPreset,
    providerName: String,
    onProviderName: (String) -> Unit,
    endpointUrl: String,
    onEndpointUrl: (String) -> Unit,
    modelName: String,
    onModelName: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    keyVisible: Boolean,
    onToggleKey: (Boolean) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    Column {
        WizardLabeledField(label = "الاسم الظاهر", value = providerName, onChange = onProviderName)
        Spacer(modifier = Modifier.height(8.dp))
        WizardLabeledField(
            label = "عنوان الخدمة (Endpoint)",
            value = endpointUrl,
            onChange = onEndpointUrl,
            placeholder = preset.defaultEndpoint
        )
        Spacer(modifier = Modifier.height(8.dp))
        WizardLabeledField(
            label = preset.offeringIdLabel,
            value = modelName,
            onChange = onModelName,
            placeholder = preset.defaultModel
        )
        if (preset.suggestedModels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "نماذج مقترحة:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                preset.suggestedModels.forEach { model ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier.clickable { onModelName(model) }
                    ) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKey,
            label = {
                Text(if (preset.requiresApiKey) "مفتاح API (مطلوب)" else "مفتاح API (اختياري)")
            },
            visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { onToggleKey(!keyVisible) }) {
                    Icon(
                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "إخفاء" else "إظهار"
                    )
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wizard_api_key_field")
        )
        preset.keyHint?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) { Text("رجوع") }
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1.4f)
                    .testTag("wizard_connect_button"),
                enabled = providerName.isNotBlank() &&
                    endpointUrl.isNotBlank() &&
                    modelName.isNotBlank() &&
                    (!preset.requiresApiKey || apiKey.isNotBlank())
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("اربط وفعّل")
            }
        }
    }
}

@Composable
private fun WizardLabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private val WIZARD_STEPS = listOf(
    "إنشاء المزوّد",
    "تسجيل الخدمة",
    "حفظ الإعدادات والمفتاح",
    "تسجيل النموذج",
    "تهيئة المورد",
    "التحقق الفعلي من الاتصال"
)

@Composable
private fun WizardProgressPanel(currentStep: Int, stepLabel: String?) {
    Column {
        LinearProgressIndicator(
            progress = { (currentStep.coerceIn(1, 6) / 6f) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stepLabel ?: "",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(10.dp))
        WIZARD_STEPS.forEachIndexed { index, stepName ->
            val stepNumber = index + 1
            val done = stepNumber < currentStep
            val active = stepNumber == currentStep
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 3.dp)
            ) {
                when {
                    done -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    active -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    else -> Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stepName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (done || active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun WizardResultPanel(
    isSuccess: Boolean,
    message: String,
    onDone: () -> Unit,
    onRetry: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (isSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) { Text("مزوّد آخر") }
            Button(
                onClick = onDone,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) { Text("تم") }
        }
    }
}
