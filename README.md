# CameraPipelineLab

Experimental Camera2 / OpenGL / MediaCodec video-message pipeline.

## This revision

- Viewfinder, zoom, status and record/camera-switch controls stay fixed; only Recording settings scroll.
- Pinch-to-zoom on the viewfinder plus a zoom slider directly below it. Zoom can be changed while recording.
- Core video-message controls remain in the main settings section.
- Additional Camera2 controls live under a nested `Advanced` spoiler. UI text is selected from Android string resources for the current device language (English default, Russian in `values-ru`).
- CaptureRequest-only changes no longer mark the whole UI busy or restart camera warmup.
- Camera request changes are debounced off the main thread (32 ms), which prevents rapid sliders/pinch gestures from flooding Camera2.
- Once a concurrently opened front/rear camera has completed its initial 3A warmup, ordinary tuning changes no longer invalidate that warm state.
- Rear concurrent pipeline is kept at its native logical-camera wide/ultrawide base zoom while inactive, when the HAL exposes zoom < 1x.
- GL rendering uses a display-priority HandlerThread; audio uses audio-priority worker threads; video drain uses display priority.
- AVC max-complexity forcing is disabled to keep the hardware encoder in a realtime-friendly configuration.
- Live pipeline diagnostics show estimated source-frame gaps and encoder EGL backpressure events. These are also saved in the CSV header.
- All primitive CameraCharacteristics arrays use explicit null-safe handling; no invalid `IntArray?.orEmpty()` / `FloatArray?.orEmpty()` calls remain.

## Default Advanced profile

Designed as a sensible realtime flagship baseline:

- Exposure: Auto
- Android low-light boost: Off
- AE priority: Off
- Anti-banding: Auto
- White balance: Auto
- Focus: Continuous Video
- Video stabilization: Preview stabilization when supported (fallback to Video)
- OIS: OEM
- Hot-pixel correction: Fast
- Lens shading: Fast
- Color correction: Fast
- Edge enhancement: Fast
- Distortion correction: Fast
- Chromatic aberration correction: Fast
- Tonemap: Fast

Noise reduction remains in the main settings and defaults to `Minimal` for this experiment.

## Performance counters

`estimated source drops` compares SurfaceTexture timestamp gaps against the requested frame interval. It is intentionally a diagnostic estimate: if adaptive AE is allowed to lower FPS in the dark, those longer intervals will also appear here.

`encoder stalls` counts encoder `eglSwapBuffers()` calls that blocked longer than one target frame interval, which is a useful indication of GPU/MediaCodec backpressure at high resolution/bitrate.

## Architecture notes

- [`docs/THREADING_REVIEW.md`](docs/THREADING_REVIEW.md) explains which dedicated threads should remain, which orchestration pieces are good coroutine candidates, and why a single dual-camera GL engine is the larger optimization.
- [`docs/LIBRARY_ARCHITECTURE.md`](docs/LIBRARY_ARCHITECTURE.md) defines the proposed reusable AAR boundaries, public recorder API, host responsibilities, sending adapter and overlay/blur integration.
- This revision intentionally does **not** rewrite the realtime GL/AudioRecord hot path to generic coroutine dispatchers. Threading changes should be benchmarked independently from camera/codec tuning.

## v1.3 concurrent 60 fps fix

- Concurrent prewarm no longer pins both camera sessions to the selected recording FPS.
- The inactive concurrent camera is capped at a <=30 fps AE range when the device exposes one.
- When switching cameras, the target is promoted to the selected recording FPS immediately before the GL source is swapped; the previous camera is then demoted back to the prewarm rate.
- Recording performance counters now count only frames while that renderer is attached to the encoder. Inactive prewarm frames no longer inflate source-drop telemetry.
- Renderer performance counters are reset once per recording rather than on every camera switch.


## v1.4 switch handoff fix

- Concurrent standby camera still runs at <=30 fps while a 60 fps recording is active.
- Both sessions declare the requested recording FPS through SessionConfiguration session parameters, avoiding a cold vendor 30->60 session-mode reconfiguration on switch.
- A switch no longer detaches the current preview/encoder immediately after promoting the standby camera. The old camera remains visible/encoded until two capture results confirm the target camera has reached the requested cadence (300 ms safety timeout).
- After the handoff the old camera is demoted back to <=30 fps, so dual 60 fps remains only a short transition window.
