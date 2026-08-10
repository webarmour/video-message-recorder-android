# Video Message Recorder

`video-message-recorder` — Android-библиотека для записи коротких видеосообщений.

Библиотека скрывает низкоуровневую работу с Camera2, OpenGL/EGL, MediaCodec, AudioRecord и MediaMuxer и предоставляет приложению небольшой публичный API:

```text
Host app
   ↓
VideoMessageRecorder
   ↓
Camera2 → SurfaceTexture → OpenGL
                         ├─ Preview
                         └─ H.264 encoder
AudioRecord → AAC encoder
             ↓
          MediaMuxer
             ↓
             MP4
```

Библиотека ориентирована именно на сценарий видеосообщений: квадратный output, 
переключение front/rear во время одной записи, device-aware AUTO-конфигурация, 
управление zoom, опциональная диагностическая telemetry и возможность физически записать круглую маску в MP4.

## Требования

- Android `minSdk 28`
- Java/Kotlin target 17 в текущем модуле
- камера
- микрофон
- runtime permissions `CAMERA` и `RECORD_AUDIO`

Библиотека **не управляет** UI, permission-dialog, хранением готового файла, MediaStore, 
сетью, upload или отправкой сообщения. Это остаётся ответственностью приложения.

## Подключение

### Как локальный Gradle module

Добавить модуль:

```kotlin
// settings.gradle.kts
include(":video-message-recorder")
```

Подключить его:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":video-message-recorder"))
}
```

### Как AAR

Если библиотека передаётся отдельным `.aar`, consumer должен подключить AAR стандартным способом своего проекта.

Пока библиотека не опубликована в Maven-репозитории, Maven coordinates в документации намеренно не указываются.

## Permissions

Manifest библиотеки уже содержит:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

После manifest merge эти permissions попадут в приложение. При желании consumer может продублировать их в manifest приложения для явности.

**Runtime permissions всегда запрашивает host-приложение.**

Перед использованием камеры сообщить библиотеке итог:

```kotlin
recorder.setPermissionGranted(
    cameraGranted && microphoneGranted
)
```

## Минимальное использование

Рекомендуемый режим — `RecordingMode.Auto`.

```kotlin
private lateinit var recorder: VideoMessageRecorder

recorder = VideoMessageRecorder(
    context = applicationContext,
    mode = RecordingMode.Auto,
    diagnosticsMode = DiagnosticsMode.Disabled,
    onState = { state ->
        // CameraUiState.
        // Этот callback публикуется на main thread.
    },
    onRecordingFinished = { result ->
        // RecordingResult.
        // Этот callback приходит с recorder worker thread.
        // Не изменяйте Compose/View UI напрямую без перехода на main thread.
    },
)
```

В обычном приложении можно короче:

```kotlin
recorder = VideoMessageRecorder(
    context = applicationContext,
    onState = ::onRecorderState,
    onRecordingFinished = ::onRecordingFinished,
)
```

`RecordingMode.Auto` и `DiagnosticsMode.Disabled` используются по умолчанию.

## Lifecycle

Библиотека не подписывается на lifecycle автоматически. Consumer должен явно передавать события:

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

`close()` — терминальная операция. После неё этот экземпляр recorder повторно использовать нельзя.

Для Compose обычно используется `DisposableEffect` + `LifecycleEventObserver`.

## Preview

Библиотеке нужен обычный Android `Surface`.

Пример с `TextureView`:

```kotlin
textureView.surfaceTextureListener =
    object : TextureView.SurfaceTextureListener {

        private var previewSurface: Surface? = null

        override fun onSurfaceTextureAvailable(
            surfaceTexture: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            val surface = Surface(surfaceTexture)
            previewSurface = surface

            recorder.attachPreview(
                surface = surface,
                width = width,
                height = height,
                displayRotation = textureView.display?.rotation
                    ?: Surface.ROTATION_0,
            )
        }

        override fun onSurfaceTextureSizeChanged(
            surfaceTexture: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            val surface = previewSurface ?: return

            recorder.attachPreview(
                surface = surface,
                width = width,
                height = height,
                displayRotation = textureView.display?.rotation
                    ?: Surface.ROTATION_0,
            )
        }

        override fun onSurfaceTextureDestroyed(
            surfaceTexture: SurfaceTexture,
        ): Boolean {
            recorder.detachPreview()

            previewSurface?.release()
            previewSurface = null

            return true
        }

        override fun onSurfaceTextureUpdated(
            surfaceTexture: SurfaceTexture,
        ) = Unit
    }
```

Форму preview задаёт host. Например, для видеокружка в Compose preview можно визуально обрезать:

```kotlin
Modifier.clip(CircleShape)
```

Это не меняет MP4-файл.

## Запись

Следить за:

```kotlin
state.cameraReady
```

и начинать запись после готовности камеры:

```kotlin
if (state.cameraReady && !state.isRecording) {
    recorder.startRecording()
}
```

Остановить:

```kotlin
recorder.stopRecording()
```

Переключить front/rear:

```kotlin
recorder.switchCamera()
```

Изменить zoom:

```kotlin
recorder.updateZoomRatio(
    zoomRatio = 1.5f
)
```

Есть convenience API:

```kotlin
recorder.toggleRecording()
```

но для production UI предпочтительнее явно использовать `startRecording()` и `stopRecording()`.

## Результат

После успешной финализации вызывается:

```kotlin
onRecordingFinished = { result ->
    // result.file
    // result.baseName
    // result.telemetryCsv
    // result.config
}
```

`RecordingResult` содержит:

```kotlin
data class RecordingResult(
    val file: File,
    val baseName: String,
    val telemetryCsv: String?,
    val config: RecordingConfig,
)
```

### `file`

Готовый MP4 во временном/cache storage host-приложения.

После callback файл принадлежит consumer-приложению. Оно должно:

- загрузить его на backend;
- либо скопировать/переместить в постоянное хранилище;
- либо удалить, если запись отменена или больше не нужна.

Пример upload:

```kotlin
onRecordingFinished = { result ->
    uploadVideo(
        file = result.file,
    )
}
```

Пример отмены:

```kotlin
var discardResult = false

fun cancelRecording() {
    discardResult = true
    recorder.stopRecording()
}

val recorder = VideoMessageRecorder(
    context = applicationContext,
    onRecordingFinished = { result ->
        if (discardResult) {
            result.file.delete()
            discardResult = false
        } else {
            uploadVideo(result.file)
        }
    },
)
```

### `baseName`

Стабильное имя текущей записи без необходимости генерировать его в host-приложении.

### `telemetryCsv`

- `null`, если diagnostics выключены;
- CSV telemetry, если включён `DiagnosticsMode.TelemetryCsv`.

### `config`

Фактический `RecordingConfig`, с которым была создана запись. Особенно полезно в `Auto`, чтобы понимать выбранные библиотекой resolution/FPS/bitrate.

## AUTO и Custom

### AUTO — рекомендуется

```kotlin
VideoMessageRecorder(
    context = context,
    mode = RecordingMode.Auto,
)
```

AUTO подбирает совместимые параметры устройства и старается использовать наиболее качественный профиль, который камера и encoder могут стабильно поддерживать.

Consumer не должен предполагать, что AUTO всегда даст `640x640 @ 60 FPS`. На более слабом устройстве библиотека может выбрать 30 FPS или более консервативный профиль.

### Custom

Если приложению нужны фиксированные параметры:

```kotlin
VideoMessageRecorder(
    context = context,
    mode = RecordingMode.Custom(
        RecordingConfig(
            quality = VideoQuality.TELEGRAM_NOTE_MAX,
            frameRate = 30,
            videoBitrate = 1_500_000,
            holdFpsInLowLight = false,
            circleMaskInSavedVideo = false,
        )
    ),
)
```

Также конфигурацию можно обновить:

```kotlin
recorder.updateConfig(
    RecordingConfig(
        quality = VideoQuality.HIGH,
        frameRate = 30,
    )
)
```

или переключить режим:

```kotlin
recorder.updateMode(
    RecordingMode.Auto
)
```

В `Custom` consumer сам отвечает за реалистичность параметров. Например, `60 FPS + holdFpsInLowLight=true` может быть недоступен на конкретной камере.

## Diagnostics

По умолчанию diagnostics выключены:

```kotlin
VideoMessageRecorder(
    context = context,
    diagnosticsMode = DiagnosticsMode.Disabled,
)
```

Для тестовой сборки:

```kotlin
VideoMessageRecorder(
    context = context,
    diagnosticsMode = DiagnosticsMode.TelemetryCsv,
)
```

или во время жизни recorder:

```kotlin
recorder.setDiagnosticsMode(
    DiagnosticsMode.TelemetryCsv
)
```

При `Disabled` библиотека не должна создавать CSV telemetry. В `RecordingResult`:

```kotlin
result.telemetryCsv == null
```

## Квадратное и круглое видео

Все текущие `VideoQuality` используют квадратный output:

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

Камера обычно отдаёт прямоугольный source. GL renderer делает center-crop до квадратного output.

### Рекомендуемый вариант

Хранить обычный квадратный MP4:

```kotlin
circleMaskInSavedVideo = false
```

а круг делать в UI:

```kotlin
Modifier.clip(CircleShape)
```

### Физическая круглая маска в MP4

Если backend/consumer не умеет показывать квадратное видео как круг:

```kotlin
RecordingConfig(
    circleMaskInSavedVideo = true,
)
```

Маска применяется GL renderer ещё до H.264 encoding.

Важно: H.264 MP4 не содержит прозрачные углы. Файл всё равно остаётся квадратным MP4, а область за кругом физически закрашивается маской. Поэтому это fallback, а не замена правильному circular UI.

## Ответственность host-приложения

Host должен управлять:

1. runtime permissions;
2. lifecycle;
3. preview `Surface`;
4. UI и формой preview;
5. кнопками start/stop/cancel/switch;
6. сохранением или upload готового MP4;
7. удалением временного файла;
8. переходом на main thread из `onRecordingFinished`, если нужно менять UI.

Библиотека управляет:

- Camera2;
- CameraCaptureSession;
- выбором front/rear camera;
- camera prewarm там, где он доступен;
- OpenGL/EGL rendering;
- square center-crop;
- preview transform/mirroring;
- H.264 MediaCodec encoder;
- AAC audio;
- audio/video timestamps;
- MediaMuxer;
- camera switching во время записи;
- optional telemetry.

## API 28+

На API 28–29 часть новых Camera2 возможностей отсутствует. Библиотека использует compatibility paths, например crop-region zoom вместо новых zoom-ratio API.

Concurrent front/rear prewarm зависит от возможностей Android/device. Если одновременная работа камер недоступна, библиотека использует single-camera switching path.

Consumer не должен ветвить UI по Camera2 API самостоятельно — это внутренняя ответственность библиотеки.

## Полная документация

См. [`docs/INTEGRATION.md`](docs/INTEGRATION.md).
