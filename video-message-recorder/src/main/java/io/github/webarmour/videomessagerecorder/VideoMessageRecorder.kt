package io.github.webarmour.videomessagerecorder

import android.content.Context
import android.view.Surface
import io.github.webarmour.videomessagerecorder.camera.CameraRecorderController
import io.github.webarmour.videomessagerecorder.camera.CameraUiState
import io.github.webarmour.videomessagerecorder.camera.RecordingConfig
import io.github.webarmour.videomessagerecorder.camera.AutoRecordingConfigResolver

class VideoMessageRecorder(
    context: Context,
    mode: RecordingMode = RecordingMode.Auto,
    diagnosticsMode: DiagnosticsMode =
        DiagnosticsMode.Disabled,
    onState: (CameraUiState) -> Unit = {},
    onRecordingFinished: (RecordingResult) -> Unit = {},
) : AutoCloseable {

    private val autoConfigResolver =
        AutoRecordingConfigResolver(
            context = context.applicationContext,
        )

    var recordingMode: RecordingMode = mode
        private set

    private val controller =
        CameraRecorderController(
            context = context.applicationContext,
            initialConfig = resolveConfig(
                mode
            ),
            onState = onState,
            onRecordingFinished =
                onRecordingFinished,
        )

    init {
        controller.setDiagnosticsMode(
            diagnosticsMode
        )
    }

    /**
     * Backwards-compatible advanced constructor.
     *
     * Existing code with initialConfig continues to work
     * and is treated as explicit Custom mode.
     */
    constructor(
        context: Context,
        initialConfig: RecordingConfig,
        diagnosticsMode: DiagnosticsMode =
            DiagnosticsMode.Disabled,
        onState: (CameraUiState) -> Unit = {},
        onRecordingFinished: (RecordingResult) -> Unit = {},
    ) : this(
        context = context,
        mode =
            RecordingMode.Custom(
                initialConfig
            ),
        diagnosticsMode =
            diagnosticsMode,
        onState =
            onState,
        onRecordingFinished =
            onRecordingFinished,
    )

    fun setPermissionGranted(
        granted: Boolean,
    ) {
        controller.setPermissionGranted(
            granted
        )
    }

    fun onStart() {
        controller.onStart()
    }

    fun onStop() {
        controller.onStop()
    }

    fun attachPreview(
        surface: Surface,
        width: Int,
        height: Int,
        displayRotation: Int,
    ) {
        controller.attachPreview(
            surface = surface,
            width = width,
            height = height,
            displayRotation = displayRotation,
        )
    }

    fun detachPreview() {
        controller.detachPreview()
    }

    fun startRecording() {
        controller.startRecording()
    }

    fun stopRecording() {
        controller.stopRecording()
    }

    fun toggleRecording() {
        controller.toggleRecording()
    }

    fun switchCamera() {
        controller.switchCamera()
    }

    fun updateZoomRatio(
        zoomRatio: Float,
    ) {
        controller.updateZoomRatio(
            zoomRatio
        )
    }

    fun updateMode(
        mode: RecordingMode,
    ) {
        recordingMode = mode

        controller.updateConfig(
            resolveConfig(
                mode
            )
        )
    }

    /**
     * Advanced API.
     *
     * Explicit RecordingConfig automatically switches
     * the recorder to Custom mode.
     */
    fun updateConfig(
        config: RecordingConfig,
    ) {
        updateMode(
            RecordingMode.Custom(
                config
            )
        )
    }

    fun setDiagnosticsMode(
        mode: DiagnosticsMode,
    ) {
        controller.setDiagnosticsMode(
            mode
        )
    }

    override fun close() {
        controller.close()
    }

    private fun resolveConfig(
        mode: RecordingMode,
    ): RecordingConfig {
        return when (mode) {

            RecordingMode.Auto -> {
                autoConfigResolver.resolve()
            }

            is RecordingMode.Custom -> {
                mode.config
            }
        }
    }
}