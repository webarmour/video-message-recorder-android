package io.github.webarmour.videomessagerecorder

import io.github.webarmour.videomessagerecorder.camera.RecordingConfig
import io.github.webarmour.videomessagerecorder.camera.VideoQuality
import io.github.webarmour.videomessagerecorder.camera.NoiseReductionSetting
import io.github.webarmour.videomessagerecorder.camera.HotPixelSetting
import io.github.webarmour.videomessagerecorder.camera.ShadingSetting
import io.github.webarmour.videomessagerecorder.camera.ColorCorrectionSetting
import io.github.webarmour.videomessagerecorder.camera.OpticalStabilizationSetting
import io.github.webarmour.videomessagerecorder.camera.EdgeSetting
import io.github.webarmour.videomessagerecorder.camera.AberrationCorrectionSetting
import io.github.webarmour.videomessagerecorder.camera.TonemapSetting
import io.github.webarmour.videomessagerecorder.camera.FocusSetting

/**
 * Defines how recording parameters are selected.
 *
 * [Auto] is intended for normal production usage.
 * [Custom] exposes [RecordingConfig] for diagnostics, experiments,
 * and applications that require explicit recording parameters.
 */
sealed interface RecordingMode {

    data object Auto : RecordingMode {
        /** Preferred values for settings UIs; recording falls back to device capabilities. */
        val preferredConfig: RecordingConfig
            get() = RecordingConfig(
                quality = VideoQuality.TELEGRAM_NOTE_MAX,
                videoBitrate = 2_000_000,
                frameRate = 60,
                enableConcurrentPrewarm = false,
                holdFpsInLowLight = true,
                noiseReduction = NoiseReductionSetting.MINIMAL,
                hotPixelCorrection = HotPixelSetting.HIGH_QUALITY,
                lensShading = ShadingSetting.HIGH_QUALITY,
                colorCorrection = ColorCorrectionSetting.HIGH_QUALITY,
                opticalStabilization = OpticalStabilizationSetting.OFF,
                edgeEnhancement = EdgeSetting.HIGH_QUALITY,
                aberrationCorrection = AberrationCorrectionSetting.HIGH_QUALITY,
                tonemap = TonemapSetting.HIGH_QUALITY,
                focus = FocusSetting.AUTO,
                audioBitrate = 128_000,
            )
    }

    data class Custom(
        val config: RecordingConfig,
    ) : RecordingMode
}
