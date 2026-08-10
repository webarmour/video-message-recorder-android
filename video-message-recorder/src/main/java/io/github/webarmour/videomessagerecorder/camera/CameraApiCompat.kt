package io.github.webarmour.videomessagerecorder.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import kotlin.math.roundToInt

/**
 * Camera2 compatibility helpers for the API 28 floor.
 *
 * API 30+ exposes logical zoom through CONTROL_ZOOM_RATIO. Android 9/10 can still zoom in using
 * the older SCALER_CROP_REGION path, but cannot request logical-camera zoom-out below 1x.
 */
internal fun CameraCharacteristics.zoomRatioRangeCompat(): Range<Float> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it }
    }

    val maxZoom = get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        ?.coerceAtLeast(1f)
        ?: 1f
    return Range(1f, maxZoom)
}

internal fun CaptureRequest.Builder.setZoomRatioCompat(
    characteristics: CameraCharacteristics,
    requestedZoomRatio: Float,
) {
    val range = characteristics.zoomRatioRangeCompat()
    val zoomRatio = requestedZoomRatio.coerceIn(range.lower, range.upper)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        return
    }

    val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        ?: return
    set(
        CaptureRequest.SCALER_CROP_REGION,
        activeArray.centerCropForZoom(zoomRatio),
    )
}

private fun Rect.centerCropForZoom(zoomRatio: Float): Rect {
    if (zoomRatio <= 1f) return Rect(this)

    val cropWidth = (width() / zoomRatio)
        .roundToInt()
        .coerceIn(1, width())
    val cropHeight = (height() / zoomRatio)
        .roundToInt()
        .coerceIn(1, height())
    val left = left + (width() - cropWidth) / 2
    val top = top + (height() - cropHeight) / 2

    return Rect(
        left,
        top,
        left + cropWidth,
        top + cropHeight,
    )
}
