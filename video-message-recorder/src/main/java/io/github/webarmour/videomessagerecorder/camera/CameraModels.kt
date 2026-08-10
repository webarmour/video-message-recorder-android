package io.github.webarmour.videomessagerecorder.camera

import android.util.Range

data class CameraLiveMetrics(
    val actualFps: Double? = null,
    val exposureMs: Double? = null,
    val iso: Int? = null,
    val aeState: Int? = null,
    val activePhysicalCameraId: String? = null,
    val lowLightBoostActive: Boolean? = null,
    val estimatedSourceDrops: Long = 0L,
    val encoderBackpressureEvents: Long = 0L,
)

data class CameraCapabilities(
    val hardwareLevel: String = "",
    val manualSensor: Boolean = false,
    val lowLightBoostAvailable: Boolean = false,
    val lowLightBoostLuxRange: Range<Float>? = null,
    val aePriorityModes: List<Int> = emptyList(),
    val antibandingModes: List<Int> = emptyList(),
    val supportedQualities: List<VideoQuality> = emptyList(),
    val stabilizationModes: List<Int> = emptyList(),
    val opticalStabilizationModes: List<Int> = emptyList(),
    val edgeModes: List<Int> = emptyList(),
    val distortionCorrectionModes: List<Int> = emptyList(),
    val aberrationCorrectionModes: List<Int> = emptyList(),
    val tonemapModes: List<Int> = emptyList(),
    val hotPixelModes: List<Int> = emptyList(),
    val shadingModes: List<Int> = emptyList(),
    val colorCorrectionModes: List<Int> = emptyList(),
    val colorTemperatureRange: Range<Int>? = null,
    val awbModes: List<Int> = emptyList(),
    val aeLockAvailable: Boolean = false,
    val awbLockAvailable: Boolean = false,
    val exposureCompensationRange: Range<Int>? = null,
    val exposureCompensationStepEv: Float = 0f,
    val sensitivityRange: Range<Int>? = null,
    val exposureTimeUsRange: Range<Long>? = null,
    val minimumFocusDistance: Float = 0f,
    val zoomRatioRange: Range<Float>? = null,
    val availableApertures: List<Float> = emptyList(),
    val vendorRequestKeys: List<String> = emptyList(),
)

data class CameraUiState(
    val cameraReady: Boolean = false,
    val isRecording: Boolean = false,
    val isBusy: Boolean = false,
    val isSwitchingCamera: Boolean = false,
    val status: String = "",
    val config: RecordingConfig = RecordingConfig(),
    val supportedFpsRanges: List<Range<Int>> = emptyList(),
    val selectedFpsRange: Range<Int>? = null,
    val fpsHoldActive: Boolean = false,
    val supportedNoiseReductionModes: List<Int> = emptyList(),
    val appliedNoiseReductionMode: Int? = null,
    val activeCameraRole: CameraRole = CameraRole.FRONT,
    val activeCameraLabel: String = "",
    val cameraSwitchAvailable: Boolean = false,
    val concurrentCameraPrewarm: Boolean = false,
    val liveMetrics: CameraLiveMetrics = CameraLiveMetrics(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
)

enum class CameraRole {
    FRONT,
    REAR_WIDE,
}
