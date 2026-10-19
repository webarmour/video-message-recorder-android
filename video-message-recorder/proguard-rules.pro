# Keep the source-level API stable for Java and Kotlin consumers of the AAR.
# Internal camera, GL, and media implementation classes remain open to R8.
-keep,allowoptimization class io.github.webarmour.videomessagerecorder.DiagnosticsMode { public *; }
-keep,allowoptimization class io.github.webarmour.videomessagerecorder.RecordingMode* { public *; }
-keep,allowoptimization class io.github.webarmour.videomessagerecorder.RecordingResult { public *; }
-keep,allowoptimization class io.github.webarmour.videomessagerecorder.VideoMessageRecorder { public *; }

-keep,allowoptimization class io.github.webarmour.videomessagerecorder.camera.CameraLiveMetrics { public *; }
-keep,allowoptimization class io.github.webarmour.videomessagerecorder.camera.CameraCapabilities { public *; }
-keep,allowoptimization class io.github.webarmour.videomessagerecorder.camera.CameraUiState { public *; }
-keep,allowoptimization class io.github.webarmour.videomessagerecorder.camera.RecordingConfig { public *; }
-keep,allowoptimization enum io.github.webarmour.videomessagerecorder.camera.* { public *; }
