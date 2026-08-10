# Changes

## 1. Camera switch latency

- Inactive concurrent camera still stays at `<= 30 fps`, so two full 60 fps pipelines are not kept running.
- A 30 -> target-FPS promotion no longer waits for AE/AWB reconvergence.
- Promotion now needs one confirmed frame with the requested FPS range/cadence instead of two 3A-stable frames.
- The 300 ms promotion timeout remains as a defensive fallback and is still reported through telemetry.

## 2. minSdk 28

- `:app` and `:video-message-recorder` use `minSdk = 28`.
- Concurrent-camera API is used only on API 30+.
- Zoom uses `CONTROL_ZOOM_RATIO` on API 30+ and `SCALER_CROP_REGION` on API 28-29.
- API 33+ `OutputConfiguration` tuning is version guarded; readout timestamps remain API 34+ only.
- API 29+ encoder capability/tuning fields are version guarded; API 28 falls back to conservative AVC configuration with no requested B-frames.
- API 30+ capture zoom telemetry is version guarded.
- API 29+ MediaStore storage stays unchanged; API 28 sample storage falls back to app-specific external directories without legacy storage permission.
- Newer Camera2 enum values used by configuration are represented by compile-time integer values so the config classes can load on API 28.
- Rear-camera discovery now prefers the logical rear camera and otherwise picks the physical lens closest to a normal ~26 mm-equivalent main camera instead of relying on camera ID ordering.

## 3. Reusable recorder library

Added `:video-message-recorder` (`com.android.library`).

The module contains:

- Camera2 controller and compatibility code;
- GL/EGL camera renderer;
- video/audio MediaCodec encoders;
- MediaMuxer;
- capture telemetry;
- recording configuration/state models;
- public `VideoMessageRecorder` facade;
- public `RecordingResult` containing the finalized cache MP4.

The library does not depend on Compose or the sample app. Permanent storage/upload/sending remains host-owned.

The `:app` module now contains only the laboratory UI, permission UX and `VideoStore`, and integrates the recorder through:

```kotlin
implementation(project(":video-message-recorder"))
```

See `video-message-recorder/README.md` for a minimal integration example.

## Validation performed in this environment

- resource XML parsing;
- library resource-reference consistency;
- stale package/import scan after module extraction;
- Kotlin parser pass with no syntax errors;
- project-symbol resolution scan for the moved recorder classes.

A real Android Gradle build was not run because the uploaded project does not contain `gradle/wrapper/gradle-wrapper.jar` and this environment has no Android SDK. The existing wrapper properties still target Gradle 8.13.
