package com.huiyi.app.data

import android.content.Context
import java.io.File

class LocalMeetingDataCleaner(context: Context) {
    private val filesDir = context.filesDir

    fun clearLocalFiles() {
        listOf("recordings", "imports", CloudAudioCache.DirectoryName).forEach { child ->
            File(filesDir, child).deleteRecursively()
        }
    }

    fun deleteLocalFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            val root = filesDir.canonicalFile
            val target = file.canonicalFile
            if (target.path.startsWith(root.path)) {
                target.delete()
            }
        }
    }
}
