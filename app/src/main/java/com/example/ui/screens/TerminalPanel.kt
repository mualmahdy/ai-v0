package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runtime.execution.LineType
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.Slate850
import com.example.ui.theme.Slate950
import com.example.ui.viewmodel.WorkspaceViewModel
import kotlinx.coroutines.launch

@Composable
fun TerminalPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val history by viewModel.terminalManager.history.collectAsState()
    var inputCommand by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(history.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .padding(8.dp)
    ) {
        // Quick Shortcuts bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("help", "ls", "status", "pkg", "run main.kt", "clear").forEach { cmd ->
                AssistChip(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.terminalManager.executeCommand(viewModel.activeProjectId.value, cmd)
                        }
                    },
                    label = {
                        Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Slate850,
                        labelColor = CyanPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Terminal Output Screen
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(history) { line ->
                val color = when (line.type) {
                    LineType.COMMAND -> CyanPrimary
                    LineType.OUTPUT -> Color(0xFFE2E8F0)
                    LineType.ERROR -> MaterialTheme.colorScheme.error
                    LineType.SYSTEM -> EmeraldSuccess
                }

                Text(
                    text = line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = color,
                    lineHeight = 16.sp
                )
            }
        }

        // Terminal Prompt Input
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Slate850,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$ ",
                    fontFamily = FontFamily.Monospace,
                    color = CyanPrimary,
                    fontSize = 13.sp
                )

                TextField(
                    value = inputCommand,
                    onValueChange = { inputCommand = it },
                    placeholder = {
                        Text("ls, cat, run, status, agent...", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("terminal_input_field"),
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        if (inputCommand.isNotBlank()) {
                            val cmd = inputCommand
                            inputCommand = ""
                            coroutineScope.launch {
                                viewModel.terminalManager.executeCommand(viewModel.activeProjectId.value, cmd)
                            }
                        }
                    },
                    modifier = Modifier.testTag("terminal_send_button")
                ) {
                    Icon(Icons.Default.Send, contentDescription = "تنفيذ الأمر", tint = CyanPrimary)
                }
            }
        }
    }
}
