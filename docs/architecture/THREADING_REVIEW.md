# Threading Review

This document describes the current threading implementation. Future improvements are explicitly separated from current behavior.

## Current thread model

While recording with concurrent front/rear pipelines, the library can own roughly eight application worker threads:

1. Camera2 single-thread executor.
2. Recording orchestration single-thread executor.
3. CaptureRequest debounce scheduled executor.
4. Front-camera GL `HandlerThread`.
5. Rear-camera GL `HandlerThread`.
6. AudioRecord input thread.
7. AAC output drain thread.
8. AVC output drain thread.

This count does not include implementation threads owned internally by Android camera services or MediaCodec.

The thread count itself is not the primary performance problem. The important constraints are serialization, GL/EGL thread confinement, blocking AudioRecord input and avoiding unnecessary work on the main thread.

## Public threading contract

For the current facade:

```text
VideoMessageRecorder control calls
    → invoke from one serialized host thread, normally Main

onState
    → Main thread

onRecordingFinished
    → recordingExecutor worker thread
```

The host should not call recorder methods concurrently from arbitrary threads.

`onRecordingFinished` should return quickly. Move upload, persistent file I/O or long processing into the host application's own IO/worker layer.

## Camera2 control

Current implementation:

```kotlin
private val cameraExecutor =
    Executors.newSingleThreadExecutor()
```

Camera open/close/session/request mutations must remain serialized.

The controller also wraps Camera2 callback dispatch so callbacks arriving after shutdown do not crash by submitting work into an already rejected executor.

Do not replace Camera2 mutation ordering with `Dispatchers.Default` or another unconstrained pool.

## OpenGL / EGL / SurfaceTexture

Each `CameraGlRenderer` owns:

- one `HandlerThread` with display priority;
- one EGLDisplay/EGLContext;
- one camera `SurfaceTexture`;
- preview EGL surface;
- encoder EGL surface while recording.

GL calls and `SurfaceTexture.updateTexImage()` remain confined to that renderer thread.

Several renderer methods use synchronous `runOnGlAndWait`, so callers may block while waiting for the GL thread.

This confinement is correct for the current architecture.

## Video timestamps

Raw camera `SurfaceTexture` timestamps are not submitted directly as the final encoder timeline.

Each renderer anchors camera timestamp deltas onto the `System.nanoTime()` / monotonic timeline. The shared `PresentationTimestampGate` then guarantees strictly increasing presentation timestamps across camera switches.

This is required for OEM/device compatibility and for front/rear pipelines whose raw timestamp zero-points must not be assumed to match.

## Audio

`AudioEncoder` uses two dedicated threads:

### AudioEncoderInput

- audio priority;
- blocking `AudioRecord.read()`;
- obtains `AudioTimestamp.TIMEBASE_MONOTONIC`;
- queues PCM into the AAC codec.

### AudioEncoderDrain

- drains encoded AAC output;
- writes samples to `RecordingMuxer`.

Blocking `AudioRecord.read()` should remain on a dedicated thread rather than on a generic shared coroutine pool whose worker priority could be modified accidentally.

## Video encoder

`VideoEncoder` starts its MediaCodec in the constructor and owns:

```text
VideoEncoderDrain
```

The drain thread uses display priority and writes encoded AVC buffers to `RecordingMuxer`.

The GL renderer feeds the codec through its input `Surface`; there is no CPU video-frame copy loop.

## Recording orchestration

`startRecording()` and `stopRecording()` use a dedicated single-thread `recordingExecutor`.

This serializes:

- encoder/muxer creation;
- encoder attachment;
- recording transitions;
- finalization;
- result callback delivery.

The actual GL and AudioRecord hot paths stay on their dedicated threads.

## CaptureRequest debounce

The controller currently owns:

```kotlin
Executors.newSingleThreadScheduledExecutor()
```

for debounced camera request updates.

This is a control-plane thread, not a realtime capture thread.

## UI state

`updateState` posts through a main-thread `Handler`, therefore:

```text
onState → Main
```

This is part of the current public callback contract.

## Known issue: synchronous close

Current `CameraRecorderController.close()` is synchronous.

It:

1. schedules recording cleanup;
2. shuts down `recordingExecutor` and waits for termination;
3. shuts down `requestScheduler` and waits;
4. shuts down `cameraExecutor` and waits;
5. closes each renderer;
6. renderer close itself waits for its GL thread.

Consequences:

- `close()` can block the calling thread;
- it must not be called from `onRecordingFinished`, because that callback runs on the recording executor;
- calling it directly from a latency-sensitive main-thread path may cause visible UI stalls on slow/broken devices.

### Recommended pre-1.0 fix

Make shutdown asynchronous or split it into a non-blocking public lifecycle operation plus internal awaited cleanup.

Until then, consumers should call `close()` only during final teardown after recording has stopped and must not call it from recorder-owned worker callbacks.

## Current implementation: keep dedicated / serialized

Keep dedicated or strictly serialized execution for:

- Camera2 mutation order;
- GL/EGL/SurfaceTexture;
- blocking AudioRecord input;
- MediaCodec output drains.

These are not good targets for a mechanical "replace threads with coroutines" refactor.

## Future improvements

The following are proposals, not current behavior.

### 1. Remove the dedicated request scheduler

The debounce scheduler could become a cancellable coroutine `Job` running on the serialized camera control dispatcher.

Potential benefit:

- one less permanently allocated executor;
- lifecycle cancellation becomes simpler.

### 2. Simplify recording orchestration

`recordingExecutor` could eventually become a serialized coroutine scope / dispatcher / mutex-based state machine.

The encoder drain and AudioRecord hot paths should remain dedicated.

### 3. StateFlow API

A future API could expose:

```kotlin
val state: StateFlow<CameraUiState>
```

instead of callback + main-handler delivery.

This would be an API change and should not be presented as current behavior.

### 4. Shared dual-camera GL engine

The largest structural optimization would be replacing two complete `CameraGlRenderer` instances with one shared engine:

```text
one GL HandlerThread
one EGLDisplay
one EGLContext
two OES textures / SurfaceTextures
one preview output
one encoder output
active input texture selected at switch time
```

Potential benefits:

- remove one GL thread;
- remove duplicate EGL infrastructure;
- simplify deterministic camera handoff.

This is a larger architecture change and must be benchmarked against the current implementation.

## Review summary

Current design priorities should be:

1. keep realtime paths isolated and serialized;
2. fix blocking shutdown semantics;
3. avoid long work in `onRecordingFinished`;
4. only then reduce control-plane executor count;
5. benchmark any shared-GL refactor separately from camera/codec tuning.
