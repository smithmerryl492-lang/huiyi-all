package com.huiyi.app.data

import android.content.Context
import java.io.File
import java.util.Locale

class CloudAudioCache(context: Context) {
    private val rootDir = File(context.filesDir, DirectoryName)

    fun cachedAudioPath(task: MeetingTask, sourceFilePath: String? = null): String? {
        val remoteTaskId = task.remoteTaskId ?: return null
        val target = cacheFile(remoteTaskId, task, sourceFilePath)
        return if (target.exists() && target.length() > 0L) target.absolutePath else null
    }

    fun cacheTaskAudio(client: HuixiaoApiClient, task: MeetingTask, sourceFilePath: String? = null): String? {
        val remoteTaskId = task.remoteTaskId ?: return null
        val target = cacheFile(remoteTaskId, task, sourceFilePath)
        if (target.exists() && target.length() > 0L) return target.absolutePath
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()
        try {
            val downloadedBytes = client.downloadTaskAudio(remoteTaskId, temp)
            require(downloadedBytes > 0L && temp.exists()) { "云端音频文件为空" }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
        return target.absolutePath
    }

    private fun cacheFile(remoteTaskId: String, task: MeetingTask, sourceFilePath: String?): File {
        val extension = task.audioExtension(sourceFilePath)
        val safeId = remoteTaskId.map { char ->
            when {
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_' -> char
                else -> '_'
            }
        }.joinToString("").ifBlank { "remote_audio" }
        return File(rootDir, "$safeId.$extension")
    }

    private fun MeetingTask.audioExtension(sourceFilePath: String?): String {
        return listOf(sourceFilePath, localFilePath, title)
            .firstNotNullOfOrNull { it.supportedAudioExtensionOrNull() }
            ?: when (source) {
                MeetingTaskSource.Recording -> "wav"
                MeetingTaskSource.Import -> "mp4"
            }
    }

    private fun String?.supportedAudioExtensionOrNull(): String? {
        val clean = this
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            ?.filter { it in 'a'..'z' || it in '0'..'9' }
        return clean?.takeIf { it in SupportedExtensions }
    }

    companion object {
        const val DirectoryName = "cloud_audio"
        private val SupportedExtensions = setOf("mp3", "mp4", "m4a", "wav", "aac", "ogg", "webm", "amr", "3gp")
    }
}
