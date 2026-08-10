package io.github.webarmour.videomessagerecorder.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import io.github.webarmour.videomessagerecorder.camera.RecordingConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class AudioEncoder(
    private val config: RecordingConfig,
    private val muxer: RecordingMuxer,
) : AutoCloseable {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val recording = AtomicBoolean(false)

    private val minBufferSize = AudioRecord.getMinBufferSize(
        config.audioSampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).also { size ->
        require(size > 0) { "Unsupported AudioRecord configuration: $size" }
    }

    private val audioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(config.audioSampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
        )
        .setBufferSizeInBytes(max(minBufferSize * 2, 16_384))
        .build()

    private lateinit var inputThread: Thread
    private lateinit var drainThread: Thread

    @Volatile
    private var lastPtsUs: Long = 0L

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            config.audioSampleRate,
            1,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max(minBufferSize, 16_384))
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize"
        }
    }

    fun start() {
        check(recording.compareAndSet(false, true)) { "Audio encoder already started" }

        codec.start()
        audioRecord.startRecording()

        drainThread = Thread(::drainLoop, "AudioEncoderDrain").apply { start() }
        inputThread = Thread(::inputLoop, "AudioEncoderInput").apply { start() }
    }

    fun stopAndAwait() {
        if (!recording.compareAndSet(true, false)) return

        runCatching { audioRecord.stop() }
        inputThread.join(1_500)
        queueEndOfStream()
        drainThread.join(3_000)
    }

    private fun inputLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var submittedFrames = 0L
        var fallbackStreamStartNs: Long? = null
        val audioTimestamp = AudioTimestamp()
        val bytesPerFrame = 2 // PCM16 mono

        while (recording.get()) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex < 0) continue

            val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()

            val bytesRead = audioRecord.read(
                inputBuffer,
                inputBuffer.capacity(),
                AudioRecord.READ_BLOCKING,
            )

            if (bytesRead <= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, lastPtsUs, 0)
                continue
            }

            val framesRead = bytesRead / bytesPerFrame
            val timestampStatus = audioRecord.getTimestamp(
                audioTimestamp,
                AudioTimestamp.TIMEBASE_MONOTONIC,
            )

            val streamStartNs = if (timestampStatus == AudioRecord.SUCCESS) {
                audioTimestamp.nanoTime -
                    audioTimestamp.framePosition * 1_000_000_000L / config.audioSampleRate
            } else {
                fallbackStreamStartNs ?: run {
                    val nowNs = System.nanoTime()
                    val capturedFrames = submittedFrames + framesRead
                    (nowNs - capturedFrames * 1_000_000_000L / config.audioSampleRate).also {
                        fallbackStreamStartNs = it
                    }
                }
            }

            val ptsNs = streamStartNs +
                submittedFrames * 1_000_000_000L / config.audioSampleRate
            val ptsUs = ptsNs / 1_000L

            codec.queueInputBuffer(inputIndex, 0, bytesRead, ptsUs, 0)

            submittedFrames += framesRead
            lastPtsUs = ptsUs + framesRead * 1_000_000L / config.audioSampleRate
        }
    }

    private fun queueEndOfStream() {
        repeat(100) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    lastPtsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                return
            }
        }
        Log.w(TAG, "Unable to queue AAC EOS")
    }

    private fun drainLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val info = MediaCodec.BufferInfo()
        var eos = false

        while (!eos) {
            val index = codec.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    Log.i(TAG, "Audio output format: $format")
                    muxer.setTrackFormat(RecordingMuxer.Track.AUDIO, format)
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        muxer.writeSample(RecordingMuxer.Track.AUDIO, buffer, info)
                    }
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    override fun close() {
        runCatching { stopAndAwait() }
        runCatching { audioRecord.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val TAG = "CameraPipelineLab"
    }
}
