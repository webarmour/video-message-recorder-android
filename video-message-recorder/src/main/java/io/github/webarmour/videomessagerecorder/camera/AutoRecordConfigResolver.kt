package io.github.webarmour.videomessagerecorder.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import io.github.webarmour.videomessagerecorder.RecordingMode

internal class AutoRecordingConfigResolver(
    context: Context,
) {

    private val cameraManager =
        context.applicationContext.getSystemService(
            CameraManager::class.java
        )

    private var cachedConfig: RecordingConfig? = null

    fun resolve(): RecordingConfig {
        cachedConfig?.let {
            return it
        }

        val config = runCatching {
            resolveInternal()
        }.onFailure { error ->
            Log.w(
                TAG,
                "Unable to resolve AUTO recording config",
                error,
            )
        }.getOrElse {
            fallbackConfig()
        }

        cachedConfig = config

        Log.i(
            TAG,
            "AUTO recording config: " +
                    "${config.outputSize.width}x${config.outputSize.height}, " +
                    "${config.frameRate} fps, " +
                    "bitrate=${config.videoBitrate}, " +
                    "holdFps=${config.holdFpsInLowLight}",
        )

        return config
    }

    private fun resolveInternal(): RecordingConfig {
        val cameras =
            discoverRecordingCameras()

        if (cameras.isEmpty()) {
            return fallbackConfig()
        }

        // Codec enumeration is relatively expensive on some devices. AUTO probes the same
        // resolution/FPS pair more than once when it tries fixed and variable FPS policies.
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val encoderSupportCache = HashMap<Pair<VideoQuality, Int>, Boolean>()

        // Prefer 640/60, then lower the FPS before reducing resolution. Include HAL
        // endpoints so devices limited to e.g. 25 or 15 FPS still get a supported pair.
        val frameRates = (listOf(60, 30) + cameras.flatMap { characteristics ->
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty().flatMap { listOf(it.lower, it.upper) }
        }).filter { it in 1..60 }.distinct().sortedDescending()

        AUTO_QUALITY_ORDER.forEach { quality ->
            frameRates.forEach { frameRate ->
                listOf(true, false).forEach { holdFps ->
                    createCandidate(
                        cameras = cameras,
                        quality = quality,
                        frameRate = frameRate,
                        holdFps = holdFps,
                        codecInfos = codecInfos,
                        encoderSupportCache = encoderSupportCache,
                    )?.let { return it }
                }
            }
        }

        return fallbackConfig()
    }

    private fun createCandidate(
        cameras: List<CameraCharacteristics>,
        quality: VideoQuality,
        frameRate: Int,
        holdFps: Boolean,
        codecInfos: Array<MediaCodecInfo>,
        encoderSupportCache: MutableMap<Pair<VideoQuality, Int>, Boolean>,
    ): RecordingConfig? {
        val outputSize =
            quality.outputSize

        if (
            !encoderSupportCache.getOrPut(quality to frameRate) {
                avcEncoderSupports(
                    size = outputSize,
                    frameRate = frameRate,
                    codecInfos = codecInfos,
                )
            }
        ) {
            return null
        }

        val camerasSupported =
            cameras.all { characteristics ->

                cameraCanFeedSquareOutput(
                    characteristics = characteristics,
                    output = outputSize,
                    frameRate = frameRate,
                ) &&
                        supportsFps(
                            characteristics = characteristics,
                            frameRate = frameRate,
                            holdFps = holdFps,
                        )
            }

        if (!camerasSupported) {
            return null
        }

        return createConfig(
            quality = quality,
            frameRate = frameRate,
            holdFps = holdFps,
        )
    }

    private fun supportsFps(
        characteristics: CameraCharacteristics,
        frameRate: Int,
        holdFps: Boolean,
    ): Boolean {
        val ranges =
            characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )

        if (ranges.isNullOrEmpty()) {
            /*
             * If HAL does not publish FPS ranges, we cannot promise fixed FPS.
             *
             * Adaptive 30 is still allowed.
             */
            return !holdFps && frameRate == 30
        }

        return if (holdFps) {
            /*
             * Be deliberately strict for AUTO.
             *
             * [30, 30] guarantees 30.
             * [60, 60] guarantees 60.
             *
             * [30, 60] does NOT guarantee 60 in low light.
             */
            ranges.any { range ->
                range.lower == frameRate &&
                        range.upper == frameRate
            }
        } else {
            ranges.any { range ->
                frameRate in range
            }
        }
    }

    private fun discoverRecordingCameras(): List<CameraCharacteristics> {
        val cameraIds =
            cameraManager.cameraIdList.toList()

        if (cameraIds.isEmpty()) {
            return emptyList()
        }

        val characteristicsById =
            cameraIds.associateWith { cameraId ->
                cameraManager.getCameraCharacteristics(
                    cameraId
                )
            }

        val frontId =
            cameraIds.firstOrNull { cameraId ->
                characteristicsById
                    .getValue(cameraId)
                    .get(
                        CameraCharacteristics.LENS_FACING
                    ) ==
                        CameraMetadata.LENS_FACING_FRONT
            }

        val rearIds =
            cameraIds.filter { cameraId ->
                characteristicsById
                    .getValue(cameraId)
                    .get(
                        CameraCharacteristics.LENS_FACING
                    ) ==
                        CameraMetadata.LENS_FACING_BACK
            }

        val rearId =
            chooseMainRearCameraId(
                cameraIds = rearIds,
                characteristicsById =
                    characteristicsById,
            )

        return buildList {
            frontId?.let { cameraId ->
                add(
                    characteristicsById.getValue(
                        cameraId
                    )
                )
            }

            rearId
                ?.takeIf {
                    it != frontId
                }
                ?.let { cameraId ->
                    add(
                        characteristicsById.getValue(
                            cameraId
                        )
                    )
                }

            /*
             * Extremely unusual device without a normal FRONT/BACK pair.
             * Still allow recording from whatever camera exists.
             */
            if (isEmpty()) {
                add(
                    characteristicsById.getValue(
                        cameraIds.first()
                    )
                )
            }
        }
    }

    private fun createConfig(
        quality: VideoQuality,
        frameRate: Int,
        holdFps: Boolean,
    ): RecordingConfig {
        return RecordingMode.Auto.preferredConfig.copy(
            quality = quality,
            frameRate = frameRate,
            holdFpsInLowLight = holdFps,
            maxBFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 2 else 0,
        )
    }

    private fun fallbackConfig(): RecordingConfig {
        /*
         * Capability discovery itself failed.
         *
         * Choose a deliberately conservative configuration and,
         * most importantly, do NOT require fixed FPS.
         */
        return createConfig(
            quality = VideoQuality.HIGH,
            frameRate = 30,
            holdFps = false,
        )
    }

    private companion object {

        const val TAG = "CameraPipelineLab"

        val AUTO_QUALITY_ORDER =
            listOf(
                VideoQuality.TELEGRAM_NOTE_MAX,
                VideoQuality.HIGH,
                VideoQuality.IPHONE_LIKE,
                VideoQuality.TELEGRAM,
                VideoQuality.COMPACT,
            )
    }
}
