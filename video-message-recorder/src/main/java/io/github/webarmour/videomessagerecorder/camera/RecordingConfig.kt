package io.github.webarmour.videomessagerecorder.camera

import android.hardware.camera2.CameraMetadata
import android.util.Size

// Keep newer Camera2 enum values as compile-time integers so RecordingConfig can be safely loaded
// on API 28. The corresponding CaptureRequest/CameraMetadata fields are only referenced behind
// SDK checks inside CameraRecorderController.
private const val COLOR_CORRECTION_MODE_CCT_API_36 = 3
private const val CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_API_33 = 2
private const val CONTROL_AE_PRIORITY_MODE_OFF_API_36 = 0
private const val CONTROL_AE_PRIORITY_MODE_ISO_API_36 = 1
private const val CONTROL_AE_PRIORITY_MODE_SHUTTER_API_36 = 2

enum class VideoQuality(
    val outputSize: Size,
) {
    COMPACT(Size(320, 320)),
    TELEGRAM(Size(384, 384)),
    IPHONE_LIKE(Size(400, 400)),
    HIGH(Size(480, 480)),
    TELEGRAM_NOTE_MAX(Size(640, 640)),
    HD(Size(720, 720)),
    FULL_HD(Size(1080, 1080)),
    QHD(Size(1440, 1440)),
}

enum class NoiseReductionSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    OFF(CameraMetadata.NOISE_REDUCTION_MODE_OFF),
    MINIMAL(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL),
    FAST(CameraMetadata.NOISE_REDUCTION_MODE_FAST),
    HIGH_QUALITY(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY),
}

enum class HotPixelSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    OFF(CameraMetadata.HOT_PIXEL_MODE_OFF),
    FAST(CameraMetadata.HOT_PIXEL_MODE_FAST),
    HIGH_QUALITY(CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY),
}

enum class ShadingSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    OFF(CameraMetadata.SHADING_MODE_OFF),
    FAST(CameraMetadata.SHADING_MODE_FAST),
    HIGH_QUALITY(CameraMetadata.SHADING_MODE_HIGH_QUALITY),
}

enum class ColorCorrectionSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    FAST(CameraMetadata.COLOR_CORRECTION_MODE_FAST),
    HIGH_QUALITY(CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY),
    CCT(COLOR_CORRECTION_MODE_CCT_API_36),
}

enum class StabilizationSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    OFF(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF),
    VIDEO(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
    PREVIEW(CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_API_33),
}

enum class OpticalStabilizationSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    OFF(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF),
    ON(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
}

enum class EdgeSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    OFF(CameraMetadata.EDGE_MODE_OFF),
    FAST(CameraMetadata.EDGE_MODE_FAST),
    HIGH_QUALITY(CameraMetadata.EDGE_MODE_HIGH_QUALITY),
}

enum class DistortionCorrectionSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    OFF(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF),
    FAST(CameraMetadata.DISTORTION_CORRECTION_MODE_FAST),
    HIGH_QUALITY(CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY),
}

enum class AberrationCorrectionSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    OFF(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF),
    FAST(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST),
    HIGH_QUALITY(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY),
}

enum class TonemapSetting(val camera2Mode: Int?) {
    OEM_DEFAULT(null),
    FAST(CameraMetadata.TONEMAP_MODE_FAST),
    HIGH_QUALITY(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY),
}

enum class AwbSetting(val camera2Mode: Int) {
    AUTO(CameraMetadata.CONTROL_AWB_MODE_AUTO),
    INCANDESCENT(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    WARM_FLUORESCENT(CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT),
    DAYLIGHT(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY_DAYLIGHT(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    TWILIGHT(CameraMetadata.CONTROL_AWB_MODE_TWILIGHT),
    SHADE(CameraMetadata.CONTROL_AWB_MODE_SHADE),
}

enum class ExposureMode {
    AUTO,
    MANUAL,
}

enum class AntibandingSetting(val camera2Mode: Int) {
    AUTO(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO),
    HZ_50(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ),
    HZ_60(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ),
    OFF(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF),
}

enum class AePrioritySetting(val camera2Mode: Int) {
    OFF(CONTROL_AE_PRIORITY_MODE_OFF_API_36),
    ISO(CONTROL_AE_PRIORITY_MODE_ISO_API_36),
    SHUTTER(CONTROL_AE_PRIORITY_MODE_SHUTTER_API_36),
}

enum class FocusSetting {
    CONTINUOUS_VIDEO,
    AUTO,
    MANUAL,
}

data class RecordingConfig(
    val quality: VideoQuality = VideoQuality.TELEGRAM_NOTE_MAX,
    val videoBitrate: Int = 1_500_000,
    val frameRate: Int = 60,
    val iFrameIntervalSeconds: Int = 1,
    val maxBFrames: Int = 2,
    val holdFpsInLowLight: Boolean = true,
    val lowLightBoost: Boolean = false,
    val aePriority: AePrioritySetting = AePrioritySetting.OFF,
    val antibanding: AntibandingSetting = AntibandingSetting.AUTO,
    val noiseReduction: NoiseReductionSetting = NoiseReductionSetting.OEM_DEFAULT,
    val hotPixelCorrection: HotPixelSetting = HotPixelSetting.FAST,
    val lensShading: ShadingSetting = ShadingSetting.FAST,
    val colorCorrection: ColorCorrectionSetting = ColorCorrectionSetting.FAST,
    val colorTemperatureKelvin: Int = 5_000,
    val colorTint: Int = 0,
    val stabilization: StabilizationSetting = StabilizationSetting.OEM_DEFAULT,
    val opticalStabilization: OpticalStabilizationSetting = OpticalStabilizationSetting.OEM_DEFAULT,
    val edgeEnhancement: EdgeSetting = EdgeSetting.FAST,
    val distortionCorrection: DistortionCorrectionSetting = DistortionCorrectionSetting.FAST,
    val aberrationCorrection: AberrationCorrectionSetting = AberrationCorrectionSetting.FAST,
    val tonemap: TonemapSetting = TonemapSetting.FAST,
    val awb: AwbSetting = AwbSetting.AUTO,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    val exposureCompensationSteps: Int = 0,
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val manualExposureTimeUs: Long = 10_000L,
    val manualIso: Int = 400,
    val focus: FocusSetting = FocusSetting.CONTINUOUS_VIDEO,
    val manualFocusDistance: Float = 0f,
    val zoomRatio: Float = 1f,
    val aperture: Float? = null,
    val circleMaskInSavedVideo: Boolean = false,
    val hidePreviewUntilRecording: Boolean = false,
    val audioSampleRate: Int = 48_000,
    val audioBitrate: Int = 64_000,
) {
    val outputSize: Size
        get() = quality.outputSize
}
