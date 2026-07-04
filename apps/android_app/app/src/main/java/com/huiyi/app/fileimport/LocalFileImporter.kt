package com.huiyi.app.fileimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class ImportedLocalFile(
    val displayName: String,
    val localFilePath: String,
    val sizeBytes: Long
) {
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024 * 1024 -> "%.1f MB".format(sizeBytes / 1024f / 1024f)
            sizeBytes >= 1024 -> "%.1f KB".format(sizeBytes / 1024f)
            else -> "$sizeBytes B"
        }
}

class LocalFileImporter(
    private val context: Context
) {
    fun import(uri: Uri): ImportedLocalFile {
        val displayName = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}"
        validateSupportedFile(displayName)
        querySize(uri)?.let { validateSize(it) }
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val target = uniqueFile(dir, displayName.sanitizeFileName())

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取选择的文件" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        validateSize(target.length())

        return ImportedLocalFile(
            displayName = displayName,
            localFilePath = target.absolutePath,
            sizeBytes = target.length()
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    private fun querySize(uri: Uri): Long? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "_$index"
            val fileName = if (ext.isBlank()) "$base$suffix" else "$base$suffix.$ext"
            val file = File(dir, fileName)
            if (!file.exists()) return file
            index += 1
        }
    }

    private fun String.sanitizeFileName(): String {
        return replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "import_${System.currentTimeMillis()}" }
    }

    private fun validateSupportedFile(displayName: String) {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        require(extension in SUPPORTED_EXTENSIONS) {
            "不支持的文件格式，请选择 mp3、m4a、wav、aac、mp4 或 mov"
        }
    }

    private fun validateSize(sizeBytes: Long) {
        require(sizeBytes > 0L) { "文件为空，无法处理" }
        require(sizeBytes <= MAX_IMPORT_BYTES) { "文件超过 500MB，请拆分后再导入" }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 500L * 1024L * 1024L
        val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "wav", "aac", "mp4", "mov")
    }
}
