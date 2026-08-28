package com.example.presentation.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.RetrievalMode
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.presentation.state.UiState
import com.example.presentation.viewmodel.MainViewModel

@Composable
fun MemoryRagScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTabIdx by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("memory_rag_screen")
    ) {
        Text(
            text = "الذاكرة الدلالية ونظام RAG (Semantic Memory Store)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "تخزين واسترجاع السياقات الذكية عبر حساب تشابه جيب التمام (Cosine Similarity) في Room.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        TabRow(selectedTabIndex = selectedTabIdx) {
            Tab(
                selected = selectedTabIdx == 0,
                onClick = { selectedTabIdx = 0 },
                text = { Text("بحث دلالي (Semantic Query)") }
            )
            Tab(
                selected = selectedTabIdx == 1,
                onClick = { selectedTabIdx = 1 },
                text = { Text("إضافة ذاكرة جديدة (Add Insight)") }
            )
            Tab(
                selected = selectedTabIdx == 2,
                onClick = { selectedTabIdx = 2 },
                text = { Text("السجلات النشطة (${state.allMemories.size})") }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTabIdx) {
            0 -> SemanticSearchTab(state = state, viewModel = viewModel)
            1 -> AddMemoryTab(state = state, viewModel = viewModel)
            2 -> ActiveMemoriesTab(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun SemanticSearchTab(state: UiState, viewModel: MainViewModel) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.memoryQuery,
                onValueChange = { viewModel.updateMemoryQuery(it) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("memory_search_input"),
                placeholder = { Text("ابحث في مفاهيم الذاكرة المخزنة...") },
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.searchMemory() },
                enabled = state.memoryQuery.isNotBlank() && !state.isSearchingMemory,
                modifier = Modifier.testTag("search_memory_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.isSearchingMemory) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("استرجاع")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.retrievedMemories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "لا توجد نتائج بحث بعد. اكتب استعلاماً واضغط استرجاع.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            Text(
                text = "النتائج المطابقة دلالياً (مرتبة حسب درجة التشابه):",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.retrievedMemories) { scored ->
                    ScoredMemoryCard(scored = scored)
                }
            }
        }
    }
}

@Composable
private fun ScoredMemoryCard(scored: ScoredMemoryRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "نمط المطابقة: ${if (scored.retrievalMode == RetrievalMode.SEMANTIC) "دلالي (Cosine Embeddings)" else "معجمي (Lexical Fallback)"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "التشابه: ${"%.2f".format(scored.similarityScore * 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    text = scored.entry.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun AddMemoryTab(state: UiState, viewModel: MainViewModel) {
    Column {
        OutlinedTextField(
            value = state.newMemoryContent,
            onValueChange = { viewModel.updateNewMemoryContent(it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .testTag("new_memory_content_input"),
            placeholder = { Text("أدخل قاعدة معمارية أو نص أو رؤية ليتم تضمينها رياضياً في الذاكرة الدلالية...") },
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { viewModel.addNewMemory() },
            enabled = state.newMemoryContent.isNotBlank(),
            modifier = Modifier
                .align(Alignment.End)
                .testTag("save_memory_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("حفظ في المتجهات الدلالية")
        }
    }
}

@Composable
private fun ActiveMemoriesTab(state: UiState, viewModel: MainViewModel) {
    if (state.allMemories.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "قاعدة الذاكرة الدلالية فارغة حالياً.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.allMemories) { memory ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = memory.content,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "المعرف: ${memory.id.take(8)}... | المصدر: ${memory.provenance.sourceSessionId ?: "USER"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
