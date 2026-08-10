package io.github.webarmour.videomessagerecorder.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.Size
import android.view.Surface
import io.github.webarmour.videomessagerecorder.camera.RecordingConfig
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoEncoder(
    private val config: RecordingConfig,
    private val muxer: RecordingMuxer,
) : AutoCloseable {

    private data class CodecSelection(
        val codecInfo: MediaCodecInfo,
        val profileLevel: MediaCodecInfo.CodecProfileLevel?,
    )

    private data class EncoderAttempt(
        val profile: Int?,
        val level: Int?,
        val maxBFrames: Int,
        val requestVbr: Boolean = true,
        val requestMaxComplexity: Boolean = false,
    )

    val inputSurface: Surface

    private val codec: MediaCodec
    private val codecName: String
    private val hardwareAccelerated: Boolean
    private val configuredAttempt: EncoderAttempt
    private val draining = AtomicBoolean(true)
    private val drainThread: Thread

    @Volatile
    private var actualOutputFormat: String? = null

    init {
        val selection = selectCodec(config.outputSize)
        codecName = selection.codecInfo.name
        hardwareAccelerated = selection.codecInfo.isHardwareAcceleratedCompat()
        codec = MediaCodec.createByCodecName(codecName)

        val preferredProfile = selection.profileLevel?.profile
        val preferredLevel = selection.profileLevel?.level
        val attempts = buildList {
            // Never silently increase B-frames above the user's request. If a codec rejects the
            // requested reorder depth, progressively reduce it until a compatible configuration is
            // found. This makes A/B testing B=0/1/2/3 deterministic.
            val requestedMaxBFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                config.maxBFrames
            } else {
                0
            }
            for (bFrames in requestedMaxBFrames downTo 0) {
                add(
                    EncoderAttempt(
                        profile = preferredProfile,
                        level = preferredLevel,
                        maxBFrames = bFrames,
                    )
                )
            }

            // Last attempt intentionally removes optional encoder tuning. Some vendor codecs
            // advertise complexity/VBR support but still reject the corresponding keys.
            add(
                EncoderAttempt(
                    profile = null,
                    level = null,
                    maxBFrames = 0,
                    requestVbr = false,
                    requestMaxComplexity = false,
                )
            )
        }.distinct()

        var successfulAttempt: EncoderAttempt? = null
        var lastError: Throwable? = null

        for (attempt in attempts) {
            try {
                codec.configure(
                    createFormat(selection.codecInfo, attempt),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                successfulAttempt = attempt
                Log.i(TAG, "Configured $codecName: $attempt")
                break
            } catch (error: Throwable) {
                lastError = error
                Log.w(TAG, "Encoder attempt failed: $attempt", error)
                codec.reset()
            }
        }

        configuredAttempt = checkNotNull(successfulAttempt) {
            "Unable to configure AVC encoder $codecName: ${lastError?.message}"
        }

        inputSurface = codec.createInputSurface()
        codec.start()
        drainThread = Thread(::drainLoop, "VideoEncoderDrain").apply { start() }
    }

    fun stopAndAwait() {
        if (!draining.compareAndSet(true, false)) return
        codec.signalEndOfInputStream()
        drainThread.join(DRAIN_TIMEOUT_MS)
        check(!drainThread.isAlive) { "Video encoder did not emit EOS in time" }
    }

    fun reportLines(): List<String> = listOfNotNull(
        "videoCodec=$codecName",
        "videoCodecHardware=$hardwareAccelerated",
        "configuredProfile=${configuredAttempt.profile}",
        "configuredLevel=${configuredAttempt.level}",
        "configuredMaxBFrames=${configuredAttempt.maxBFrames}",
        "configuredVbrRequest=${configuredAttempt.requestVbr}",
        "configuredMaxComplexityRequest=${configuredAttempt.requestMaxComplexity}",
        actualOutputFormat?.let { "actualVideoFormat=${it.replace('\n', ' ')}" },
    )

    private fun drainLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        val info = MediaCodec.BufferInfo()
        var eos = false

        while (!eos) {
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    actualOutputFormat = format.toString()
                    Log.i(TAG, "Video output format: $format")
                    muxer.setTrackFormat(RecordingMuxer.Track.VIDEO, format)
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        muxer.writeSample(RecordingMuxer.Track.VIDEO, buffer, info)
                    }
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun createFormat(
        codecInfo: MediaCodecInfo,
        attempt: EncoderAttempt,
    ): MediaFormat {
        val size = config.outputSize
        val capabilities = codecInfo.getCapabilitiesForType(MIME)
        val encoderCapabilities = capabilities.encoderCapabilities

        return MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_PRIORITY, 0)

            // Keep the container/decoder interpretation deterministic and match the SDR iPhone sample.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Optional encoder pacing/reorder controls were added in API 29.
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.frameRate.toFloat())
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, attempt.maxBFrames)
            }

            if (attempt.profile != null && attempt.level != null) {
                setInteger(MediaFormat.KEY_PROFILE, attempt.profile)
                setInteger(MediaFormat.KEY_LEVEL, attempt.level)
            }

            if (
                attempt.requestVbr &&
                encoderCapabilities.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            ) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
            }

            if (attempt.requestMaxComplexity) {
                val complexityRange = encoderCapabilities.complexityRange
                setInteger(MediaFormat.KEY_COMPLEXITY, complexityRange.upper)
            }
        }
    }

    private fun selectCodec(size: Size): CodecSelection {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } }
            .filter { info ->
                runCatching {
                    info.getCapabilitiesForType(MIME)
                        .videoCapabilities
                        .areSizeAndRateSupported(
                            size.width,
                            size.height,
                            config.frameRate.toDouble(),
                        )
                }.getOrDefault(false)
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.isHardwareAcceleratedCompat() }
                    .thenBy { it.isSoftwareOnlyCompat() }
            )
            .toList()

        val codecInfo = codecInfos.firstOrNull()
            ?: error("No AVC encoder supports ${size.width}x${size.height}@${config.frameRate}")

        val capabilities = codecInfo.getCapabilitiesForType(MIME)
        val preferredProfiles = listOf(
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        )

        val profileLevel = preferredProfiles.firstNotNullOfOrNull { profile ->
            capabilities.profileLevels
                .filter { it.profile == profile }
                .maxByOrNull { it.level }
        }

        Log.i(
            TAG,
            "Selected codec=${codecInfo.name}, hardware=${codecInfo.isHardwareAcceleratedCompat()}, " +
                "profile=${profileLevel?.profile}, advertisedLevel=${profileLevel?.level}",
        )

        return CodecSelection(codecInfo, profileLevel)
    }


    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            !isSoftwareOnlyCompat()
        }

    private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isSoftwareOnly
        } else {
            name.startsWith("OMX.google.", ignoreCase = true) ||
                name.startsWith("c2.android.", ignoreCase = true)
        }

    override fun close() {
        runCatching { stopAndAwait() }
        runCatching { inputSurface.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val TAG = "CameraPipelineLab"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DRAIN_TIMEOUT_MS = 5_000L
    }
}
