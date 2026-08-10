package io.webarmour.camerapipelinelab.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.webarmour.videomessagerecorder.RecordingResult
import io.github.webarmour.videomessagerecorder.VideoMessageRecorder
import io.github.webarmour.videomessagerecorder.camera.CameraUiState

@Composable
fun VideoMessageRecordingOverlay(
    sendRequested: Boolean,
    cancelRequested: Boolean,
    onRecorded: (RecordingResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    background: VideoMessageRecorderBackground =
        VideoMessageRecorderBackground.Blurred,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentOnRecorded by rememberUpdatedState(
        onRecorded
    )

    val currentOnDismiss by rememberUpdatedState(
        onDismiss
    )

    val mainHandler = remember {
        Handler(
            Looper.getMainLooper()
        )
    }

    var uiState by remember {
        mutableStateOf(
            CameraUiState()
        )
    }

    var recordingStarted by remember {
        mutableStateOf(false)
    }

    var finishAction by remember {
        mutableStateOf<FinishAction?>(
            null
        )
    }

    var waitingForResult by remember {
        mutableStateOf(false)
    }

    val recorder = remember {
        VideoMessageRecorder(
            context = context.applicationContext,

            onState = { state ->
                mainHandler.post {
                    uiState = state
                }
            },

            onRecordingFinished = { result ->
                mainHandler.post {
                    waitingForResult = false

                    when (finishAction) {
                        FinishAction.Send -> {
                            currentOnRecorded(
                                result
                            )
                        }

                        FinishAction.Cancel -> {
                            result.file.delete()

                            currentOnDismiss()
                        }

                        null -> {
                            result.file.delete()

                            currentOnDismiss()
                        }
                    }
                }
            },
        )
    }

    DisposableEffect(
        recorder,
        lifecycleOwner,
    ) {
        recorder.setPermissionGranted(
            true
        )

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    recorder.onStart()
                }

                Lifecycle.Event.ON_STOP -> {
                    recorder.onStop()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(
            observer
        )

        if (
            lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        ) {
            recorder.onStart()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(
                observer
            )

            recorder.onStop()
            recorder.close()
        }
    }

    /*
     * Камера готова → сразу начинаем запись.
     */
    LaunchedEffect(
        uiState.cameraReady,
        recordingStarted,
    ) {
        if (
            uiState.cameraReady &&
            !recordingStarted &&
            finishAction == null
        ) {
            recordingStarted = true

            recorder.startRecording()
        }
    }

    /*
     * Палец отпущен.
     *
     * Если MediaCodec уже реально пишет — завершаем запись.
     *
     * Если пользователь отпустил кнопку быстрее, чем камера успела
     * подготовиться, sendRequested останется true. После перехода
     * uiState.isRecording в true этот effect вызовется снова и запись
     * корректно завершится.
     */
    LaunchedEffect(
        sendRequested,
        uiState.isRecording,
        waitingForResult,
    ) {
        if (
            sendRequested &&
            uiState.isRecording &&
            !waitingForResult &&
            finishAction == null
        ) {
            finishAction = FinishAction.Send
            waitingForResult = true

            recorder.stopRecording()
        }
    }

    /*
     * Gesture был отменён системой или пользователь явно нажал Cancel.
     */
    LaunchedEffect(
        cancelRequested,
        uiState.isRecording,
        waitingForResult,
    ) {
        if (
            cancelRequested &&
            !waitingForResult &&
            finishAction == null
        ) {
            finishAction = FinishAction.Cancel

            if (uiState.isRecording) {
                waitingForResult = true

                recorder.stopRecording()
            } else {
                currentOnDismiss()
            }
        }
    }

    fun cancelRecording() {
        if (waitingForResult) {
            return
        }

        finishAction = FinishAction.Cancel

        if (uiState.isRecording) {
            waitingForResult = true

            recorder.stopRecording()
        } else {
            currentOnDismiss()
        }
    }

    BackHandler(
        enabled = true,
        onBack = ::cancelRecording,
    )

    val overlayColor = when (background) {
        VideoMessageRecorderBackground.Transparent -> {
            Color.Transparent
        }

        VideoMessageRecorderBackground.Blurred -> {
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                Color.Black.copy(
                    alpha = 0.12f
                )
            } else {
                Color.Black.copy(
                    alpha = 0.32f
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                overlayColor
            ),
    ) {
        Column(
            modifier = Modifier.align(
                Alignment.Center
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                CircularVideoMessagePreview(
                    modifier = Modifier.size(
                        280.dp
                    ),
                    onPreviewAvailable = recorder::attachPreview,
                    onPreviewDestroyed = recorder::detachPreview,
                )

                if (!uiState.cameraReady) {
                    CircularProgressIndicator()
                }
            }

            Spacer(
                modifier = Modifier.height(
                    24.dp
                )
            )

            Text(
                text = when {
                    waitingForResult -> {
                        "Отправка..."
                    }

                    uiState.isRecording -> {
                        "Отпустите для отправки"
                    }

                    else -> {
                        "Подготовка камеры..."
                    }
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )

            Spacer(
                modifier = Modifier.height(
                    24.dp
                )
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    12.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !waitingForResult,
                    onClick = ::cancelRecording,
                ) {
                    Text(
                        text = "Отмена"
                    )
                }

                Button(
                    enabled =
                        uiState.cameraReady &&
                                !uiState.isSwitchingCamera &&
                                !waitingForResult,
                    onClick = {
                        recorder.switchCamera()
                    },
                ) {
                    Text(
                        text = "Камера"
                    )
                }
            }
        }
    }
}

private enum class FinishAction {
    Send,
    Cancel,
}