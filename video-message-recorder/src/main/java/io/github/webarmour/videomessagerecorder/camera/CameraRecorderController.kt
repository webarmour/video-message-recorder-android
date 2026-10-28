package io.github.webarmour.videomessagerecorder.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import io.github.webarmour.videomessagerecorder.gl.CameraGlRenderer
import io.github.webarmour.videomessagerecorder.gl.PresentationTimestampGate
import io.github.webarmour.videomessagerecorder.media.AudioEncoder
import io.github.webarmour.videomessagerecorder.media.VideoEncoder
import io.github.webarmour.videomessagerecorder.DiagnosticsMode
import io.github.webarmour.videomessagerecorder.RecordingResult
import io.github.webarmour.videomessagerecorder.newRecordingBaseName
import io.github.webarmour.videomessagerecorder.R
import io.github.webarmour.videomessagerecorder.media.RecordingMuxer
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

internal class CameraRecorderController(
    context: Context,
    initialConfig: RecordingConfig = RecordingConfig(),
    private val onState: (CameraUiState) -> Unit,
    private val onRecordingFinished: (RecordingResult) -> Unit,
) : AutoCloseable {

    private data class CameraProfile(
        val role: CameraRole,
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val sourceSize: Size,
        val sensorOrientationDegrees: Int,
        val mirrorHorizontally: Boolean,
        val zoomRatio: Float,
        val label: String,
    )

    private class CameraPipeline(
        val profile: CameraProfile,
        val renderer: CameraGlRenderer,
        val inputSurface: Surface,
    ) {
        var prewarmReader: ImageReader? = null
        val prewarmReaderFrames = AtomicLong(0L)
        val cameraZoomRange = profile.characteristics.zoomRatioRangeCompat()
        // Front camera uses GL shader digital zoom; Rear camera uses Camera2 hardware sensor zoom (CONTROL_ZOOM_RATIO / SCALER_CROP_REGION).
        val zoomInRenderer = profile.role == CameraRole.FRONT
        val concurrentZoomRange = Range(
            1f,
            minOf(
                cameraZoomRange.upper,
                profile.characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?.takeIf { it.isFinite() && it >= 1f } ?: 1f,
            ).coerceAtLeast(1f),
        )
        @Volatile var device: CameraDevice? = null
        @Volatile var session: CameraCaptureSession? = null
        var repeatingRequestBuilder: CaptureRequest.Builder? = null
        var repeatingCaptureCallback: CameraCaptureSession.CaptureCallback? = null
        var lastSubmittedZoomRatio = Float.NaN
        var zoomSubmittedSequence = 0L
        var zoomObservedSequence = 0L
        var maxPendingZoomRequests = 0L
        @Volatile var lastZoomRequestNs = 0L
        @Volatile var zoomRatio: Float = profile.zoomRatio
        var opening = false
        var openGeneration = 0L
        var sessionRetryWithoutUseCase = false

        var selectedFpsRange: Range<Int>? = null
        var fpsHoldActive = false
        var stabilizationMode: Int? = null
        var stabilizationSessionParameter: Int? = null
        var initialSessionParameterKeys: List<String> = emptyList()
        var appliedNoiseReductionMode: Int? = null
        var videoRecordUseCaseEnabled = false
        var readoutTimestampEnabled = false

        var warmupStartedNs = 0L
        var stable3AFrames = 0
        var warmed = false

        // When the inactive concurrent camera is kept at 15 fps but recording is 60 fps,
        // switching first promotes this camera to the recording rate. The old renderer stays
        // attached until the promoted request is actually observed in CaptureResult, which hides
        // vendor sensor/ISP mode-switch latency from preview and MediaCodec.
        var switchPromotionStartedNs = 0L
        var switchPromotionStableFrames = 0
        var switchPromotionFpsReadyNs = 0L
        var switchPromotionAeWaitMs = 0L
        var switchGlInputBaseline = -1L
        var switchGlRequestSequence = 0L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prewarmDrainThread = HandlerThread(
        "CameraPrewarmDrain", Process.THREAD_PRIORITY_DEFAULT,
    ).apply { start() }
    private val prewarmDrainHandler = Handler(prewarmDrainThread.looper)
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recordingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val requestScheduler = Executors.newSingleThreadScheduledExecutor()
    private val requestRevision = AtomicLong(0L)
    private val zoomRequestRevision = AtomicLong(0L)
    private val zoomUpdateScheduled = AtomicBoolean(false)
    private val zoomUiUpdatePending = AtomicBoolean(false)
    private val recoveryGeneration = AtomicLong(0L)
    private val switchGeneration = AtomicLong(0L)
    private val timestampGate = PresentationTimestampGate()
    private val telemetry = CaptureTelemetry()
    private val cameraCallbackExecutor = Executor { task ->
        try {
            cameraExecutor.execute(task)
        } catch (error: RejectedExecutionException) {
            if (!closed.get()) {
                throw error
            }

            Log.d(
                TAG,
                "Ignoring Camera2 callback after recorder close",
            )
        }
    }

    @Volatile
    private var config: RecordingConfig = initialConfig

    private var currentUiState = CameraUiState(
        config = initialConfig,
        status = appContext.getString(R.string.status_waiting_for_camera),
        activeCameraLabel = appContext.getString(R.string.camera_front),
    )

    private val pipelines = linkedMapOf<CameraRole, CameraPipeline>()
    // Camera2 close() returns before the device releases its resources. All access stays on
    // cameraExecutor so onClosed can resume a switch without blocking the callback thread.
    private val closingCameraDevices = mutableSetOf<CameraDevice>()
    private val deferredCameraOpens = linkedMapOf<CameraRole, Boolean>()
    private var closeWaitScheduled = false
    private var closeWaitGeneration = 0L
    @Volatile private var activeRole = CameraRole.FRONT
    @Volatile private var diagnosticsMode: DiagnosticsMode = DiagnosticsMode.Disabled
    private var pendingActivationRole: CameraRole? = null
    @Volatile private var concurrentCameraPrewarm = false
    private var concurrentVideoRecordUseCase = true
    private var concurrentReaderPrewarm = false
    private var concurrentPrewarmFps = CONCURRENT_PREWARM_MAX_FPS
    private var concurrentHighFpsRejected = false
    @Volatile private var cameraRecoveryPending = false
    @Volatile private var recoveryAttemptCount = 0
    private var cameraSystemPrepared = false
    private var cameraSwitchCount = 0
    private var lastCameraSwitchPromotionMs = 0L
    private var lastCameraSwitchAeWaitMs = 0L
    private var cameraSwitchPromotionTimeoutCount = 0
    private var cameraSwitchAeTimeoutCount = 0

    private var previewSurface: Surface? = null
    private var previewWidth = 1
    private var previewHeight = 1
    private var displayRotation = Surface.ROTATION_0

    @Volatile
    private var permissionGranted = false

    @Volatile
    private var lifecycleActive = false

    @Volatile
    private var recording = false

    private val recordingTransition = AtomicBoolean(false)
    private val switchRequestPending = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private var muxer: RecordingMuxer? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var tempFile: File? = null
    private var recordingBaseName: String? = null
    private var activeRecordingConfig: RecordingConfig? = null
    private var lastMetricsUiUpdateNs = 0L

    fun setPermissionGranted(granted: Boolean) {
        permissionGranted = granted
        maybePrepareAndOpenCameras()
    }

    fun onStart() {
        lifecycleActive = true
        maybePrepareAndOpenCameras()
    }

    fun onStop() {
        lifecycleActive = false
        recoveryGeneration.incrementAndGet()
        cameraRecoveryPending = false
        recoveryAttemptCount = 0
        requestRevision.incrementAndGet()
        zoomRequestRevision.incrementAndGet()
        switchRequestPending.set(false)
        updateState { state -> state.copy(isSwitchingCamera = false) }
        if (recording) stopRecording()
        closeAllCameraDevices()
    }

    fun attachPreview(
        surface: Surface,
        width: Int,
        height: Int,
        displayRotation: Int,
    ) {
        previewSurface = surface
        previewWidth = width.coerceAtLeast(1)
        previewHeight = height.coerceAtLeast(1)
        this.displayRotation = displayRotation

        if (cameraSystemPrepared) {
            cameraExecutor.execute {
                pipelines.values.forEach { pipeline ->
                    pipeline.renderer.updateDisplayRotation(rotationToDegrees(displayRotation))
                }
                attachPreviewToActiveRenderer()
            }
        }
        maybePrepareAndOpenCameras()
    }

    fun detachPreview() {
        previewSurface = null
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.execute {
                pipelines.values.forEach { pipeline ->
                    runCatching { pipeline.renderer.detachPreview() }
                }
            }
        }
    }

    fun toggleRecording() {
        if (recordingTransition.get()) return
        if (recording) stopRecording() else startRecording()
    }

    fun switchCamera() {
        if (closed.get() || !lifecycleActive || cameraRecoveryPending || recoveryAttemptCount != 0 ||
            currentUiState.isSwitchingCamera) return

        val targetRole = when (activeRole) {
            CameraRole.FRONT -> CameraRole.REAR_WIDE
            CameraRole.REAR_WIDE -> CameraRole.FRONT
        }
        val target = pipelines[targetRole] ?: return
        if (!switchRequestPending.compareAndSet(false, true)) return
        val currentSwitchGeneration = switchGeneration.incrementAndGet()
        zoomRequestRevision.incrementAndGet()
        config = config.copy(zoomRatio = 1f)

        updateState { state ->
            state.copy(
                config = config,
                isSwitchingCamera = true,
                status = appContext.getString(
                    if (concurrentCameraPrewarm) {
                        R.string.status_switching_prewarmed
                    } else {
                        R.string.status_switching_audio_continues
                    },
                    localizedCameraLabel(target.profile),
                ),
            )
        }

        try {
            cameraExecutor.execute {
                if (closed.get() || !lifecycleActive) {
                    switchRequestPending.set(false)
                    return@execute
                }

                // Blur the active renderer's last frame into the preview surface during switch.
                activePipeline()?.let { outgoing ->
                    outgoing.renderer.setBlurPreview(true)
                    outgoing.zoomRatio = 1f
                    if (outgoing.zoomInRenderer) outgoing.renderer.setDigitalZoomRatio(1f)
                    if (concurrentCameraPrewarm && outgoing.session != null &&
                        config.frameRate <= concurrentPrewarmFps
                    ) {
                        submitZoomRequest(outgoing)
                    }
                }
                target.zoomRatio = 1f
                if (target.zoomInRenderer) target.renderer.setDigitalZoomRatio(1f)
                if (target.session != null && target.lastSubmittedZoomRatio != 1f &&
                    (!concurrentCameraPrewarm || config.frameRate <= concurrentPrewarmFps)
                ) {
                    submitZoomRequest(target)
                }
                if (cameraRecoveryPending) {
                    switchRequestPending.set(false)
                    return@execute
                }
                pendingActivationRole = targetRole

                if (target.session != null && target.warmed) {
                    val needsHighFpsPromotion =
                        concurrentCameraPrewarm && config.frameRate > concurrentPrewarmFps

                    target.switchPromotionStableFrames = 0
                    target.switchPromotionFpsReadyNs = 0L
                    target.switchPromotionAeWaitMs = 0L
                    target.switchGlInputBaseline = -1L
                    target.switchGlRequestSequence = 0L
                    if (needsHighFpsPromotion) {
                        // The inactive camera is prewarmed below the requested recording rate.
                        // Do not expose that rate promotion
                        // transition to preview/encoder: keep rendering the old camera until Capture2
                        // confirms that the target has reached the requested cadence.
                        target.switchPromotionStartedNs = System.nanoTime()
                    }
                    if (needsHighFpsPromotion || concurrentReaderPrewarm) {
                        // In reader prewarm mode redirect the target to GL while the outgoing
                        // camera still feeds encoder and preview. Handoff follows its first frame.
                        if (concurrentReaderPrewarm) {
                            target.switchGlInputBaseline = target.renderer.performanceStats().inputFrames
                        }
                        submitRepeatingRequest(
                            pipeline = target,
                            resetWarmup = false,
                            publishUiStatus = false,
                        )
                        if (concurrentReaderPrewarm) {
                            target.switchGlRequestSequence = target.zoomSubmittedSequence
                        }
                    }
                    // The target may still be emitting frames from its previous zoom.
                    // Activate only after Camera2 reports a frame captured at 1x.
                    scheduleSwitchZoomWatchdog(target, currentSwitchGeneration)
                    return@execute
                }

                if (!concurrentCameraPrewarm) {
                    // Keep the old renderer attached to preview/encoder while the camera is closed.
                    // Its last frame remains visible and audio keeps running until the new camera is warm.
                    activePipeline()?.let(::closePipelineCamera)
                }

                if (target.device == null && !target.opening) {
                    openPipeline(target)
                }
                // If a concurrent session already exists but is still finishing its one-time initial
                // warmup, onCaptureResult() will activate it as soon as 3A converges. No request restart.
            }
        } catch (_: RejectedExecutionException) {
            switchRequestPending.set(false)
            if (!closed.get()) {
                updateState { state -> state.copy(isSwitchingCamera = false) }
            }
        }
    }

    fun updateConfig(newConfig: RecordingConfig) {
        if (recording || recordingTransition.get()) return

        val active = activePipeline()
        val activeCapabilities = active?.profile?.characteristics
        val aeCompRange =
            activeCapabilities?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val sensitivityRange =
            activeCapabilities?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRangeNs =
            activeCapabilities?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocusDistance = activeCapabilities
            ?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: 0f
        val zoomRange = active?.let(::availableZoomRange)
        val availableApertures = activeCapabilities
            ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?: floatArrayOf()
        val colorTemperatureRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            activeCapabilities?.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
        } else {
            null
        }

        var normalized = newConfig.copy(
            videoBitrate = newConfig.videoBitrate.coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE),
            frameRate = newConfig.frameRate.coerceIn(MIN_FRAME_RATE, MAX_FRAME_RATE),
            maxBFrames = newConfig.maxBFrames.coerceIn(0, MAX_B_FRAMES),
            exposureCompensationSteps = aeCompRange?.let {
                newConfig.exposureCompensationSteps.coerceIn(it.lower, it.upper)
            } ?: 0,
            manualIso = sensitivityRange?.let {
                newConfig.manualIso.coerceIn(it.lower, it.upper)
            } ?: newConfig.manualIso,
            manualExposureTimeUs = exposureRangeNs?.let {
                newConfig.manualExposureTimeUs.coerceIn(
                    (it.lower / 1_000L).coerceAtLeast(1L),
                    (it.upper / 1_000L).coerceAtLeast(1L),
                )
            } ?: newConfig.manualExposureTimeUs,
            manualFocusDistance = newConfig.manualFocusDistance.coerceIn(0f, minFocusDistance),
            zoomRatio = zoomRange?.let {
                (newConfig.zoomRatio.takeIf { zoom -> zoom.isFinite() }
                    ?: config.zoomRatio.takeIf { zoom -> zoom.isFinite() }
                    ?: 1f)
                    .coerceIn(it.lower, it.upper)
            } ?: 1f,
            colorTemperatureKelvin = colorTemperatureRange?.let {
                newConfig.colorTemperatureKelvin.coerceIn(it.lower, it.upper)
            } ?: newConfig.colorTemperatureKelvin,
            colorTint = newConfig.colorTint.coerceIn(-50, 50),
            aperture = newConfig.aperture?.let { requested ->
                availableApertures.minByOrNull { available -> abs(available - requested) }
            },
        )

        // Codec capability discovery is relatively expensive. Only revalidate the output when the
        // requested quality or FPS actually changes; bitrate/ISP sliders must stay UI-cheap.
        val qualityOrFpsChanged =
            normalized.quality != config.quality || normalized.frameRate != config.frameRate

        // A new FPS can invalidate a previously selected high-resolution square output. Prefer
        // reducing the output size rather than silently upscaling or starting an unsupported codec.
        if (
            qualityOrFpsChanged &&
            pipelines.isNotEmpty() &&
            !isQualitySupported(normalized.quality, normalized.frameRate)
        ) {
            val fallbackQuality = VideoQuality.entries
                .filter { isQualitySupported(it, normalized.frameRate) }
                .filter { it.outputSize.width <= normalized.quality.outputSize.width }
                .maxByOrNull { it.outputSize.width }
                ?: VideoQuality.entries.firstOrNull { isQualitySupported(it, normalized.frameRate) }
            if (fallbackQuality != null) {
                normalized = normalized.copy(quality = fallbackQuality)
            }
        }

        val prewarmModeChanged =
            normalized.enableConcurrentPrewarm != config.enableConcurrentPrewarm ||
                    normalized.concurrentPrewarmFps != config.concurrentPrewarmFps

        val sourcePipelineChanged =
            normalized.quality != config.quality ||
                    normalized.frameRate != config.frameRate ||
                    prewarmModeChanged

        val exposurePipelineChanged =
            normalized.holdFpsInLowLight != config.holdFpsInLowLight ||
                    normalized.lowLightBoost != config.lowLightBoost ||
                    normalized.aePriority != config.aePriority ||
                    normalized.exposureMode != config.exposureMode ||
                    normalized.exposureCompensationSteps != config.exposureCompensationSteps ||
                    normalized.manualExposureTimeUs != config.manualExposureTimeUs ||
                    normalized.manualIso != config.manualIso ||
                    normalized.aeLock != config.aeLock ||
                    normalized.awb != config.awb ||
                    normalized.awbLock != config.awbLock ||
                    normalized.focus != config.focus ||
                    normalized.manualFocusDistance != config.manualFocusDistance

        val tuningChanged =
            normalized.noiseReduction != config.noiseReduction ||
                    normalized.hotPixelCorrection != config.hotPixelCorrection ||
                    normalized.lensShading != config.lensShading ||
                    normalized.colorCorrection != config.colorCorrection ||
                    normalized.colorTemperatureKelvin != config.colorTemperatureKelvin ||
                    normalized.colorTint != config.colorTint ||
                    normalized.stabilization != config.stabilization ||
                    normalized.opticalStabilization != config.opticalStabilization ||
                    normalized.edgeEnhancement != config.edgeEnhancement ||
                    normalized.distortionCorrection != config.distortionCorrection ||
                    normalized.aberrationCorrection != config.aberrationCorrection ||
                    normalized.tonemap != config.tonemap ||
                    normalized.antibanding != config.antibanding ||
                    (normalized.zoomRatio != config.zoomRatio && active?.zoomInRenderer != true) ||
                    normalized.aperture != config.aperture

        val cameraRequestChanged = exposurePipelineChanged || tuningChanged
        val previewVisibilityChanged =
            normalized.hidePreviewUntilRecording != config.hidePreviewUntilRecording

        config = normalized
        activePipeline()?.let { pipeline ->
            pipeline.zoomRatio = normalized.zoomRatio
            if (pipeline.zoomInRenderer) {
                pipeline.renderer.setDigitalZoomRatio(normalized.zoomRatio)
            }
        }

        // Keep UI state stable for CaptureRequest-only changes. Rebuilding the source is the only
        // configuration operation that temporarily marks the camera busy. This avoids the visible
        // enable/disable flash of every chip and switch while tuning Camera2 controls.
        updateState { state ->
            state.copy(
                config = normalized,
                isBusy = if (sourcePipelineChanged) true else state.isBusy,
                status = if (sourcePipelineChanged) appContext.getString(R.string.status_reconfiguring_camera_source) else state.status,
            )
        }

        if (sourcePipelineChanged && cameraSystemPrepared) {
            requestRevision.incrementAndGet()
            zoomRequestRevision.incrementAndGet()
            cameraExecutor.execute(::rebuildCameraSystem)
            return
        }

        if (cameraRequestChanged && cameraSystemPrepared) {
            scheduleRepeatingRequestUpdate()
        }

        if (previewVisibilityChanged && cameraSystemPrepared) {
            cameraExecutor.execute { updateActivePreviewVisibility() }
        }
    }

    fun updateZoomRatio(requestedZoomRatio: Float) {
        if (closed.get() || cameraRecoveryPending || recoveryAttemptCount != 0 ||
            switchRequestPending.get() || recordingTransition.get() ||
            !requestedZoomRatio.isFinite()
        ) return

        val pipeline = activePipeline() ?: return
        if (pipeline.device == null || pipeline.session == null) return
        val range = availableZoomRange(pipeline)

        val normalizedZoom = requestedZoomRatio.coerceIn(range.lower, range.upper)
        if (normalizedZoom == config.zoomRatio) return

        config = config.copy(zoomRatio = normalizedZoom)
        pipeline.zoomRatio = normalizedZoom
        if (pipeline.zoomInRenderer) {
            pipeline.renderer.setDigitalZoomRatio(normalizedZoom)
        }
        zoomRequestRevision.incrementAndGet()
        publishZoomUiState()

        if (cameraSystemPrepared && !pipeline.zoomInRenderer) {
            scheduleZoomRequestUpdate()
        }
    }

    private fun scheduleZoomRequestUpdate() {
        if (!zoomUpdateScheduled.compareAndSet(false, true)) return

        try {
            val pipeline = activePipeline() ?: run {
                zoomUpdateScheduled.set(false)
                return
            }
            val maxRequestRate = config.frameRate
            val frameIntervalNs = 1_000_000_000L /
                maxRequestRate.coerceAtLeast(1)
            val lastRequestNs = pipeline.lastZoomRequestNs
            val delayNs = (frameIntervalNs - (System.nanoTime() - lastRequestNs))
                .coerceAtLeast(0L)
            requestScheduler.schedule({
                try {
                    cameraExecutor.execute {
                        val revision = zoomRequestRevision.get()
                        try {
                            if (closed.get() || cameraRecoveryPending || recoveryAttemptCount != 0 ||
                                switchRequestPending.get() || !cameraSystemPrepared
                            ) return@execute

                            val pipeline = activePipeline() ?: return@execute
                            if (pipeline.device != null && pipeline.session != null) {
                                submitZoomRequest(pipeline)
                            }
                        } finally {
                            zoomUpdateScheduled.set(false)
                            // Keep the newest gesture position without queuing every touch event.
                            if (zoomRequestRevision.get() != revision &&
                                !closed.get() && !switchRequestPending.get()
                            ) scheduleZoomRequestUpdate()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    zoomUpdateScheduled.set(false)
                }
            }, delayNs, TimeUnit.NANOSECONDS)
        } catch (_: RejectedExecutionException) {
            zoomUpdateScheduled.set(false)
        }
    }

    private fun publishZoomUiState() {
        if (!zoomUiUpdatePending.compareAndSet(false, true)) return
        updateState { state ->
            zoomUiUpdatePending.set(false)
            val latestConfig = config
            if (state.config == latestConfig) state else state.copy(config = latestConfig)
        }
    }

    private fun maxUnobservedZoomRequests(pipeline: CameraPipeline): Long =
        if (pipeline.profile.role == CameraRole.FRONT) {
            MAX_PENDING_FRONT_ZOOM_REQUESTS
        } else {
            MAX_PENDING_REAR_ZOOM_REQUESTS
        }

    private fun scheduleRepeatingRequestUpdate() {
        val revision = requestRevision.incrementAndGet()

        requestScheduler.schedule(
            {
                if (closed.get() || cameraRecoveryPending || recoveryAttemptCount != 0 ||
                    switchRequestPending.get() ||
                    revision != requestRevision.get() || !cameraSystemPrepared
                ) {
                    return@schedule
                }

                if (!cameraExecutor.isShutdown) {
                    try {
                        cameraExecutor.execute {
                            if (closed.get() || cameraRecoveryPending || recoveryAttemptCount != 0 ||
                                switchRequestPending.get() ||
                                revision != requestRevision.get()
                            ) return@execute

                            pipelines.values.forEach { pipeline ->
                                if (pipeline.device != null && pipeline.session != null) {
                                    submitRepeatingRequest(
                                        pipeline = pipeline,
                                        resetWarmup = false,
                                        publishUiStatus = false,
                                    )
                                }
                            }
                            updateState { state ->
                                if (state.config == config) state else state.copy(config = config)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        // Shutdown may happen after the scheduler's isShutdown check.
                    }
                }
            },
            REQUEST_UPDATE_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun maybePrepareAndOpenCameras() {
        if (closed.get() || cameraRecoveryPending || !permissionGranted || !lifecycleActive || previewSurface == null) return
        if (
            appContext.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        updateState { state ->
            state.copy(
                isBusy = !cameraSystemPrepared,
                status = appContext.getString(R.string.status_preparing_cameras)
            )
        }

        cameraExecutor.execute {
            runCatching {
                if (!cameraSystemPrepared) prepareCameraSystem()
                openRequiredPipelines()
            }.onFailure { error ->
                Log.e(TAG, "Unable to prepare camera", error)
                updateError(appContext.getString(R.string.error_unable_prepare_camera))
            }
        }
    }

    private fun prepareCameraSystem() {
        if (cameraSystemPrepared) return

        val profiles = discoverProfiles()
        check(profiles.isNotEmpty()) { "No usable cameras found" }

        val preferredProfile = profiles[activeRole]
            ?: profiles[CameraRole.FRONT]
            ?: profiles.values.first()
        activeRole = preferredProfile.role

        profiles.values.forEach { profile ->
            val renderer = CameraGlRenderer(timestampGate)
            val inputSurface = renderer.initialize(
                sourceSize = profile.sourceSize,
                sensorOrientationDegrees = profile.sensorOrientationDegrees,
                displayRotationDegrees = rotationToDegrees(displayRotation),
                mirrorHorizontally = profile.mirrorHorizontally,
            )
            pipelines[profile.role] = CameraPipeline(
                profile = profile,
                renderer = renderer,
                inputSurface = inputSurface,
            ).also { pipeline ->
                if (profile.role == activeRole) pipeline.zoomRatio = config.zoomRatio
                if (pipeline.zoomInRenderer) {
                    pipeline.renderer.setDigitalZoomRatio(pipeline.zoomRatio)
                }
            }
        }

        val front = pipelines[CameraRole.FRONT]
        val rear = pipelines[CameraRole.REAR_WIDE]
        if (config.enableConcurrentPrewarm && front != null && rear != null) {
            listOf(front, rear).forEach { pipeline ->
                pipeline.prewarmReader = runCatching {
                    ImageReader.newInstance(
                        pipeline.profile.sourceSize.width,
                        pipeline.profile.sourceSize.height,
                        ImageFormat.PRIVATE,
                        3,
                    ).also { reader ->
                        reader.setOnImageAvailableListener({ available ->
                            runCatching { available.acquireLatestImage()?.close() }
                                .onSuccess { pipeline.prewarmReaderFrames.incrementAndGet() }
                        }, prewarmDrainHandler)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Unable to prepare low-overhead camera prewarm", error)
                }.getOrNull()
            }
        }
        concurrentCameraPrewarm = if (
            config.enableConcurrentPrewarm &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            front != null &&
            rear != null
        ) {
            val pairAdvertised = cameraManager.concurrentCameraIds.any { combination ->
                combination.contains(front.profile.cameraId) &&
                        combination.contains(rear.profile.cameraId)
            }
            if (pairAdvertised) {
                concurrentReaderPrewarm = front.prewarmReader != null && rear.prewarmReader != null &&
                    supportsConcurrentSessions(front, rear, allowVideoRecordUseCase = true,
                        useReaderPrewarm = true)
                concurrentVideoRecordUseCase = concurrentReaderPrewarm || supportsConcurrentSessions(
                    front, rear, allowVideoRecordUseCase = true,
                )
                if (!concurrentReaderPrewarm && front.prewarmReader != null && rear.prewarmReader != null &&
                    supportsConcurrentSessions(front, rear, allowVideoRecordUseCase = false,
                        useReaderPrewarm = true)) {
                    concurrentReaderPrewarm = true
                    concurrentVideoRecordUseCase = false
                }
                concurrentVideoRecordUseCase || concurrentReaderPrewarm || supportsConcurrentSessions(
                    front, rear, allowVideoRecordUseCase = false,
                )
            } else {
                false
            }
        } else {
            false
        }
        if (!concurrentReaderPrewarm) {
            pipelines.values.forEach(::closePrewarmReader)
        }

        concurrentPrewarmFps = minOf(config.frameRate, config.concurrentPrewarmFps.coerceAtLeast(1))
        Log.i(TAG, "Concurrent prewarm=$concurrentCameraPrewarm, fps=$concurrentPrewarmFps, reader=$concurrentReaderPrewarm")

        cameraSystemPrepared = true
        attachPreviewToActiveRenderer()

        updateStateForActivePipeline(
            status = if (concurrentCameraPrewarm) {
                appContext.getString(R.string.status_opening_both_for_prewarm)
            } else {
                appContext.getString(
                    R.string.status_opening_camera,
                    activePipeline()?.profile?.let(::localizedCameraLabel)
                        ?: appContext.getString(R.string.camera_generic),
                )
            },
            isBusy = true,
        )
    }

    private fun rebuildCameraSystem() {
        recoveryGeneration.incrementAndGet()
        cameraRecoveryPending = false
        recoveryAttemptCount = 0
        zoomRequestRevision.incrementAndGet()
        switchRequestPending.set(false)
        pipelines.values.forEach { pipeline ->
            closePipelineCamera(pipeline)
            closePrewarmReader(pipeline)
            runCatching { pipeline.renderer.close() }
        }
        pipelines.clear()
        pendingActivationRole = null
        concurrentCameraPrewarm = false
        concurrentVideoRecordUseCase = true
        concurrentReaderPrewarm = false
        cameraSystemPrepared = false
        timestampGate.reset()

        prepareCameraSystem()
        openRequiredPipelines()
    }

    private fun supportsConcurrentSessions(
        front: CameraPipeline,
        rear: CameraPipeline,
        allowVideoRecordUseCase: Boolean,
        useReaderPrewarm: Boolean = false,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = Unit
            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        }
        val configurations = listOf(front, rear).associate { pipeline ->
            pipeline.profile.cameraId to SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                buildList {
                    add(createOutputConfiguration(pipeline, allowVideoRecordUseCase))
                    if (useReaderPrewarm) {
                        pipeline.prewarmReader?.surface?.let { add(OutputConfiguration(it)) }
                    }
                },
                cameraCallbackExecutor,
                callback,
            )
        }
        return runCatching {
            cameraManager.isConcurrentSessionConfigurationSupported(configurations)
        }.onFailure { error ->
            Log.w(TAG, "Unable to validate concurrent camera sessions", error)
        }.getOrDefault(false)
    }

    private fun supportsFixedFps(profile: CameraProfile, fps: Int): Boolean {
        val characteristics = profile.characteristics
        val ranges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: return false
        if (ranges.none { it.lower == fps && it.upper == fps }) return false

        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return false
        val minFrameDurationNs = runCatching {
            streamMap.getOutputMinFrameDuration(SurfaceTexture::class.java, profile.sourceSize)
        }.getOrNull() ?: return false
        return minFrameDurationNs <= 0L || minFrameDurationNs <= 1_000_000_000L / fps
    }

    private fun selectPipelineFpsRange(
        ranges: Array<Range<Int>>?,
        inactiveConcurrentPrewarm: Boolean,
    ): FpsRangeSelection {
        // When both concurrent cameras are prewarmed at the recording rate, request the
        // fixed range on both. A variable range could silently run the inactive one slower.
        return if (inactiveConcurrentPrewarm ||
            (concurrentCameraPrewarm && concurrentPrewarmFps == config.frameRate &&
                config.frameRate > CONCURRENT_PREWARM_MAX_FPS)
        ) {
            chooseConcurrentPrewarmFpsRange(
                ranges,
                if (inactiveConcurrentPrewarm) concurrentPrewarmFps else config.frameRate,
            )
        } else {
            chooseVideoFpsRange(ranges, config.frameRate, config.holdFpsInLowLight)
        }
    }


    private fun discoverProfiles(): Map<CameraRole, CameraProfile> {
        val ids = cameraManager.cameraIdList.toList()

        val characteristicsById = ids.associateWith(
            cameraManager::getCameraCharacteristics
        )

        val frontId = ids.firstOrNull { id ->
            characteristicsById
                .getValue(id)
                .get(CameraCharacteristics.LENS_FACING) ==
                    CameraMetadata.LENS_FACING_FRONT
        } ?: ids.firstOrNull()

        val rearIds = ids.filter { id ->
            characteristicsById
                .getValue(id)
                .get(CameraCharacteristics.LENS_FACING) ==
                    CameraMetadata.LENS_FACING_BACK
        }

        val rearId = chooseMainRearCameraId(
            cameraIds = rearIds,
            characteristicsById = characteristicsById,
        )

        return buildMap {
            frontId?.let { id ->
                put(
                    CameraRole.FRONT,
                    createProfile(
                        role = CameraRole.FRONT,
                        cameraId = id,
                        characteristics = characteristicsById.getValue(id),
                        zoomRatio = 1f,
                    ),
                )
            }

            if (rearId != null && rearId != frontId) {
                val characteristics =
                    characteristicsById.getValue(rearId)

                val zoomRange = characteristics.zoomRatioRangeCompat()
                val defaultRearZoom = 1f.coerceIn(
                    zoomRange.lower,
                    zoomRange.upper,
                )

                put(
                    CameraRole.REAR_WIDE,
                    createProfile(
                        role = CameraRole.REAR_WIDE,
                        cameraId = rearId,
                        characteristics = characteristics,
                        zoomRatio = defaultRearZoom,
                    ),
                )
            }
        }
    }

    private fun createProfile(
        role: CameraRole,
        cameraId: String,
        characteristics: CameraCharacteristics,
        zoomRatio: Float,
    ): CameraProfile {
        val sourceSize = chooseCameraSourceSize(
            characteristics = characteristics,
            targetOutput = config.outputSize,
            targetFps = config.frameRate,
        )

        val facing = characteristics.get(
            CameraCharacteristics.LENS_FACING
        )

        /*
         * API 33+:
         *
         * createCameraSession() explicitly uses MIRROR_MODE_NONE.
         * Therefore the GL renderer owns front-camera mirroring.
         *
         * API 28-32:
         *
         * We cannot explicitly disable camera-output mirroring.
         * SurfaceTexture#getTransformMatrix() may already contain the
         * producer-side mirror transform, depending on the camera stack.
         *
         * Applying our own horizontal mirror as well can therefore lead
         * to different behaviour between old and new devices.
         *
         * On old Android versions we trust the SurfaceTexture transform.
         */
        val mirrorInRenderer =
            facing == CameraMetadata.LENS_FACING_FRONT &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        val sensorOrientation = characteristics.get(
            CameraCharacteristics.SENSOR_ORIENTATION
        ) ?: 0

        val label = when (role) {
            CameraRole.FRONT -> {
                "Front"
            }

            CameraRole.REAR_WIDE -> {
                if (zoomRatio < 0.99f) {
                    "Rear ${
                        String.format(
                            Locale.US,
                            "%.1f",
                            zoomRatio,
                        )
                    }×"
                } else {
                    "Rear 1×"
                }
            }
        }

        return CameraProfile(
            role = role,
            cameraId = cameraId,
            characteristics = characteristics,
            sourceSize = sourceSize,
            sensorOrientationDegrees = sensorOrientation,
            mirrorHorizontally = mirrorInRenderer,
            zoomRatio = zoomRatio,
            label = label,
        )
    }

    private fun openRequiredPipelines() {
        val active = activePipeline() ?: return

        if (concurrentCameraPrewarm && pipelines.size > 1) {
            // CameraManager's concurrent contract requires every camera device to be opened before
            // configuring a session on any of them. Do not configure the first camera eagerly.
            pipelines.values.forEach { pipeline ->
                openPipeline(pipeline, deferSessionConfiguration = true)
            }
            maybeConfigureConcurrentSessions()
        } else {
            openPipeline(active, deferSessionConfiguration = false)
        }
    }

    private fun openPipeline(
        pipeline: CameraPipeline,
        deferSessionConfiguration: Boolean = false,
    ) {
        if (
            pipeline.device != null ||
            pipeline.opening ||
            closed.get() ||
            cameraRecoveryPending ||
            !lifecycleActive
        ) {
            return
        }

        if (
            appContext.checkSelfPermission(
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (closingCameraDevices.isNotEmpty()) {
            deferredCameraOpens[pipeline.profile.role] = deferSessionConfiguration
            scheduleCloseWaitTimeout()
            return
        }

        pipeline.opening = true
        pipeline.warmed = false
        pipeline.stable3AFrames = 0
        pipeline.switchPromotionStartedNs = 0L
        pipeline.switchPromotionStableFrames = 0
        pipeline.switchPromotionFpsReadyNs = 0L
        pipeline.switchPromotionAeWaitMs = 0L
        val openGeneration = ++pipeline.openGeneration

        try {
            cameraManager.openCamera(
            pipeline.profile.cameraId,
            cameraCallbackExecutor,
            object : CameraDevice.StateCallback() {

                override fun onOpened(
                    camera: CameraDevice,
                ) {
                    if (openGeneration != pipeline.openGeneration) {
                        closeCameraDevice(camera)
                        return
                    }
                    if (closed.get() || !lifecycleActive) {
                        closePipelineCamera(pipeline)
                        closeCameraDevice(camera)
                        return
                    }

                    pipeline.device = camera

                    try {
                        if (deferSessionConfiguration && concurrentCameraPrewarm) {
                            maybeConfigureConcurrentSessions()
                        } else {
                            createCameraSession(
                                pipeline = pipeline,
                                allowVideoRecordUseCase = true,
                            )
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Unable to configure ${pipeline.profile.label} camera", error)
                        handlePipelineFailure(
                            pipeline,
                            "Unable to configure camera: ${error.message}",
                            cameraError = error is CameraAccessException,
                        )
                    }
                }

                override fun onDisconnected(
                    camera: CameraDevice,
                ) {
                    closeCameraDevice(camera)
                    if (openGeneration != pipeline.openGeneration) return

                    pipeline.device = null
                    pipeline.session = null
                    pipeline.opening = false

                    if (!closed.get() && lifecycleActive) {
                        handlePipelineFailure(
                            pipeline,
                            appContext.getString(
                                R.string.error_camera_disconnected
                            ),
                            cameraError = true,
                        )
                    }
                }

                override fun onError(
                    camera: CameraDevice,
                    error: Int,
                ) {
                    closeCameraDevice(camera)
                    if (openGeneration != pipeline.openGeneration) return

                    pipeline.device = null
                    pipeline.session = null
                    pipeline.opening = false

                    if (!closed.get() && lifecycleActive) {
                        handlePipelineFailure(
                            pipeline,
                            appContext.getString(
                                R.string.error_camera_code,
                                error,
                            ),
                            cameraError = true,
                        )
                    }
                }

                override fun onClosed(camera: CameraDevice) {
                    closingCameraDevices.remove(camera)
                    resumeDeferredCameraOpens()
                }
            },
            )
        } catch (error: Exception) {
            pipeline.opening = false
            Log.e(TAG, "Unable to open ${pipeline.profile.label} camera", error)
            handlePipelineFailure(
                pipeline,
                "Unable to open camera: ${error.message}",
                cameraError = true,
            )
        }
    }

    private fun maybeConfigureConcurrentSessions() {
        if (!concurrentCameraPrewarm || pipelines.size < 2) return
        if (pipelines.values.any { it.device == null }) return
        if (pipelines.values.any { it.session != null }) return

        pipelines.values.forEach { pipeline ->
            createCameraSession(pipeline, allowVideoRecordUseCase = concurrentVideoRecordUseCase)
        }
    }

    private fun createCameraSession(
        pipeline: CameraPipeline,
        allowVideoRecordUseCase: Boolean,
    ) {
        val camera = pipeline.device ?: return
        val characteristics = pipeline.profile.characteristics
        val output = createOutputConfiguration(pipeline, allowVideoRecordUseCase)
        val outputs = buildList {
            add(output)
            if (concurrentReaderPrewarm) {
                pipeline.prewarmReader?.surface?.let { add(OutputConfiguration(it)) }
            }
        }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraCallbackExecutor,
            object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(
                    session: CameraCaptureSession,
                ) {
                    if (closed.get() || !lifecycleActive || pipeline.device !== camera) {
                        runCatching {
                            session.close()
                        }

                        return
                    }

                    pipeline.session = session
                    pipeline.opening = false
                    pipeline.sessionRetryWithoutUseCase = false

                    submitRepeatingRequest(
                        pipeline = pipeline,
                        resetWarmup = true,
                    )
                }

                override fun onConfigureFailed(
                    session: CameraCaptureSession,
                ) {
                    runCatching {
                        session.close()
                    }

                    if (pipeline.device !== camera) return

                    pipeline.session = null

                    if (closed.get() || !lifecycleActive) {
                        pipeline.opening = false
                        return
                    }

                    if (allowVideoRecordUseCase) {
                        Log.w(
                            TAG,
                            "${pipeline.profile.label}: VIDEO_RECORD session failed, retrying generic stream",
                        )

                        pipeline.sessionRetryWithoutUseCase = true

                        try {
                            createCameraSession(
                                pipeline = pipeline,
                                allowVideoRecordUseCase = false,
                            )
                        } catch (error: Exception) {
                            Log.e(TAG, "Unable to retry ${pipeline.profile.label} session", error)
                            handlePipelineFailure(
                                pipeline,
                                "Unable to configure camera: ${error.message}",
                            )
                        }
                    } else {
                        pipeline.opening = false

                        handlePipelineFailure(
                            pipeline,
                            appContext.getString(
                                R.string.error_session_configuration_failed
                            ),
                        )
                    }
                }
            },
        )

        // Apply supported session-wide values before the first repeating request. Changing a
        // session key after configuration may cause a camera pipeline reconfiguration delay.
        //
        // Match the session parameters to the cadence of the first repeating request.
        runCatching {
            val ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            val fpsSelection = chooseVideoFpsRange(ranges, config.frameRate, config.holdFpsInLowLight)
            val sessionRequest = camera
                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                .apply {
                    addTarget(pipeline.inputSurface)
                    applyInitialSessionParameters(this, pipeline, fpsSelection.range)
                }
                .build()
            sessionConfig.setSessionParameters(sessionRequest)
        }.onFailure { error ->
            Log.w(TAG, "${pipeline.profile.label}: unable to set session FPS parameters", error)
        }

        camera.createCaptureSession(sessionConfig)
    }

    private fun applyInitialSessionParameters(
        builder: CaptureRequest.Builder,
        pipeline: CameraPipeline,
        fpsRange: Range<Int>?,
    ) {
        val characteristics = pipeline.profile.characteristics
        val sessionKeys = characteristics.availableSessionKeys?.toSet().orEmpty()
        val appliedKeys = mutableListOf<String>()
        fun <T> setIfSessionKey(key: CaptureRequest.Key<T>, value: T?) {
            if (value != null && key in sessionKeys) {
                builder.set(key, value)
                appliedKeys += key.name
            }
        }

        val manualSensor = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val manualExposure = config.exposureMode == ExposureMode.MANUAL && manualSensor
        val availableAeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            ?: intArrayOf()
        val lowLightBoost = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            !manualExposure && config.lowLightBoost &&
            CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY in availableAeModes
        val aeMode = when {
            manualExposure -> CameraMetadata.CONTROL_AE_MODE_OFF
            lowLightBoost -> CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY
            else -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        setIfSessionKey(CaptureRequest.CONTROL_AE_MODE, aeMode)
        if (manualExposure) {
            val frameIntervalNs = 1_000_000_000L /
                (fpsRange?.upper ?: config.frameRate).coerceAtLeast(1)
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val requestedNs = config.manualExposureTimeUs * 1_000L
            val exposureNs = (exposureRange?.let {
                requestedNs.coerceIn(it.lower, it.upper)
            } ?: requestedNs).let {
                if (config.holdFpsInLowLight) it.coerceAtMost(frameIntervalNs) else it
            }
            setIfSessionKey(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            setIfSessionKey(
                CaptureRequest.SENSOR_SENSITIVITY,
                characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    ?.let { config.manualIso.coerceIn(it.lower, it.upper) } ?: config.manualIso,
            )
            setIfSessionKey(CaptureRequest.SENSOR_FRAME_DURATION, max(frameIntervalNs, exposureNs))
        } else {
            setIfSessionKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                config.aePriority != AePrioritySetting.OFF
            ) {
                val priorityMode = resolveAePriorityMode(characteristics, lowLightBoost)
                setIfSessionKey(CaptureRequest.CONTROL_AE_PRIORITY_MODE, priorityMode)
                if (priorityMode != null) {
                    when (config.aePriority) {
                        AePrioritySetting.ISO -> {
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                                ?.let {
                                    setIfSessionKey(
                                        CaptureRequest.SENSOR_SENSITIVITY,
                                        config.manualIso.coerceIn(it.lower, it.upper),
                                    )
                                }
                        }
                        AePrioritySetting.SHUTTER -> {
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                                ?.let {
                                    val maxNs = if (config.holdFpsInLowLight) {
                                        minOf(it.upper, 1_000_000_000L /
                                            (fpsRange?.upper ?: config.frameRate).coerceAtLeast(1))
                                    } else {
                                        it.upper
                                    }
                                    setIfSessionKey(
                                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                                        (config.manualExposureTimeUs * 1_000L)
                                            .coerceIn(it.lower, maxNs),
                                    )
                                }
                        }
                        AePrioritySetting.OFF -> Unit
                    }
                }
            }
            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
                setIfSessionKey(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    config.exposureCompensationSteps.coerceIn(it.lower, it.upper),
                )
            }
            if (characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
                setIfSessionKey(CaptureRequest.CONTROL_AE_LOCK, config.aeLock)
            }
        }

        setIfSessionKey(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            config.antibanding.camera2Mode.takeIf {
                it in (characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES
                ) ?: intArrayOf())
            },
        )
        val colorMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            resolveSupportedMode(
                characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES),
                config.colorCorrection.camera2Mode,
            )
        } else {
            config.colorCorrection.camera2Mode
                ?.takeIf { config.colorCorrection != ColorCorrectionSetting.CCT }
        }
        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf()
        val cctActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            colorMode == CameraMetadata.COLOR_CORRECTION_MODE_CCT &&
            CameraMetadata.CONTROL_AWB_MODE_OFF in awbModes
        val awbMode = if (cctActive) CameraMetadata.CONTROL_AWB_MODE_OFF else
            config.awb.camera2Mode.takeIf { it in awbModes } ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
        setIfSessionKey(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            setIfSessionKey(CaptureRequest.CONTROL_AWB_LOCK, config.awbLock && !cctActive)
        }
        setIfSessionKey(CaptureRequest.COLOR_CORRECTION_MODE, colorMode)
        if (cctActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            characteristics.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
                ?.let {
                    setIfSessionKey(
                        CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE,
                        config.colorTemperatureKelvin.coerceIn(it.lower, it.upper),
                    )
                }
            setIfSessionKey(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, config.colorTint.coerceIn(-50, 50))
        }

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        val minFocusDistance = characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f
        when (config.focus) {
            FocusSetting.CONTINUOUS_VIDEO -> setIfSessionKey(
                CaptureRequest.CONTROL_AF_MODE,
                when {
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes ->
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    CameraMetadata.CONTROL_AF_MODE_AUTO in afModes ->
                        CameraMetadata.CONTROL_AF_MODE_AUTO
                    else -> CameraMetadata.CONTROL_AF_MODE_OFF
                },
            )
            FocusSetting.AUTO -> setIfSessionKey(
                CaptureRequest.CONTROL_AF_MODE,
                if (CameraMetadata.CONTROL_AF_MODE_AUTO in afModes) {
                    CameraMetadata.CONTROL_AF_MODE_AUTO
                } else {
                    CameraMetadata.CONTROL_AF_MODE_OFF
                },
            )
            FocusSetting.MANUAL -> if (minFocusDistance > 0f &&
                CameraMetadata.CONTROL_AF_MODE_OFF in afModes
            ) {
                setIfSessionKey(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                setIfSessionKey(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    config.manualFocusDistance.coerceIn(0f, minFocusDistance),
                )
            }
        }

        val stabilization = resolveVideoStabilizationMode(characteristics, config.stabilization)
        pipeline.stabilizationSessionParameter = stabilization.takeIf {
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in sessionKeys
        }
        setIfSessionKey(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilization)
        setIfSessionKey(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            resolveOpticalStabilizationMode(characteristics, config.opticalStabilization),
        )
        setIfSessionKey(
            CaptureRequest.NOISE_REDUCTION_MODE,
            resolveNoiseReductionMode(characteristics, config.noiseReduction),
        )
        setIfSessionKey(
            CaptureRequest.HOT_PIXEL_MODE,
            resolveSupportedMode(
                characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                config.hotPixelCorrection.camera2Mode,
            ),
        )
        setIfSessionKey(
            CaptureRequest.SHADING_MODE,
            resolveSupportedMode(
                characteristics.get(CameraCharacteristics.SHADING_AVAILABLE_MODES),
                config.lensShading.camera2Mode,
            ),
        )
        setIfSessionKey(
            CaptureRequest.EDGE_MODE,
            resolveSupportedMode(
                characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                config.edgeEnhancement.camera2Mode,
            ),
        )
        setIfSessionKey(
            CaptureRequest.DISTORTION_CORRECTION_MODE,
            resolveSupportedMode(
                characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
                config.distortionCorrection.camera2Mode,
            ),
        )
        setIfSessionKey(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            resolveSupportedMode(
                characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                config.aberrationCorrection.camera2Mode,
            ),
        )
        setIfSessionKey(
            CaptureRequest.TONEMAP_MODE,
            resolveSupportedMode(
                characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
                config.tonemap.camera2Mode,
            ),
        )
        setIfSessionKey(
            CaptureRequest.LENS_APERTURE,
            config.aperture?.let { requested ->
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.minByOrNull { abs(it - requested) }
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setIfSessionKey(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                if (pipeline.zoomInRenderer) 1f else pipeline.zoomRatio,
            )
        } else if (CaptureRequest.SCALER_CROP_REGION in sessionKeys) {
            builder.setZoomRatioCompat(
                characteristics,
                if (pipeline.zoomInRenderer) 1f else pipeline.zoomRatio,
            )
            appliedKeys += CaptureRequest.SCALER_CROP_REGION.name
        }
        pipeline.initialSessionParameterKeys = appliedKeys
    }

    private fun createOutputConfiguration(
        pipeline: CameraPipeline,
        allowVideoRecordUseCase: Boolean,
    ): OutputConfiguration {
        val characteristics = pipeline.profile.characteristics
        return OutputConfiguration(pipeline.inputSurface).apply {
            pipeline.videoRecordUseCaseEnabled = false
            pipeline.readoutTimestampEnabled = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
                setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE)
                setDynamicRangeProfile(DynamicRangeProfiles.STANDARD)

                if (allowVideoRecordUseCase) {
                    val useCases = characteristics.get(
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES
                    )
                    val videoRecordUseCase =
                        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()

                    if (useCases?.contains(videoRecordUseCase) == true) {
                        setStreamUseCase(videoRecordUseCase)
                        pipeline.videoRecordUseCaseEnabled = true
                    }
                }
            }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                characteristics.get(CameraCharacteristics.SENSOR_READOUT_TIMESTAMP) ==
                CameraMetadata.SENSOR_READOUT_TIMESTAMP_HARDWARE
            ) {
                setReadoutTimestampEnabled(true)
                pipeline.readoutTimestampEnabled = true
            }
        }
    }

    private fun submitRepeatingRequest(
        pipeline: CameraPipeline,
        resetWarmup: Boolean,
        publishUiStatus: Boolean = true,
    ) {
        val camera = pipeline.device ?: return
        val session = pipeline.session ?: return
        val characteristics = pipeline.profile.characteristics

        runCatching {
            val manualSensorAvailable = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            val useManualExposure =
                config.exposureMode == ExposureMode.MANUAL && manualSensorAvailable
            val availableAeModes = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES
            ) ?: intArrayOf()
            val useLowLightBoost = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                    !useManualExposure &&
                    config.lowLightBoost &&
                    CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY in availableAeModes
            val aePriorityMode = resolveAePriorityMode(characteristics, useLowLightBoost)

            val isInactiveConcurrentPrewarm =
                concurrentCameraPrewarm &&
                        (pipeline.profile.role != activeRole &&
                            pipeline.profile.role != pendingActivationRole)
            val requestedPipelineFps = if (isInactiveConcurrentPrewarm) {
                concurrentPrewarmFps
            } else {
                config.frameRate
            }
            val availableFpsRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            val fpsSelection = selectPipelineFpsRange(
                availableFpsRanges, isInactiveConcurrentPrewarm,
            )
            val stabilizationMode = resolveVideoStabilizationMode(
                characteristics = characteristics,
                requested = config.stabilization,
            )
            val opticalStabilizationMode = resolveOpticalStabilizationMode(
                characteristics = characteristics,
                requested = config.opticalStabilization,
            )
            val noiseReductionMode = resolveNoiseReductionMode(
                characteristics = characteristics,
                requested = config.noiseReduction,
            )
            val hotPixelMode = resolveSupportedMode(
                characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                config.hotPixelCorrection.camera2Mode,
            )
            val shadingMode = resolveSupportedMode(
                characteristics.get(CameraCharacteristics.SHADING_AVAILABLE_MODES),
                config.lensShading.camera2Mode,
            )
            val colorCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                resolveSupportedMode(
                    characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES),
                    config.colorCorrection.camera2Mode,
                )
            } else {
                config.colorCorrection.camera2Mode
                    ?.takeIf { config.colorCorrection != ColorCorrectionSetting.CCT }
            }

            val frameIntervalNs = 1_000_000_000L / requestedPipelineFps.coerceAtLeast(1)
            val manualExposureNs = if (useManualExposure) {
                val range =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val raw = config.manualExposureTimeUs * 1_000L
                val bounded = range?.let { raw.coerceIn(it.lower, it.upper) } ?: raw
                if (config.holdFpsInLowLight) bounded.coerceAtMost(frameIntervalNs) else bounded
            } else {
                null
            }

            pipeline.selectedFpsRange = fpsSelection.range
            pipeline.fpsHoldActive = if (useManualExposure) {
                manualExposureNs != null && manualExposureNs <= frameIntervalNs
            } else {
                fpsSelection.holdsRequestedFps
            }
            pipeline.stabilizationMode = stabilizationMode
            pipeline.appliedNoiseReductionMode = noiseReductionMode

            if (resetWarmup || pipeline.warmupStartedNs == 0L) {
                resetWarmup(pipeline)
            }

            val requestZoomRatio = if (pipeline.zoomInRenderer) 1f else pipeline.zoomRatio
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                val prewarmSurface = if (isInactiveConcurrentPrewarm && concurrentReaderPrewarm) {
                    pipeline.prewarmReader?.surface
                } else {
                    null
                }
                addTarget(prewarmSurface ?: pipeline.inputSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(
                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD,
                )

                if (useManualExposure) {
                    val sensitivityRange = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
                    )
                    val iso = sensitivityRange?.let {
                        config.manualIso.coerceIn(it.lower, it.upper)
                    } ?: config.manualIso
                    val exposureNs = checkNotNull(manualExposureNs)

                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(
                        CaptureRequest.SENSOR_FRAME_DURATION,
                        max(frameIntervalNs, exposureNs),
                    )
                } else {
                    set(
                        CaptureRequest.CONTROL_AE_MODE,
                        if (useLowLightBoost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY
                        } else {
                            CameraMetadata.CONTROL_AE_MODE_ON
                        },
                    )
                    fpsSelection.range?.let {
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                    }

                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                        !useLowLightBoost &&
                        aePriorityMode != null &&
                        config.aePriority != AePrioritySetting.OFF
                    ) {
                        set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, aePriorityMode)
                        when (config.aePriority) {
                            AePrioritySetting.ISO -> {
                                characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                                    ?.let { range ->
                                        set(
                                            CaptureRequest.SENSOR_SENSITIVITY,
                                            config.manualIso.coerceIn(range.lower, range.upper),
                                        )
                                    }
                            }

                            AePrioritySetting.SHUTTER -> {
                                characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                                    ?.let { range ->
                                        val requestedNs = config.manualExposureTimeUs * 1_000L
                                        val maxNs = if (config.holdFpsInLowLight) {
                                            minOf(range.upper, frameIntervalNs)
                                        } else {
                                            range.upper
                                        }
                                        set(
                                            CaptureRequest.SENSOR_EXPOSURE_TIME,
                                            requestedNs.coerceIn(range.lower, maxNs),
                                        )
                                    }
                            }

                            AePrioritySetting.OFF -> Unit
                        }
                    }

                    characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                        ?.let { range ->
                            set(
                                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                config.exposureCompensationSteps.coerceIn(range.lower, range.upper),
                            )
                        }
                    if (characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
                        set(CaptureRequest.CONTROL_AE_LOCK, config.aeLock)
                    }
                }

                val supportedAntibandingModes = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES
                ) ?: intArrayOf()
                config.antibanding.camera2Mode
                    .takeIf { it in supportedAntibandingModes }
                    ?.let { set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it) }

                val supportedAwbModes = characteristics.get(
                    CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
                ) ?: intArrayOf()
                val cctActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                        colorCorrectionMode == CameraMetadata.COLOR_CORRECTION_MODE_CCT &&
                        CameraMetadata.CONTROL_AWB_MODE_OFF in supportedAwbModes
                val awbMode = if (cctActive) {
                    CameraMetadata.CONTROL_AWB_MODE_OFF
                } else {
                    config.awb.camera2Mode.takeIf { it in supportedAwbModes }
                        ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
                }
                set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
                if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                    set(CaptureRequest.CONTROL_AWB_LOCK, config.awbLock && !cctActive)
                }

                colorCorrectionMode?.let {
                    set(CaptureRequest.COLOR_CORRECTION_MODE, it)
                }
                if (cctActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    characteristics.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
                        ?.let { range ->
                            set(
                                CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE,
                                config.colorTemperatureKelvin.coerceIn(range.lower, range.upper),
                            )
                        }
                    set(
                        CaptureRequest.COLOR_CORRECTION_COLOR_TINT,
                        config.colorTint.coerceIn(-50, 50)
                    )
                }

                applyFocusSettings(this, characteristics)

                stabilizationMode?.let {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it)
                }
                opticalStabilizationMode?.let {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it)
                }
                noiseReductionMode?.let {
                    set(CaptureRequest.NOISE_REDUCTION_MODE, it)
                }
                hotPixelMode?.let {
                    set(CaptureRequest.HOT_PIXEL_MODE, it)
                }
                shadingMode?.let {
                    set(CaptureRequest.SHADING_MODE, it)
                }

                resolveSupportedMode(
                    characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                    config.edgeEnhancement.camera2Mode,
                )?.let { set(CaptureRequest.EDGE_MODE, it) }

                resolveSupportedMode(
                    characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
                    config.distortionCorrection.camera2Mode,
                )?.let { set(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }

                resolveSupportedMode(
                    characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                    config.aberrationCorrection.camera2Mode,
                )?.let { set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }

                resolveSupportedMode(
                    characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
                    config.tonemap.camera2Mode,
                )?.let { set(CaptureRequest.TONEMAP_MODE, it) }

                setZoomRatioCompat(
                    characteristics = characteristics,
                    requestedZoomRatio = requestZoomRatio,
                )
                config.aperture?.let { requestedAperture ->
                    characteristics
                        .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                        ?.minByOrNull { availableAperture ->
                            abs(availableAperture - requestedAperture)
                        }
                        ?.let { aperture ->
                            set(CaptureRequest.LENS_APERTURE, aperture)
                        }
                }
            }
            val requestSequence = pipeline.zoomSubmittedSequence + 1L
            requestBuilder.setTag(requestSequence)
            val request = requestBuilder.build()
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (closed.get() || pipeline.session !== session) return

                    val observedSequence = (request.tag as? Long) ?: 0L
                    pipeline.zoomObservedSequence = maxOf(
                        pipeline.zoomObservedSequence,
                        observedSequence,
                    )
                    if (!pipeline.zoomInRenderer && pipeline.profile.role == activeRole &&
                        pipeline.zoomRatio != pipeline.lastSubmittedZoomRatio &&
                        pipeline.zoomSubmittedSequence - pipeline.zoomObservedSequence <
                        maxUnobservedZoomRequests(pipeline)
                    ) {
                        scheduleZoomRequestUpdate()
                    }

                    onCaptureResult(pipeline, result)
                    if (recoveryAttemptCount != 0 &&
                        (!concurrentCameraPrewarm ||
                            pipelines.values.all { it.session != null && it.warmed })
                    ) {
                        recoveryAttemptCount = 0
                        recoveryGeneration.incrementAndGet()
                    }
                }
            }

            session.setSingleRepeatingRequest(
                request,
                cameraCallbackExecutor,
                captureCallback,
            )
            pipeline.repeatingRequestBuilder = requestBuilder
            pipeline.repeatingCaptureCallback = captureCallback
            pipeline.zoomSubmittedSequence = requestSequence
            pipeline.lastSubmittedZoomRatio = requestZoomRatio
            pipeline.lastZoomRequestNs = System.nanoTime()

            if (pipeline.profile.role == activeRole) {
                if (resetWarmup) {
                    updateStateForActivePipeline(
                        status = appContext.getString(
                            R.string.status_warming_camera,
                            localizedCameraLabel(
                                pipeline.profile
                            ),
                        ),

                        /*
                         * Repeating request has already been accepted.
                         * Camera can be used while 3A continues warming.
                         */
                        isBusy = false,
                    )
                } else if (publishUiStatus) {
                    refreshReadyStatus()
                }
            }
        }.onFailure { error ->
            handlePipelineFailure(
                pipeline,
                appContext.getString(
                    R.string.error_repeating_request_failed,
                    error.message ?: appContext.getString(R.string.unknown)
                ),
                cameraError = error is CameraAccessException,
            )
        }
    }

    private fun submitZoomRequest(pipeline: CameraPipeline) {
        if (pipeline.zoomInRenderer) return
        val session = pipeline.session ?: return
        val builder = pipeline.repeatingRequestBuilder ?: return
        val callback = pipeline.repeatingCaptureCallback ?: return
        val zoom = pipeline.zoomRatio
        if (zoom == pipeline.lastSubmittedZoomRatio) return
        if (pipeline.zoomSubmittedSequence - pipeline.zoomObservedSequence >=
            maxUnobservedZoomRequests(pipeline)
        ) return

        runCatching {
            val requestSequence = pipeline.zoomSubmittedSequence + 1L
            builder.setZoomRatioCompat(pipeline.profile.characteristics, zoom)
            builder.setTag(requestSequence)
            session.setSingleRepeatingRequest(builder.build(), cameraCallbackExecutor, callback)
            pipeline.zoomSubmittedSequence = requestSequence
            pipeline.maxPendingZoomRequests = maxOf(
                pipeline.maxPendingZoomRequests,
                pipeline.zoomSubmittedSequence - pipeline.zoomObservedSequence,
            )
            pipeline.lastSubmittedZoomRatio = zoom
            pipeline.lastZoomRequestNs = System.nanoTime()
        }.onFailure { error ->
            Log.e(
                TAG,
                "${pipeline.profile.label}: zoom request failed at zoom=$zoom, " +
                    "concurrentPrewarm=$concurrentCameraPrewarm",
                error,
            )
            handlePipelineFailure(
                pipeline,
                appContext.getString(
                    R.string.error_repeating_request_failed,
                    error.message ?: appContext.getString(R.string.unknown),
                ),
                cameraError = error is CameraAccessException,
            )
        }
    }

    private fun applyFocusSettings(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
    ) {
        val supportedModes = characteristics
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        val minFocusDistance = characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f

        when (config.focus) {
            FocusSetting.CONTINUOUS_VIDEO -> {
                val mode = when {
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in supportedModes ->
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO

                    CameraMetadata.CONTROL_AF_MODE_AUTO in supportedModes ->
                        CameraMetadata.CONTROL_AF_MODE_AUTO

                    else -> CameraMetadata.CONTROL_AF_MODE_OFF
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
            }

            FocusSetting.AUTO -> {
                val mode = if (CameraMetadata.CONTROL_AF_MODE_AUTO in supportedModes) {
                    CameraMetadata.CONTROL_AF_MODE_AUTO
                } else {
                    CameraMetadata.CONTROL_AF_MODE_OFF
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
            }

            FocusSetting.MANUAL -> {
                if (
                    minFocusDistance > 0f &&
                    CameraMetadata.CONTROL_AF_MODE_OFF in supportedModes
                ) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    builder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        config.manualFocusDistance.coerceIn(0f, minFocusDistance),
                    )
                }
            }
        }
    }

    private fun resolveSupportedMode(
        supportedModes: IntArray?,
        requestedMode: Int?,
    ): Int? {
        if (requestedMode == null) return null

        return requestedMode.takeIf { mode ->
            supportedModes?.contains(mode) == true
        }
    }

    private fun onCaptureResult(
        pipeline: CameraPipeline,
        result: TotalCaptureResult,
    ) {
        updateWarmup(pipeline, result)
        if (pendingActivationRole == pipeline.profile.role && pipeline.warmed &&
            concurrentReaderPrewarm && pipeline.switchGlInputBaseline < 0L &&
            pipeline.session != null
        ) {
            pipeline.switchGlInputBaseline = pipeline.renderer.performanceStats().inputFrames
            submitRepeatingRequest(pipeline, resetWarmup = false, publishUiStatus = false)
            pipeline.switchGlRequestSequence = pipeline.zoomSubmittedSequence
        }

        if (pendingActivationRole == pipeline.profile.role && pipeline.warmed &&
            pipeline.switchPromotionStartedNs == 0L && isOneXCapture(pipeline, result) &&
            isPrewarmedSwitchAeReady(pipeline, result) && hasSwitchGlFrame(pipeline, result)
        ) {
            activatePipeline(pipeline)
        }

        if (
            concurrentCameraPrewarm &&
            pendingActivationRole == pipeline.profile.role &&
            pipeline.warmed &&
            pipeline.switchPromotionStartedNs != 0L &&
            isConcurrentSwitchTargetReady(pipeline, result) &&
            isOneXCapture(pipeline, result) && hasSwitchGlFrame(pipeline, result)
        ) {
            activatePipeline(pipeline)
        }

        if (pipeline.profile.role != activeRole) return

        if (recording) {
            telemetry.onCapture(
                result = result,
                cameraLabel = pipeline.profile.label,
                noiseReductionMode = pipeline.appliedNoiseReductionMode,
                targetZoomRatio = pipeline.zoomRatio,
            )
        }
        publishLiveMetrics(result)
    }

    private fun hasSwitchGlFrame(
        pipeline: CameraPipeline,
        result: TotalCaptureResult,
    ): Boolean =
        !concurrentReaderPrewarm ||
            (pipeline.switchGlInputBaseline >= 0L &&
                pipeline.switchGlRequestSequence > 0L &&
                (result.request.tag as? Long ?: 0L) >= pipeline.switchGlRequestSequence &&
                pipeline.renderer.performanceStats().inputFrames > pipeline.switchGlInputBaseline)

    private fun isOneXCapture(pipeline: CameraPipeline, result: TotalCaptureResult): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                ?: result.request.get(CaptureRequest.CONTROL_ZOOM_RATIO)
            return zoom != null && abs(zoom - 1f) < 0.01f
        }
        val crop = result.request.get(CaptureRequest.SCALER_CROP_REGION)
        val full = pipeline.profile.characteristics
            .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        return crop == null || full == null || crop == full
    }

    private fun scheduleSwitchZoomWatchdog(target: CameraPipeline, generation: Long) {
        try {
            requestScheduler.schedule({
                try {
                    cameraExecutor.execute {
                        if (pendingActivationRole != target.profile.role ||
                            switchGeneration.get() != generation ||
                            closed.get() || !lifecycleActive
                        ) return@execute
                        Log.w(TAG, "${target.profile.label}: ready frame not received during camera switch")
                        pendingActivationRole = null
                        target.switchPromotionStartedNs = 0L
                        target.switchPromotionFpsReadyNs = 0L
                        target.switchPromotionAeWaitMs = 0L
                        target.switchGlInputBaseline = -1L
                        target.switchGlRequestSequence = 0L
                        if (concurrentCameraPrewarm) {
                            activePipeline()?.takeIf { it.session != null }?.let {
                                submitRepeatingRequest(it, resetWarmup = false, publishUiStatus = false)
                            }
                            target.takeIf { it.session != null }?.let {
                                submitRepeatingRequest(it, resetWarmup = false, publishUiStatus = false)
                            }
                        }
                        if (cameraRecoveryPending) return@execute
                        switchRequestPending.set(false)
                        updateStateForActivePipeline(isBusy = false, isSwitching = false)
                        refreshReadyStatus()
                    }
                } catch (_: RejectedExecutionException) {
                    // Recorder closed before the watchdog ran.
                }
            }, if (target.switchPromotionStartedNs != 0L) {
                SWITCH_FPS_PROMOTION_FRAME_TIMEOUT_MS
            } else {
                SWITCH_ZOOM_FRAME_TIMEOUT_MS
            }, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // Recorder closed before the watchdog could be scheduled.
        }
    }

    private fun isConcurrentSwitchTargetReady(
        pipeline: CameraPipeline,
        result: TotalCaptureResult,
    ): Boolean {
        val targetFps = config.frameRate.coerceAtLeast(1)
        val targetFrameDurationNs = 1_000_000_000L / targetFps
        val maxAcceptedFrameDurationNs =
            targetFrameDurationNs + targetFrameDurationNs / SWITCH_FPS_TOLERANCE_DIVISOR

        val appliedFpsRange = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
        val actualFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)

        val requestReachedHal = appliedFpsRange?.upper?.let { it >= targetFps } == true
        val cadenceReached = actualFrameDurationNs?.let {
            it <= maxAcceptedFrameDurationNs
        } ?: requestReachedHal

        val fpsReady = requestReachedHal && cadenceReached
        val nowNs = System.nanoTime()
        if (fpsReady && pipeline.switchPromotionFpsReadyNs == 0L) {
            pipeline.switchPromotionFpsReadyNs = nowNs
        }

        // AE can start searching again when the sensor changes from prewarm to recording FPS.
        // Show two consecutive frames with stable AE after the new cadence is established.
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        pipeline.switchPromotionStableFrames = if (fpsReady && isAeStable(aeState)) {
            pipeline.switchPromotionStableFrames + 1
        } else {
            0
        }

        val requiredFrames = if (config.exposureMode == ExposureMode.MANUAL) 1 else 2
        if (pipeline.switchPromotionStableFrames >= requiredFrames) {
            pipeline.switchPromotionAeWaitMs =
                (nowNs - pipeline.switchPromotionFpsReadyNs) / 1_000_000L
            return true
        }

        val fpsReadySinceNs = pipeline.switchPromotionFpsReadyNs
        if (fpsReady && fpsReadySinceNs != 0L &&
            nowNs - fpsReadySinceNs >= MAX_SWITCH_AE_WAIT_NS
        ) {
            pipeline.switchPromotionAeWaitMs = (nowNs - fpsReadySinceNs) / 1_000_000L
            cameraSwitchAeTimeoutCount += 1
            Log.w(
                TAG,
                "${pipeline.profile.label}: AE did not settle after FPS promotion; " +
                    "state=$aeState, waited=${(nowNs - fpsReadySinceNs) / 1_000_000L}ms",
            )
            return true
        }

        val elapsedNs = nowNs - pipeline.switchPromotionStartedNs
        if (!fpsReady && elapsedNs >= MAX_SWITCH_PROMOTION_WAIT_NS) {
            cameraSwitchPromotionTimeoutCount += 1
            Log.w(
                TAG,
                "${pipeline.profile.label}: high-FPS promotion timed out after " +
                        "${elapsedNs / 1_000_000L} ms; switching with range=$appliedFpsRange, " +
                        "frameDurationNs=$actualFrameDurationNs",
            )
            return true
        }

        return false
    }

    private fun isPrewarmedSwitchAeReady(
        pipeline: CameraPipeline,
        result: TotalCaptureResult,
    ): Boolean {
        val nowNs = System.nanoTime()
        if (pipeline.switchPromotionFpsReadyNs == 0L) {
            pipeline.switchPromotionFpsReadyNs = nowNs
        }
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        pipeline.switchPromotionStableFrames = if (isAeStable(aeState)) {
            pipeline.switchPromotionStableFrames + 1
        } else {
            0
        }
        val waitedNs = nowNs - pipeline.switchPromotionFpsReadyNs
        if (pipeline.switchPromotionStableFrames >=
            if (config.exposureMode == ExposureMode.MANUAL) 1 else 2
        ) {
            pipeline.switchPromotionAeWaitMs = waitedNs / 1_000_000L
            return true
        }
        if (waitedNs >= MAX_SWITCH_AE_WAIT_NS) {
            pipeline.switchPromotionAeWaitMs = waitedNs / 1_000_000L
            cameraSwitchAeTimeoutCount += 1
            Log.w(TAG, "${pipeline.profile.label}: AE did not settle before switch; state=$aeState")
            return true
        }
        return false
    }

    private fun isAeStable(state: Int?): Boolean =
        config.exposureMode == ExposureMode.MANUAL || state == null ||
            state == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
            state == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
            state == CameraMetadata.CONTROL_AE_STATE_LOCKED

    private fun updateWarmup(
        pipeline: CameraPipeline,
        result: TotalCaptureResult,
    ) {
        if (pipeline.warmed) return

        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)

        val aeStable = isAeStable(aeState)

        val awbStable = awbState == null ||
                awbState == CameraMetadata.CONTROL_AWB_STATE_CONVERGED ||
                awbState == CameraMetadata.CONTROL_AWB_STATE_LOCKED

        pipeline.stable3AFrames = if (aeStable && awbStable) {
            pipeline.stable3AFrames + 1
        } else {
            0
        }

        val elapsedNs = System.nanoTime() - pipeline.warmupStartedNs
        val converged = elapsedNs >= MIN_WARMUP_NS && pipeline.stable3AFrames >= STABLE_3A_FRAMES
        val timedOut = elapsedNs >= MAX_WARMUP_NS

        if (converged || timedOut) {
            pipeline.warmed = true
            if (pipeline.profile.role == activeRole) {
                refreshReadyStatus()
            }
        }
    }

    private fun resetWarmup(pipeline: CameraPipeline) {
        pipeline.warmupStartedNs = System.nanoTime()
        pipeline.stable3AFrames = 0
        pipeline.warmed = false
    }

    private fun activatePipeline(target: CameraPipeline) {
        if (target.session == null || !target.warmed) return

        val old = activePipeline()
        if (old === target) {
            pendingActivationRole = null
            switchRequestPending.set(false)
            updateStateForActivePipeline(isBusy = false, isSwitching = false)
            refreshReadyStatus()
            return
        }

        runCatching {
            if (recording) {
                old?.renderer?.detachEncoder()
            }
            old?.renderer?.detachPreview()

            activeRole = target.profile.role
            config = config.copy(zoomRatio = target.zoomRatio)
            if (cameraRecoveryPending) return
            if (recording) {
                val video = checkNotNull(videoEncoder)
                val recordingConfig = checkNotNull(activeRecordingConfig)
                target.renderer.attachEncoder(
                    surface = video.inputSurface,
                    size = recordingConfig.outputSize,
                    circleMask = recordingConfig.circleMaskInSavedVideo,
                    targetFrameRate = recordingConfig.frameRate,
                )
            }

            old?.renderer?.setBlurPreview(false)
            target.renderer.setBlurPreview(false)
            // Reconnect the encoder before the preview: a slow TextureView/EGL attach must
            // not prolong the gap in the recorded video during a warm camera switch.
            attachPreviewToActiveRenderer()

            updateActivePreviewVisibility()
            // Keep the outgoing camera at its current cadence until it is no longer
            // visible. Changing its FPS while it is still the active preview can cause
            // a transient exposure/color shift in the last frame shown during switching.
            if (concurrentCameraPrewarm && old?.session != null &&
                (concurrentReaderPrewarm ||
                    (old.selectedFpsRange?.upper ?: Int.MAX_VALUE) > concurrentPrewarmFps)
            ) {
                submitRepeatingRequest(old, resetWarmup = false, publishUiStatus = false)
            }
            if (target.switchPromotionStartedNs != 0L) {
                lastCameraSwitchPromotionMs =
                    (System.nanoTime() - target.switchPromotionStartedNs) / 1_000_000L
            } else {
                lastCameraSwitchPromotionMs = 0L
            }
            lastCameraSwitchAeWaitMs = target.switchPromotionAeWaitMs
            target.switchPromotionStartedNs = 0L
            target.switchPromotionStableFrames = 0
            target.switchPromotionFpsReadyNs = 0L
            target.switchPromotionAeWaitMs = 0L
            target.switchGlInputBaseline = -1L
            target.switchGlRequestSequence = 0L
            pendingActivationRole = null
            switchRequestPending.set(false)
            cameraSwitchCount += 1

            if (!concurrentCameraPrewarm && old != null) {
                closePipelineCamera(old)
            }

            updateStateForActivePipeline(
                status = if (recording) {
                    appContext.getString(
                        R.string.status_recording_camera_audio_uninterrupted,
                        localizedCameraLabel(target.profile),
                    )
                } else {
                    appContext.getString(
                        R.string.status_ready_camera,
                        localizedCameraLabel(target.profile),
                    )
                },
                isBusy = false,
                isSwitching = false,
            )
            refreshReadyStatus()
        }.onFailure { error ->
            handlePipelineFailure(
                target,
                appContext.getString(
                    R.string.error_activate_camera_failed,
                    error.message ?: appContext.getString(R.string.unknown)
                )
            )
        }
    }

    private fun publishLiveMetrics(result: TotalCaptureResult) {
        if (diagnosticsMode == DiagnosticsMode.Disabled) return
        val nowNs = System.nanoTime()
        if (nowNs - lastMetricsUiUpdateNs < METRICS_UI_INTERVAL_NS) return
        lastMetricsUiUpdateNs = nowNs

        val frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val activePhysicalCameraId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else {
            null
        }
        val lowLightBoostActive =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                result.get(CaptureResult.CONTROL_LOW_LIGHT_BOOST_STATE) ==
                        CameraMetadata.CONTROL_LOW_LIGHT_BOOST_STATE_ACTIVE
            } else {
                null
            }

        val actualFps = frameDurationNs
            ?.takeIf { it > 0L }
            ?.let { 1_000_000_000.0 / it.toDouble() }

        val renderStats = activePipeline()?.renderer?.performanceStats()

        updateState { state ->
            state.copy(
                liveMetrics = CameraLiveMetrics(
                    actualFps = actualFps,
                    exposureMs = exposureTimeNs?.div(1_000_000.0),
                    iso = iso,
                    aeState = aeState,
                    activePhysicalCameraId = activePhysicalCameraId,
                    lowLightBoostActive = lowLightBoostActive,
                    estimatedSourceDrops = renderStats?.estimatedSourceDrops ?: 0L,
                    encoderBackpressureEvents = renderStats?.encoderBackpressureEvents ?: 0L,
                ),
            )
        }
    }

    private fun resolveAePriorityMode(
        characteristics: CameraCharacteristics,
        lowLightBoostEnabled: Boolean,
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA || lowLightBoostEnabled) {
            return null
        }
        val supported = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES
        ) ?: intArrayOf()
        return config.aePriority.camera2Mode.takeIf { it in supported }
    }

    private fun resolveNoiseReductionMode(
        characteristics: CameraCharacteristics,
        requested: NoiseReductionSetting,
    ): Int? {
        if (requested == NoiseReductionSetting.OEM_DEFAULT) return null

        val supported = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        )?.toSet().orEmpty()

        val exact = requested.camera2Mode
        if (exact != null && exact in supported) return exact

        return when (requested) {
            NoiseReductionSetting.MINIMAL -> when {
                CameraMetadata.NOISE_REDUCTION_MODE_OFF in supported ->
                    CameraMetadata.NOISE_REDUCTION_MODE_OFF

                CameraMetadata.NOISE_REDUCTION_MODE_FAST in supported ->
                    CameraMetadata.NOISE_REDUCTION_MODE_FAST

                else -> null
            }

            NoiseReductionSetting.OFF -> null
            NoiseReductionSetting.FAST -> null
            NoiseReductionSetting.HIGH_QUALITY -> when {
                CameraMetadata.NOISE_REDUCTION_MODE_FAST in supported ->
                    CameraMetadata.NOISE_REDUCTION_MODE_FAST

                else -> null
            }

            NoiseReductionSetting.OEM_DEFAULT -> null
        }
    }

    private fun resolveVideoStabilizationMode(
        characteristics: CameraCharacteristics,
        requested: StabilizationSetting,
    ): Int? {
        val modes = characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        ) ?: intArrayOf()

        requested.camera2Mode?.let { requestedMode ->
            if (requestedMode in modes) return requestedMode

            if (requested == StabilizationSetting.PREVIEW) {
                if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in modes) {
                    return CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                }
                if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF in modes) {
                    return CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                }
            }
            return null
        }

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in modes ->
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION

            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in modes ->
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON

            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF in modes ->
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF

            else -> null
        }
    }

    private fun resolveOpticalStabilizationMode(
        characteristics: CameraCharacteristics,
        requested: OpticalStabilizationSetting,
    ): Int? {
        if (requested == OpticalStabilizationSetting.OEM_DEFAULT) return null
        val modes = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        ) ?: intArrayOf()
        return requested.camera2Mode?.takeIf { it in modes }
    }

    fun startRecording() {
        if (!recordingTransition.compareAndSet(false, true)) {
            return
        }

        val active = activePipeline() ?: run {
            recordingTransition.set(false)
            return
        }

        /*
         * active.warmed intentionally is NOT required here.
         *
         * A configured CameraCaptureSession is enough to start recording.
         * AE/AWB may continue converging during the first frames.
         *
         * This is important for older/OEM Camera2 implementations where
         * 3A can stay in SEARCHING state for a long time.
         */
        if (
            !permissionGranted ||
            active.session == null ||
            recording ||
            currentUiState.isSwitchingCamera
        ) {
            recordingTransition.set(false)
            return
        }

        if (
            appContext.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            recordingTransition.set(false)

            updateError(
                appContext.getString(
                    R.string.error_microphone_permission_missing
                )
            )

            return
        }

        if (
            config.holdFpsInLowLight &&
            !active.fpsHoldActive
        ) {
            recordingTransition.set(false)

            updateError(
                appContext.getString(
                    R.string.error_cannot_guarantee_fps,
                    config.frameRate,
                )
            )

            return
        }

        updateState { state ->
            state.copy(
                cameraReady = true,
                isBusy = true,
                status = appContext.getString(
                    R.string.status_starting_recorder,
                    localizedCameraLabel(
                        active.profile
                    ),
                ),
            )
        }

        recordingExecutor.execute {
            runCatching {
                check(
                    !closed.get() &&
                            lifecycleActive
                ) {
                    "Controller is not active"
                }

                val recordingConfig =
                    config

                /*
                 * VideoEncoder timestamps come from the camera/SurfaceTexture
                 * monotonic clock and AudioEncoder uses AudioTimestamp
                 * TIMEBASE_MONOTONIC.
                 *
                 * RecordingMuxer subtracts this common origin from both tracks.
                 */
                val originUs =
                    System.nanoTime() / 1_000L

                val baseName =
                    newRecordingBaseName()

                val file =
                    File.createTempFile(
                        "camera_pipeline_",
                        ".mp4",
                        appContext.cacheDir,
                    )

                val recordingMuxer =
                    RecordingMuxer(
                        outputFile = file,
                        originUs = originUs,
                    )

                val video =
                    VideoEncoder(
                        config = recordingConfig,
                        muxer = recordingMuxer,
                        onFailure = { error ->
                            Log.e(TAG, "Stopping recording after video encoder failure", error)
                            mainHandler.post {
                                if (!closed.get()) stopRecording()
                            }
                        },
                    )

                val audio =
                    AudioEncoder(
                        config = recordingConfig,
                        muxer = recordingMuxer,
                        onFailure = { error ->
                            Log.e(TAG, "Stopping recording after audio encoder failure", error)
                            mainHandler.post {
                                if (!closed.get()) stopRecording()
                            }
                        },
                    )

                tempFile = file
                recordingBaseName = baseName
                activeRecordingConfig = recordingConfig

                muxer = recordingMuxer
                videoEncoder = video
                audioEncoder = audio

                cameraSwitchCount = 0
                lastCameraSwitchPromotionMs = 0L
                cameraSwitchPromotionTimeoutCount = 0

                telemetry.start()

                timestampGate.reset()

                pipelines.values.forEach { pipeline ->
                    pipeline.renderer.resetPerformanceStats()
                }

                /*
                 * VideoEncoder is already configured and started in its init.
                 *
                 * We only connect its input Surface to the existing GL pipeline.
                 * Camera2 session itself is NOT recreated here.
                 */
                active.renderer.attachEncoder(
                    surface = video.inputSurface,
                    size = recordingConfig.outputSize,
                    circleMask =
                        recordingConfig.circleMaskInSavedVideo,
                    targetFrameRate =
                        recordingConfig.frameRate,
                )

                /*
                 * AudioEncoder, unlike VideoEncoder, starts explicitly.
                 */
                audio.start()

                check(
                    !closed.get() &&
                            lifecycleActive
                ) {
                    "Controller became inactive while recorder was starting"
                }

                recording = true

                active.renderer.setPreviewContentVisible(
                    true
                )

                recordingTransition.set(false)

                updateState { state ->
                    state.copy(
                        cameraReady = true,
                        isRecording = true,
                        isBusy = false,
                        config = recordingConfig,
                        status = appContext.getString(
                            R.string.status_recording_details,
                            localizedCameraLabel(
                                active.profile
                            ),
                            recordingConfig.outputSize.width,
                            recordingConfig.outputSize.height,
                            recordingConfig.frameRate,
                            formatBitrate(
                                recordingConfig.videoBitrate
                            ),
                            recordingConfig.maxBFrames,
                        ),
                    )
                }
            }.onFailure { error ->
                telemetry.stop()

                cleanupRecording(
                    deleteTempFile = true
                )

                recordingTransition.set(false)

                updateError(
                    appContext.getString(
                        R.string.error_start_failed,
                        error.message
                            ?: appContext.getString(
                                R.string.unknown
                            ),
                    )
                )
            }
        }
    }

    fun setDiagnosticsMode(
        mode: DiagnosticsMode,
    ) {
        this.diagnosticsMode = mode
        telemetry.setMode(
            mode
        )
    }

    fun stopRecording() {
        if (
            !recordingTransition.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        if (!recording) {
            recordingTransition.set(false)
            return
        }

        recording = false

        updateState { state ->
            state.copy(
                cameraReady = true,
                isRecording = false,
                isBusy = true,
                status = appContext.getString(
                    R.string.status_finalizing_mp4
                ),
            )
        }

        recordingExecutor.execute {
            runCatching {
                telemetry.stop()

                pipelines.values.forEach { pipeline ->
                    runCatching {
                        pipeline.renderer.detachEncoder()
                    }
                }

                audioEncoder?.stopAndAwait()
                videoEncoder?.stopAndAwait()

                val file =
                    checkNotNull(tempFile)

                val baseName =
                    checkNotNull(recordingBaseName)

                val video =
                    checkNotNull(videoEncoder)

                val recordingConfig =
                    checkNotNull(activeRecordingConfig)

                val active =
                    checkNotNull(activePipeline())

                muxer?.close()
                audioEncoder?.close()
                videoEncoder?.close()

                val telemetryCsv =
                    telemetry.toCsv {
                        val renderPerformanceByRole =
                            pipelines.mapValues { (_, pipeline) ->
                                pipeline.renderer.performanceStats()
                            }
                        val renderPerformance = renderPerformanceByRole.values.toList()

                        buildList {
                            add(
                                "output=${recordingConfig.outputSize.width}x${recordingConfig.outputSize.height}"
                            )

                            add(
                                "requestedFrameRate=${recordingConfig.frameRate}"
                            )

                            add(
                                "requestedVideoBitrate=${recordingConfig.videoBitrate}"
                            )

                            add(
                                "requestedMaxBFrames=${recordingConfig.maxBFrames}"
                            )

                            add(
                                "holdFpsInLowLight=${recordingConfig.holdFpsInLowLight}"
                            )

                            add(
                                "lowLightBoost=${recordingConfig.lowLightBoost}"
                            )

                            add(
                                "aePriority=${recordingConfig.aePriority}"
                            )

                            add(
                                "antibanding=${recordingConfig.antibanding}"
                            )

                            add(
                                "requestedZoomRatio=${recordingConfig.zoomRatio}"
                            )

                            add(
                                "requestedAperture=${recordingConfig.aperture}"
                            )

                            add(
                                "requestedNoiseReduction=${recordingConfig.noiseReduction}"
                            )

                            add(
                                "requestedHotPixelCorrection=${recordingConfig.hotPixelCorrection}"
                            )

                            add(
                                "requestedLensShading=${recordingConfig.lensShading}"
                            )

                            add(
                                "requestedColorCorrection=${recordingConfig.colorCorrection}"
                            )

                            add(
                                "requestedColorTemperatureKelvin=${recordingConfig.colorTemperatureKelvin}"
                            )

                            add(
                                "requestedColorTint=${recordingConfig.colorTint}"
                            )

                            add(
                                "noiseReduction=${recordingConfig.noiseReduction}"
                            )

                            add(
                                "circleMaskInSavedVideo=${recordingConfig.circleMaskInSavedVideo}"
                            )

                            add(
                                "hidePreviewUntilRecording=${recordingConfig.hidePreviewUntilRecording}"
                            )

                            add(
                                "cameraSwitchCount=$cameraSwitchCount"
                            )

                            add(
                                "lastCameraSwitchPromotionMs=$lastCameraSwitchPromotionMs"
                            )

                            add(
                                "lastCameraSwitchAeWaitMs=$lastCameraSwitchAeWaitMs"
                            )

                            add(
                                "cameraSwitchPromotionTimeoutCount=$cameraSwitchPromotionTimeoutCount"
                            )

                            add(
                                "cameraSwitchAeTimeoutCount=$cameraSwitchAeTimeoutCount"
                            )

                            add(
                                "concurrentCameraPrewarm=$concurrentCameraPrewarm"
                            )
                            add("concurrentReaderPrewarm=$concurrentReaderPrewarm")


                            add(
                                "concurrentInactivePrewarmMaxFps=$concurrentPrewarmFps"
                            )

                            add(
                                "cameraSwitchOutgoingFpsDemotion=afterTargetPreviewAttach"
                            )

                            pipelines.values.forEach { pipeline ->
                                add(
                                    "cameraFpsRange.${pipeline.profile.role.name}=${pipeline.selectedFpsRange}"
                                )
                                add(
                                    "cameraSourceSize.${pipeline.profile.role.name}=${pipeline.profile.sourceSize}"
                                )
                                add(
                                    "prewarmReaderFrames.${pipeline.profile.role.name}=${pipeline.prewarmReaderFrames.get()}"
                                )
                            }

                            add(
                                "finalCamera=${active.profile.label}"
                            )

                            add(
                                "finalCameraId=${active.profile.cameraId}"
                            )

                            add(
                                "finalCameraSourceSize=${active.profile.sourceSize}"
                            )

                            add(
                                "finalCameraBaseZoom=${active.profile.zoomRatio}"
                            )

                            add(
                                "cameraAeFpsRange=${active.selectedFpsRange}"
                            )

                            add(
                                "fpsHoldActive=${active.fpsHoldActive}"
                            )

                            add(
                                "cameraStabilizationMode=${active.stabilizationMode}"
                            )
                            add(
                                "cameraStabilizationSessionParameter=${active.stabilizationSessionParameter}"
                            )
                            add(
                                "cameraInitialSessionParameterKeys=${active.initialSessionParameterKeys.joinToString()}"
                            )

                            add(
                                "appliedNoiseReductionMode=${active.appliedNoiseReductionMode}"
                            )

                            add(
                                "videoRecordStreamUseCase=${active.videoRecordUseCaseEnabled}"
                            )

                            add(
                                "sensorReadoutTimestamps=${active.readoutTimestampEnabled}"
                            )

                            add(
                                "rendererSourceFrames=${
                                    renderPerformance.sumOf {
                                        it.sourceFrames
                                    }
                                }"
                            )

                            add(
                                "rendererEstimatedSourceDrops=${
                                    renderPerformance.sumOf {
                                        it.estimatedSourceDrops
                                    }
                                }"
                            )

                            add(
                                "rendererEncoderFrames=${
                                    renderPerformance.sumOf {
                                        it.encoderFrames
                                    }
                                }"
                            )

                            add(
                                "rendererEncoderBackpressureEvents=${
                                    renderPerformance.sumOf {
                                        it.encoderBackpressureEvents
                                    }
                                }"
                            )

                            add("rendererPreviewFrames=${renderPerformance.sumOf { it.previewFrames }}")
                            add("rendererPreviewFramesCoalesced=${renderPerformance.sumOf { it.previewFramesCoalesced }}")
                            add("rendererPreviewBackpressureEvents=${renderPerformance.sumOf { it.previewBackpressureEvents }}")
                            add("rendererEncoderDrawOverBudgetEvents=${renderPerformance.sumOf { it.encoderDrawOverBudgetEvents }}")
                            add("rendererPreviewDrawOverBudgetEvents=${renderPerformance.sumOf { it.previewDrawOverBudgetEvents }}")
                            add("rendererMaxEncoderSwapMs=${renderPerformance.maxOfOrNull { it.maxEncoderSwapNs }?.div(1_000_000.0) ?: 0.0}")
                            add("rendererMaxPreviewSwapMs=${renderPerformance.maxOfOrNull { it.maxPreviewSwapNs }?.div(1_000_000.0) ?: 0.0}")
                            add("rendererMaxSourceAcquireMs=${renderPerformance.maxOfOrNull { it.maxSourceAcquireNs }?.div(1_000_000.0) ?: 0.0}")
                            add("rendererMaxEncoderDrawMs=${renderPerformance.maxOfOrNull { it.maxEncoderDrawNs }?.div(1_000_000.0) ?: 0.0}")
                            add("rendererMaxEncoderGlSetupMs=${renderPerformance.maxOfOrNull { it.maxEncoderGlSetupNs }?.div(1_000_000.0) ?: 0.0}")
                            add("rendererMaxEncoderGlDrawCallMs=${renderPerformance.maxOfOrNull { it.maxEncoderGlDrawCallNs }?.div(1_000_000.0) ?: 0.0}")
                            add("rendererMaxPreviewDrawMs=${renderPerformance.maxOfOrNull { it.maxPreviewDrawNs }?.div(1_000_000.0) ?: 0.0}")
                            renderPerformanceByRole.forEach { (role, stats) ->
                                add("renderer.${role.name}.threadPriority=${stats.threadPriority}")
                                add("renderer.${role.name}.inputFrames=${stats.inputFrames}")
                                add("renderer.${role.name}.sourceAcquireOverBudgetEvents=${stats.sourceAcquireOverBudgetEvents}")
                                add("renderer.${role.name}.sourceFrames=${stats.sourceFrames}")
                                add("renderer.${role.name}.estimatedSourceDrops=${stats.estimatedSourceDrops}")
                                add("renderer.${role.name}.previewFrames=${stats.previewFrames}")
                                add("renderer.${role.name}.previewFramesCoalesced=${stats.previewFramesCoalesced}")
                                add("renderer.${role.name}.previewDrawOverBudgetEvents=${stats.previewDrawOverBudgetEvents}")
                                add("renderer.${role.name}.maxSourceAcquireMs=${stats.maxSourceAcquireNs / 1_000_000.0}")
                                add("renderer.${role.name}.maxEncoderDrawMs=${stats.maxEncoderDrawNs / 1_000_000.0}")
                                add("renderer.${role.name}.maxEncoderGlSetupMs=${stats.maxEncoderGlSetupNs / 1_000_000.0}")
                                add("renderer.${role.name}.maxEncoderGlDrawCallMs=${stats.maxEncoderGlDrawCallNs / 1_000_000.0}")
                                add("renderer.${role.name}.maxEglMakeCurrentMs=${stats.maxEglMakeCurrentNs / 1_000_000.0}")
                            }
                            add("maxPendingZoomRequests=${pipelines.values.maxOfOrNull { it.maxPendingZoomRequests } ?: 0L}")
                            add("rearZoomInRenderer=${pipelines[CameraRole.REAR_WIDE]?.zoomInRenderer == true}")
                            add("frontZoomInRenderer=${pipelines[CameraRole.FRONT]?.zoomInRenderer == true}")

                            addAll(
                                video.reportLines()
                            )
                        }
                    }

                audioEncoder = null
                videoEncoder = null
                muxer = null
                tempFile = null
                recordingBaseName = null
                activeRecordingConfig = null

                val result = RecordingResult(
                    file = file,
                    baseName = baseName,
                    telemetryCsv = telemetryCsv,
                    config = recordingConfig,
                )

                updateActivePreviewVisibility()

                recordingTransition.set(false)

                updateStateForActivePipeline(
                    status = appContext.getString(
                        R.string.status_recording_ready
                    ),
                    isBusy = false,
                    isSwitching = false,
                )

                runCatching {
                    onRecordingFinished(
                        result
                    )
                }.onFailure { error ->
                    Log.e(
                        TAG,
                        "Recording result callback failed",
                        error,
                    )
                }
            }.onFailure { error ->
                cleanupRecording(
                    deleteTempFile = true
                )

                recordingTransition.set(false)

                updateError(
                    appContext.getString(
                        R.string.error_stop_failed,
                        error.message
                            ?: appContext.getString(
                                R.string.unknown
                            ),
                    )
                )
            }
        }
    }

    private fun closeCameraSystemAsync() {
        try {
            cameraExecutor.execute {
                try {
                    /*
                     * Recording cleanup has already completed before this task
                     * is scheduled, so renderers are no longer attached to the
                     * encoder.
                     *
                     * Keep Camera2 teardown serialized on cameraExecutor.
                     */
                    pipelines.values.forEach(
                        ::closePipelineCamera
                    )
                    pipelines.values.forEach(::closePrewarmReader)

                    /*
                     * Take a snapshot before clearing controller state.
                     *
                     * CameraGlRenderer#close() may block while its GL thread
                     * releases EGL resources, but that happens here on the
                     * camera worker — never on the caller/main thread.
                     */
                    val renderers =
                        pipelines.values
                            .map { pipeline ->
                                pipeline.renderer
                            }

                    pipelines.clear()

                    pendingActivationRole = null
                    concurrentCameraPrewarm = false
                    concurrentReaderPrewarm = false
                    cameraSystemPrepared = false

                    renderers.forEach { renderer ->
                        runCatching {
                            renderer.close()
                        }.onFailure { error ->
                            Log.w(
                                TAG,
                                "Unable to close camera renderer",
                                error,
                            )
                        }
                    }
                } finally {
                    /*
                     * shutdown() is non-blocking.
                     *
                     * Calling it from the executor's own final task is safe:
                     * this task completes normally and no new Camera2 work is
                     * accepted afterwards.
                     */
                    cameraExecutor.shutdown()
                    prewarmDrainThread.quitSafely()
                }
            }
        } catch (error: RejectedExecutionException) {
            /*
             * This should only be possible during an already-running shutdown.
             * closed is already true, therefore there must be no attempt to
             * restart camera work here.
             */
            Log.d(
                TAG,
                "Camera executor already shutting down",
                error,
            )
        }
    }

    private fun cleanupRecording(
        deleteTempFile: Boolean,
    ) {
        telemetry.stop()

        pipelines.values.forEach { pipeline ->
            runCatching {
                pipeline.renderer.detachEncoder()
            }
        }

        runCatching {
            audioEncoder?.close()
        }

        runCatching {
            videoEncoder?.close()
        }

        runCatching {
            muxer?.close()
        }

        audioEncoder = null
        videoEncoder = null
        muxer = null

        if (deleteTempFile) {
            tempFile?.delete()
        }

        tempFile = null
        recordingBaseName = null
        activeRecordingConfig = null
        recording = false

        /*
         * During normal recording cleanup we restore preview visibility.
         *
         * During controller shutdown this is unnecessary and, more importantly,
         * would synchronously wait for the GL thread through
         * CameraGlRenderer#setPreviewContentVisible().
         *
         * The renderer will be released by the camera teardown stage anyway.
         */
        if (!closed.get()) {
            runCatching {
                updateActivePreviewVisibility()
            }
        }
    }

    private fun attachPreviewToActiveRenderer() {
        val active =
            activePipeline()
                ?: return

        val surface =
            previewSurface
                ?.takeIf { it.isValid }
                ?: return

        pipelines.values
            .filter {
                it !== active
            }
            .forEach { pipeline ->
                runCatching {
                    pipeline.renderer.detachPreview()
                }
            }

        active.renderer.attachPreview(
            surface = surface,
            width = previewWidth,
            height = previewHeight,
        )

        updateActivePreviewVisibility()
    }

    private fun updateActivePreviewVisibility() {
        activePipeline()?.renderer?.setPreviewContentVisible(
            visible = !config.hidePreviewUntilRecording || recording,
        )
    }

    private fun closeAllCameraDevices() {
        if (cameraExecutor.isShutdown) return
        cameraExecutor.execute {
            pendingActivationRole = null
            pipelines.values.forEach(::closePipelineCamera)
        }
    }

    private fun closePrewarmReader(pipeline: CameraPipeline) {
        pipeline.prewarmReader?.let { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
        pipeline.prewarmReader = null
    }

    private fun closePipelineCamera(pipeline: CameraPipeline) {
        pipeline.openGeneration += 1L
        runCatching { pipeline.session?.stopRepeating() }
        runCatching { pipeline.session?.close() }
        pipeline.session = null
        pipeline.repeatingRequestBuilder = null
        pipeline.repeatingCaptureCallback = null
        pipeline.lastSubmittedZoomRatio = Float.NaN
        pipeline.zoomSubmittedSequence = 0L
        pipeline.zoomObservedSequence = 0L
        pipeline.lastZoomRequestNs = 0L
        pipeline.device?.let(::closeCameraDevice)
        pipeline.device = null
        pipeline.opening = false
        pipeline.warmed = false
        pipeline.stable3AFrames = 0
        pipeline.switchPromotionStartedNs = 0L
        pipeline.switchPromotionStableFrames = 0
        pipeline.switchPromotionFpsReadyNs = 0L
        pipeline.switchPromotionAeWaitMs = 0L
        pipeline.switchGlInputBaseline = -1L
        pipeline.switchGlRequestSequence = 0L
    }

    private fun closeCameraDevice(camera: CameraDevice) {
        if (!closingCameraDevices.add(camera)) return
        runCatching { camera.close() }.onFailure { error ->
            closingCameraDevices.remove(camera)
            Log.w(TAG, "Unable to close camera ${camera.id}", error)
            resumeDeferredCameraOpens()
        }
    }

    private fun resumeDeferredCameraOpens() {
        if (closingCameraDevices.isNotEmpty()) return
        closeWaitGeneration += 1L
        closeWaitScheduled = false
        if (deferredCameraOpens.isEmpty()) return
        val pending = deferredCameraOpens.toList()
        deferredCameraOpens.clear()
        pending.forEach { (role, deferSessionConfiguration) ->
            pipelines[role]?.let { openPipeline(it, deferSessionConfiguration) }
        }
    }

    private fun scheduleCloseWaitTimeout() {
        if (closeWaitScheduled) return
        closeWaitScheduled = true
        val generation = ++closeWaitGeneration
        try {
            requestScheduler.schedule({
                try {
                    cameraExecutor.execute {
                        if (generation != closeWaitGeneration) return@execute
                        closeWaitScheduled = false
                        if (closingCameraDevices.isNotEmpty() && deferredCameraOpens.isNotEmpty()) {
                            Log.w(TAG, "Camera close callback timed out; retrying deferred open")
                            closingCameraDevices.clear()
                            resumeDeferredCameraOpens()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    // The recorder has already been closed.
                }
            }, CAMERA_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            closeWaitScheduled = false
        }
    }

    private fun handlePipelineFailure(
        pipeline: CameraPipeline,
        message: String,
        cameraError: Boolean = false,
    ) {
        Log.e(TAG, "${pipeline.profile.label}: $message")

        if (cameraError) {
            scheduleCameraRecovery(pipeline, message)
            return
        }

        if (concurrentCameraPrewarm) {
            // Keep the advertised concurrent configuration during recovery. Opening only the
            // active device here silently loses prewarm for the rest of the recorder's lifetime.
            scheduleCameraRecovery(pipeline, message)
            return
        }

        if (pendingActivationRole == pipeline.profile.role && !concurrentCameraPrewarm) {
            val oldRole = activeRole
            if (pipeline.profile.role == oldRole) {
                closePipelineCamera(pipeline)
                pendingActivationRole = null
                switchRequestPending.set(false)
                if (recording) {
                    mainHandler.post { if (!closed.get()) stopRecording() }
                }
                updateError("${localizedCameraLabel(pipeline.profile)}: $message")
                return
            }
            closePipelineCamera(pipeline)
            pendingActivationRole = oldRole
            val old = pipelines[oldRole]
            if (old != null && old.device == null && !old.opening) openPipeline(old)
            if (pendingActivationRole != oldRole) return
            if (old == null || (old.session == null && !old.opening)) {
                pendingActivationRole = null
                switchRequestPending.set(false)
                updateError("${localizedCameraLabel(pipeline.profile)}: $message")
                return
            }
            updateState { state ->
                state.copy(
                    isSwitchingCamera = true,
                    status = appContext.getString(
                        R.string.status_restoring_camera,
                        message,
                        pipelines[oldRole]?.profile?.let(::localizedCameraLabel)
                            ?: appContext.getString(R.string.camera_generic),
                    ),
                )
            }
            return
        }

        closePipelineCamera(pipeline)
        pendingActivationRole = null
        switchRequestPending.set(false)
        if (pipeline.profile.role == activeRole && recording) {
            mainHandler.post { if (!closed.get()) stopRecording() }
        }
        updateError("${localizedCameraLabel(pipeline.profile)}: $message")
    }

    private fun scheduleCameraRecovery(
        pipeline: CameraPipeline,
        message: String,
    ) {
        if (closed.get() || !lifecycleActive || cameraRecoveryPending) return

        if (concurrentCameraPrewarm && concurrentPrewarmFps > CONCURRENT_PREWARM_MAX_FPS) {
            // Advertised stream configurations do not guarantee that the vendor ISP can
            // sustain them simultaneously. Retry with the inactive camera capped at 15 fps.
            concurrentHighFpsRejected = true
            concurrentPrewarmFps = minOf(config.frameRate, CONCURRENT_PREWARM_MAX_FPS)
            Log.w(TAG, "Concurrent high-FPS prewarm failed; retrying with $concurrentPrewarmFps fps")
        }
        cameraRecoveryPending = true
        requestRevision.incrementAndGet()
        zoomRequestRevision.incrementAndGet()
        switchRequestPending.set(false)
        pendingActivationRole = null
        config = config.copy(zoomRatio = 1f)
        pipelines.values.forEach {
            it.zoomRatio = 1f
            if (it.zoomInRenderer) it.renderer.setDigitalZoomRatio(1f)
        }
        pipelines.values.forEach(::closePipelineCamera)

        if (recoveryAttemptCount >= MAX_CAMERA_RECOVERY_ATTEMPTS) {
            cameraRecoveryPending = false
            recoveryAttemptCount = 0
            if (recording) {
                mainHandler.post { if (!closed.get()) stopRecording() }
            }
            updateError("${localizedCameraLabel(pipeline.profile)}: $message")
            return
        }

        recoveryAttemptCount++
        val generation = recoveryGeneration.incrementAndGet()
        val delayMs = CAMERA_RECOVERY_BASE_DELAY_MS * recoveryAttemptCount
        updateState { state ->
            state.copy(
                config = config,
                cameraReady = false,
                isBusy = true,
                isSwitchingCamera = false,
                status = appContext.getString(
                    R.string.status_restoring_camera,
                    message,
                    activePipeline()?.profile?.let(::localizedCameraLabel)
                        ?: appContext.getString(R.string.camera_generic),
                ),
            )
        }

        requestScheduler.schedule({
            if (closed.get() || !lifecycleActive || generation != recoveryGeneration.get()) {
                return@schedule
            }
            try {
                cameraExecutor.execute {
                    if (closed.get() || !lifecycleActive || generation != recoveryGeneration.get()) {
                        return@execute
                    }
                    cameraRecoveryPending = false
                    val active = activePipeline() ?: return@execute
                    openRequiredPipelines()

                    // Some HALs never deliver onError after accepting openCamera. Do not leave
                    // recording or preview waiting forever for the first capture result.
                    requestScheduler.schedule({
                        if (closed.get() || !lifecycleActive || generation != recoveryGeneration.get()) {
                            return@schedule
                        }
                        try {
                            cameraExecutor.execute {
                                if (generation == recoveryGeneration.get() && recoveryAttemptCount != 0) {
                                    scheduleCameraRecovery(active, message)
                                }
                            }
                        } catch (_: RejectedExecutionException) {
                            // Recorder closed while waiting for the camera.
                        }
                    }, CAMERA_RECOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            } catch (_: RejectedExecutionException) {
                // Recorder closed while recovery was waiting.
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun refreshReadyStatus() {
        if (
            recording ||
            recordingTransition.get()
        ) {
            return
        }

        val active =
            activePipeline()
                ?: return

        val holdState =
            appContext.getString(
                when {
                    !config.holdFpsInLowLight -> {
                        R.string.status_adaptive_ae
                    }

                    active.fpsHoldActive -> {
                        R.string.status_fps_hold_active
                    }

                    else -> {
                        R.string.status_fps_hold_unsupported
                    }
                }
            )

        val rangeText =
            active.selectedFpsRange
                ?.let { range ->
                    "${range.lower}-${range.upper}"
                }
                ?: appContext.getString(
                    R.string.status_default_range
                )

        val warmText =
            appContext.getString(
                if (active.warmed) {
                    R.string.status_prewarmed
                } else {
                    R.string.status_warming
                }
            )

        val prewarmText =
            appContext.getString(
                if (concurrentCameraPrewarm) {
                    R.string.status_dual_camera_prewarm
                } else {
                    R.string.status_single_camera
                }
            )

        updateStateForActivePipeline(
            status = appContext.getString(
                R.string.status_ready_details,
                localizedCameraLabel(
                    active.profile
                ),
                warmText,
                config.frameRate,
                rangeText,
                holdState,
                prewarmText,
            ),

            /*
             * 3A warmup is not a blocking operation anymore.
             */
            isBusy = false,

            isSwitching = false,
        )
    }

    private fun updateStateForActivePipeline(
        status: String? = null,
        isBusy: Boolean? = null,
        isSwitching: Boolean? = null,
    ) {
        val active =
            activePipeline()

        val characteristics =
            active?.profile?.characteristics

        val supportedFpsRanges =
            characteristics
                ?.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                )
                ?.distinct()
                ?.sortedWith(
                    compareBy<Range<Int>>(
                        { it.lower },
                        { it.upper },
                    )
                )
                .orEmpty()

        val supportedNoiseModes =
            characteristics
                ?.get(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
                )
                ?.toList()
                .orEmpty()

        val capabilities = active?.let(::buildCapabilities) ?: CameraCapabilities()

        updateState { state ->
            state.copy(
                /*
                 * Session exists = camera can be used.
                 *
                 * Do not make public camera readiness depend on AE/AWB
                 * convergence.
                 */
                cameraReady =
                    active?.session != null,

                isRecording =
                    recording,

                isBusy =
                    isBusy
                        ?: state.isBusy,

                isSwitchingCamera =
                    isSwitching
                        ?: state.isSwitchingCamera,

                status =
                    status
                        ?: state.status,

                config =
                    config,

                supportedFpsRanges =
                    supportedFpsRanges,

                selectedFpsRange =
                    active?.selectedFpsRange,

                fpsHoldActive =
                    active?.fpsHoldActive == true,

                supportedNoiseReductionModes =
                    supportedNoiseModes,

                appliedNoiseReductionMode =
                    active?.appliedNoiseReductionMode,

                activeCameraRole =
                    activeRole,

                activeCameraLabel =
                    active
                        ?.profile
                        ?.let(::localizedCameraLabel)
                        ?: localizedCameraRoleLabel(
                            activeRole
                        ),

                cameraSwitchAvailable =
                    pipelines.size > 1,

                concurrentCameraPrewarm =
                    concurrentCameraPrewarm,

                capabilities =
                    capabilities,
            )
        }
    }

    private fun isQualitySupported(
        quality: VideoQuality,
        frameRate: Int,
    ): Boolean = avcEncoderSupports(quality.outputSize, frameRate) &&
            pipelines.values.all { pipeline ->
                cameraCanFeedSquareOutput(
                    characteristics = pipeline.profile.characteristics,
                    output = quality.outputSize,
                    frameRate = frameRate,
                )
            }

    private fun availableZoomRange(pipeline: CameraPipeline): Range<Float> =
        if (concurrentCameraPrewarm) pipeline.concurrentZoomRange else pipeline.cameraZoomRange

    private fun buildCapabilities(
        pipeline: CameraPipeline,
    ): CameraCapabilities {
        val characteristics = pipeline.profile.characteristics
        val requestCapabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val exposureNsRange = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        val supportedQualities = VideoQuality.entries.filter { quality ->
            isQualitySupported(quality, config.frameRate)
        }
        val vendorKeys = runCatching {
            characteristics.availableCaptureRequestKeys
                .map { it.name }
                .filterNot { it.startsWith("android.") }
                .sorted()
        }.getOrDefault(emptyList())

        return CameraCapabilities(
            hardwareLevel = hardwareLevelLabel(
                characteristics.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
                )
            ),
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in
                    requestCapabilities,
            lowLightBoostAvailable =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                        characteristics
                            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                            ?.contains(
                                CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY
                            ) == true,
            lowLightBoostLuxRange =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    characteristics.get(
                        CameraCharacteristics.CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE
                    )
                } else {
                    null
                },
            aePriorityModes =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    characteristics
                        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
                        ?.toList()
                        .orEmpty()
                } else {
                    emptyList()
                },
            antibandingModes = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                ?.toList()
                .orEmpty(),
            supportedQualities = supportedQualities,
            stabilizationModes = characteristics
                .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.toList()
                .orEmpty(),
            opticalStabilizationModes = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.toList()
                .orEmpty(),
            edgeModes = characteristics
                .get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?.toList()
                .orEmpty(),
            distortionCorrectionModes = characteristics
                .get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?.toList()
                .orEmpty(),
            aberrationCorrectionModes = characteristics
                .get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
                ?.toList()
                .orEmpty(),
            tonemapModes = characteristics
                .get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                ?.toList()
                .orEmpty(),
            hotPixelModes = characteristics
                .get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
                ?.toList()
                .orEmpty(),
            shadingModes = characteristics
                .get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
                ?.toList()
                .orEmpty(),
            colorCorrectionModes =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    characteristics
                        .get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
                        ?.toList()
                        .orEmpty()
                } else {
                    emptyList()
                },
            colorTemperatureRange =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    characteristics.get(
                        CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE
                    )
                } else {
                    null
                },
            awbModes = characteristics
                .get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.toList()
                .orEmpty(),
            aeLockAvailable = characteristics.get(
                CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE
            ) == true,
            awbLockAvailable = characteristics.get(
                CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
            ) == true,
            exposureCompensationRange = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
            ),
            exposureCompensationStepEv = characteristics
                .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?.toFloat()
                ?: 0f,
            sensitivityRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            ),
            exposureTimeUsRange = exposureNsRange?.let {
                Range(
                    (it.lower / 1_000L).coerceAtLeast(1L),
                    (it.upper / 1_000L).coerceAtLeast(1L),
                )
            },
            minimumFocusDistance = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f,
            zoomRatioRange = availableZoomRange(pipeline),
            availableApertures = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.toList()
                .orEmpty(),
            vendorRequestKeys = vendorKeys,
        )
    }

    private fun localizedCameraLabel(profile: CameraProfile): String = when (profile.role) {
        CameraRole.FRONT -> appContext.getString(R.string.camera_front)
        CameraRole.REAR_WIDE -> if (profile.zoomRatio < 0.99f) {
            appContext.getString(R.string.camera_rear_zoom, profile.zoomRatio)
        } else {
            appContext.getString(R.string.camera_rear_one_x)
        }
    }

    private fun localizedCameraRoleLabel(role: CameraRole): String = when (role) {
        CameraRole.FRONT -> appContext.getString(R.string.camera_front)
        CameraRole.REAR_WIDE -> appContext.getString(R.string.camera_rear_wide)
    }

    private fun hardwareLevelLabel(level: Int?): String = appContext.getString(
        when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> R.string.camera2_level_legacy
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> R.string.camera2_level_limited
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> R.string.camera2_level_full
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> R.string.camera2_level_3
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> R.string.camera2_level_external
            else -> R.string.camera2_level_unknown
        }
    )

    private fun activePipeline(): CameraPipeline? = pipelines[activeRole]

    private fun updateState(transform: (CameraUiState) -> CameraUiState) {
        mainHandler.post {
            val updatedState = transform(currentUiState)
            if (updatedState == currentUiState) return@post

            currentUiState = updatedState
            onState(updatedState)
        }
    }

    private fun updateError(
        message: String,
    ) {
        Log.e(
            TAG,
            message,
        )

        updateState { state ->
            state.copy(
                cameraReady =
                    activePipeline()
                        ?.session != null,

                isRecording =
                    recording,

                isBusy =
                    false,

                isSwitchingCamera =
                    false,

                config =
                    config,

                status =
                    message,
            )
        }
    }

    private fun formatBitrate(bitsPerSecond: Int): String =
        appContext.getString(
            R.string.format_mbps,
            bitsPerSecond / 1_000_000.0,
        )

    override fun close() {
        if (
            !closed.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        lifecycleActive = false
        recoveryGeneration.incrementAndGet()
        cameraRecoveryPending = false

        /*
         * Invalidate pending debounced CaptureRequest updates immediately.
         */
        requestRevision.incrementAndGet()
        zoomRequestRevision.incrementAndGet()

        /*
         * No more state callbacks should be delivered after the public recorder
         * has been closed.
         *
         * This Handler instance is private to this controller, so removing all
         * callbacks here does not affect the host application's other handlers.
         */
        mainHandler.removeCallbacksAndMessages(
            null
        )

        /*
         * shutdownNow() itself does not wait for termination.
         *
         * Scheduled request-update tasks also check closed before doing camera
         * work, so there is no reason to await this executor on the caller.
         */
        requestScheduler.shutdownNow()

        /*
         * Do not wait here.
         *
         * Recording teardown is deliberately appended to recordingExecutor.
         * This gives us two useful properties:
         *
         * 1. Any already-running start/stop/finalization operation completes
         *    before destructive cleanup starts.
         *
         * 2. close() is safe even when called from onRecordingFinished(),
         *    because it only queues this task and returns. The callback can then
         *    finish, allowing this task to run next.
         */
        try {
            recordingExecutor.execute {
                try {
                    cleanupRecording(
                        deleteTempFile = true
                    )
                } catch (error: Throwable) {
                    Log.w(
                        TAG,
                        "Unable to clean up recording during close",
                        error,
                    )
                } finally {
                    recordingTransition.set(
                        false
                    )

                    /*
                     * shutdown() does not wait for this executor.
                     *
                     * We are already running its final task, so it is safe to
                     * stop accepting new work now.
                     */
                    recordingExecutor.shutdown()

                    /*
                     * Camera2 and EGL teardown starts only after recording
                     * resources have been released.
                     */
                    closeCameraSystemAsync()
                }
            }
        } catch (error: RejectedExecutionException) {
            /*
             * Defensive fallback. Normally only close() shuts down this
             * executor, and AtomicBoolean above makes close idempotent.
             */
            Log.d(
                TAG,
                "Recording executor already shutting down",
                error,
            )

            recordingTransition.set(
                false
            )

            closeCameraSystemAsync()
        }
    }

    private companion object {
        const val TAG = "CameraPipelineLab"
        const val MIN_VIDEO_BITRATE = 300_000
        const val MAX_VIDEO_BITRATE = 40_000_000
        const val MIN_FRAME_RATE = 15
        const val MAX_FRAME_RATE = 60
        const val MAX_B_FRAMES = 4
        const val METRICS_UI_INTERVAL_NS = 500_000_000L
        const val REQUEST_UPDATE_DEBOUNCE_MS = 32L
        const val CAMERA_RECOVERY_BASE_DELAY_MS = 500L
        const val CAMERA_RECOVERY_TIMEOUT_MS = 2_500L
        const val CAMERA_CLOSE_TIMEOUT_MS = 2_000L
        const val MAX_CAMERA_RECOVERY_ATTEMPTS = 5
        // Both camera streams stay active during recording. Keep the inactive stream just fast
        // enough for a warm switch while reserving ISP/GPU bandwidth for the 60 fps output.
        const val CONCURRENT_PREWARM_MAX_FPS = 60
        const val SWITCH_FPS_TOLERANCE_DIVISOR = 5
        const val MAX_SWITCH_PROMOTION_WAIT_NS = 300_000_000L
        const val MAX_SWITCH_AE_WAIT_NS = 150_000_000L
        const val SWITCH_ZOOM_FRAME_TIMEOUT_MS = 500L
        const val SWITCH_FPS_PROMOTION_FRAME_TIMEOUT_MS = 1_000L
        const val MAX_REAR_ZOOM_REQUESTS_PER_SECOND = 60
        const val MAX_PENDING_FRONT_ZOOM_REQUESTS = 6L
        const val MAX_PENDING_REAR_ZOOM_REQUESTS = 6L
        const val MIN_WARMUP_NS = 300_000_000L
        const val MAX_WARMUP_NS = 1_500_000_000L
        const val STABLE_3A_FRAMES = 4
    }
}
