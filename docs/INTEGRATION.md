# Integration Guide

This document describes the consumer-facing contract of `video-message-recorder`.

For internal design details see:

- [Library Architecture](architecture/LIBRARY_ARCHITECTURE.md)
- [Threading Review](architecture/THREADING_REVIEW.md)

## Requirements

- Android API 28+
- runtime `CAMERA` permission
- runtime `RECORD_AUDIO` permission
- a valid Android `Surface` for preview

The library does not own permission dialogs, UI, navigation, MediaStore policy, upload or message sending.

## Create the recorder

Recommended production mode:

```kotlin
val recorder = VideoMessageRecorder(
    context = context.applicationContext,
    mode = RecordingMode.Auto,
    diagnosticsMode = DiagnosticsMode.Disabled,
    onState = { state ->
        // Delivered on the main thread.
    },
    onRecordingFinished = { result ->
        // Delivered on the recorder worker thread.
    },
)
```

`RecordingMode.Auto` and `DiagnosticsMode.Disabled` are defaults, so the minimal form is:

```kotlin
val recorder = VideoMessageRecorder(
    context = context.applicationContext,
    onState = ::onRecorderState,
    onRecordingFinished = ::onRecordingFinished,
)
```

## Permissions

The host application owns runtime permission UX.

Required permissions:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

After the permission flow, pass the effective result to the recorder:

```kotlin
recorder.setPermissionGranted(
    cameraGranted && microphoneGranted
)
```

Do not pass `true` unless both permissions are actually granted.

## Lifecycle

Forward host lifecycle events:

```kotlin
override fun onStart() {
    super.onStart()
    recorder.onStart()
}

override fun onStop() {
    recorder.onStop()
    super.onStop()
}

override fun onDestroy() {
    recorder.close()
    super.onDestroy()
}
```

`close()` is terminal. A closed recorder instance must not be reused.

### Current close behavior

The current implementation performs synchronous worker shutdown and waits for internal executors/GL threads. Do not call `close()` from `onRecordingFinished` or another recorder-owned worker callback.

For the current version, close the recorder only during final host teardown and preferably after recording has already stopped. Making close fully non-blocking is tracked as an internal architecture improvement; see [Threading Review](architecture/THREADING_REVIEW.md).

## Preview Surface

The host owns the preview `Surface`.

Attach:

```kotlin
recorder.attachPreview(
    surface = surface,
    width = width,
    height = height,
    displayRotation = displayRotation,
)
```

Detach before releasing the host-owned surface:

```kotlin
recorder.detachPreview()
surface.release()
```

A `TextureView`, `SurfaceView` or Compose `AndroidView` wrapper may be used by the host.

The library is UI-agnostic. If a circular video-message preview is required, clip the preview in the host UI.

## Recorder state

Use `CameraUiState` for UI decisions.

Most commonly used fields:

```kotlin
state.cameraReady
state.isRecording
state.isBusy
state.isSwitchingCamera
state.cameraSwitchAvailable
state.activeCameraRole
state.activeCameraLabel
state.config
state.liveMetrics
state.capabilities
```

Start recording only when the camera is ready:

```kotlin
if (
    state.cameraReady &&
    !state.isBusy &&
    !state.isSwitchingCamera &&
    !state.isRecording
) {
    recorder.startRecording()
}
```

Do not use the human-readable `state.status` string as application logic.

## Recording

Start:

```kotlin
recorder.startRecording()
```

Stop and finalize the MP4:

```kotlin
recorder.stopRecording()
```

Convenience toggle:

```kotlin
recorder.toggleRecording()
```

For production UI, explicit `startRecording()` / `stopRecording()` calls are easier to reason about than toggle state.

## Camera switching

```kotlin
recorder.switchCamera()
```

The library keeps the recording pipeline alive while moving between front and rear cameras.

Concurrent prewarming is device/API dependent. The host does not need to implement its own Camera2 branching.

Use:

```kotlin
state.cameraSwitchAvailable
state.isSwitchingCamera
```

to control the UI.

## Zoom

```kotlin
recorder.updateZoomRatio(
    zoomRatio = 1.5f
)
```

The library selects the appropriate Camera2 implementation for the running Android version.

## Recording modes

### Auto

Recommended:

```kotlin
RecordingMode.Auto
```

AUTO resolves a compatible recording configuration for the device. The host must not assume that AUTO always means 60 FPS or a particular quality.

The actual configuration is available through:

```kotlin
result.config
```

### Custom

Use when explicit parameters are a product requirement:

```kotlin
RecordingMode.Custom(
    RecordingConfig(
        quality = VideoQuality.TELEGRAM_NOTE_MAX,
        frameRate = 30,
        videoBitrate = 1_500_000,
        holdFpsInLowLight = false,
    )
)
```

With Custom mode, the consumer is responsible for requesting realistic parameters. A device may not be able to guarantee a requested fixed FPS.

Update mode:

```kotlin
recorder.updateMode(
    RecordingMode.Auto
)
```

Update explicit config:

```kotlin
recorder.updateConfig(
    RecordingConfig(
        quality = VideoQuality.HIGH,
        frameRate = 30,
    )
)
```

`updateConfig()` switches the recorder to `RecordingMode.Custom`.

## RecordingConfig

All fields have defaults. A Custom consumer should override only what it needs.

### Output / encoder

```kotlin
quality
videoBitrate
frameRate
iFrameIntervalSeconds
maxBFrames
```

Current square qualities:

```text
COMPACT             320x320
TELEGRAM            384x384
IPHONE_LIKE         400x400
HIGH                480x480
TELEGRAM_NOTE_MAX   640x640
HD                   720x720
FULL_HD             1080x1080
QHD                 1440x1440
```

### FPS / exposure

```kotlin
holdFpsInLowLight
lowLightBoost
aePriority
antibanding
exposureCompensationSteps
exposureMode
manualExposureTimeUs
manualIso
```

`holdFpsInLowLight = true` is a strict request. If the camera cannot guarantee the requested fixed range, recording may be rejected.

### Image processing

```kotlin
noiseReduction
hotPixelCorrection
lensShading
colorCorrection
colorTemperatureKelvin
colorTint
edgeEnhancement
distortionCorrection
aberrationCorrection
tonemap
```

OEM support varies. `RecordingMode.Auto` is preferred unless the product needs explicit tuning.

### White balance / focus

```kotlin
awb
aeLock
awbLock
focus
manualFocusDistance
```

### Stabilization / optics

```kotlin
stabilization
opticalStabilization
zoomRatio
aperture
```

### Preview / output mask

```kotlin
circleMaskInSavedVideo
hidePreviewUntilRecording
```

Recommended video-message behavior:

```kotlin
circleMaskInSavedVideo = false
```

Store a normal square MP4 and clip playback to a circle in the host UI.

If `circleMaskInSavedVideo = true`, the circle is baked into the encoded pixels. The MP4 is still a rectangular/square H.264 video and has no transparent corners.

### Audio

```kotlin
audioSampleRate
audioBitrate
```

Defaults are intended for short video-message recording.

## Diagnostics

Production default:

```kotlin
DiagnosticsMode.Disabled
```

Enable CSV telemetry:

```kotlin
DiagnosticsMode.TelemetryCsv
```

or update it later:

```kotlin
recorder.setDiagnosticsMode(
    DiagnosticsMode.TelemetryCsv
)
```

When disabled:

```kotlin
result.telemetryCsv == null
```

Diagnostics are optional and should not be required by normal product flow.

## RecordingResult

Successful finalization returns:

```kotlin
data class RecordingResult(
    val file: File,
    val baseName: String,
    val telemetryCsv: String?,
    val config: RecordingConfig,
)
```

### File ownership

The MP4 is created in the host application's cache directory.

After `onRecordingFinished`, the host owns the file and must decide to:

- upload it;
- copy/move it to persistent storage;
- keep it for retry;
- delete it.

Do not assume cache files are permanent.

### Worker callback

`onRecordingFinished` currently runs on the recorder's recording worker.

Do not perform long blocking work inline in this callback. Hand the result to your own IO/upload layer and return.

For UI:

```kotlin
onRecordingFinished = { result ->
    mainHandler.post {
        showVideoMessage(result.file)
    }
}
```

For background processing, enqueue the file in the application's own coroutine/worker/repository layer.

## Cancel flow

The core API intentionally has no chat-specific `send()` or `cancelMessage()` concept.

A host can implement cancellation by stopping the recording and discarding the finalized result:

```kotlin
private var discardNextResult = false

fun cancelRecording() {
    discardNextResult = true
    recorder.stopRecording()
}

fun onRecordingFinished(
    result: RecordingResult,
) {
    if (discardNextResult) {
        discardNextResult = false
        result.file.delete()
        return
    }

    enqueueVideoMessage(result.file)
}
```

Keep product-specific chat behavior outside the camera library.

## Threading contract

For the current API:

```text
Host control methods         one serialized host thread, normally Main
onState                      Main thread
onRecordingFinished          recorder recording worker
Camera2 mutations            internal serialized camera executor
OpenGL / SurfaceTexture      internal GL HandlerThread per renderer
AudioRecord blocking input   dedicated audio-priority thread
MediaCodec output drains     dedicated encoder drain threads
```

Do not call recorder methods concurrently from multiple arbitrary host threads.

See [Threading Review](architecture/THREADING_REVIEW.md) for details.

## Consumer checklist

Before shipping:

- request `CAMERA` and `RECORD_AUDIO`;
- pass the real permission result to `setPermissionGranted`;
- forward lifecycle events;
- attach only a valid preview `Surface`;
- detach before releasing the host surface;
- wait for `cameraReady` before recording;
- use AUTO unless fixed parameters are required;
- do not manipulate UI directly from `onRecordingFinished`;
- move/upload/delete the cache MP4 after finalization;
- keep diagnostics disabled in production unless intentionally collected;
- prefer a UI circular clip over a baked circular video mask.
