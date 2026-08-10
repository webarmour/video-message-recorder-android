# Library Architecture

This document describes the current reusable-library boundary of the repository.

## Modules

```text
:video-message-recorder
    public VideoMessageRecorder facade
    public RecordingMode / DiagnosticsMode
    public RecordingResult
    public RecordingConfig / CameraUiState and related models
    internal AutoRecordingConfigResolver
    internal Camera2 controller
    internal concurrent-camera / prewarm logic
    internal OpenGL / EGL renderer
    internal MediaCodec AVC/AAC encoders
    internal AudioRecord capture
    internal MediaMuxer
    internal telemetry

:app
    minimal Compose demo
    runtime permission UX
    lifecycle host
    preview Surface integration
    sample send/cancel behavior
```

The library module uses `com.android.library`, has `minSdk 28`, and does not depend on the `app` module or Compose.

## Public entry point

```kotlin
class VideoMessageRecorder(
    context: Context,
    mode: RecordingMode = RecordingMode.Auto,
    diagnosticsMode: DiagnosticsMode = DiagnosticsMode.Disabled,
    onState: (CameraUiState) -> Unit = {},
    onRecordingFinished: (RecordingResult) -> Unit = {},
) : AutoCloseable
```

A backwards-compatible constructor accepting `initialConfig: RecordingConfig` maps that configuration to `RecordingMode.Custom`.

The facade exposes:

```text
setPermissionGranted
onStart / onStop
attachPreview / detachPreview
startRecording / stopRecording / toggleRecording
switchCamera
updateZoomRatio
updateMode / updateConfig
setDiagnosticsMode
close
```

Camera2, EGL, MediaCodec, AudioRecord and MediaMuxer implementation classes remain internal.

## Configuration ownership

### RecordingMode.Auto

Normal production path.

`AutoRecordingConfigResolver` selects a compatible `RecordingConfig` for the device. Consumers must not hard-code assumptions about the final quality or FPS selected by AUTO.

### RecordingMode.Custom

Advanced path for applications that require explicit parameters.

The consumer accepts responsibility for requesting a configuration that the current camera and encoder can actually support.

### DiagnosticsMode

```kotlin
DiagnosticsMode.Disabled
DiagnosticsMode.TelemetryCsv
```

Diagnostics are separate from recording quality configuration.

## Recording result

```kotlin
data class RecordingResult(
    val file: File,
    val baseName: String,
    val telemetryCsv: String?,
    val config: RecordingConfig,
)
```

The finalized MP4 is created in the host application's cache directory.

After `onRecordingFinished`, file ownership transfers to the host. The host decides whether to upload, persist, retry or delete it.

`telemetryCsv` is nullable and is normally `null` when diagnostics are disabled.

## Host responsibilities

The embedding application owns:

- runtime `CAMERA` / `RECORD_AUDIO` permission UX;
- Activity/Fragment/Compose lifecycle;
- placement and visual clipping of the preview;
- ownership and release of the preview `Surface`;
- permanent storage / MediaStore policy;
- upload, retry and authentication;
- message sending and cancellation semantics;
- navigation and product analytics.

The library owns:

- camera discovery and capability probing;
- front/rear camera selection;
- device-aware AUTO configuration;
- camera session/request management;
- camera warmup and optional concurrent prewarm;
- FPS/exposure/focus/stabilization controls;
- zoom compatibility paths;
- OpenGL transformations and square center-crop;
- optional baked circular mask;
- video timestamp normalization;
- H.264/AAC encoding;
- audio/video muxing;
- recorder state and optional diagnostics.

## Rendering and encoding path

```text
Camera2
  ↓
SurfaceTexture
  ↓
CameraGlRenderer
  ├─→ preview EGL surface
  └─→ MediaCodec H.264 input surface
              ↓
          video drain
              ↓
          RecordingMuxer
              ↑
          audio drain
              ↑
         AAC MediaCodec
              ↑
          AudioRecord
```

Camera timestamps are converted to a common monotonic encoder timeline before they are submitted to the encoder. `PresentationTimestampGate` keeps timestamps strictly increasing across independently running front/rear renderers.

## Public models

The current public API also exposes types from:

```text
io.github.webarmour.videomessagerecorder.camera
```

including:

```text
RecordingConfig
VideoQuality
CameraUiState
CameraLiveMetrics
CameraCapabilities
CameraRole
camera-setting enums
```

These types are part of the consumer ABI because they occur in public constructor parameters, callbacks or return values.

Before declaring a stable `1.0.0`, changes to these models should be treated as public API changes.

## API-level behavior

### API 28-29

- Camera2 / EGL / H.264 / AAC recording.
- crop-region zoom compatibility path.
- no platform concurrent-camera API.

### API 30+

- newer zoom-ratio path where supported.
- concurrent front/rear prewarming when the device advertises a compatible camera pair.

### Newer APIs

Newer Camera2 request/output features are guarded by SDK checks and capability checks. A value existing in `RecordingConfig` does not mean every device/API can apply it.

The host should not implement its own SDK branching for camera internals.

## UI boundary

The core recorder intentionally does not depend on Compose.

Circular preview, blurred chat background, buttons, recording duration, animations and message presentation belong in the host.

If a reusable ready-made UI is added later, it should be a separate module such as:

```text
:video-message-recorder-compose
```

rather than making the core module depend on Compose.

## Transport boundary

The recorder produces a local MP4 result. It does not know about:

- Telegram;
- a specific backend;
- multipart upload;
- authentication;
- message IDs;
- retry queues.

A backend/transport adapter should remain outside the camera module.

## Documentation ownership

```text
README.md
    short public overview and installation

docs/INTEGRATION.md
    consumer-facing API contract

docs/architecture/LIBRARY_ARCHITECTURE.md
    internal module/ownership boundaries

docs/architecture/THREADING_REVIEW.md
    threading implementation and future improvements

video-message-recorder/README.md
    links only; no duplicated documentation
```
