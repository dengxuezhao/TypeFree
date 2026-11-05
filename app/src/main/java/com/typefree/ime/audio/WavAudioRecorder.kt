package com.typefree.ime.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RecordedAudioResult(
    val wavFile: File,
    val durationMs: Long,
    val hasValidSpeech: Boolean
)

class WavAudioRecorder(private val context: Context) {

    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var startTimeMs: Long = 0L

    @Volatile
    private var ignoreAudioUntilMs: Long = 0L

    private val _amplitudes = MutableStateFlow(List(BAR_COUNT) { 0.08f })
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    @Volatile
    private var pcmBufferStream: ByteArrayOutputStream? = null

    @Volatile
    private var maxRmsObserved: Double = 0.0

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasDetectedSpeech(): Boolean {
        return maxRmsObserved > SPEECH_RMS_THRESHOLD
    }

    /**
     * Clears initial swipe/touch friction & haptic motor frames when locking from the
     * Swipe-Up Arc Translate wheel into Hands-Free Translate recording, resetting timer to 00:00.
     */
    fun clearSilencePrefix(skipHapticMs: Long = 120L) {
        val stream = pcmBufferStream ?: return
        synchronized(stream) {
            stream.reset()
        }
        val now = System.currentTimeMillis()
        maxRmsObserved = 0.0
        startTimeMs = now
        ignoreAudioUntilMs = now + skipHapticMs.coerceAtLeast(0L)
        _elapsedMs.value = 0L
        _amplitudes.value = List(BAR_COUNT) { 0.08f }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(scope: CoroutineScope): Result<Unit> {
        if (!hasRecordPermission()) {
            return Result.failure(SecurityException("尚未授予麦克风录音权限"))
        }
        if (recordingJob?.isActive == true) {
            return Result.success(Unit)
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            return Result.failure(IllegalStateException("无法初始化系统音频缓冲区"))
        }

        val bufferSize = maxOf(minBufSize * 2, CHUNK_SAMPLES * 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            return Result.failure(e)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(IllegalStateException("麦克风当前被其他应用占用或初始化失败"))
        }

        audioRecord = record
        val stream = ByteArrayOutputStream(SAMPLE_RATE * 2 * 10)
        pcmBufferStream = stream
        maxRmsObserved = 0.0
        val initNow = System.currentTimeMillis()
        startTimeMs = initNow
        ignoreAudioUntilMs = initNow + 60L
        _elapsedMs.value = 0L
        _amplitudes.value = List(BAR_COUNT) { 0.08f }

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            audioRecord = null
            return Result.failure(e)
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(CHUNK_SAMPLES)
            val byteBuf = ByteBuffer.allocate(CHUNK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
            val rollingBars = ArrayDeque<Float>(BAR_COUNT)
            repeat(BAR_COUNT) { rollingBars.addLast(0.08f) }

            while (isActive) {
                val readCount = record.read(shortBuf, 0, shortBuf.size)
                if (readCount > 0) {
                    val now = System.currentTimeMillis()
                    if (now < ignoreAudioUntilMs) {
                        _elapsedMs.value = (now - startTimeMs).coerceAtLeast(0L)
                        continue
                    }

                    byteBuf.clear()
                    var sumSquares = 0.0
                    for (i in 0 until readCount) {
                        val sample = shortBuf[i]
                        byteBuf.putShort(sample)
                        val d = sample.toDouble()
                        sumSquares += d * d
                    }
                    synchronized(stream) {
                        stream.write(byteBuf.array(), 0, readCount * 2)
                    }

                    val rms = sqrt(sumSquares / readCount)
                    val elapsedNow = now - startTimeMs
                    if (elapsedNow >= 120L && rms > maxRmsObserved) {
                        maxRmsObserved = rms
                    }
                    // Normalize speech level into [0.08f .. 1.0f] for crisp waveform display (sensitive on VOICE_RECOGNITION)
                    val normalized = ((rms / 2400.0).toFloat()).coerceIn(0.08f, 1.0f)
                    if (rollingBars.size >= BAR_COUNT) {
                        rollingBars.removeFirst()
                    }
                    rollingBars.addLast(normalized)
                    _amplitudes.value = rollingBars.toList()
                    _elapsedMs.value = elapsedNow.coerceAtLeast(0L)
                }
            }
        }

        return Result.success(Unit)
    }

    suspend fun stopRecording(): Result<RecordedAudioResult> = withContext(Dispatchers.IO) {
        val job = recordingJob
        recordingJob = null
        try {
            job?.cancelAndJoin()
        } catch (_: Exception) {
        }

        val record = audioRecord
        audioRecord = null
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Exception) {
        } finally {
            try {
                record?.release()
            } catch (_: Exception) {
            }
        }

        _amplitudes.value = List(BAR_COUNT) { 0.08f }

        val duration = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
        val rawPcmBytes = pcmBufferStream?.let {
            synchronized(it) { it.toByteArray() }
        } ?: ByteArray(0)
        pcmBufferStream = null

        if (rawPcmBytes.size < SAMPLE_RATE * 2 / 5 || duration < 240L) {
            return@withContext Result.failure(IllegalArgumentException("录音时间太短，请说完后再点击完成"))
        }

        val normalizedPcm = normalizePcm16Le(rawPcmBytes)
        val hasSpeech = maxRmsObserved > SPEECH_RMS_THRESHOLD

        val outFile = getLastRecordingFile(context)
        writeWavFile(outFile, normalizedPcm, SAMPLE_RATE, 1, 16)

        Result.success(
            RecordedAudioResult(
                wavFile = outFile,
                durationMs = duration,
                hasValidSpeech = hasSpeech
            )
        )
    }

    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        val job = recordingJob
        recordingJob = null
        try {
            job?.cancelAndJoin()
        } catch (_: Exception) {
        }
        val record = audioRecord
        audioRecord = null
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Exception) {
        } finally {
            try {
                record?.release()
            } catch (_: Exception) {
            }
        }
        pcmBufferStream = null
        _amplitudes.value = List(BAR_COUNT) { 0.08f }
        _elapsedMs.value = 0L
    }

    private fun writeWavFile(
        file: File,
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(chunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM Subchunk1Size
            putShort(1.toShort()) // AudioFormat = 1 (PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(pcmData)
            fos.flush()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 720 // ~45ms per UI frame at 16kHz
        private const val SPEECH_RMS_THRESHOLD = 160.0
        private const val MIN_VOICE_PEAK_FOR_BOOST = 220
        private const val TARGET_PEAK_AMPLITUDE = 18500
        private const val MAX_NORMALIZATION_GAIN = 6.0
        const val BAR_COUNT = 24

        fun getLastRecordingFile(context: Context): File {
            return File(context.cacheDir, "last_typefree_voice.wav")
        }

        /**
         * Boosts quiet 16-bit little-endian PCM recordings (common on Android OEMs where
         * `AudioSource.VOICE_RECOGNITION` disables hardware AGC) up toward [TARGET_PEAK_AMPLITUDE]
         * with a capped gain ([MAX_NORMALIZATION_GAIN], +15.5 dB) and soft sample clamping, while
         * leaving pure digital silence (`peak < MIN_VOICE_PEAK_FOR_BOOST`) untouched.
         */
        fun normalizePcm16Le(pcmBytes: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val sampleCount = pcmBytes.size / 2
            if (sampleCount <= 0) return pcmBytes

            val edgeMargin = if (sampleCount > sampleRate / 2) sampleRate / 10 else 0 // 100ms edge trim for peak scan
            var interiorPeak = 0
            var globalPeak = 0

            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val mag = if (sample == Short.MIN_VALUE.toInt()) 32767 else abs(sample)
                if (mag > globalPeak) globalPeak = mag
                if (i >= edgeMargin && i < sampleCount - edgeMargin && mag > interiorPeak) {
                    interiorPeak = mag
                }
            }

            val refPeak = if (interiorPeak >= MIN_VOICE_PEAK_FOR_BOOST) interiorPeak else globalPeak
            if (refPeak < MIN_VOICE_PEAK_FOR_BOOST || refPeak >= TARGET_PEAK_AMPLITUDE) {
                return pcmBytes
            }

            val gain = (TARGET_PEAK_AMPLITUDE.toDouble() / refPeak.toDouble())
                .coerceIn(1.0, MAX_NORMALIZATION_GAIN)
            if (gain <= 1.05) return pcmBytes

            val out = ByteArray(sampleCount * 2)
            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val scaled = (sample * gain).roundToInt().coerceIn(-32767, 32767)
                out[i * 2] = (scaled and 0xFF).toByte()
                out[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
