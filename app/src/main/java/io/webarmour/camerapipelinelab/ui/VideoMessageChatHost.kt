package io.webarmour.camerapipelinelab.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.webarmour.videomessagerecorder.RecordingResult

@Composable
fun VideoMessageChatHost(
    onVideoRecorded: (RecordingResult) -> Unit,
    modifier: Modifier = Modifier,
    recorderBackground: VideoMessageRecorderBackground =
        VideoMessageRecorderBackground.Blurred,
    onPermissionDenied: () -> Unit = {},
    content: @Composable (
        onVideoPressStart: () -> Unit,
        onVideoPressRelease: () -> Unit,
        onVideoPressCancel: () -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var showRecorder by remember {
        mutableStateOf(false)
    }

    var sendRequested by remember {
        mutableStateOf(false)
    }

    var cancelRequested by remember {
        mutableStateOf(false)
    }

    val permissions = remember {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    fun hasPermissions(): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (!hasPermissions()) {
            onPermissionDenied()
        }

        /*
         * После permission dialog запись автоматически не запускаем.
         * Пользователь должен снова нажать и удерживать кнопку.
         */
    }

    val startRecordingGesture = {
        focusManager.clearFocus(
            force = true
        )

        keyboardController?.hide()

        if (hasPermissions()) {
            sendRequested = false
            cancelRequested = false
            showRecorder = true
        } else {
            permissionLauncher.launch(
                permissions
            )
        }
    }

    val finishRecordingGesture = {
        if (showRecorder) {
            sendRequested = true
        }
    }

    val cancelRecordingGesture = {
        if (showRecorder) {
            cancelRequested = true
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        val contentModifier = when {
            showRecorder &&
                    recorderBackground == VideoMessageRecorderBackground.Blurred &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {

                Modifier
                    .fillMaxSize()
                    .blur(20.dp)
            }

            else -> {
                Modifier.fillMaxSize()
            }
        }

        Box(
            modifier = contentModifier,
        ) {
            content(
                startRecordingGesture,
                finishRecordingGesture,
                cancelRecordingGesture,
            )
        }

        if (showRecorder) {
            VideoMessageRecordingOverlay(
                background = recorderBackground,
                sendRequested = sendRequested,
                cancelRequested = cancelRequested,

                onRecorded = { result ->
                    showRecorder = false
                    sendRequested = false
                    cancelRequested = false

                    onVideoRecorded(
                        result
                    )
                },

                onDismiss = {
                    showRecorder = false
                    sendRequested = false
                    cancelRequested = false
                },
            )
        }
    }
}