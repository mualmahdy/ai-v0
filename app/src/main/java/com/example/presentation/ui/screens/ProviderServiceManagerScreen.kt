package com.example.presentation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.presentation.state.UiState
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * ProviderServiceManagerScreen — Phase 4 UI
 * ============================================================================
 *
 * Minimal functional provider/service management UI per the architectural plan
 * (Section 23):
 *
 *   Provider: create, rename, delete, toggle
 *   Service: add service, select service type, select protocol
 *   Configuration: endpoint, credentials/auth alias, headers, timeout, enable/disable
 *   Validation: Test Connection (explicit, real POST)
 *   Discovery: Discover Models/Offerings (explicit, produces ServiceOfferings)
 *   Selection: inspect discovered offerings, select one
 *   Materialization: create concrete resource
 *   Validation: validate resource (per-ResourceType)
 *   Runtime state: lifecycle, health, runtimeSupported, configurationVersion
 *
 * The user must NOT see "save = ready to use" semantics. Saving a configuration
 * is pure persistence. Test, Discover, Materialize, Validate are all explicit
 * separate buttons.
 */
@Composable
fun ProviderServiceManagerScreen(state: UiState, viewModel: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "لوحة تحكم المزودين والخدمات (Phase 4)",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Provider → Service → Protocol → Configuration → Test → Discover → Materialize → Validate → Enable",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // === Providers section ===
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "المزودون (${state.generalizedProviders.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.openAddProviderDialog() },
                    modifier = Modifier.semantics { testTag = "btn_add_provider" }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Provider")
                }
            }
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

        // === Materialized resources section ===
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "الموارد المُفعّلة (${state.materializedResources.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }
        items(state.materializedResources, key = { it.resourceId.value }) { resource ->
            ResourceRecordCard(resource = resource, viewModel = viewModel)
        }
    }

    // FIX F-4 (audit c03919d): the real credential input dialog — the missing
    // user path for entering API keys. Rendered whenever the dialog state is set.
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

    // FIX F-4 (phantom dialog): isAddProviderDialogOpen was set but NO dialog
    // was ever rendered — the "+" button was a no-op. Now a real dialog exists.
    if (state.isAddProviderDialogOpen) {
        AddProviderDialog(
            onDismiss = viewModel::closeAddProviderDialog,
            onConfirm = { name, description, websiteUrl, isLocal ->
                viewModel.createProvider(name, description, websiteUrl, isLocal)
            }
        )
    }
}

/**
 * FIX F-4: dialog for creating a new Provider (previously a phantom flag).
 */
@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, websiteUrl: String?, isLocal: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var websiteUrl by remember { mutableStateOf("") }
    var isLocal by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مزوّد جديد") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم المزوّد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "new_provider_name" }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("الوصف") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = websiteUrl,
                    onValueChange = { websiteUrl = it },
                    label = { Text("رابط الموقع (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isLocal, onCheckedChange = { isLocal = it })
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("مزوّد محلي (بدون شبكة)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), description.trim(), websiteUrl.trim().ifBlank { null }, isLocal) },
                enabled = name.isNotBlank()
            ) { Text("إنشاء") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

/**
 * FIX F-4: dialog for entering a provider API key. The key is stored in the
 * EncryptedSecretStorage vault and immediately validated with a real
 * connection test.
 */
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
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("إدخال مفتاح API — $serviceName") },
        text = {
            Column {
                Text(
                    text = "سيُحفظ المفتاح مشفراً في قبو الاعتمادات" +
                        (if (authAlias != null) " (المعرّف: $authAlias)" else "") +
                        " ثم يُختبر الاتصال فوراً.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = onValueChange,
                    label = { Text("مفتاح API") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "credential_input_field" }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isSaving && inputValue.isNotBlank(),
                modifier = Modifier.semantics { testTag = "credential_confirm_button" }
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
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (provider.isLocal) {
                    Text(text = "محلي", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { viewModel.toggleProvider(provider.id, !provider.isEnabled) }) {
                    Icon(Icons.Default.Settings, contentDescription = "Toggle")
                }
                IconButton(onClick = { viewModel.deleteProvider(provider.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            Text(
                text = "ID: ${provider.id} | Services: ${services.size}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Services
            services.forEach { service ->
                ServiceRow(
                    provider = provider,
                    service = service,
                    config = configurations.firstOrNull { it.serviceId == service.id },
                    offerings = offerings.filter { it.serviceId == service.id },
                    resources = resources.filter { it.providerId == provider.id && it.serviceId == service.id },
                    isTesting = isTesting && testingId != null,
                    isDiscovering = isDiscovering,
                    viewModel = viewModel
                )
            }
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
    isDiscovering: Boolean,
    viewModel: MainViewModel
) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "${service.name} (${service.serviceType.displayName})",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Protocols: ${service.supportedProtocolIds.joinToString()}",
                style = MaterialTheme.typography.bodySmall
            )

            if (config != null) {
                Text(text = "Endpoint: ${config.endpointUrl}", style = MaterialTheme.typography.bodySmall)
                Text(text = "Version: ${config.configurationVersion}", style = MaterialTheme.typography.bodySmall)

                Row(modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.testServiceConnection(config.id) },
                        enabled = !isTesting,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("Test")
                    }
                    OutlinedButton(
                        onClick = { viewModel.discoverOfferings(service.id) },
                        enabled = !isDiscovering,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("Discover")
                    }
                    // FIX F-4: real path to enter the API key for this service —
                    // opens the credential dialog bound to the vault.
                    OutlinedButton(
                        onClick = {
                            viewModel.openCredentialDialog(
                                serviceId = service.id,
                                serviceName = service.name,
                                authAlias = config.authAlias ?: service.id
                            )
                        },
                        modifier = Modifier.padding(end = 4.dp).semantics { testTag = "btn_enter_api_key_" + service.id }
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("API Key")
                    }
                }
            }

            // Offerings
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
private fun OfferingRow(
    provider: com.example.domain.core.provider.Provider,
    offering: com.example.domain.core.provider.offering.ServiceOffering,
    resources: List<com.example.domain.core.resource.ResourceRecord>,
    viewModel: MainViewModel
) {
    val isMaterialized = resources.any {
        it.metadata["offeringId"] == offering.id
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            Text(
                text = "${offering.offeringType.code}: ${offering.name}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (!isMaterialized) {
                // FIX F-5 (audit c03919d): the Materialize button is now actually
                // wired — previously onClick was an empty body with a TODO-style
                // comment, so no resource could ever be created from the UI.
                Button(
                    onClick = {
                        viewModel.materializeResource(
                            providerId = provider.id,
                            serviceId = offering.serviceId,
                            offeringId = offering.id
                        )
                    },
                    modifier = Modifier.padding(horizontal = 4.dp).semantics {
                        testTag = "btn_materialize_" + offering.id
                    }
                ) {
                    Text("Materialize")
                }
            } else {
                val resource = resources.first { it.metadata["offeringId"] == offering.id }
                OutlinedButton(
                    onClick = { viewModel.validateResource(resource.resourceId.value) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Validate")
                }
            }
        }
    }
}

@Composable
private fun ResourceRecordCard(
    resource: com.example.domain.core.resource.ResourceRecord,
    viewModel: MainViewModel
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = resource.resourceId.value,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Type: ${resource.resourceType} | Lifecycle: ${resource.lifecycleState}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "runtimeSupported: ${resource.runtimeSupported} | health: ${resource.healthStatus}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Version: ${resource.configurationVersion}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                if (resource.lifecycleState == com.example.domain.core.resource.ResourceLifecycleState.ENABLED) {
                    OutlinedButton(onClick = { viewModel.disableResource(resource.resourceId.value) }) {
                        Text("Disable")
                    }
                } else if (resource.runtimeSupported) {
                    Button(onClick = { viewModel.enableResource(resource.resourceId.value) }) {
                        Text("Enable")
                    }
                }
            }
        }
    }
}
