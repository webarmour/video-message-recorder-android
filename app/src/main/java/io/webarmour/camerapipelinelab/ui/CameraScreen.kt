package io.webarmour.camerapipelinelab.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.webarmour.videomessagerecorder.camera.AberrationCorrectionSetting
import io.github.webarmour.camerapipelinelab.R
import io.github.webarmour.videomessagerecorder.camera.AePrioritySetting
import io.github.webarmour.videomessagerecorder.camera.AntibandingSetting
import io.github.webarmour.videomessagerecorder.camera.AwbSetting
import io.github.webarmour.videomessagerecorder.camera.CameraUiState
import io.github.webarmour.videomessagerecorder.camera.ColorCorrectionSetting
import io.github.webarmour.videomessagerecorder.camera.DistortionCorrectionSetting
import io.github.webarmour.videomessagerecorder.camera.EdgeSetting
import io.github.webarmour.videomessagerecorder.camera.ExposureMode
import io.github.webarmour.videomessagerecorder.camera.FocusSetting
import io.github.webarmour.videomessagerecorder.camera.HotPixelSetting
import io.github.webarmour.videomessagerecorder.camera.NoiseReductionSetting
import io.github.webarmour.videomessagerecorder.camera.OpticalStabilizationSetting
import io.github.webarmour.videomessagerecorder.camera.RecordingConfig
import io.github.webarmour.videomessagerecorder.camera.ShadingSetting
import io.github.webarmour.videomessagerecorder.camera.StabilizationSetting
import io.github.webarmour.videomessagerecorder.camera.TonemapSetting
import io.github.webarmour.videomessagerecorder.camera.VideoQuality
import kotlin.math.roundToInt

private val PanelColor = Color(0xFF171717)
private val ChipColor = Color(0xFF242424)

@Composable
private fun CameraPreview(
    state: CameraUiState,
    hasPermissions: Boolean,
    onPreviewAvailable: (Surface, Int, Int, Int) -> Unit,
    onPreviewDestroyed: () -> Unit,
    onZoomChange: (Float) -> Unit,
) {
    val zoomRange = state.capabilities.zoomRatioRange
    val currentZoom by rememberUpdatedState(state.config.zoomRatio)
    val currentZoomCallback by rememberUpdatedState(onZoomChange)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.Black, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (hasPermissions) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        setZOrderMediaOverlay(false)
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    onPreviewAvailable(
                                        holder.surface,
                                        width,
                                        height,
                                        display?.rotation ?: Surface.ROTATION_0,
                                    )
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onPreviewDestroyed()
                                }
                            }
                        )
                    }
                },
            )

            if (zoomRange != null && zoomRange.upper > zoomRange.lower) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(zoomRange.lower, zoomRange.upper) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                if (zoomChange != 1f) {
                                    val nextZoom = (currentZoom * zoomChange)
                                        .coerceIn(zoomRange.lower, zoomRange.upper)
                                    currentZoomCallback(nextZoom)
                                }
                            }
                        },
                ) {}
            }

            if (state.config.hidePreviewUntilRecording && !state.isRecording) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.cameraReady) {
                            stringResource(R.string.camera_prewarmed_preview_on_record)
                        } else {
                            stringResource(R.string.prewarming_camera)
                        },
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.camera_microphone_permissions_required),
                color = Color.White,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun ZoomControl(
    state: CameraUiState,
    enabled: Boolean,
    onZoomChange: (Float) -> Unit,
) {
    val range = state.capabilities.zoomRatioRange ?: return
    if (range.upper <= range.lower) return

    val uiUpper = range.upper.coerceAtMost(10f)
    val value = state.config.zoomRatio.coerceIn(range.lower, uiUpper)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.zoom_summary, value),
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = value,
            onValueChange = onZoomChange,
            valueRange = range.lower..uiUpper,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingsContent(
    state: CameraUiState,
    config: RecordingConfig,
    enabled: Boolean,
    ispEnabled: Boolean,
    onConfigChange: (RecordingConfig) -> Unit,
) {
    val capabilities = state.capabilities
    val supportedQualities = capabilities.supportedQualities.ifEmpty {
        VideoQuality.entries.toList()
    }

    SettingTitle(stringResource(R.string.quality_output))
    ChoiceRow(
        values = supportedQualities,
        selected = config.quality,
        enabled = enabled,
        label = { qualityLabel(it) },
        onSelected = { quality -> onConfigChange(config.copy(quality = quality)) },
    )
    BasicHint(stringResource(R.string.quality_supported_hint))

    Spacer(Modifier.height(14.dp))

    SettingTitle(
        stringResource(
            R.string.video_bitrate,
            formatMbps(config.videoBitrate),
        )
    )
    Slider(
        value = config.videoBitrate / 1_000_000f,
        onValueChange = { value ->
            val roundedMbps = (value * 2f).roundToInt() / 2f
            onConfigChange(
                config.copy(videoBitrate = (roundedMbps * 1_000_000f).roundToInt())
            )
        },
        valueRange = 0.5f..40f,
        steps = 78,
        enabled = enabled,
    )

    Spacer(Modifier.height(8.dp))

    SettingTitle(stringResource(R.string.frame_rate))
    ChoiceRow(
        values = listOf(24, 30, 60),
        selected = config.frameRate,
        enabled = enabled,
        label = { fps -> stringResource(R.string.fps_value, fps) },
        onSelected = { fps -> onConfigChange(config.copy(frameRate = fps)) },
    )

    Spacer(Modifier.height(10.dp))

    BooleanSetting(
        title = stringResource(R.string.hold_fps_low_light),
        subtitle = when {
            !config.holdFpsInLowLight -> stringResource(R.string.hold_fps_adaptive)
            state.fpsHoldActive -> stringResource(R.string.hold_fps_active, config.frameRate)
            else -> stringResource(R.string.hold_fps_unsupported)
        },
        checked = config.holdFpsInLowLight,
        enabled = enabled,
        error = config.holdFpsInLowLight && !state.fpsHoldActive,
        onCheckedChange = { value -> onConfigChange(config.copy(holdFpsInLowLight = value)) },
    )

    val aeRanges = if (state.supportedFpsRanges.isEmpty()) {
        stringResource(R.string.not_available)
    } else {
        val rangeLabels = mutableListOf<String>()
        for (range in state.supportedFpsRanges) {
            rangeLabels += stringResource(
                R.string.ae_range_item,
                range.lower,
                range.upper,
            )
        }
        rangeLabels.joinToString()
    }
    val aeRangesText = state.selectedFpsRange?.let { selected ->
        stringResource(
            R.string.ae_ranges_selected,
            aeRanges,
            selected.lower,
            selected.upper,
        )
    } ?: stringResource(R.string.ae_ranges, aeRanges)
    BasicHint(aeRangesText)

    SectionDivider(stringResource(R.string.noise_reduction))
    val noiseValues = NoiseReductionSetting.entries.filter {
        it.camera2Mode == null || it.camera2Mode in state.supportedNoiseReductionModes
    }
    ChoiceRow(
        values = noiseValues,
        selected = config.noiseReduction.takeIf { it in noiseValues }
            ?: NoiseReductionSetting.OEM_DEFAULT,
        enabled = ispEnabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(noiseReduction = it)) },
    )
    BasicHint(stringResource(R.string.noise_reduction_minimal_hint))

    SectionDivider(stringResource(R.string.encoder))
    SettingTitle(stringResource(R.string.b_frames))
    ChoiceRow(
        values = listOf(0, 1, 2, 3),
        selected = config.maxBFrames,
        enabled = enabled,
        label = { it.toString() },
        onSelected = { onConfigChange(config.copy(maxBFrames = it)) },
    )

    BooleanSetting(
        title = stringResource(R.string.circle_mask_saved_mp4),
        subtitle = stringResource(R.string.circle_mask_saved_mp4_hint),
        checked = config.circleMaskInSavedVideo,
        enabled = enabled,
        onCheckedChange = { onConfigChange(config.copy(circleMaskInSavedVideo = it)) },
    )

    BooleanSetting(
        title = stringResource(R.string.hide_preview_until_record),
        subtitle = stringResource(R.string.hide_preview_until_record_hint),
        checked = config.hidePreviewUntilRecording,
        enabled = enabled,
        onCheckedChange = { onConfigChange(config.copy(hidePreviewUntilRecording = it)) },
    )

    AdvancedSpoiler(
        state = state,
        config = config,
        enabled = enabled,
        ispEnabled = ispEnabled,
        supportedQualities = supportedQualities,
        onConfigChange = onConfigChange,
    )
}

@Composable
private fun AdvancedSpoiler(
    state: CameraUiState,
    config: RecordingConfig,
    enabled: Boolean,
    ispEnabled: Boolean,
    supportedQualities: List<VideoQuality>,
    onConfigChange: (RecordingConfig) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = Color.DarkGray)
    SpoilerHeader(
        title = stringResource(R.string.advanced),
        expanded = expanded,
        onClick = { expanded = !expanded },
    )

    if (!expanded) return

    AdvancedSettingsContent(
        state = state,
        config = config,
        enabled = enabled,
        ispEnabled = ispEnabled,
        supportedQualities = supportedQualities,
        onConfigChange = onConfigChange,
    )
}

@Composable
private fun AdvancedSettingsContent(
    state: CameraUiState,
    config: RecordingConfig,
    enabled: Boolean,
    ispEnabled: Boolean,
    supportedQualities: List<VideoQuality>,
    onConfigChange: (RecordingConfig) -> Unit,
) {
    val capabilities = state.capabilities

    SectionDivider(stringResource(R.string.section_exposure))
    ChoiceRow(
        values = if (capabilities.manualSensor) {
            ExposureMode.entries.toList()
        } else {
            listOf(ExposureMode.AUTO)
        },
        selected = config.exposureMode,
        enabled = enabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(exposureMode = it)) },
    )
    AdvancedHint(
        description = stringResource(R.string.advanced_exposure_description),
        recommendation = stringResource(R.string.advanced_exposure_recommendation),
    )

    if (config.exposureMode == ExposureMode.AUTO) {
        if (capabilities.lowLightBoostAvailable) {
            val lowLightSubtitle = capabilities.lowLightBoostLuxRange?.let { range ->
                stringResource(
                    R.string.low_light_boost_subtitle_lux,
                    range.lower,
                    range.upper,
                )
            } ?: stringResource(R.string.low_light_boost_subtitle)

            BooleanSetting(
                title = stringResource(R.string.android_low_light_boost),
                subtitle = lowLightSubtitle,
                checked = config.lowLightBoost,
                enabled = enabled,
                onCheckedChange = { value ->
                    onConfigChange(
                        config.copy(
                            lowLightBoost = value,
                            aePriority = if (value) AePrioritySetting.OFF else config.aePriority,
                        )
                    )
                },
            )
            AdvancedHint(
                description = stringResource(R.string.advanced_low_light_boost_description),
                recommendation = stringResource(R.string.advanced_low_light_boost_recommendation),
            )
        }

        val aePriorityValues = AePrioritySetting.entries.filter {
            it.camera2Mode in capabilities.aePriorityModes
        }
        if (aePriorityValues.size > 1 && !config.lowLightBoost) {
            SettingTitle(stringResource(R.string.ae_priority))
            ChoiceRow(
                values = aePriorityValues,
                selected = config.aePriority.takeIf { it in aePriorityValues }
                    ?: AePrioritySetting.OFF,
                enabled = enabled,
                label = { enumOptionLabel(it.name) },
                onSelected = { priority ->
                    onConfigChange(config.copy(aePriority = priority, lowLightBoost = false))
                },
            )
            AdvancedHint(
                description = stringResource(R.string.advanced_ae_priority_description),
                recommendation = stringResource(R.string.advanced_ae_priority_recommendation),
            )

            if (config.aePriority == AePrioritySetting.SHUTTER) {
                capabilities.exposureTimeUsRange?.let { exposureRange ->
                    val maxUs = exposureRange.upper.coerceAtMost(
                        if (config.holdFpsInLowLight) {
                            1_000_000L / config.frameRate.coerceAtLeast(1)
                        } else {
                            250_000L
                        }
                    )
                    if (maxUs > exposureRange.lower) {
                        val selectedUs = config.manualExposureTimeUs.coerceIn(
                            exposureRange.lower,
                            maxUs,
                        )
                        SettingTitle(
                            stringResource(
                                R.string.priority_shutter,
                                formatShutter(selectedUs),
                            )
                        )
                        Slider(
                            value = selectedUs.toFloat(),
                            onValueChange = { value ->
                                onConfigChange(config.copy(manualExposureTimeUs = value.toLong()))
                            },
                            valueRange = exposureRange.lower.toFloat()..maxUs.toFloat(),
                            enabled = enabled,
                        )
                    }
                }
            } else if (config.aePriority == AePrioritySetting.ISO) {
                capabilities.sensitivityRange?.let { isoRange ->
                    val selectedIso = config.manualIso.coerceIn(isoRange.lower, isoRange.upper)
                    SettingTitle(stringResource(R.string.priority_iso, selectedIso))
                    Slider(
                        value = selectedIso.toFloat(),
                        onValueChange = { value ->
                            onConfigChange(config.copy(manualIso = value.roundToInt()))
                        },
                        valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
                        enabled = enabled,
                    )
                }
            }
        }

        val compensationRange = capabilities.exposureCompensationRange
        if (compensationRange != null && compensationRange.lower != compensationRange.upper) {
            val ev = config.exposureCompensationSteps * capabilities.exposureCompensationStepEv
            SettingTitle(
                stringResource(
                    R.string.exposure_compensation,
                    stringResource(R.string.format_ev, ev),
                )
            )
            Slider(
                value = config.exposureCompensationSteps.toFloat(),
                onValueChange = { value ->
                    onConfigChange(config.copy(exposureCompensationSteps = value.roundToInt()))
                },
                valueRange = compensationRange.lower.toFloat()..compensationRange.upper.toFloat(),
                steps = (compensationRange.upper - compensationRange.lower - 1).coerceAtLeast(0),
                enabled = enabled,
            )
            AdvancedHint(
                description = stringResource(R.string.advanced_exposure_compensation_description),
                recommendation = stringResource(R.string.advanced_exposure_compensation_recommendation),
            )
        }

        if (capabilities.aeLockAvailable) {
            BooleanSetting(
                title = stringResource(R.string.ae_lock),
                subtitle = stringResource(R.string.ae_lock_hint),
                checked = config.aeLock,
                enabled = enabled,
                onCheckedChange = { onConfigChange(config.copy(aeLock = it)) },
            )
        }
    } else {
        val exposureRange = capabilities.exposureTimeUsRange
        val maxForVideoUs = exposureRange?.upper?.coerceAtMost(
            if (config.holdFpsInLowLight) {
                1_000_000L / config.frameRate.coerceAtLeast(1)
            } else {
                250_000L
            }
        )
        if (exposureRange != null && maxForVideoUs != null && maxForVideoUs > exposureRange.lower) {
            val selectedUs = config.manualExposureTimeUs.coerceIn(
                exposureRange.lower,
                maxForVideoUs,
            )
            SettingTitle(stringResource(R.string.shutter, formatShutter(selectedUs)))
            Slider(
                value = selectedUs.toFloat(),
                onValueChange = { value ->
                    onConfigChange(config.copy(manualExposureTimeUs = value.toLong()))
                },
                valueRange = exposureRange.lower.toFloat()..maxForVideoUs.toFloat(),
                enabled = enabled,
            )
        }
        capabilities.sensitivityRange?.let { isoRange ->
            val selectedIso = config.manualIso.coerceIn(isoRange.lower, isoRange.upper)
            SettingTitle(stringResource(R.string.iso, selectedIso))
            Slider(
                value = selectedIso.toFloat(),
                onValueChange = { value ->
                    onConfigChange(config.copy(manualIso = value.roundToInt()))
                },
                valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
                enabled = enabled,
            )
        }
    }

    val antibandingValues = AntibandingSetting.entries.filter {
        it.camera2Mode in capabilities.antibandingModes
    }
    if (antibandingValues.isNotEmpty()) {
        SettingTitle(stringResource(R.string.anti_banding))
        ChoiceRow(
            values = antibandingValues,
            selected = config.antibanding.takeIf { it in antibandingValues }
                ?: AntibandingSetting.AUTO,
            enabled = enabled,
            label = { enumOptionLabel(it.name) },
            onSelected = { onConfigChange(config.copy(antibanding = it)) },
        )
        AdvancedHint(
            description = stringResource(R.string.advanced_antibanding_description),
            recommendation = stringResource(R.string.advanced_antibanding_recommendation),
        )
    }

    SectionDivider(stringResource(R.string.section_white_balance))
    val awbValues = AwbSetting.entries.filter { it.camera2Mode in capabilities.awbModes }
    if (awbValues.isNotEmpty()) {
        ChoiceRow(
            values = awbValues,
            selected = config.awb.takeIf { it in awbValues } ?: AwbSetting.AUTO,
            enabled = enabled,
            label = { enumOptionLabel(it.name) },
            onSelected = { onConfigChange(config.copy(awb = it)) },
        )
        AdvancedHint(
            description = stringResource(R.string.advanced_awb_description),
            recommendation = stringResource(R.string.advanced_awb_recommendation),
        )
    }

    if (capabilities.awbLockAvailable) {
        BooleanSetting(
            title = stringResource(R.string.awb_lock),
            subtitle = stringResource(R.string.awb_lock_hint),
            checked = config.awbLock,
            enabled = enabled,
            onCheckedChange = { onConfigChange(config.copy(awbLock = it)) },
        )
    }

    if (capabilities.colorCorrectionModes.isNotEmpty()) {
        val colorValues = ColorCorrectionSetting.entries.filter {
            it.camera2Mode == null || it.camera2Mode in capabilities.colorCorrectionModes
        }
        SettingTitle(stringResource(R.string.color_correction))
        ChoiceRow(
            values = colorValues,
            selected = config.colorCorrection.takeIf { it in colorValues }
                ?: ColorCorrectionSetting.OEM_DEFAULT,
            enabled = ispEnabled,
            label = { enumOptionLabel(it.name) },
            onSelected = { onConfigChange(config.copy(colorCorrection = it)) },
        )
        AdvancedHint(
            description = stringResource(R.string.advanced_color_correction_description),
            recommendation = stringResource(R.string.advanced_color_correction_recommendation),
        )

        if (config.colorCorrection == ColorCorrectionSetting.CCT) {
            capabilities.colorTemperatureRange?.let { range ->
                SettingTitle(
                    stringResource(
                        R.string.color_temperature,
                        config.colorTemperatureKelvin,
                    )
                )
                Slider(
                    value = config.colorTemperatureKelvin.toFloat(),
                    onValueChange = { value ->
                        onConfigChange(config.copy(colorTemperatureKelvin = value.roundToInt()))
                    },
                    valueRange = range.lower.toFloat()..range.upper.toFloat(),
                    enabled = ispEnabled,
                )
                SettingTitle(stringResource(R.string.color_tint, config.colorTint))
                Slider(
                    value = config.colorTint.toFloat(),
                    onValueChange = { value ->
                        onConfigChange(config.copy(colorTint = value.roundToInt()))
                    },
                    valueRange = -50f..50f,
                    enabled = ispEnabled,
                )
            }
        }
    }

    SectionDivider(stringResource(R.string.section_focus))
    val focusValues = buildList {
        add(FocusSetting.CONTINUOUS_VIDEO)
        add(FocusSetting.AUTO)
        if (capabilities.minimumFocusDistance > 0f) add(FocusSetting.MANUAL)
    }
    ChoiceRow(
        values = focusValues,
        selected = config.focus.takeIf { it in focusValues } ?: FocusSetting.CONTINUOUS_VIDEO,
        enabled = enabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(focus = it)) },
    )
    AdvancedHint(
        description = stringResource(R.string.advanced_focus_description),
        recommendation = stringResource(R.string.advanced_focus_recommendation),
    )

    if (config.focus == FocusSetting.MANUAL && capabilities.minimumFocusDistance > 0f) {
        SettingTitle(
            stringResource(
                R.string.focus_distance,
                config.manualFocusDistance,
            )
        )
        Slider(
            value = config.manualFocusDistance.coerceIn(0f, capabilities.minimumFocusDistance),
            onValueChange = { onConfigChange(config.copy(manualFocusDistance = it)) },
            valueRange = 0f..capabilities.minimumFocusDistance,
            enabled = enabled,
        )
    }

    if (capabilities.availableApertures.size > 1) {
        SettingTitle(stringResource(R.string.aperture))
        val apertureValues = listOf<Float?>(null) + capabilities.availableApertures
        ChoiceRow(
            values = apertureValues,
            selected = config.aperture.takeIf { it in apertureValues },
            enabled = enabled,
            label = { aperture ->
                aperture?.let { stringResource(R.string.aperture_value, it) }
                    ?: stringResource(R.string.option_oem)
            },
            onSelected = { onConfigChange(config.copy(aperture = it)) },
        )
        AdvancedHint(
            description = stringResource(R.string.advanced_aperture_description),
            recommendation = stringResource(R.string.advanced_aperture_recommendation),
        )
    }

    SectionDivider(stringResource(R.string.section_stabilization))
    val stabilizationValues = StabilizationSetting.entries.filter {
        it.camera2Mode == null || it.camera2Mode in capabilities.stabilizationModes
    }
    ChoiceRow(
        values = stabilizationValues,
        selected = config.stabilization.takeIf { it in stabilizationValues }
            ?: StabilizationSetting.OEM_DEFAULT,
        enabled = enabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(stabilization = it)) },
    )
    AdvancedHint(
        description = stringResource(R.string.advanced_stabilization_description),
        recommendation = stringResource(R.string.advanced_stabilization_recommendation),
    )

    if (capabilities.opticalStabilizationModes.isNotEmpty()) {
        SettingTitle(stringResource(R.string.optical_stabilization))
        val oisValues = OpticalStabilizationSetting.entries.filter {
            it.camera2Mode == null || it.camera2Mode in capabilities.opticalStabilizationModes
        }
        ChoiceRow(
            values = oisValues,
            selected = config.opticalStabilization.takeIf { it in oisValues }
                ?: OpticalStabilizationSetting.OEM_DEFAULT,
            enabled = enabled,
            label = { enumOptionLabel(it.name) },
            onSelected = { onConfigChange(config.copy(opticalStabilization = it)) },
        )
        AdvancedHint(
            description = stringResource(R.string.advanced_ois_description),
            recommendation = stringResource(R.string.advanced_ois_recommendation),
        )
    }

    SectionDivider(stringResource(R.string.section_isp_processing))
    IspChoice(
        title = stringResource(R.string.hot_pixel_correction),
        values = HotPixelSetting.entries.filter {
            it.camera2Mode == null || it.camera2Mode in capabilities.hotPixelModes
        },
        selected = config.hotPixelCorrection,
        enabled = ispEnabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(hotPixelCorrection = it)) },
        description = stringResource(R.string.hot_pixel_description),
        recommendation = stringResource(R.string.hot_pixel_recommendation),
    )

    IspChoice(
        title = stringResource(R.string.lens_shading_correction),
        values = ShadingSetting.entries.filter {
            it.camera2Mode == null || it.camera2Mode in capabilities.shadingModes
        },
        selected = config.lensShading,
        enabled = ispEnabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(lensShading = it)) },
        description = stringResource(R.string.lens_shading_description),
        recommendation = stringResource(R.string.lens_shading_recommendation),
    )

    IspChoice(
        title = stringResource(R.string.edge_enhancement),
        values = EdgeSetting.entries.filter {
            it.camera2Mode == null || it.camera2Mode in capabilities.edgeModes
        },
        selected = config.edgeEnhancement,
        enabled = ispEnabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(edgeEnhancement = it)) },
        description = stringResource(R.string.edge_enhancement_description),
        recommendation = stringResource(R.string.edge_enhancement_recommendation),
    )

    IspChoice(
        title = stringResource(R.string.lens_distortion_correction),
        values = DistortionCorrectionSetting.entries.filter {
            it.camera2Mode == null || it.camera2Mode in capabilities.distortionCorrectionModes
        },
        selected = config.distortionCorrection,
        enabled = ispEnabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(distortionCorrection = it)) },
        description = stringResource(R.string.lens_distortion_description),
        recommendation = stringResource(R.string.lens_distortion_recommendation),
    )

    IspChoice(
        title = stringResource(R.string.chromatic_aberration_correction),
        values = AberrationCorrectionSetting.entries.filter {
            it.camera2Mode == null || it.camera2Mode in capabilities.aberrationCorrectionModes
        },
        selected = config.aberrationCorrection,
        enabled = ispEnabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(aberrationCorrection = it)) },
        description = stringResource(R.string.chromatic_aberration_description),
        recommendation = stringResource(R.string.chromatic_aberration_recommendation),
    )

    IspChoice(
        title = stringResource(R.string.tonemap),
        values = TonemapSetting.entries.filter {
            it.camera2Mode == null || it.camera2Mode in capabilities.tonemapModes
        },
        selected = config.tonemap,
        enabled = ispEnabled,
        label = { enumOptionLabel(it.name) },
        onSelected = { onConfigChange(config.copy(tonemap = it)) },
        description = stringResource(R.string.tonemap_description),
        recommendation = stringResource(R.string.tonemap_recommendation),
    )

    SectionDivider(stringResource(R.string.section_device_capabilities))
    val yesText = stringResource(R.string.option_yes)
    val noText = stringResource(R.string.option_no)
    val capabilityParts = mutableListOf(
        stringResource(R.string.capability_camera2, capabilities.hardwareLevel),
        stringResource(
            R.string.capability_manual_sensor,
            if (capabilities.manualSensor) yesText else noText,
        ),
        stringResource(
            R.string.capability_low_light_boost,
            if (capabilities.lowLightBoostAvailable) yesText else noText,
        ),
    )
    if (capabilities.colorCorrectionModes.isNotEmpty()) {
        capabilityParts += stringResource(R.string.capability_cct_color)
    }
    if (capabilities.aePriorityModes.size > 1) {
        capabilityParts += stringResource(R.string.capability_ae_priority)
    }
    capabilities.zoomRatioRange?.let { range ->
        capabilityParts += stringResource(
            R.string.capability_zoom,
            range.lower,
            range.upper,
        )
    }
    if (capabilities.availableApertures.size > 1) {
        val apertureLabels = mutableListOf<String>()
        for (aperture in capabilities.availableApertures) {
            apertureLabels += apertureValue(aperture)
        }
        capabilityParts += stringResource(
            R.string.capability_aperture,
            apertureLabels.joinToString(),
        )
    }

    val qualityLabels = mutableListOf<String>()
    for (quality in supportedQualities) {
        qualityLabels += qualityLabel(quality)
    }

    val middleDotSeparator = stringResource(R.string.separator_middle_dot)
    val ellipsisSuffix = stringResource(R.string.ellipsis_suffix)
    val capabilitiesText = buildString {
        append(capabilityParts.joinToString(middleDotSeparator))
        append('\n')
        append(stringResource(R.string.supported_square_encode_sizes, qualityLabels.joinToString()))
        if (capabilities.vendorRequestKeys.isNotEmpty()) {
            append('\n')
            append(
                stringResource(
                    R.string.vendor_request_tags_exposed,
                    capabilities.vendorRequestKeys.size,
                )
            )
            append('\n')
            append(capabilities.vendorRequestKeys.take(8).joinToString())
            if (capabilities.vendorRequestKeys.size > 8) append(ellipsisSuffix)
        }
    }

    Text(
        text = capabilitiesText,
        color = Color.Gray,
        style = MaterialTheme.typography.bodySmall,
    )
    AdvancedHint(
        description = stringResource(R.string.vendor_tags_description),
        recommendation = stringResource(R.string.vendor_tags_recommendation),
    )
}

@Composable
private fun <T> IspChoice(
    title: String,
    values: List<T>,
    selected: T,
    enabled: Boolean,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    description: String,
    recommendation: String,
) {
    if (values.isEmpty()) return
    SettingTitle(title)
    ChoiceRow(
        values = values,
        selected = selected.takeIf { it in values } ?: values.first(),
        enabled = enabled,
        label = label,
        onSelected = onSelected,
    )
    AdvancedHint(
        description = description,
        recommendation = recommendation,
    )
}

@Composable
private fun LiveMetrics(state: CameraUiState) {
    val metrics = state.liveMetrics
    val fps = metrics.actualFps?.let { actualFps ->
        stringResource(R.string.format_fps_decimal, actualFps)
    } ?: stringResource(R.string.not_available)
    val exposure = metrics.exposureMs?.let {
        stringResource(R.string.format_milliseconds, it)
    } ?: stringResource(R.string.not_available)
    val iso = metrics.iso?.toString() ?: stringResource(R.string.not_available)
    val lowLightBoost = metrics.lowLightBoostActive?.let { active ->
        stringResource(
            R.string.metrics_suffix,
            stringResource(
                if (active) R.string.metrics_low_light_active else R.string.metrics_low_light_idle
            ),
        )
    }.orEmpty()

    Text(
        text = stringResource(
            R.string.sensor_metrics,
            fps,
            exposure,
            iso,
            lowLightBoost,
        ),
        color = Color.LightGray,
        style = MaterialTheme.typography.bodySmall,
    )

    if (
        state.isRecording ||
        metrics.estimatedSourceDrops > 0 ||
        metrics.encoderBackpressureEvents > 0
    ) {
        Text(
            text = stringResource(
                R.string.pipeline_metrics,
                metrics.estimatedSourceDrops,
                metrics.encoderBackpressureEvents,
            ),
            color = if (
                metrics.estimatedSourceDrops > 0 || metrics.encoderBackpressureEvents > 0
            ) {
                Color(0xFFFFB74D)
            } else {
                Color.Gray
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


@Composable
private fun BooleanSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    error: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                color = if (error) MaterialTheme.colorScheme.error else Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun SpoilerHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                if (expanded) R.string.spoiler_expanded else R.string.spoiler_collapsed
            ),
            color = Color.LightGray,
        )
    }
}

@Composable
private fun AdvancedHint(
    description: String,
    recommendation: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp)
            .background(PanelColor)
            .padding(10.dp),
    ) {
        Text(
            text = description,
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = stringResource(R.string.recommendation_prefix, recommendation),
            color = Color(0xFFBDBDBD),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BasicHint(text: String) {
    Text(
        text = text,
        color = Color.Gray,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SectionDivider(title: String) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = Color.DarkGray)
    Spacer(Modifier.height(10.dp))
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    enabled: Boolean,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    if (values.isEmpty()) {
        Text(
            text = stringResource(R.string.not_exposed_by_camera),
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = ChipColor,
                    labelColor = Color.White,
                    selectedContainerColor = Color.White,
                    selectedLabelColor = Color.Black,
                    disabledContainerColor = Color(0xFF202020),
                    disabledLabelColor = Color(0xFF777777),
                ),
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun apertureValue(aperture: Float): String =
    stringResource(R.string.aperture_value, aperture)

@Composable
private fun qualityLabel(quality: VideoQuality): String = when (quality) {
    VideoQuality.TELEGRAM_NOTE_MAX -> stringResource(R.string.quality_telegram_max)
    VideoQuality.FULL_HD -> stringResource(R.string.quality_full_hd)
    VideoQuality.QHD -> stringResource(R.string.quality_qhd)
    else -> stringResource(
        R.string.quality_generic,
        quality.outputSize.width,
        quality.outputSize.height,
    )
}

@Composable
private fun enumOptionLabel(name: String): String = when (name) {
    "OEM_DEFAULT" -> stringResource(R.string.option_oem)
    "OFF" -> stringResource(R.string.option_off)
    "ON" -> stringResource(R.string.option_on)
    "AUTO" -> stringResource(R.string.option_auto)
    "MANUAL" -> stringResource(R.string.option_manual)
    "MINIMAL" -> stringResource(R.string.option_minimal)
    "FAST" -> stringResource(R.string.option_fast)
    "HIGH_QUALITY" -> stringResource(R.string.option_high_quality)
    "VIDEO" -> stringResource(R.string.option_video)
    "PREVIEW" -> stringResource(R.string.option_preview)
    "CONTINUOUS_VIDEO" -> stringResource(R.string.option_continuous)
    "ISO" -> stringResource(R.string.option_iso)
    "SHUTTER" -> stringResource(R.string.option_shutter)
    "CCT" -> stringResource(R.string.option_cct)
    "HZ_50" -> stringResource(R.string.option_50_hz)
    "HZ_60" -> stringResource(R.string.option_60_hz)
    "INCANDESCENT" -> stringResource(R.string.awb_incandescent)
    "FLUORESCENT" -> stringResource(R.string.awb_fluorescent)
    "WARM_FLUORESCENT" -> stringResource(R.string.awb_warm_fluorescent)
    "DAYLIGHT" -> stringResource(R.string.awb_daylight)
    "CLOUDY_DAYLIGHT" -> stringResource(R.string.awb_cloudy)
    "TWILIGHT" -> stringResource(R.string.awb_twilight)
    "SHADE" -> stringResource(R.string.awb_shade)
    else -> name
}

@Composable
private fun formatShutter(exposureUs: Long): String {
    if (exposureUs <= 0L) return stringResource(R.string.not_available)

    val seconds = exposureUs / 1_000_000.0
    return if (seconds >= 0.5) {
        stringResource(R.string.format_seconds, seconds)
    } else {
        val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
        stringResource(R.string.format_shutter_fraction, denominator)
    }
}

@Composable
private fun formatMbps(bitsPerSecond: Int): String =
    stringResource(R.string.format_mbps, bitsPerSecond / 1_000_000.0)

