package io.github.webarmour.videomessagerecorder.camera

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import io.github.webarmour.videomessagerecorder.DiagnosticsMode

internal class CaptureTelemetry {

    private data class Row(
        val cameraLabel: String,
        val frameNumber: Long,
        val sensorTimestampNs: Long?,
        val exposureTimeNs: Long?,
        val frameDurationNs: Long?,
        val iso: Int?,
        val aeState: Int?,
        val aeMode: Int?,
        val aePriorityMode: Int?,
        val lowLightBoostState: Int?,
        val awbState: Int?,
        val stabilizationMode: Int?,
        val noiseReductionMode: Int?,
        val focalLength: Float?,
        val zoomRatio: Float?,
    )

    private val rows = ArrayList<Row>(1_024)

    private var mode: DiagnosticsMode =
        DiagnosticsMode.Disabled

    private var collecting = false

    @Synchronized
    fun setMode(
        mode: DiagnosticsMode,
    ) {
        this.mode = mode

        if (mode == DiagnosticsMode.Disabled) {
            collecting = false
            rows.clear()
        }
    }

    @Synchronized
    fun start() {
        rows.clear()

        collecting =
            mode == DiagnosticsMode.TelemetryCsv
    }

    @Synchronized
    fun stop() {
        collecting = false
    }

    @Synchronized
    fun onCapture(
        result: TotalCaptureResult,
        cameraLabel: String,
        noiseReductionMode: Int?,
    ) {
        if (!collecting) {
            return
        }

        rows += Row(
            cameraLabel = cameraLabel,
            frameNumber = result.frameNumber,
            sensorTimestampNs =
                result.get(
                    CaptureResult.SENSOR_TIMESTAMP
                ),
            exposureTimeNs =
                result.get(
                    CaptureResult.SENSOR_EXPOSURE_TIME
                ),
            frameDurationNs =
                result.get(
                    CaptureResult.SENSOR_FRAME_DURATION
                ),
            iso =
                result.get(
                    CaptureResult.SENSOR_SENSITIVITY
                ),
            aeState =
                result.get(
                    CaptureResult.CONTROL_AE_STATE
                ),
            aeMode =
                result.get(
                    CaptureResult.CONTROL_AE_MODE
                ),
            aePriorityMode =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.BAKLAVA
                ) {
                    result.get(
                        CaptureResult.CONTROL_AE_PRIORITY_MODE
                    )
                } else {
                    null
                },
            lowLightBoostState =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.VANILLA_ICE_CREAM
                ) {
                    result.get(
                        CaptureResult.CONTROL_LOW_LIGHT_BOOST_STATE
                    )
                } else {
                    null
                },
            awbState =
                result.get(
                    CaptureResult.CONTROL_AWB_STATE
                ),
            stabilizationMode =
                result.get(
                    CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE
                ),
            noiseReductionMode =
                noiseReductionMode,
            focalLength =
                result.get(
                    CaptureResult.LENS_FOCAL_LENGTH
                ),
            zoomRatio =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.R
                ) {
                    result.get(
                        CaptureResult.CONTROL_ZOOM_RATIO
                    )
                } else {
                    null
                },
        )
    }

    @Synchronized
    fun toCsv(
        extraHeader: () -> List<String> = {
            emptyList()
        },
    ): String? {
        if (mode != DiagnosticsMode.TelemetryCsv) {
            return null
        }

        return buildString {
            appendLine(
                "# CameraPipelineLab capture telemetry"
            )

            extraHeader().forEach { line ->
                appendLine(
                    "# $line"
                )
            }

            appendLine(
                "camera,frameNumber,sensorTimestampNs,exposureTimeNs,frameDurationNs,iso,aeState," +
                        "aeMode,aePriorityMode,lowLightBoostState,awbState,stabilizationMode,noiseReductionMode," +
                        "focalLength,zoomRatio"
            )

            rows.forEach { row ->
                append(
                    csv(row.cameraLabel)
                )
                append(',')
                append(row.frameNumber)
                append(',')
                append(
                    value(row.sensorTimestampNs)
                )
                append(',')
                append(
                    value(row.exposureTimeNs)
                )
                append(',')
                append(
                    value(row.frameDurationNs)
                )
                append(',')
                append(
                    value(row.iso)
                )
                append(',')
                append(
                    value(row.aeState)
                )
                append(',')
                append(
                    value(row.aeMode)
                )
                append(',')
                append(
                    value(row.aePriorityMode)
                )
                append(',')
                append(
                    value(row.lowLightBoostState)
                )
                append(',')
                append(
                    value(row.awbState)
                )
                append(',')
                append(
                    value(row.stabilizationMode)
                )
                append(',')
                append(
                    value(row.noiseReductionMode)
                )
                append(',')
                append(
                    value(row.focalLength)
                )
                append(',')
                append(
                    value(row.zoomRatio)
                )
                appendLine()
            }
        }
    }

    private fun value(
        value: Any?,
    ): String {
        return value
            ?.toString()
            .orEmpty()
    }

    private fun csv(
        value: String,
    ): String {
        return if (
            ',' in value ||
            '"' in value
        ) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}