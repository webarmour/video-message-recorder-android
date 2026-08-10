package io.github.webarmour.videomessagerecorder

import io.github.webarmour.videomessagerecorder.camera.RecordingConfig

/**
 * Defines how recording parameters are selected.
 *
 * [Auto] is intended for normal production usage.
 * [Custom] exposes [RecordingConfig] for diagnostics, experiments,
 * and applications that require explicit recording parameters.
 */
sealed interface RecordingMode {

    data object Auto : RecordingMode

    data class Custom(
        val config: RecordingConfig,
    ) : RecordingMode
}
