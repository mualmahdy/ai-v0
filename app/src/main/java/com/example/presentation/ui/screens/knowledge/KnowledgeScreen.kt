package com.example.presentation.ui.screens.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.rag.KnowledgePersistenceState
import com.example.presentation.ui.components.BusyIndicator
import com.example.presentation.ui.components.ConfirmDialog
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.InfoRow
import com.example.presentation.ui.components.MetricBar
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * KnowledgeScreen — knowledge base + long-term memory + semantic engine
 * ============================================================================
 *
 * Full RAG surface, previously primitive: retrieval with per-chunk scores
 * and modes, document management (list + delete with honest persistence
 * states + ingest dialog), the LONG-TERM MEMORY browser (search / add /
 * browse — VM functions that existed with NO screen), and the local ONNX
 * semantic engine provisioning surface.
 */
@Composable
fun KnowledgeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var ingestDialogOpen by rememberSaveable { mutableStateOf(false) }
    var deleteDocTarget by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = modifier.testTag("screen_knowledge_rag")) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("قاعدة المعرفة", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("الذاكرة طويلة المدى", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("المحرك الدلالي", fontWeight = FontWeight.Bold) }
            )
        }

        when (selectedTab) {
            0 -> KnowledgeBaseTab(
                state = state,
                viewModel = viewModel,
                onIngest = { ingestDialogOpen = true },
                onDeleteDoc = { deleteDocTarget = it }
            )
            1 -> MemoryTab(state = state, viewModel = viewModel)
            2 -> SemanticEngineTab(state = state, viewModel = viewModel)
        }
    }

    if (ingestDialogOpen) {
        IngestDocumentDialog(
            title = state.newDocTitle,
            content = state.newDocContent,
            onTitleChange = viewModel::updateDocTitle,
            onContentChange = viewModel::updateDocContent,
            onConfirm = {
                viewModel.ingestNewDocument()
                ingestDialogOpen = false
            },
            onDismiss = { ingestDialogOpen = false }
        )
    }

    deleteDocTarget?.let { docId ->
        val docTitle = state.knowledgeDocuments.firstOrNull { it.id == docId }?.title ?: ""
        ConfirmDialog(
            title = "حذف المستند",
            message = "سيُحذف «$docTitle» مع كل مقاطعه (Chunks) من قاعدة المعرفة نهائياً.",
            confirmLabel = "حذف",
            onConfirm = {
                viewModel.deleteKnowledgeDocument(docId)
                deleteDocTarget = null
            },
            onDismiss = { deleteDocTarget = null }
        )
    }
}

// ---------------------------------------------------------------------------
// Tab 0 — Knowledge base (RAG)
// ---------------------------------------------------------------------------

@Composable
private fun KnowledgeBaseTab(
    state: com.example.presentation.state.UiState,
    viewModel: MainViewModel,
    onIngest: () -> Unit,
    onDeleteDoc: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ---- Retrieval query ----
        item {
            SectionHeader(
                icon = Icons.Default.Search,
                title = "استرجاع من قاعدة المعرفة (RAG)",
                subtitle = "استرجاع هجين: دلالي + معجمي، مع درجات ملاءمة لكل مقطع"
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = state.memoryQuery,
                        onValueChange = viewModel::updateMemoryQuery,
                        placeholder = { Text("اسأل قاعدة المعرفة… مثال: مبادئ الأمان") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_rag_query"),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.queryKnowledgeRag(state.memoryQuery) },
                        enabled = state.memoryQuery.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_search_rag")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("استرجاع السياق")
                    }
                }
            }
        }

        // ---- Retrieved context result ----
        state.assembledRagContext?.let { ctx ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "السياق المُجمّع",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            StatusBadge(
                                text = "${ctx.retrievedChunks.size} مقطع",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            StatusBadge(
                                text = "~${ctx.totalTokensEstimated} رمز",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        if (ctx.isTruncated) {
                            Spacer(modifier = Modifier.height(4.dp))
                            StatusBadge(
                                text = "تم الاقتطاع لحد الميزانية",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        ctx.retrievedChunks.forEach { chunk ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                MetricBar(
                                    fraction = chunk.relevanceScore,
                                    label = chunk.chunk.documentTitle,
                                    valueText = "ملاءمة ${(chunk.relevanceScore * 100).toInt()}%"
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusBadge(
                                        text = when (chunk.retrievalMode.name) {
                                            "SEMANTIC" -> "استرجاع دلالي"
                                            "LEXICAL_FALLBACK" -> "احتياطي معجمي"
                                            "HYBRID" -> "هجين"
                                            else -> chunk.retrievalMode.name
                                        },
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = chunk.snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Documents management ----
        item {
            SectionHeader(
                icon = Icons.Default.Description,
                title = "المستندات (${state.knowledgeDocuments.size})",
                subtitle = "تُقسَّم إلى مقاطع وتُضمَّن فهرستها للاسترجاع",
                trailing = {
                    TextButton(onClick = onIngest, modifier = Modifier.testTag("btn_open_ingest_dialog")) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إضافة مستند")
                    }
                }
            )
        }

        if (state.knowledgeDocuments.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.MenuBook,
                    title = "لا توجد مستندات بعد",
                    hint = "أضف مستنداً ليبني النظام فهرساً قابلاً للاسترجاع الدلالي والمعجمي."
                )
            }
        } else {
            items(state.knowledgeDocuments, key = { it.id }) { doc ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = doc.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = "${doc.totalChunks} مقطعاً • ${doc.mimeType}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusBadge(
                            text = when (doc.persistenceState) {
                                KnowledgePersistenceState.PERSISTED -> "محفوظ"
                                KnowledgePersistenceState.PENDING -> "معلّق"
                                KnowledgePersistenceState.FAILED -> "فشل الحفظ"
                            },
                            tint = when (doc.persistenceState) {
                                KnowledgePersistenceState.PERSISTED -> MaterialTheme.colorScheme.tertiary
                                KnowledgePersistenceState.PENDING -> MaterialTheme.colorScheme.secondary
                                KnowledgePersistenceState.FAILED -> MaterialTheme.colorScheme.error
                            }
                        )
                        IconButton(
                            onClick = { onDeleteDoc(doc.id) },
                            modifier = Modifier.testTag("btn_delete_doc_${doc.id}")
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "حذف المستند",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Tab 1 — Long-term memory
// ---------------------------------------------------------------------------

@Composable
private fun MemoryTab(
    state: com.example.presentation.state.UiState,
    viewModel: MainViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionHeader(
                icon = Icons.Default.Memory,
                title = "الذاكرة طويلة المدى",
                subtitle = "بحث دلالي + إدراج معارف يدوية — تُشاركها الوكلاء في مساحة العمل"
            )
        }

        // ---- Search ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = state.memoryQuery,
                        onValueChange = viewModel::updateMemoryQuery,
                        placeholder = { Text("ابحث في الذاكرة…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_memory_search")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::searchMemory,
                            enabled = state.memoryQuery.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("btn_search_memory")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("بحث")
                        }
                        OutlinedButton(
                            onClick = viewModel::refreshMemories,
                            modifier = Modifier.weight(1f).testTag("btn_refresh_memories")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تحديث")
                        }
                    }
                    if (state.isSearchingMemory) {
                        BusyIndicator("جاري البحث في الذاكرة…")
                    }
                }
            }
        }

        // ---- Search results ----
        if (state.retrievedMemories.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = Icons.Default.Search,
                    title = "نتائج البحث (${state.retrievedMemories.size})"
                )
            }
            items(state.retrievedMemories, key = { it.entry.id }) { record ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(
                                text = "ملاءمة ${(record.similarityScore * 100).toInt()}%",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            StatusBadge(
                                text = when (record.retrievalMode.name) {
                                    "SEMANTIC" -> "دلالي"
                                    "LEXICAL_FALLBACK" -> "معجمي (احتياطي)"
                                    "HYBRID" -> "هجين"
                                    else -> record.retrievalMode.name
                                },
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = record.entry.content,
                            style = MaterialTheme.typography.bodySmall
                        )
                        record.degradedReason?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }

        // ---- Add memory ----
        item {
            SectionHeader(icon = Icons.Default.Add, title = "إضافة معرفة إلى الذاكرة")
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = state.newMemoryContent,
                        onValueChange = viewModel::updateNewMemoryContent,
                        placeholder = { Text("مثال: يفضل المستخدم الردود العربية المختصرة") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .testTag("input_new_memory")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::addNewMemory,
                        enabled = state.newMemoryContent.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("btn_add_memory")
                    ) {
                        Text("تخزين كرؤية واقعية (FACTUAL_INSIGHT)")
                    }
                }
            }
        }

        // ---- All memories browser ----
        item {
            SectionHeader(
                icon = Icons.Default.Memory,
                title = "كل الذكريات النشطة (${state.allMemories.size})"
            )
        }
        if (state.allMemories.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Memory,
                    title = "الذاكرة فارغة",
                    hint = "خزّن رؤىً واقعية ليتعلم منها محرك القرار في المهام القادمة."
                )
            }
        } else {
            items(state.allMemories, key = { it.id }) { memory ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(
                                text = when (memory.type.name) {
                                    "PREFERENCE" -> "تفضيل"
                                    "FACTUAL_INSIGHT" -> "رؤية واقعية"
                                    "CASE_EXAMPLE" -> "مثال حالة"
                                    "CONVERSATION_SUMMARY" -> "ملخص محادثة"
                                    else -> memory.type.name
                                },
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "ثقة ${(memory.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = memory.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3
                        )
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Tab 2 — Semantic engine
// ---------------------------------------------------------------------------

@Composable
private fun SemanticEngineTab(
    state: com.example.presentation.state.UiState,
    viewModel: MainViewModel
) {
    // Refresh the honest provisioning flag whenever the tab is shown.
    LaunchedEffect(Unit) { viewModel.refreshSemanticModelStatus() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionHeader(
                icon = Icons.Default.Psychology,
                title = "محرك التضمين الدلالي المحلي",
                subtitle = "ONNX MiniLM-L6 (كمّي int8) — يعمل على الجهاز بالكامل"
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "الحالة:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (state.semanticModelReady) {
                            StatusBadge("جاهز — استرجاع دلالي حقيقي", MaterialTheme.colorScheme.tertiary)
                        } else {
                            StatusBadge("غير مُجهّز (احتياطي معجمي فقط)", MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "تجهيز النموذج الدلالي المحلي (~23MB لمرة واحدة) يرقّي استرجاع المعرفة " +
                            "والذاكرة من مطابقة معجمية إلى فهم دلالي حقيقي على الجهاز — دون إرسال " +
                            "أي بيانات إلى السحابة. التعذّر (بلا اتصال) يُعرض بصدق ولا يُفتَرَض نجاحه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::provisionLocalSemanticModel,
                        enabled = !state.isProvisioningSemanticModel && !state.semanticModelReady,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_provision_semantic_model")
                    ) {
                        if (state.isProvisioningSemanticModel) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري التنزيل والتجهيز…")
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تجهيز النموذج الدلالي")
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    InfoRow(
                        label = "التوافق",
                        value = "حدود التوافق بين الموارد تُحترم — لا خلط للمتجهات من موارد مختلفة"
                    )
                    InfoRow(
                        label = "التشغيل",
                        value = "محلي بالكامل — مناسب لسياسة OFFLINE/LOCAL_FIRST"
                    )
                    InfoRow(
                        label = "الأثر",
                        value = "ترقية الاسترجاع من LEXICAL_FALLBACK إلى SEMANTIC/HYBRID"
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun IngestDocumentDialog(
    title: String,
    content: String,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مستند إلى قاعدة المعرفة", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("عنوان المستند") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_doc_title")
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    label = { Text("المحتوى") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp)
                        .testTag("input_doc_content")
                )
                Text(
                    text = "سيُقسَّم إلى مقاطع (~300 كلمة بتقاطع 50) وتُحسب متجهاته، مع حالة حفظ صادقة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = title.isNotBlank() && content.isNotBlank()
            ) {
                Text("إدخال وفهرسة", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
