# Video Message Recorder for Android

[English](#english) · [Русский](#русский)

Android library for recording short in-app video messages.  
Built on Camera2, OpenGL/EGL, MediaCodec, AudioRecord and MediaMuxer.

The repository contains:

- [`app`](app) — minimal demo UI and integration example.
- [`video-message-recorder`](video-message-recorder) — reusable Android library module.
- [`docs/INTEGRATION.md`](docs/INTEGRATION.md) — detailed integration guide.

---

# English

## Features / use cases

Use the library when you need video messages inside a chat, messenger, social app or another in-app flow.

Supported scenarios:

- square video recording with front or rear camera;
- switching front/rear camera during an active recording;
- zoom during recording;
- automatic device-aware recording configuration;
- fully custom recording configuration when required;
- circular preview in the host UI;
- optional circular mask baked into the saved MP4;
- optional CSV diagnostics/telemetry.

The library owns the camera/encoding pipeline. The host application owns UI, runtime permissions, file storage, upload and message sending.

**Requirements:** Android API 28+, `CAMERA` and `RECORD_AUDIO` runtime permissions.

## Installation

### 1. Local Gradle module

For development or when the module is included directly in your project:

```kotlin
// settings.gradle.kts
include(":video-message-recorder")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":video-message-recorder"))
}
```

### 2. AAR

Build the release AAR:

```bash
./gradlew :video-message-recorder:assembleRelease
```

The artifact is created in:

```text
video-message-recorder/build/outputs/aar/
```

Copy it to the consumer project, for example:

```text
app/libs/video-message-recorder-release.aar
```

and add:

```kotlin
dependencies {
    implementation(files("libs/video-message-recorder-release.aar"))
}
```

### 3. Maven Local

Useful for testing the packaged library from a separate Android project:

```bash
./gradlew :video-message-recorder:publishToMavenLocal
```

Add `mavenLocal()` to the consumer project:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
```

Then:

```kotlin
dependencies {
    implementation(
        "io.github.webarmour:video-message-recorder:0.0.1"
    )
}
```

> The library is not published to a public Maven repository yet. The coordinate above currently works with `mavenLocal()` after local publication.

## Quick start

Create the recorder. `RecordingMode.Auto` is the recommended default:

```kotlin
val recorder = VideoMessageRecorder(
    context = context.applicationContext,
    mode = RecordingMode.Auto,
    onState = { state ->
        if (state.cameraReady) {
            // Recorder is ready.
        }
    },
    onRecordingFinished = { result ->
        val videoFile = result.file

        // Upload, move, copy or delete the MP4.
    },
)
```

Pass the permission result:

```kotlin
recorder.setPermissionGranted(
    cameraGranted && microphoneGranted
)
```

Forward lifecycle events:

```kotlin
recorder.onStart()
recorder.onStop()
recorder.close()
```

Attach your preview `Surface`:

```kotlin
recorder.attachPreview(
    surface = surface,
    width = width,
    height = height,
    displayRotation = displayRotation,
)
```

Recording controls:

```kotlin
recorder.startRecording()
recorder.stopRecording()

recorder.switchCamera()
recorder.updateZoomRatio(1.5f)
```

The result is returned through `onRecordingFinished`:

```kotlin
result.file          // finalized MP4
result.baseName      // generated recording name
result.config        // actual RecordingConfig
result.telemetryCsv  // null unless telemetry is enabled
```

### Custom configuration

Use AUTO unless the application explicitly needs fixed parameters:

```kotlin
val recorder = VideoMessageRecorder(
    context = context,
    mode = RecordingMode.Custom(
        RecordingConfig(
            quality = VideoQuality.TELEGRAM_NOTE_MAX,
            frameRate = 30,
            videoBitrate = 1_500_000,
            circleMaskInSavedVideo = false,
        )
    ),
)
```

See [`docs/INTEGRATION.md`](docs/INTEGRATION.md) for the complete API and configuration options.

---

# Русский

## Возможности / сценарии использования

Библиотека предназначена для видеосообщений внутри чатов, мессенджеров, социальных приложений и других встроенных сценариев записи видео.

Поддерживаются:

- запись квадратного видео с фронтальной или основной камеры;
- переключение front/rear камеры во время активной записи;
- zoom во время записи;
- автоматический подбор параметров под возможности устройства;
- ручная настройка параметров записи;
- круглый preview на стороне приложения;
- опциональная круглая маска непосредственно в сохранённом MP4;
- опциональная CSV-диагностика/telemetry.

Библиотека управляет камерой и encoding pipeline. UI, runtime permissions, хранение файла, upload и отправка сообщения остаются на стороне приложения.

**Требования:** Android API 28+, runtime permissions `CAMERA` и `RECORD_AUDIO`.

## Подключение

### 1. Локальный Gradle module

Для разработки или подключения исходного модуля напрямую:

```kotlin
// settings.gradle.kts
include(":video-message-recorder")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":video-message-recorder"))
}
```

### 2. AAR

Собрать release AAR:

```bash
./gradlew :video-message-recorder:assembleRelease
```

Файл появится в:

```text
video-message-recorder/build/outputs/aar/
```

Скопировать его в consumer-проект, например:

```text
app/libs/video-message-recorder-release.aar
```

и подключить:

```kotlin
dependencies {
    implementation(files("libs/video-message-recorder-release.aar"))
}
```

### 3. Maven Local

Удобно для проверки упакованной библиотеки из отдельного тестового Android-проекта:

```bash
./gradlew :video-message-recorder:publishToMavenLocal
```

В consumer-проекте добавить:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
```

и dependency:

```kotlin
dependencies {
    implementation(
        "io.github.webarmour:video-message-recorder:0.0.1"
    )
}
```

> Сейчас библиотека ещё не опубликована в публичном Maven-репозитории. Указанная dependency работает через `mavenLocal()` после локальной публикации.

## Быстрый старт

Рекомендуемый режим — `RecordingMode.Auto`:

```kotlin
val recorder = VideoMessageRecorder(
    context = context.applicationContext,
    mode = RecordingMode.Auto,
    onState = { state ->
        if (state.cameraReady) {
            // Камера готова к записи.
        }
    },
    onRecordingFinished = { result ->
        val videoFile = result.file

        // Загрузить, переместить, скопировать
        // или удалить готовый MP4.
    },
)
```

Передать результат runtime permissions:

```kotlin
recorder.setPermissionGranted(
    cameraGranted && microphoneGranted
)
```

Передавать lifecycle:

```kotlin
recorder.onStart()
recorder.onStop()
recorder.close()
```

Передать `Surface` для preview:

```kotlin
recorder.attachPreview(
    surface = surface,
    width = width,
    height = height,
    displayRotation = displayRotation,
)
```

Управление записью:

```kotlin
recorder.startRecording()
recorder.stopRecording()

recorder.switchCamera()
recorder.updateZoomRatio(1.5f)
```

Готовый результат приходит в `onRecordingFinished`:

```kotlin
result.file          // готовый MP4
result.baseName      // имя записи
result.config        // фактически использованный RecordingConfig
result.telemetryCsv  // null, если telemetry выключена
```

### Свои параметры записи

AUTO рекомендуется для обычного использования. `Custom` нужен, когда приложению действительно необходимы конкретные параметры:

```kotlin
val recorder = VideoMessageRecorder(
    context = context,
    mode = RecordingMode.Custom(
        RecordingConfig(
            quality = VideoQuality.TELEGRAM_NOTE_MAX,
            frameRate = 30,
            videoBitrate = 1_500_000,
            circleMaskInSavedVideo = false,
        )
    ),
)
```

Полное описание API и параметров находится в [`docs/INTEGRATION.md`](docs/INTEGRATION.md).
