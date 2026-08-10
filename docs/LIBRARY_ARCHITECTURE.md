# Reusable video-message recorder architecture

## Current split

The recorder has been extracted from the sample app into an Android library module:

```text
:video-message-recorder
    public VideoMessageRecorder facade
    public RecordingConfig / CameraUiState
    public RecordingResult
    internal Camera2 controller
    internal concurrent-camera/prewarm logic
    internal OpenGL/EGL renderer
    internal MediaCodec video/audio encoders
    internal MediaMuxer
    internal capture telemetry

:app
    Compose laboratory UI
    runtime permission UX
    lifecycle host
    MediaStore / app-specific storage policy
```

The core module uses `com.android.library` and has `minSdk 28`. It does not depend on Compose or on
the app module.

## Public entry point

```kotlin
class VideoMessageRecorder(
    context: Context,
    initialConfig: RecordingConfig = RecordingConfig(),
    onState: (CameraUiState) -> Unit = {},
    onRecordingFinished: (RecordingResult) -> Unit = {},
) : AutoCloseable
```

The facade exposes preview attachment, explicit start/stop/toggle recording, camera switching,
zoom/config updates and host lifecycle hooks. Low-level Camera2/EGL/MediaCodec classes remain
internal so consumers are not coupled to pipeline implementation details.

## Recording result

```kotlin
data class RecordingResult(
    val file: File,
    val baseName: String,
    val telemetryCsv: String,
    val config: RecordingConfig,
)
```

The finalized MP4 is created in the host application's cache directory. After
`onRecordingFinished`, the host owns the file and decides whether to upload, persist or delete it.
The callback currently runs on the recorder worker thread so slow persistence does not block UI.

## Host responsibilities

The embedding application owns:

- CAMERA and RECORD_AUDIO permission UX;
- Activity/Fragment/navigation lifecycle;
- preview surface placement;
- permanent file/storage policy;
- upload/message sending/authentication;
- Telegram-specific integration, if any.

The library owns:

- camera discovery/capability probing;
- camera warmup/switching;
- camera/FPS/exposure configuration;
- GL transformations and optional circular encode mask;
- synchronized audio/video capture;
- encoding/muxing;
- capture state and diagnostics.

## API-level behavior

```text
API 28-29
    Camera2 + EGL + AVC/AAC recording
    crop-region zoom fallback
    no concurrent-camera prewarm
    inactive/active camera swap keeps the recording pipeline alive

API 30+
    CONTROL_ZOOM_RATIO path
    concurrent-camera prewarm when the device advertises the requested pair

API 33+
    advanced OutputConfiguration hints where supported
```

## Next optional extraction

The current Compose laboratory screen intentionally remains in `:app`. If a reusable ready-made
UI is needed later, add a separate `:video-message-recorder-compose` module rather than making the
core depend on Compose.

A Telegram/transport adapter should also stay separate from the camera library.
