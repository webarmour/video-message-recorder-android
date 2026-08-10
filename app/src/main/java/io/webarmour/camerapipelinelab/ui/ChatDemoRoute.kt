package io.webarmour.camerapipelinelab.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.webarmour.camerapipelinelab.storage.VideoStore

enum class VideoMessageRecorderBackground {
    Transparent,
    Blurred,
}

@Composable
fun ChatDemoRoute() {
    val context = LocalContext.current

    var messages by remember {
        mutableStateOf(
            listOf(
                "Привет",
                "Тестируем запись видеосообщений",
                "Удерживай ● для записи",
            )
        )
    }

    var messageText by remember {
        mutableStateOf("")
    }

    var recordingStatus by remember {
        mutableStateOf<String?>(null)
    }

    VideoMessageChatHost(
        recorderBackground = VideoMessageRecorderBackground.Blurred,

        onVideoRecorded = { result ->
            runCatching {
                VideoStore.save(
                    context = context.applicationContext,
                    videoFile = result.file,
                    baseName = result.baseName,
                    telemetryCsv = result.telemetryCsv,
                )

                messages =
                    messages + "Видеосообщение записано"

                recordingStatus =
                    "Видео: ${result.file.name}"
            }.onFailure { error ->
                Log.e(
                    "ChatDemo",
                    "Unable to save video message",
                    error,
                )

                recordingStatus =
                    "Ошибка сохранения: ${error.message}"
            }
        },

        onPermissionDenied = {
            recordingStatus =
                "Для записи нужны Camera и Microphone permissions"
        },
    ) {
            onVideoPressStart,
            onVideoPressRelease,
            onVideoPressCancel,
        ->

        ChatDemoScreen(
            messages = messages,
            messageText = messageText,
            recordingStatus = recordingStatus,

            onMessageTextChange = {
                messageText = it
            },

            onSendText = {
                val text = messageText.trim()

                if (text.isNotEmpty()) {
                    messages = messages + text
                    messageText = ""
                }
            },

            onVideoPressStart = onVideoPressStart,
            onVideoPressRelease = onVideoPressRelease,
            onVideoPressCancel = onVideoPressCancel,
        )
    }
}