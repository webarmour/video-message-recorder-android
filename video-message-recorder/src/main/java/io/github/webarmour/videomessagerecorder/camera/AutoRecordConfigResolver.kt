package io.github.webarmour.videomessagerecorder.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log

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

        AUTO_QUALITY_ORDER.forEach { quality ->

            /*
             * Best case:
             * both front and rear can really hold 60 FPS.
             */
            createCandidate(
                cameras = cameras,
                quality = quality,
                frameRate = 60,
                holdFps = true,
            )?.let {
                return it
            }

            /*
             * Main fallback:
             * stable fixed 30 FPS.
             *
             * This is exactly what Pixel emulator from your log supports:
             * FPS=[30, 30]
             */
            createCandidate(
                cameras = cameras,
                quality = quality,
                frameRate = 30,
                holdFps = true,
            )?.let {
                return it
            }

            /*
             * Last fallback for this resolution:
             * allow HAL to vary FPS around 30.
             *
             * Recording must not fail just because fixed FPS is unavailable.
             */
            createCandidate(
                cameras = cameras,
                quality = quality,
                frameRate = 30,
                holdFps = false,
            )?.let {
                return it
            }
        }

        return fallbackConfig()
    }

    private fun createCandidate(
        cameras: List<CameraCharacteristics>,
        quality: VideoQuality,
        frameRate: Int,
        holdFps: Boolean,
    ): RecordingConfig? {
        val outputSize =
            quality.outputSize

        if (
            !avcEncoderSupports(
                size = outputSize,
                frameRate = frameRate,
            )
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
            return !holdFps
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
        return RecordingConfig(
            quality = quality,

            videoBitrate = bitrateFor(
                quality = quality,
                frameRate = frameRate,
            ),

            frameRate = frameRate,

            iFrameIntervalSeconds = 1,

            maxBFrames =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {
                    2
                } else {
                    0
                },

            holdFpsInLowLight = holdFps,

            lowLightBoost = false,

            aePriority =
                AePrioritySetting.OFF,

            antibanding =
                AntibandingSetting.AUTO,

            /*
             * AUTO should avoid forcing OEM ISP modes unless necessary.
             *
             * This gives old Realme/Samsung/etc. HALs maximum freedom.
             */
            noiseReduction =
                NoiseReductionSetting.OEM_DEFAULT,

            hotPixelCorrection =
                HotPixelSetting.OEM_DEFAULT,

            lensShading =
                ShadingSetting.OEM_DEFAULT,

            colorCorrection =
                ColorCorrectionSetting.OEM_DEFAULT,

            stabilization =
                StabilizationSetting.OEM_DEFAULT,

            opticalStabilization =
                OpticalStabilizationSetting.OEM_DEFAULT,

            edgeEnhancement =
                EdgeSetting.OEM_DEFAULT,

            distortionCorrection =
                DistortionCorrectionSetting.OEM_DEFAULT,

            aberrationCorrection =
                AberrationCorrectionSetting.OEM_DEFAULT,

            tonemap =
                TonemapSetting.OEM_DEFAULT,

            awb =
                AwbSetting.AUTO,

            aeLock = false,
            awbLock = false,

            exposureCompensationSteps = 0,

            exposureMode =
                ExposureMode.AUTO,

            focus =
                FocusSetting.CONTINUOUS_VIDEO,

            zoomRatio = 1f,

            aperture = null,

            circleMaskInSavedVideo = false,

            hidePreviewUntilRecording = false,

            audioSampleRate = 48_000,

            audioBitrate = 64_000,
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

    private fun bitrateFor(
        quality: VideoQuality,
        frameRate: Int,
    ): Int {
        return when (quality) {

            VideoQuality.TELEGRAM_NOTE_MAX -> {
                if (frameRate >= 60) {
                    2_200_000
                } else {
                    1_500_000
                }
            }

            VideoQuality.HIGH -> {
                if (frameRate >= 60) {
                    1_600_000
                } else {
                    1_100_000
                }
            }

            VideoQuality.IPHONE_LIKE -> {
                if (frameRate >= 60) {
                    1_300_000
                } else {
                    900_000
                }
            }

            VideoQuality.TELEGRAM -> {
                if (frameRate >= 60) {
                    1_200_000
                } else {
                    850_000
                }
            }

            VideoQuality.COMPACT -> {
                if (frameRate >= 60) {
                    1_000_000
                } else {
                    700_000
                }
            }

            VideoQuality.HD,
            VideoQuality.FULL_HD,
            VideoQuality.QHD,
                -> {
                if (frameRate >= 60) {
                    2_500_000
                } else {
                    1_800_000
                }
            }
        }
    }

    private companion object {

        const val TAG =
            "VideoMessageRecorder"

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