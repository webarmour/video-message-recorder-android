package io.github.webarmour.videomessagerecorder

import io.github.webarmour.videomessagerecorder.camera.RecordingConfig

/**
 * Defines how recording parameters are selected.
 *
 * Auto is intended for normal production usage.
 * Custom exposes the existing low-level RecordingConfig for diagnostics,
 * experiments and applications that require explicit camera configuration.
 */
sealed interface RecordingMode {

    data object Auto : RecordingMode

    data class Custom(
        val config: RecordingConfig,
    ) : RecordingMode
}

enum class DiagnosticsMode {
    Disabled,
    TelemetryCsv,
}