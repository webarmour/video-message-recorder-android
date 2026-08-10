package io.github.webarmour.videomessagerecorder.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs


/**
 * Prefer the logical rear camera because its 1.0x coordinate is the device's normal wide camera.
 * If only physical rear cameras are exposed, pick the lens whose 35 mm equivalent is closest to
 * a typical phone main camera instead of relying on camera-id ordering (which may put ultrawide first).
 */
internal fun chooseMainRearCameraId(
    cameraIds: List<String>,
    characteristicsById: Map<String, CameraCharacteristics>,
): String? {
    if (cameraIds.isEmpty()) return null

    cameraIds.firstOrNull { cameraId ->
        val capabilities = characteristicsById[cameraId]
            ?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in
                capabilities
    }?.let { return it }

    return cameraIds.minByOrNull { cameraId ->
        val equivalentFocalLength = characteristicsById[cameraId]
            ?.estimated35mmEquivalentFocalLength()
        if (equivalentFocalLength == null) {
            Float.MAX_VALUE
        } else {
            abs(equivalentFocalLength - MAIN_CAMERA_EQUIVALENT_FOCAL_LENGTH_MM)
        }
    } ?: cameraIds.first()
}

private fun CameraCharacteristics.estimated35mmEquivalentFocalLength(): Float? {
    val sensorSize = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val focalLength = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.minOrNull()
        ?: return null
    val sensorLongSideMm = maxOf(sensorSize.width, sensorSize.height)
    if (sensorLongSideMm <= 0f) return null

    return focalLength * 36f / sensorLongSideMm
}

private const val MAIN_CAMERA_EQUIVALENT_FOCAL_LENGTH_MM = 26f

internal fun rotationToDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_0 -> 0
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

/**
 * Choose a camera SurfaceTexture size that contains enough real sensor pixels for the requested
 * square encoder output. Prefer a 16:9 video-like source and avoid upscaling whenever possible.
 */
internal fun chooseCameraSourceSize(
    characteristics: CameraCharacteristics,
    targetOutput: Size,
    targetFps: Int,
): Size {
    val map = checkNotNull(
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    ) { "Camera has no stream configuration map" }

    val sizes = checkNotNull(map.getOutputSizes(SurfaceTexture::class.java)) {
        "Camera has no SurfaceTexture sizes"
    }

    val targetShortSide = minOf(targetOutput.width, targetOutput.height)
    val targetFrameDurationNs = 1_000_000_000L / targetFps.coerceAtLeast(1)

    fun supportsRate(size: Size): Boolean {
        val duration = runCatching {
            map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        }.getOrDefault(0L)
        return duration <= 0L || duration <= targetFrameDurationNs
    }

    val rateCompatible = sizes.filter(::supportsRate).ifEmpty { sizes.toList() }
    val enoughPixels = rateCompatible.filter { size ->
        minOf(size.width, size.height) >= targetShortSide
    }
    val candidates = enoughPixels.ifEmpty { rateCompatible }

    val targetAspect = 16f / 9f
    return candidates.minBy { size ->
        val longSide = maxOf(size.width, size.height).toFloat()
        val shortSide = minOf(size.width, size.height).toFloat()
        val aspect = longSide / shortSide
        val shortSidePenalty = abs(shortSide - targetShortSide) / targetShortSide.coerceAtLeast(1)
        val upscalePenalty = if (shortSide < targetShortSide) 100.0 else 0.0
        upscalePenalty + shortSidePenalty + abs(aspect - targetAspect) * 0.35
    }
}

internal fun cameraCanFeedSquareOutput(
    characteristics: CameraCharacteristics,
    output: Size,
    frameRate: Int,
): Boolean {
    val map =
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
    val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return false
    val neededShortSide = minOf(output.width, output.height)
    val maxFrameDurationNs = 1_000_000_000L / frameRate.coerceAtLeast(1)

    return sizes.any { size ->
        if (minOf(size.width, size.height) < neededShortSide) return@any false
        val duration = runCatching {
            map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        }.getOrDefault(0L)
        duration <= 0L || duration <= maxFrameDurationNs
    }
}

internal fun avcEncoderSupports(
    size: Size,
    frameRate: Int,
): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
    if (!info.isEncoder || info.supportedTypes.none {
            it.equals(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                true
            )
        }) {
        return@any false
    }
    runCatching {
        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .videoCapabilities
            .areSizeAndRateSupported(size.width, size.height, frameRate.toDouble())
    }.getOrDefault(false)
}

internal data class FpsRangeSelection(
    val range: Range<Int>?,
    val holdsRequestedFps: Boolean,
)


internal fun chooseConcurrentPrewarmFpsRange(
    ranges: Array<Range<Int>>?,
    maxFps: Int = 30,
): FpsRangeSelection {
    if (ranges.isNullOrEmpty()) {
        return FpsRangeSelection(
            range = null,
            holdsRequestedFps = false,
        )
    }

    val cappedFps = maxFps.coerceAtLeast(1)
    val candidates = ranges
        .distinct()
        .filter { range -> range.upper <= cappedFps }

    val selected = candidates
        .filter { range -> range.lower == range.upper }
        .maxByOrNull { range -> range.upper }
        ?: candidates.maxWithOrNull(
            compareBy<Range<Int>>(
                { it.upper },
                { it.lower },
            )
        )

    return FpsRangeSelection(
        range = selected,
        holdsRequestedFps = selected?.lower == selected?.upper,
    )
}

internal fun chooseVideoFpsRange(
    ranges: Array<Range<Int>>?,
    targetFps: Int,
    holdFpsInLowLight: Boolean,
): FpsRangeSelection {
    if (ranges.isNullOrEmpty()) {
        return FpsRangeSelection(
            range = null,
            holdsRequestedFps = false,
        )
    }

    val sortedRanges = ranges.distinct().sortedWith(
        compareBy<Range<Int>>({ it.lower }, { it.upper })
    )

    if (holdFpsInLowLight) {
        sortedRanges.firstOrNull { range ->
            range.lower == targetFps && range.upper == targetFps
        }?.let { range ->
            return FpsRangeSelection(range, true)
        }

        sortedRanges
            .filter { range -> range.lower >= targetFps }
            .minWithOrNull(
                compareBy<Range<Int>>(
                    { it.lower - targetFps },
                    { it.upper - it.lower },
                    { it.upper },
                )
            )
            ?.let { range ->
                return FpsRangeSelection(range, true)
            }
    }

    sortedRanges
        .filter { range -> targetFps in range }
        .minWithOrNull(
            compareBy<Range<Int>>(
                { abs(it.upper - targetFps) },
                { it.lower },
                { it.upper - it.lower },
            )
        )
        ?.let { range ->
            return FpsRangeSelection(
                range = range,
                holdsRequestedFps = range.lower >= targetFps,
            )
        }

    val fallback = sortedRanges.minWithOrNull(
        compareBy<Range<Int>>(
            { abs(it.upper - targetFps) },
            { abs(it.lower - targetFps) },
            { it.upper - it.lower },
        )
    )

    return FpsRangeSelection(
        range = fallback,
        holdsRequestedFps = fallback?.lower?.let { it >= targetFps } == true,
    )
}
