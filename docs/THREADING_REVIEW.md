# Threading review

## Conclusion

Do not replace every dedicated thread with coroutines. Coroutines simplify the control plane, but the hot path still needs thread confinement and/or realtime-friendly priorities.

The current implementation can own up to roughly eight application worker threads while concurrent front/rear capture is active:

1. Camera2 executor.
2. Recording orchestration executor.
3. CaptureRequest debounce scheduler.
4. Front-camera GL HandlerThread.
5. Rear-camera GL HandlerThread.
6. AudioRecord input thread.
7. AAC output drain thread.
8. AVC output drain thread.

This is more than necessary, but the expensive part is not the existence of coroutines versus threads. The main optimization target is duplicate GL/EGL infrastructure and unnecessary always-alive orchestration executors.

## Keep dedicated / serialized

### Camera2 control

Keep camera open/close/session/request mutations serialized. This may be represented by one single-thread executor/dispatcher, but it must remain a single ordered execution context.

Do not move Camera2 state mutations to `Dispatchers.Default` or an unconstrained pool.

### OpenGL / EGL / SurfaceTexture

Keep GL work confined to a GL thread. EGL context ownership, SurfaceTexture updates and rendering should remain serialized.

The larger improvement is to replace the current two `CameraGlRenderer` instances with one `DualCameraGlEngine`:

- one GL HandlerThread;
- one EGLDisplay;
- one EGLContext;
- two external OES textures / SurfaceTextures, one per camera;
- one preview output surface;
- one encoder output surface;
- switching changes only the active input texture.

That removes a GL thread and a second EGL context while also making front/rear switching deterministic.

### AudioRecord input

Keep blocking `AudioRecord.read()` on a dedicated audio-priority thread. Do not put it on a generic shared coroutine pool while changing thread priority to `THREAD_PRIORITY_AUDIO`, because coroutine execution can resume on another shared worker and modifying the priority of a shared pool worker is undesirable.

## Good coroutine candidates

### CaptureRequest debounce

Replace the dedicated `ScheduledExecutorService` with a cancellable coroutine `Job` on the serialized camera dispatcher:

```kotlin
private var pendingRequestJob: Job? = null

private fun scheduleRepeatingRequestUpdate() {
    pendingRequestJob?.cancel()
    pendingRequestJob = cameraScope.launch {
        delay(REQUEST_UPDATE_DEBOUNCE_MS)
        submitRepeatingRequests()
    }
}
```

This removes one permanently allocated scheduler thread and gives cancellation as part of controller lifecycle.

### Recording orchestration

`startRecording()` / `stopRecording()` orchestration does not need its own permanently alive single-thread executor. It can use a `SupervisorJob`, a serialized coroutine dispatcher and/or a `Mutex`.

Do not move the actual AudioRecord/GL hot path onto that dispatcher.

### UI state

Replace callback + `mainHandler.post` with:

```kotlin
val state: StateFlow<CameraUiState>
```

The host can collect it with lifecycle-aware Compose APIs. This removes manual main-thread delivery from the reusable core API and makes ownership/cancellation explicit.

## MediaCodec

A later phase can evaluate `MediaCodec.Callback` instead of explicit synchronous drain loops. This can remove application-owned drain-loop code, but it does not mean MediaCodec itself becomes thread-free. Keep this as a separate change because codec callback ordering/EOS handling is easy to regress.

## Recommended migration order

### Phase A — low risk

- Introduce `CoroutineScope(SupervisorJob() + cameraDispatcher)` for controller orchestration.
- Replace the request scheduler with `Job + delay`.
- Replace recording orchestration executor with structured coroutines + `Mutex`.
- Expose `StateFlow` / `SharedFlow` from the future library API.
- Keep current GL, AudioRecord and codec internals unchanged.

### Phase B — performance

- Merge the two GL renderers into one `DualCameraGlEngine`.
- Keep both camera SurfaceTextures receiving frames while concurrent cameras are available.
- Render only the active source to encoder/preview.
- Re-measure source drops, `eglSwapBuffers()` stalls and camera-switch latency.

### Phase C — optional codec modernization

- Evaluate asynchronous `MediaCodec.Callback` for AAC/AVC output.
- Keep AudioRecord capture dedicated.
- Compare frame pacing and EOS reliability before removing the old drain implementation.

## Why not refactor the hot path immediately

The project is currently being used as an instrumentation lab. A full concurrency rewrite at the same time as camera/codec tuning would make performance regressions harder to attribute. First simplify orchestration, then merge GL, then change codec mode independently.
