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
import com.example.runtime.rag.RagSearchResult
import com.example.ui.viewmodel.WorkspaceViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgePanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val documents by viewModel.documents.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<RagSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_knowledge_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "إضافة وثيقة معرفية")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Text(
                text = "قاعدة المعرفة المحلية (Local RAG & Vector Index)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "تضمين متجهي 100% Offline محلي مع تقسيم المقاطع وحساب تشابه جيب التمام.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Search Tester Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("ابحث في المستندات عبر الاسترجاع المتجهي (Vector Similarity)...", fontSize = 12.sp) },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                isSearching = true
                                coroutineScope.launch {
                                    searchResults = viewModel.ragEngine.search(viewModel.activeProjectId.value, searchQuery, topK = 4)
                                    isSearching = false
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "بحث")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (searchResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("نتائج البحث الدلالي (${searchResults.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                searchResults.forEach { res ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(res.docId, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Cosine Sim: ${String.format(Locale.US, "%.3f", res.similarity)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(res.text, fontSize = 11.sp, maxLines = 2)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("المستندات المفهرسة (${documents.size}):", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(documents) { doc ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(doc.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        doc.collectionName,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(doc.content, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var title by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        var collection by remember { mutableStateOf("default") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("إضافة مستند جديد للمعرفة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("عنوان الوثيقة") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = collection, onValueChange = { collection = it }, label = { Text("المجموعة (Collection)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("محتوى الوثيقة") }, maxLines = 5, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (title.isNotBlank() && content.isNotBlank()) {
                            viewModel.addKnowledgeDoc(title, content, collection)
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("فهرسة وتضمين")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") }
            }
        )
    }
}
