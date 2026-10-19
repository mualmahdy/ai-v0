package com.example.presentation.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/**
 * ============================================================================
 * PhoneStorageBrowserSheet — the IN-APP storage browser (STAGE 7 user
 * round 3: the DocumentsUI-less device)
 * ============================================================================
 *
 * THE PROBLEM THIS CLOSES: the app's file/folder attachment and workspace
 * import relied exclusively on the system SAF pickers
 * (ACTION_OPEN_DOCUMENT / ACTION_OPEN_DOCUMENT_TREE). A device without a
 * DocumentsUI activity has NO picker at all — every attach/import attempt
 * died with «تعذر فتح منتقي الملفات على هذا الجهاز (لا يوجد تطبيق
 * مستندات)» and the app had nothing to offer instead.
 *
 * THE HONEST FALLBACK: this sheet browses the REAL phone storage
 * (java.io.File over the external storage root) and hands the selection
 * to the SAME production seams the SAF pickers feed:
 *
 *   FILES mode   → the selected files leave as `file://` URIs through the
 *                  SAME callback the SAF file picker fills (the governed
 *                  staging + atomic-promotion import — AndroidContentPort
 *                  opens file:// streams through ContentResolver).
 *   FOLDER mode  → the CURRENT directory leaves as a `file://` URI through
 *                  the SAME callback the SAF tree picker fills (the
 *                  bounded directory→ZIP bridge → importFolderZip).
 *
 * PERMISSION HONESTY (no fabricated access): plain-file browsing needs
 * real storage access — "All files access" on API 30+ (a settings toggle
 * the user grants explicitly), READ_EXTERNAL_STORAGE at runtime on
 * API ≤29. While access is missing the sheet says exactly what it needs
 * and why — never a dead-looking list, never a silent empty screen.
 */
enum class PhoneStorageBrowserMode {
    /** Multi-select files → attach/import each one. */
    FILES,

    /** Pick the CURRENT directory as the folder attachment. */
    FOLDER
}

/**
 * Pure availability probe (kept free of composition so the permission
 * state is re-checkable on every activity resume — the user flips the
 * toggle in system settings and returns).
 */
fun phoneStorageAccessGranted(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true // pre-R: the runtime permission gate below decides at listing time
    }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PhoneStorageBrowserSheet(
    mode: PhoneStorageBrowserMode,
    onConfirmFiles: (List<String>) -> Unit,
    onConfirmFolder: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Honest note shown in the header when this browser opened as the SAF fallback. */
    fallbackNote: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    var accessGranted by remember { mutableStateOf(phoneStorageAccessGranted()) }
    var preRPermissionDenied by remember { mutableStateOf(false) }

    // Re-check on every resume: the user grants All-Files-Access (or the
    // runtime permission) in system settings and comes straight back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessGranted = phoneStorageAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        accessGranted = granted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        preRPermissionDenied = !granted && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("phone_storage_browser_sheet")
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // ---- Header ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (mode == PhoneStorageBrowserMode.FOLDER) {
                        "اختيار مجلد من تخزين الهاتف"
                    } else {
                        "اختيار ملفات من تخزين الهاتف"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            fallbackNote?.let { note ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (!accessGranted) {
                PhoneStoragePermissionGate(
                    preRPermissionDenied = preRPermissionDenied,
                    onRequestRuntimePermission = {
                        runtimePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    },
                    onOpenAllFilesSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                )
            } else {
                PhoneStorageBrowserContent(
                    mode = mode,
                    onConfirmFiles = onConfirmFiles,
                    onConfirmFolder = onConfirmFolder
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

@Composable
private fun PhoneStoragePermissionGate(
    preRPermissionDenied: Boolean,
    onRequestRuntimePermission: () -> Unit,
    onOpenAllFilesSettings: () -> Unit
) {
    Column(modifier = Modifier.testTag("phone_storage_permission_gate")) {
        Text(
            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "يحتاج المتصفح الداخلي إلى إذن «الوصول إلى كل الملفات» مرة واحدة — " +
                    "يُمنح من إعدادات النظام ويُلغى متى شئت من هناك."
            } else if (preRPermissionDenied) {
                "رُفض إذن قراءة التخزين — بدون إذن القراءة لا يمكن عرض ملفات الهاتف."
            } else {
                "يحتاج المتصفح الداخلي إلى إذن قراءة التخزين لعرض ملفات الهاتف."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                onOpenAllFilesSettings
            } else {
                onRequestRuntimePermission
            },
            modifier = Modifier.fillMaxWidth().testTag("btn_phone_storage_grant")
        ) {
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "فتح إعدادات الوصول إلى كل الملفات"
                } else {
                    "منح إذن قراءة التخزين"
                }
            )
        }
    }
}

@Composable
private fun PhoneStorageBrowserContent(
    mode: PhoneStorageBrowserMode,
    onConfirmFiles: (List<String>) -> Unit,
    onConfirmFolder: (String) -> Unit
) {
    val context = LocalContext.current

    // The browsing root: the real external-storage root (Documents first
    // would hide the rest; the root with a visible path is the honest map).
    val rootDir = remember {
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            ?: File(context.filesDir.parentFile ?: context.filesDir, "storage")
    }
    var currentPath by rememberSaveable {
        mutableStateOf(rootDir.absolutePath)
    }
    // Selection is session state (NOT saveable — a Set is not Bundle-
    // compatible and pick drafts need no process-death survival; the SAF
    // pickers behave the same way).
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }

    val currentDir = File(currentPath)
    val entries = remember(currentPath) {
        currentDir.listFiles()
            ?.filter { it.name.startsWith(".").not() }
            ?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
            ?: emptyList()
    }

    // ---- Path bar + up navigation ----
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (currentDir.absolutePath != rootDir.absolutePath) {
            IconButton(
                onClick = {
                    currentDir.parentFile?.let { currentPath = it.absolutePath }
                },
                modifier = Modifier.testTag("btn_phone_storage_up")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "المجلد الأعلى"
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = currentDir.absolutePath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(modifier = Modifier.height(6.dp))

    LazyColumn(modifier = Modifier.height(340.dp).testTag("phone_storage_list")) {
        if (entries.isEmpty()) {
            item {
                Text(
                    text = "لا توجد عناصر قابلة للعرض في هذا المجلد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        items(entries, key = { it.absolutePath }) { entry ->
            val isSelected = entry.absolutePath in selectedPaths
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (entry.isDirectory) {
                            currentPath = entry.absolutePath
                        } else if (mode == PhoneStorageBrowserMode.FILES) {
                            selectedPaths =
                                if (isSelected) selectedPaths - entry.absolutePath
                                else selectedPaths + entry.absolutePath
                        }
                    }
                    .padding(vertical = 8.dp)
                    .testTag("phone_storage_entry_${entry.name}")
            ) {
                if (mode == PhoneStorageBrowserMode.FILES && !entry.isDirectory) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (entry.isFile) {
                    Text(
                        text = formatFileSize(entry.length()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(10.dp))

    // ---- Confirm ----
    if (mode == PhoneStorageBrowserMode.FILES) {
        Button(
            onClick = {
                onConfirmFiles(selectedPaths.map { Uri.fromFile(File(it)).toString() })
            },
            enabled = selectedPaths.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("btn_phone_storage_confirm_files")
        ) {
            if (selectedPaths.isEmpty()) {
                Text("اختر ملفاً واحداً على الأقل")
            } else {
                Text("إرفاق ${selectedPaths.size} ${if (selectedPaths.size == 1) "ملف" else "ملفات"}")
            }
        }
    } else {
        Button(
            onClick = {
                onConfirmFolder(Uri.fromFile(currentDir).toString())
            },
            modifier = Modifier.fillMaxWidth().testTag("btn_phone_storage_confirm_folder")
        ) {
            Text("اختيار هذا المجلد (${currentDir.name})")
        }
        if (currentDir.absolutePath == rootDir.absolutePath) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "افتح مجلداً فرعياً أولاً — لا يمكن إرفاق جذر التخزين كاملاً.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes بايت"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f ك.ب", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f م.ب", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.US, "%.1f ج.ب", bytes / (1024.0 * 1024 * 1024))
}
