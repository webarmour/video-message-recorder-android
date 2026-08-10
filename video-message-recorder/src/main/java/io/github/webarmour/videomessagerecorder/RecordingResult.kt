package io.github.webarmour.videomessagerecorder

import io.github.webarmour.videomessagerecorder.camera.RecordingConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Finalized MP4 produced by [io.github.webarmour.videomessagerecorder.VideoMessageRecorder].
 *
 * The file lives in the host application's cache directory. The host owns it after this callback:
 * upload it, move/copy it to permanent storage, or delete it when it is no longer needed.
 */
data class RecordingResult(
    val file: File,
    val baseName: String,
    val telemetryCsv: String?,
    val config: RecordingConfig,
)

internal fun newRecordingBaseName(): String =
    "VideoMessage_${LocalDateTime.now().format(FILE_TIMESTAMP)}"

private val FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
