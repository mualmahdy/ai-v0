package com.example.runtime.storage

import android.content.Context
import com.example.data.local.db.daos.FileVersionDao
import com.example.data.local.db.entities.FileVersionEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L
)

class WorkspaceStorageManager(
    private val context: Context,
    private val fileVersionDao: FileVersionDao
) {
    private val baseDir: File by lazy {
        File(context.filesDir, "workspaces").apply { mkdirs() }
    }

    fun getProjectRoot(projectId: Long): File {
        val dir = File(baseDir, "project_$projectId")
        if (!dir.exists()) {
            dir.mkdirs()
            // Create some initial files for the project
            File(dir, "README.md").writeText(
                "# AI-V0 Ultimate Android Workspace\n\nهذا المجلد المحلي معزول وخاص بالمشروع $projectId.\nيمكن للوكلاء والمحرر وTerminal قراءة وتعديل الملفات داخله بأمان وبصلاحيات متحكم بها."
            )
            File(dir, "main.kt").writeText(
                "fun main() {\n    println(\"Hello from AI-V0 Native Android Runtime!\")\n}"
            )
            File(dir, "script.py").writeText(
                "# AI-V0 Native Script Execution\ndef calculate(a, b):\n    return a * b + 10\n\nprint('Result:', calculate(5, 7))\n"
            )
        }
        return dir
    }

    fun listFiles(projectId: Long, subPath: String = ""): List<FileEntry> {
        val root = getProjectRoot(projectId)
        val target = if (subPath.isEmpty()) root else File(root, subPath)
        if (!target.exists() || !target.isDirectory) return emptyList()

        return target.listFiles()?.map { file ->
            val rel = file.relativeTo(root).path
            FileEntry(
                name = file.name,
                relativePath = rel,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    fun readFile(projectId: Long, relativePath: String): String {
        val root = getProjectRoot(projectId)
        val target = File(root, relativePath).canonicalFile
        // Sandbox boundary check
        if (!target.path.startsWith(root.canonicalPath)) {
            throw SecurityException("Access Denied: Path escapes project workspace boundary")
        }
        if (!target.exists() || !target.isFile) {
            throw IllegalArgumentException("File does not exist: $relativePath")
        }
        return target.readText()
    }

    suspend fun writeFile(projectId: Long, relativePath: String, content: String): Boolean {
        val root = getProjectRoot(projectId)
        val target = File(root, relativePath).canonicalFile
        if (!target.path.startsWith(root.canonicalPath)) {
            throw SecurityException("Access Denied: Path escapes project workspace boundary")
        }

        // Snapshot previous content to Version History
        if (target.exists() && target.isFile) {
            val oldContent = target.readText()
            if (oldContent != content) {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                fileVersionDao.insertVersion(
                    FileVersionEntity(
                        projectId = projectId,
                        relativePath = relativePath,
                        content = oldContent,
                        sizeBytes = oldContent.toByteArray().size.toLong(),
                        createdAt = now
                    )
                )
            }
        }

        target.parentFile?.mkdirs()
        target.writeText(content)
        return true
    }

    fun deleteFile(projectId: Long, relativePath: String): Boolean {
        val root = getProjectRoot(projectId)
        val target = File(root, relativePath).canonicalFile
        if (!target.path.startsWith(root.canonicalPath)) {
            throw SecurityException("Access Denied")
        }
        return target.deleteRecursively()
    }

    fun computeDiff(oldContent: String, newContent: String): String {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        val builder = StringBuilder()
        var i = 0
        var j = 0
        while (i < oldLines.size || j < newLines.size) {
            if (i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j]) {
                builder.append("  ").append(oldLines[i]).append("\n")
                i++
                j++
            } else if (j < newLines.size && (i >= oldLines.size || !oldLines.contains(newLines[j]))) {
                builder.append("+ ").append(newLines[j]).append("\n")
                j++
            } else if (i < oldLines.size) {
                builder.append("- ").append(oldLines[i]).append("\n")
                i++
            }
        }
        return builder.toString()
    }
}
