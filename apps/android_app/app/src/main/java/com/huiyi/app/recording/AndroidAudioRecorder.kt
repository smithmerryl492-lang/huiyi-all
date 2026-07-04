package com.huiyi.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

private fun pcm16SampleAt(data: ByteArray, offset: Int): Int {
    val low = data[offset].toInt() and 0xff
    val high = data[offset + 1].toInt()
    return ((high shl 8) or low).toShort().toInt()
}

private fun audioSourceName(source: Int): String {
    return when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        else -> "SOURCE_$source"
    }
}

class AndroidAudioRecorder(
    private val context: Context
) {
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2
    private val liveFrameBytes = sampleRate * bytesPerSample * 60 / 1000
    private var audioRecord: AudioRecord? = null
    private var audioEffects: List<AudioEffect> = emptyList()
    private var recordingThread: Thread? = null
    private var currentFile: File? = null
    private val recording = AtomicBoolean(false)
    @Volatile private var paused = false

    @SuppressLint("MissingPermission")
    fun start(
        onPcmFrame: (ByteArray, Long) -> Unit = { _, _ -> },
        onAudioLevel: (RecordingAudioLevel) -> Unit = {},
        onAudioIssue: (String) -> Unit = {}
    ): File {
        stopSafely()

        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "meeting_${System.currentTimeMillis()}.wav")
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuffer, liveFrameBytes * 2)
        val started = createStartedAudioRecord(bufferSize)
        val recorder = started.recorder

        currentFile = file
        audioRecord = recorder
        audioEffects = started.effects
        recording.set(true)
        paused = false
        if (!started.hasSignal) {
            onAudioIssue("麦克风输入为空，请检查模拟器或系统麦克风设置")
        }
        recordingThread = Thread({
            writeRecordingLoop(
                recorder,
                file,
                bufferSize,
                started.sourceName,
                started.initialFrames,
                started.effects,
                onPcmFrame,
                onAudioLevel,
                onAudioIssue
            )
        }, "huixiao-audio-record").apply {
            isDaemon = true
            start()
        }
        return file
    }

    @SuppressLint("MissingPermission")
    private fun createStartedAudioRecord(bufferSize: Int): StartedAudioRecord {
        val sources = buildList {
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.DEFAULT)
            add(MediaRecorder.AudioSource.CAMCORDER)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
        }.distinct()
        var firstUsableSource: Int? = null
        for (source in sources) {
            val started = startAudioRecord(source, bufferSize) ?: continue
            val recorder = started.recorder
            if (firstUsableSource == null) firstUsableSource = source
            val initialFrames = readProbeFrames(recorder)
            val stats = PcmStats.from(initialFrames)
            Log.i(TAG, "probe source=${audioSourceName(source)} ${stats.summary()}")
            if (stats.hasDigitalSignal) {
                Log.i(TAG, "selected source=${audioSourceName(source)} ${stats.summary()}")
                return StartedAudioRecord(
                    recorder = recorder,
                    sourceName = audioSourceName(source),
                    initialFrames = initialFrames,
                    effects = started.effects,
                    hasSignal = true
                )
            }
            stopAndRelease(started)
        }
        val fallbackSource = firstUsableSource ?: error("录音设备初始化失败")
        val started = startAudioRecord(fallbackSource, bufferSize) ?: error("录音设备初始化失败")
        val recorder = started.recorder
        val initialFrames = readProbeFrames(recorder)
        val stats = PcmStats.from(initialFrames)
        Log.w(
            TAG,
            "no audio source produced non-zero probe; selected source=${audioSourceName(fallbackSource)} ${stats.summary()}"
        )
        return StartedAudioRecord(
            recorder = recorder,
            sourceName = audioSourceName(fallbackSource),
            initialFrames = initialFrames,
            effects = started.effects,
            hasSignal = false
        )
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecord(source: Int, bufferSize: Int): StartedRecorder? {
        val recorder = runCatching {
            AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
        }.getOrElse { error ->
            Log.w(TAG, "source=${audioSourceName(source)} init failed: ${error.message}")
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "source=${audioSourceName(source)} not initialized")
            runCatching { recorder.release() }
            return null
        }
        val effects = enableAudioEffects(recorder)
        val started = runCatching { recorder.startRecording() }.isSuccess
        if (!started || recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "source=${audioSourceName(source)} did not enter recording state")
            releaseEffects(effects)
            runCatching { recorder.release() }
            return null
        }
        return StartedRecorder(recorder, effects)
    }

    private fun enableAudioEffects(recorder: AudioRecord): List<AudioEffect> {
        val sessionId = recorder.audioSessionId
        val effects = mutableListOf<AudioEffect>()
        createEffect("NS", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }?.let { effects += it }
        createEffect("AGC", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }?.let { effects += it }
        createEffect("AEC", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }?.let { effects += it }
        return effects
    }

    private fun createEffect(
        name: String,
        available: Boolean,
        factory: () -> AudioEffect?
    ): AudioEffect? {
        if (!available) {
            Log.i(TAG, "audio effect $name unavailable")
            return null
        }
        return runCatching {
            factory()?.apply { enabled = true }
        }.onSuccess { effect ->
            Log.i(TAG, "audio effect $name ${if (effect != null) "enabled" else "not created"}")
        }.onFailure { error ->
            Log.w(TAG, "audio effect $name failed: ${error.message}")
        }.getOrNull()
    }

    private fun stopAndRelease(started: StartedRecorder) {
        releaseEffects(started.effects)
        runCatching { started.recorder.stop() }
        runCatching { started.recorder.release() }
    }

    private fun releaseEffects(effects: List<AudioEffect>) {
        effects.forEach { effect ->
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
    }

    private fun readProbeFrames(recorder: AudioRecord): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val buffer = ByteArray(liveFrameBytes)
        repeat(PROBE_FRAME_COUNT) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
                frames += buffer.copyOf(read)
                if (buffer.hasNonZeroAudio(read)) {
                    return frames
                }
            }
        }
        return frames
    }

    private data class StartedAudioRecord(
        val recorder: AudioRecord,
        val sourceName: String,
        val initialFrames: List<ByteArray>,
        val effects: List<AudioEffect>,
        val hasSignal: Boolean
    )

    private data class StartedRecorder(
        val recorder: AudioRecord,
        val effects: List<AudioEffect>
    )

    private fun ByteArray.hasNonZeroAudio(length: Int = size): Boolean {
        val upper = minOf(length, size)
        for (index in 0 until upper) {
            if (this[index].toInt() != 0) return true
        }
        return false
    }

    private fun ByteArray.copyIntoLiveFrames(
        length: Int,
        absoluteStartBytes: Long,
        liveFrameBuffer: ByteArray,
        liveFrameFill: Int,
        onFrame: (ByteArray, Long) -> Unit
    ): Int {
        var fill = liveFrameFill
        var offset = 0
        while (offset < length) {
            val copySize = minOf(liveFrameBuffer.size - fill, length - offset)
            copyInto(
                liveFrameBuffer,
                destinationOffset = fill,
                startIndex = offset,
                endIndex = offset + copySize
            )
            fill += copySize
            offset += copySize
            if (fill == liveFrameBuffer.size) {
                onFrame(liveFrameBuffer.copyOf(), absoluteStartBytes + offset)
                fill = 0
            }
        }
        return fill
    }

    private fun RandomAccessFile.writePcmChunk(
        data: ByteArray,
        length: Int,
        fullBytes: Long,
        bytesSinceHeaderSync: Long
    ): Pair<Long, Long> {
        write(data, 0, length)
        var nextFullBytes = fullBytes + length
        var nextBytesSinceHeaderSync = bytesSinceHeaderSync + length
        if (nextBytesSinceHeaderSync >= HEADER_SYNC_BYTES) {
            val position = filePointer
            updateWavHeader(this, nextFullBytes)
            seek(position)
            nextBytesSinceHeaderSync = 0L
        }
        return nextFullBytes to nextBytesSinceHeaderSync
    }

    private fun maybeReportDigitalSilence(
        data: ByteArray,
        length: Int,
        bytesUntilSignal: Long,
        alreadyReported: Boolean,
        onAudioIssue: (String) -> Unit
    ): Pair<Long, Boolean> {
        if (data.hasNonZeroAudio(length)) {
            return 0L to alreadyReported
        }
        val nextBytes = bytesUntilSignal + length
        if (!alreadyReported && nextBytes >= DIGITAL_SILENCE_WARNING_BYTES) {
            onAudioIssue("麦克风输入持续为空，请检查模拟器或系统麦克风设置")
            return nextBytes to true
        }
        return nextBytes to alreadyReported
    }

    private fun maybeDropDigitalSilenceFrame(frame: ByteArray, onFrame: (ByteArray) -> Unit) {
        if (frame.hasNonZeroAudio()) {
            onFrame(frame)
        }
    }

    private fun processInitialFrames(
        frames: List<ByteArray>,
        output: RandomAccessFile,
        liveFrameBuffer: ByteArray,
        liveFrameFill: Int,
        fullBytes: Long,
        bytesSinceHeaderSync: Long,
        bytesUntilSignal: Long,
        reportedDigitalSilence: Boolean,
        audioMeter: PcmMeter,
        onPcmFrame: (ByteArray, Long) -> Unit,
        onAudioIssue: (String) -> Unit
    ): InitialFrameState {
        var fill = liveFrameFill
        var totalBytes = fullBytes
        var headerBytes = bytesSinceHeaderSync
        var silenceBytes = bytesUntilSignal
        var reported = reportedDigitalSilence
        for (frame in frames) {
            val frameStartBytes = totalBytes
            audioMeter.append(frame, frame.size)
            val written = output.writePcmChunk(frame, frame.size, totalBytes, headerBytes)
            totalBytes = written.first
            headerBytes = written.second
            val silence = maybeReportDigitalSilence(frame, frame.size, silenceBytes, reported, onAudioIssue)
            silenceBytes = silence.first
            reported = silence.second
            fill = frame.copyIntoLiveFrames(frame.size, frameStartBytes, liveFrameBuffer, fill) { liveFrame, endBytes ->
                maybeDropDigitalSilenceFrame(liveFrame) { cleanFrame -> onPcmFrame(cleanFrame, endBytes) }
            }
        }
        return InitialFrameState(fill, totalBytes, headerBytes, silenceBytes, reported)
    }

    private data class InitialFrameState(
        val liveFrameFill: Int,
        val fullBytes: Long,
        val bytesSinceHeaderSync: Long,
        val bytesUntilSignal: Long,
        val reportedDigitalSilence: Boolean
    )

    private class PcmMeter(
        private val sourceName: String,
        private val onAudioLevel: (RecordingAudioLevel) -> Unit
    ) {
        private var peak = 0
        private var sumSquares = 0.0
        private var nonZeroSamples = 0L
        private var totalSamples = 0L

        fun append(data: ByteArray, length: Int) {
            val upper = minOf(length, data.size) / 2 * 2
            var index = 0
            while (index < upper) {
                val sample = pcm16SampleAt(data, index)
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
                if (sample != 0) nonZeroSamples += 1
                sumSquares += sample.toDouble() * sample.toDouble()
                totalSamples += 1
                index += 2
            }
            if (totalSamples >= METER_EMIT_SAMPLES) {
                emitAndReset()
            }
        }

        private fun emitAndReset() {
            if (totalSamples <= 0) return
            val rms = sqrt(sumSquares / totalSamples.toDouble())
            val nonZeroRatio = nonZeroSamples.toDouble() / totalSamples.toDouble()
            val level = RecordingAudioLevel(
                source = sourceName,
                peak = peak,
                rms = rms,
                nonZeroRatio = nonZeroRatio
            )
            Log.i(
                TAG,
                "input source=$sourceName peak=${level.peak} rms=${"%.2f".format(Locale.US, level.rms)} " +
                    "nonZero=${"%.5f".format(Locale.US, level.nonZeroRatio)} level=${level.levelPercent}%"
            )
            onAudioLevel(level)
            peak = 0
            sumSquares = 0.0
            nonZeroSamples = 0L
            totalSamples = 0L
        }
    }

    private data class PcmStats(
        val bytes: Int,
        val samples: Int,
        val peak: Int,
        val rms: Double,
        val nonZeroRatio: Double
    ) {
        val hasDigitalSignal: Boolean
            get() = peak > 0 && nonZeroRatio > 0.0

        fun summary(): String {
            return "bytes=$bytes samples=$samples peak=$peak rms=${"%.2f".format(Locale.US, rms)} " +
                "nonZero=${"%.5f".format(Locale.US, nonZeroRatio)}"
        }

        companion object {
            fun from(frames: List<ByteArray>): PcmStats {
                var bytes = 0
                var samples = 0
                var peak = 0
                var sumSquares = 0.0
                var nonZero = 0
                for (frame in frames) {
                    bytes += frame.size
                    val upper = frame.size / 2 * 2
                    var index = 0
                    while (index < upper) {
                        val sample = pcm16SampleAt(frame, index)
                        val abs = kotlin.math.abs(sample)
                        if (abs > peak) peak = abs
                        if (sample != 0) nonZero += 1
                        sumSquares += sample.toDouble() * sample.toDouble()
                        samples += 1
                        index += 2
                    }
                }
                val rms = if (samples > 0) sqrt(sumSquares / samples.toDouble()) else 0.0
                val nonZeroRatio = if (samples > 0) nonZero.toDouble() / samples.toDouble() else 0.0
                return PcmStats(bytes, samples, peak, rms, nonZeroRatio)
            }
        }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop(): File? {
        val file = currentFile
        stopSafely()
        return file
    }

    fun release() {
        stopSafely()
    }

    private fun stopSafely() {
        recording.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { recordingThread?.join(3000) }
        releaseEffects(audioEffects)
        runCatching { audioRecord?.release() }
        audioRecord = null
        audioEffects = emptyList()
        recordingThread = null
        currentFile = null
        paused = false
    }

    private fun writeRecordingLoop(
        recorder: AudioRecord,
        fullFile: File,
        bufferSize: Int,
        sourceName: String,
        initialFrames: List<ByteArray>,
        effects: List<AudioEffect>,
        onPcmFrame: (ByteArray, Long) -> Unit,
        onAudioLevel: (RecordingAudioLevel) -> Unit,
        onAudioIssue: (String) -> Unit
    ) {
        val buffer = ByteArray(bufferSize)
        val liveFrameBuffer = ByteArray(liveFrameBytes)
        var liveFrameFill = 0
        var fullBytes = 0L
        var bytesSinceHeaderSync = 0L
        var bytesUntilSignal = 0L
        var reportedDigitalSilence = false
        val audioMeter = PcmMeter(sourceName, onAudioLevel)
        RandomAccessFile(fullFile, "rw").use { output ->
            writeWavHeader(output, 0)
            try {
                val initialState = processInitialFrames(
                    initialFrames,
                    output,
                    liveFrameBuffer,
                    liveFrameFill,
                    fullBytes,
                    bytesSinceHeaderSync,
                    bytesUntilSignal,
                    reportedDigitalSilence,
                    audioMeter,
                    onPcmFrame,
                    onAudioIssue
                )
                liveFrameFill = initialState.liveFrameFill
                fullBytes = initialState.fullBytes
                bytesSinceHeaderSync = initialState.bytesSinceHeaderSync
                bytesUntilSignal = initialState.bytesUntilSignal
                reportedDigitalSilence = initialState.reportedDigitalSilence
                while (recording.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0 || paused) continue
                    val chunkStartBytes = fullBytes
                    audioMeter.append(buffer, read)
                    val written = output.writePcmChunk(buffer, read, fullBytes, bytesSinceHeaderSync)
                    fullBytes = written.first
                    bytesSinceHeaderSync = written.second
                    val silence = maybeReportDigitalSilence(buffer, read, bytesUntilSignal, reportedDigitalSilence, onAudioIssue)
                    bytesUntilSignal = silence.first
                    reportedDigitalSilence = silence.second
                    liveFrameFill = buffer.copyIntoLiveFrames(read, chunkStartBytes, liveFrameBuffer, liveFrameFill) { frame, endBytes ->
                        maybeDropDigitalSilenceFrame(frame) { cleanFrame -> onPcmFrame(cleanFrame, endBytes) }
                    }
                }
            } finally {
                if (liveFrameFill > 0 && liveFrameBuffer.hasNonZeroAudio(liveFrameFill)) {
                    onPcmFrame(liveFrameBuffer.copyOf(liveFrameFill), fullBytes)
                }
                runCatching { recorder.stop() }
                releaseEffects(effects)
                runCatching { recorder.release() }
                updateWavHeader(output, fullBytes)
            }
        }
    }

    private fun writeWavHeader(file: RandomAccessFile, dataBytes: Long) {
        file.setLength(0)
        file.write(ByteArray(44))
        updateWavHeader(file, dataBytes)
        file.seek(44)
    }

    private fun updateWavHeader(file: RandomAccessFile, dataBytes: Long) {
        file.seek(0)
        file.writeBytes("RIFF")
        writeIntLE(file, (36 + dataBytes).toInt())
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        writeIntLE(file, 16)
        writeShortLE(file, 1)
        writeShortLE(file, 1)
        writeIntLE(file, sampleRate)
        writeIntLE(file, sampleRate * bytesPerSample)
        writeShortLE(file, bytesPerSample)
        writeShortLE(file, 16)
        file.writeBytes("data")
        writeIntLE(file, dataBytes.toInt())
    }

    private fun writeIntLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write(value shr 8 and 0xff)
        file.write(value shr 16 and 0xff)
        file.write(value shr 24 and 0xff)
    }

    private fun writeShortLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write(value shr 8 and 0xff)
    }

    private companion object {
        private const val TAG = "HuixiaoAudioRecorder"
        private const val HEADER_SYNC_BYTES = 16_000L * 2L * 5L
        private const val PROBE_FRAME_COUNT = 4
        private const val METER_EMIT_SAMPLES = 16_000L
        private const val DIGITAL_SILENCE_WARNING_BYTES = 16_000L * 2L * 3L
    }
}
