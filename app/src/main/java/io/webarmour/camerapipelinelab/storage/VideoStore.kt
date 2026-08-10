package io.webarmour.camerapipelinelab.storage


import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

internal object VideoStore {

    data class SavedRecording(
        val videoUri: Uri,
        val telemetryUri: Uri?,
    )

    fun save(
        context: Context,
        videoFile: File,
        baseName: String,
        telemetryCsv: String?,
    ): SavedRecording {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            SavedRecording(
                videoUri =
                    saveVideoToMediaStore(
                        context = context,
                        source = videoFile,
                        displayName = "$baseName.mp4",
                    ),
                telemetryUri =
                    telemetryCsv?.let { csv ->
                        runCatching {
                            saveTextToMediaStore(
                                context = context,
                                text = csv,
                                displayName =
                                    "${baseName}_capture.csv",
                            )
                        }.getOrNull()
                    },
            )
        } else {
            saveToAppExternalStorage(
                context = context,
                videoFile = videoFile,
                baseName = baseName,
                telemetryCsv = telemetryCsv,
            )
        }
    }

    @RequiresApi(
        Build.VERSION_CODES.Q
    )
    private fun saveVideoToMediaStore(
        context: Context,
        source: File,
        displayName: String,
    ): Uri {
        val resolver =
            context.contentResolver

        val values =
            ContentValues().apply {
                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    displayName,
                )

                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4",
                )

                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/CameraPipelineLab",
                )

                put(
                    MediaStore.Video.Media.IS_PENDING,
                    1,
                )
            }

        val uri =
            checkNotNull(
                resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values,
                )
            ) {
                "Unable to create MediaStore video item"
            }

        try {
            resolver
                .openOutputStream(
                    uri,
                    "w",
                )
                .use { output ->

                    checkNotNull(output) {
                        "Unable to open MediaStore video output stream"
                    }

                    source
                        .inputStream()
                        .use { input ->
                            input.copyTo(
                                output
                            )
                        }
                }

            resolver.update(
                uri,
                ContentValues().apply {
                    put(
                        MediaStore.Video.Media.IS_PENDING,
                        0,
                    )
                },
                null,
                null,
            )

            return uri
        } catch (error: Throwable) {
            resolver.delete(
                uri,
                null,
                null,
            )

            throw error
        } finally {
            source.delete()
        }
    }

    @RequiresApi(
        Build.VERSION_CODES.Q
    )
    private fun saveTextToMediaStore(
        context: Context,
        text: String,
        displayName: String,
    ): Uri {
        val resolver =
            context.contentResolver

        val values =
            ContentValues().apply {
                put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    displayName,
                )

                put(
                    MediaStore.Downloads.MIME_TYPE,
                    "text/csv",
                )

                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/CameraPipelineLab",
                )

                put(
                    MediaStore.Downloads.IS_PENDING,
                    1,
                )
            }

        val uri =
            checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                )
            ) {
                "Unable to create MediaStore telemetry item"
            }

        try {
            val output =
                checkNotNull(
                    resolver.openOutputStream(
                        uri,
                        "w",
                    )
                ) {
                    "Unable to open MediaStore telemetry output stream"
                }

            output
                .bufferedWriter()
                .use { writer ->
                    writer.write(
                        text
                    )
                }

            resolver.update(
                uri,
                ContentValues().apply {
                    put(
                        MediaStore.Downloads.IS_PENDING,
                        0,
                    )
                },
                null,
                null,
            )

            return uri
        } catch (error: Throwable) {
            resolver.delete(
                uri,
                null,
                null,
            )

            throw error
        }
    }

    private fun saveToAppExternalStorage(
        context: Context,
        videoFile: File,
        baseName: String,
        telemetryCsv: String?,
    ): SavedRecording {
        val moviesRoot =
            context.getExternalFilesDir(
                Environment.DIRECTORY_MOVIES
            )
                ?: File(
                    context.filesDir,
                    "movies",
                )

        val videoDir =
            File(
                moviesRoot,
                "CameraPipelineLab",
            ).apply {
                mkdirs()
            }

        val targetVideo =
            File(
                videoDir,
                "$baseName.mp4",
            )

        videoFile.copyTo(
            target = targetVideo,
            overwrite = true,
        )

        videoFile.delete()

        val telemetryUri =
            telemetryCsv?.let { csv ->

                val downloadsRoot =
                    context.getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                        ?: File(
                            context.filesDir,
                            "downloads",
                        )

                val telemetryDir =
                    File(
                        downloadsRoot,
                        "CameraPipelineLab",
                    ).apply {
                        mkdirs()
                    }

                runCatching {
                    File(
                        telemetryDir,
                        "${baseName}_capture.csv",
                    ).apply {
                        writeText(
                            csv
                        )
                    }
                }
                    .getOrNull()
                    ?.let(
                        Uri::fromFile
                    )
            }

        return SavedRecording(
            videoUri =
                Uri.fromFile(
                    targetVideo
                ),
            telemetryUri =
                telemetryUri,
        )
    }
}
