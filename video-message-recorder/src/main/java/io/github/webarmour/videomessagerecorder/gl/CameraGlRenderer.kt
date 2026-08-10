package io.github.webarmour.videomessagerecorder.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Shared timestamp guard for two independently warmed camera renderers writing into one encoder.
 * Camera SurfaceTexture timestamps are monotonic, but a camera switch can still expose an older
 * queued frame. The gate guarantees strictly increasing encoder presentation timestamps without
 * replacing the camera's monotonic clock with wall time.
 */
internal class PresentationTimestampGate {

    private val lastTimestampNs =
        AtomicLong(
            Long.MIN_VALUE
        )

    fun reset() {
        lastTimestampNs.set(
            Long.MIN_VALUE
        )
    }

    /**
     * Receives timestamps that have already been converted to the
     * System.nanoTime() / MONOTONIC timebase.
     *
     * Its only responsibility is maintaining a strictly increasing
     * encoder timeline when switching between independently running
     * camera renderers.
     *
     * It must NOT be used to convert raw timestamps between camera
     * timebases.
     */
    fun normalize(
        timestampNs: Long,
    ): Long {
        while (true) {
            val current =
                lastTimestampNs.get()

            val next =
                if (
                    current ==
                    Long.MIN_VALUE
                ) {
                    timestampNs
                } else {
                    max(
                        timestampNs,
                        current + 1L,
                    )
                }

            if (
                lastTimestampNs.compareAndSet(
                    current,
                    next,
                )
            ) {
                return next
            }
        }
    }
}

/**
 * Renders one Camera2 SurfaceTexture into preview and, when recording, MediaCodec.
 *
 * SurfaceTexture#getTransformMatrix() is the only transformation applied in texture space.
 * Rotation, mirroring and square center-crop are geometry operations. This keeps OEM camera
 * producer transforms intact and avoids double-transforming OnePlus/Samsung/Pixel buffers.
 */
internal data class RendererPerformanceStats(
    val sourceFrames: Long,
    val estimatedSourceDrops: Long,
    val encoderFrames: Long,
    val encoderBackpressureEvents: Long,
)

internal class CameraGlRenderer(
    private val timestampGate: PresentationTimestampGate,
) : AutoCloseable {

    private val thread = HandlerThread(
        "CameraGlRenderer",
        Process.THREAD_PRIORITY_DISPLAY,
    ).apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface = EGL14.EGL_NO_SURFACE

    private var previewEglSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: Surface? = null
    private var previewWidth = 1
    private var previewHeight = 1
    private var previewContentVisible = true

    private var encoderEglSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: Surface? = null
    private var encoderSize = Size(1, 1)
    private var encoderCircleMask = false
    private var expectedFrameIntervalNs = 33_333_333L
    private var lastSourceTimestampNs = Long.MIN_VALUE
    private var encoderSourceTimestampBaseNs =
        Long.MIN_VALUE

    private var encoderMonotonicTimestampBaseNs =
        Long.MIN_VALUE

    private val sourceFrames = AtomicLong(0L)
    private val estimatedSourceDrops = AtomicLong(0L)
    private val encoderFrames = AtomicLong(0L)
    private val encoderBackpressureEvents = AtomicLong(0L)

    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null
    private var oesTextureId = 0

    private var sourceSize = Size(1, 1)
    private var sensorOrientationDegrees = 0
    private var displayRotationDegrees = 0
    private var mirrorHorizontally = false

    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var textureMatrixLocation = -1
    private var samplerLocation = -1
    private var circleMaskLocation = -1
    private var viewportSizeLocation = -1

    private val stMatrix = FloatArray(16)

    private var vertexBuffer: FloatBuffer = floatBufferOf(*DEFAULT_VERTICES)
    private val textureBuffer: FloatBuffer = floatBufferOf(*DEFAULT_TEX_COORDS)

    fun initialize(
        sourceSize: Size,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        mirrorHorizontally: Boolean,
    ): Surface = runOnGlAndWait {
        releaseGlObjects()
        initEgl()
        initProgram()
        oesTextureId = createExternalTexture()

        this.sourceSize = sourceSize
        this.sensorOrientationDegrees = normalizeRightAngle(sensorOrientationDegrees)
        this.displayRotationDegrees = normalizeRightAngle(displayRotationDegrees)
        this.mirrorHorizontally = mirrorHorizontally
        rebuildVertexBuffer()

        cameraSurfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(sourceSize.width, sourceSize.height)
            setOnFrameAvailableListener({ renderFrame() }, handler)
        }
        cameraInputSurface = Surface(cameraSurfaceTexture)
        cameraInputSurface!!
    }

    fun updateDisplayRotation(displayRotationDegrees: Int) {
        runOnGlAndWait {
            val normalized = normalizeRightAngle(displayRotationDegrees)
            if (this.displayRotationDegrees == normalized) return@runOnGlAndWait
            this.displayRotationDegrees = normalized
            rebuildVertexBuffer()
        }
    }

    fun setPreviewContentVisible(
        visible: Boolean,
    ) {
        runOnGlAndWait {
            previewContentVisible = visible

            if (
                !visible &&
                previewEglSurface !=
                EGL14.EGL_NO_SURFACE
            ) {
                clearSurface(
                    target = previewEglSurface,
                    width = previewWidth,
                    height = previewHeight,
                )

                swapPreviewBuffers()
            }
        }
    }

    fun attachPreview(
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        runOnGlAndWait {
            if (!surface.isValid) {
                Log.d(
                    TAG,
                    "Ignoring invalid preview Surface",
                )

                return@runOnGlAndWait
            }

            previewSurface = surface
            previewWidth =
                width.coerceAtLeast(1)
            previewHeight =
                height.coerceAtLeast(1)

            destroyEglSurface(
                previewEglSurface
            )

            previewEglSurface =
                createWindowSurface(
                    surface
                )

            if (!previewContentVisible) {
                clearSurface(
                    target = previewEglSurface,
                    width = previewWidth,
                    height = previewHeight,
                )

                swapPreviewBuffers()
            }
        }
    }

    fun detachPreview() {
        runOnGlAndWait {
            destroyEglSurface(previewEglSurface)
            previewEglSurface = EGL14.EGL_NO_SURFACE
            previewSurface = null
        }
    }

    fun attachEncoder(
        surface: Surface,
        size: Size,
        circleMask: Boolean,
        targetFrameRate: Int,
    ) {
        runOnGlAndWait {
            encoderSurface = surface
            encoderSize = size
            encoderCircleMask = circleMask

            expectedFrameIntervalNs =
                1_000_000_000L /
                        targetFrameRate.coerceAtLeast(1)

            lastSourceTimestampNs =
                Long.MIN_VALUE

            /*
             * The first camera frame rendered after this attachment becomes
             * the timestamp anchor for this particular Camera/SurfaceTexture.
             *
             * Do not reuse an anchor from a previous camera.
             *
             * This is especially important during front <-> rear switching:
             * timestamps from separate SurfaceTexture instances must not be
             * assumed to have the same zero point.
             */
            encoderSourceTimestampBaseNs =
                Long.MIN_VALUE

            encoderMonotonicTimestampBaseNs =
                Long.MIN_VALUE

            destroyEglSurface(
                encoderEglSurface
            )

            encoderEglSurface =
                createWindowSurface(
                    surface
                )
        }
    }

    fun performanceStats(): RendererPerformanceStats = RendererPerformanceStats(
        sourceFrames = sourceFrames.get(),
        estimatedSourceDrops = estimatedSourceDrops.get(),
        encoderFrames = encoderFrames.get(),
        encoderBackpressureEvents = encoderBackpressureEvents.get(),
    )

    fun resetPerformanceStats() {
        runOnGlAndWait {
            resetPerformanceStatsLocked()
        }
    }

    private fun resetPerformanceStatsLocked() {
        sourceFrames.set(0L)
        estimatedSourceDrops.set(0L)
        encoderFrames.set(0L)
        encoderBackpressureEvents.set(0L)
        lastSourceTimestampNs = Long.MIN_VALUE
    }

    fun detachEncoder() {
        runOnGlAndWait {
            destroyEglSurface(
                encoderEglSurface
            )

            encoderEglSurface =
                EGL14.EGL_NO_SURFACE

            encoderSurface =
                null

            lastSourceTimestampNs =
                Long.MIN_VALUE

            encoderSourceTimestampBaseNs =
                Long.MIN_VALUE

            encoderMonotonicTimestampBaseNs =
                Long.MIN_VALUE
        }
    }

    private fun renderFrame() {
        val surfaceTexture =
            cameraSurfaceTexture
                ?: return

        makeCurrent(
            pbufferSurface
        )

        runCatching {
            surfaceTexture.updateTexImage()

            surfaceTexture.getTransformMatrix(
                stMatrix
            )

            val cameraTimestampNs =
                surfaceTexture.timestamp

            if (
                encoderEglSurface !=
                EGL14.EGL_NO_SURFACE
            ) {
                sourceFrames.incrementAndGet()

                if (
                    lastSourceTimestampNs !=
                    Long.MIN_VALUE &&
                    expectedFrameIntervalNs > 0L
                ) {
                    val deltaNs =
                        cameraTimestampNs -
                                lastSourceTimestampNs

                    if (
                        deltaNs >
                        expectedFrameIntervalNs * 3L / 2L
                    ) {
                        val missing =
                            (
                                    deltaNs /
                                            expectedFrameIntervalNs -
                                            1L
                                    )
                                .coerceAtLeast(
                                    1L
                                )

                        estimatedSourceDrops.addAndGet(
                            missing
                        )
                    }
                }

                lastSourceTimestampNs =
                    cameraTimestampNs
            } else {
                /*
                 * The inactive concurrently prewarmed camera must keep consuming
                 * SurfaceTexture frames, but these frames are not part of the
                 * recording.
                 */
                lastSourceTimestampNs =
                    Long.MIN_VALUE
            }

            /*
             * Encoder has priority over preview.
             */
            if (
                encoderEglSurface !=
                EGL14.EGL_NO_SURFACE
            ) {
                renderTo(
                    target =
                        encoderEglSurface,
                    width =
                        encoderSize.width,
                    height =
                        encoderSize.height,
                    circleMask =
                        encoderCircleMask,
                )

                /*
                 * IMPORTANT:
                 *
                 * Never pass SurfaceTexture.timestamp directly to MediaCodec.
                 *
                 * SurfaceTexture timestamp:
                 * - may use a different clock than System.nanoTime();
                 * - may have a source-specific zero point;
                 * - cannot safely be compared between two SurfaceTexture
                 *   instances.
                 *
                 * Preserve only the timestamp DELTAS from the camera and anchor
                 * them to System.nanoTime().
                 */
                val monotonicPresentationTimeNs =
                    mapCameraTimestampToMonotonic(
                        cameraTimestampNs
                    )

                val safePresentationTimeNs =
                    timestampGate.normalize(
                        monotonicPresentationTimeNs
                    )

                EGLExt.eglPresentationTimeANDROID(
                    eglDisplay,
                    encoderEglSurface,
                    safePresentationTimeNs,
                )

                val swapStartedNs =
                    System.nanoTime()

                check(
                    EGL14.eglSwapBuffers(
                        eglDisplay,
                        encoderEglSurface,
                    )
                ) {
                    "Encoder eglSwapBuffers failed"
                }

                val swapDurationNs =
                    System.nanoTime() -
                            swapStartedNs

                encoderFrames.incrementAndGet()

                if (
                    swapDurationNs >
                    expectedFrameIntervalNs
                ) {
                    encoderBackpressureEvents
                        .incrementAndGet()
                }
            }

            if (
                previewEglSurface !=
                EGL14.EGL_NO_SURFACE
            ) {
                if (previewContentVisible) {
                    renderTo(
                        target =
                            previewEglSurface,
                        width =
                            previewWidth,
                        height =
                            previewHeight,
                        circleMask =
                            true,
                    )
                } else {
                    clearSurface(
                        target =
                            previewEglSurface,
                        width =
                            previewWidth,
                        height =
                            previewHeight,
                    )
                }

                swapPreviewBuffers()
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "Unable to render camera frame",
                error,
            )
        }
    }

    private fun mapCameraTimestampToMonotonic(
        cameraTimestampNs: Long,
    ): Long {
        /*
         * A zero/negative timestamp should not normally happen for Camera2,
         * but using System.nanoTime() is a safe fallback for a broken producer.
         */
        if (cameraTimestampNs <= 0L) {
            return System.nanoTime()
        }

        val sourceBase =
            encoderSourceTimestampBaseNs

        /*
         * First encoded frame for this renderer/camera.
         *
         * Anchor the camera's private timestamp timeline to the application's
         * monotonic clock.
         */
        if (
            sourceBase == Long.MIN_VALUE ||
            encoderMonotonicTimestampBaseNs == Long.MIN_VALUE
        ) {
            encoderSourceTimestampBaseNs =
                cameraTimestampNs

            encoderMonotonicTimestampBaseNs =
                System.nanoTime()

            Log.i(
                TAG,
                "Encoder timestamp anchor: " +
                        "cameraNs=$cameraTimestampNs, " +
                        "monotonicNs=$encoderMonotonicTimestampBaseNs",
            )

            return encoderMonotonicTimestampBaseNs
        }

        /*
         * Camera timestamps for one SurfaceTexture should be strictly
         * monotonic.
         *
         * A backwards timestamp indicates a vendor/producer reset.
         * Re-anchor instead of generating a broken media timeline.
         */
        if (cameraTimestampNs < sourceBase) {
            Log.w(
                TAG,
                "Camera timestamp moved backwards: " +
                        "previousBaseNs=$sourceBase, " +
                        "cameraNs=$cameraTimestampNs. Re-anchoring.",
            )

            encoderSourceTimestampBaseNs =
                cameraTimestampNs

            encoderMonotonicTimestampBaseNs =
                System.nanoTime()

            return encoderMonotonicTimestampBaseNs
        }

        val elapsedCameraNs =
            cameraTimestampNs -
                    encoderSourceTimestampBaseNs

        return encoderMonotonicTimestampBaseNs +
                elapsedCameraNs
    }

    private fun swapPreviewBuffers(): Boolean {
        val surface = previewEglSurface

        if (
            surface == EGL14.EGL_NO_SURFACE ||
            eglDisplay == EGL14.EGL_NO_DISPLAY
        ) {
            return false
        }

        if (
            EGL14.eglSwapBuffers(
                eglDisplay,
                surface,
            )
        ) {
            return true
        }

        val error = EGL14.eglGetError()

        return when (error) {
            EGL14.EGL_BAD_SURFACE,
            EGL14.EGL_BAD_NATIVE_WINDOW,
                -> {
                Log.d(
                    TAG,
                    "Preview surface disappeared while rendering; detaching EGL preview",
                )

                destroyEglSurface(
                    surface
                )

                previewEglSurface =
                    EGL14.EGL_NO_SURFACE

                previewSurface = null

                false
            }

            else -> {
                throw IllegalStateException(
                    "Preview eglSwapBuffers failed: 0x${error.toString(16)}"
                )
            }
        }
    }

    private fun clearSurface(
        target: EGLSurface,
        width: Int,
        height: Int,
    ) {
        makeCurrent(target)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun renderTo(
        target: EGLSurface,
        width: Int,
        height: Int,
        circleMask: Boolean,
    ) {
        makeCurrent(target)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer,
        )

        textureBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(
            texCoordLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureBuffer,
        )

        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, stMatrix, 0)
        GLES20.glUniform1f(circleMaskLocation, if (circleMask) 1f else 0f)
        GLES20.glUniform2f(viewportSizeLocation, width.toFloat(), height.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(samplerLocation, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
    }

    private fun rebuildVertexBuffer() {
        vertexBuffer = floatBufferOf(
            *buildVertices(
                sourceSize = sourceSize,
                sensorOrientationDegrees = sensorOrientationDegrees,
                displayRotationDegrees = displayRotationDegrees,
                mirrorHorizontally = mirrorHorizontally,
            )
        )
    }

    private fun buildVertices(
        sourceSize: Size,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        mirrorHorizontally: Boolean,
    ): FloatArray {
        val sensorSwapsAxes = sensorOrientationDegrees % 180 != 0
        val orientedWidth = if (sensorSwapsAxes) {
            sourceSize.height.toFloat()
        } else {
            sourceSize.width.toFloat()
        }
        val orientedHeight = if (sensorSwapsAxes) {
            sourceSize.width.toFloat()
        } else {
            sourceSize.height.toFloat()
        }

        val orientedAspect = orientedWidth / orientedHeight
        val aspectScaleX = if (orientedAspect >= 1f) orientedAspect else 1f
        val aspectScaleY = if (orientedAspect >= 1f) 1f else 1f / orientedAspect

        val rotationRadians = Math.toRadians(-displayRotationDegrees.toDouble())
        val cosRotation = cos(rotationRadians).toFloat()
        val sinRotation = sin(rotationRadians).toFloat()

        fun transform(x: Float, y: Float): Pair<Float, Float> {
            val scaledX = x * aspectScaleX
            val scaledY = y * aspectScaleY

            val rotatedX = scaledX * cosRotation - scaledY * sinRotation
            val rotatedY = scaledX * sinRotation + scaledY * cosRotation

            val finalX = if (mirrorHorizontally) -rotatedX else rotatedX
            return finalX to rotatedY
        }

        val bottomLeft = transform(-1f, -1f)
        val bottomRight = transform(1f, -1f)
        val topLeft = transform(-1f, 1f)
        val topRight = transform(1f, 1f)

        return floatArrayOf(
            bottomLeft.first, bottomLeft.second,
            bottomRight.first, bottomRight.second,
            topLeft.first, topLeft.second,
            topRight.first, topRight.second,
        )
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            "Unable to initialize EGL"
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )

        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                attributes,
                0,
                configs,
                0,
                1,
                count,
                0,
            ) && count[0] > 0
        ) { "Unable to choose recordable EGL config" }

        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }

        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            ),
            0,
        )
        check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL pbuffer" }
        makeCurrent(pbufferSurface)
    }

    private fun initProgram() {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram().also { programId ->
            GLES20.glAttachShader(programId, vertexShader)
            GLES20.glAttachShader(programId, fragmentShader)
            GLES20.glLinkProgram(programId)

            val status = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Program link failed: ${GLES20.glGetProgramInfoLog(programId)}"
            }
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTextureCoord")
        textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        samplerLocation = GLES20.glGetUniformLocation(program, "sTexture")
        circleMaskLocation = GLES20.glGetUniformLocation(program, "uCircleMask")
        viewportSizeLocation = GLES20.glGetUniformLocation(program, "uViewportSize")

        check(
            positionLocation >= 0 &&
                texCoordLocation >= 0 &&
                textureMatrixLocation >= 0 &&
                circleMaskLocation >= 0 &&
                viewportSizeLocation >= 0
        ) { "Unable to resolve GL shader locations" }
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        check(textureId != 0) { "Unable to create external OES texture" }

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        checkGlError("createExternalTexture")
        return textureId
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val result = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(result != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}"
        }
    }

    private fun destroyEglSurface(surface: EGLSurface) {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, surface)
        }
    }

    private fun checkGlError(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "$operation failed with GL error 0x${error.toString(16)}"
        }
    }

    private fun releaseGlObjects() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return

        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbufferSurface)
        }

        destroyEglSurface(previewEglSurface)
        destroyEglSurface(encoderEglSurface)
        previewEglSurface = EGL14.EGL_NO_SURFACE
        encoderEglSurface = EGL14.EGL_NO_SURFACE

        cameraInputSurface?.release()
        cameraInputSurface = null
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null

        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }

        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }

        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }

        EGL14.eglReleaseThread()
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    override fun close() {
        runOnGlAndWait { releaseGlObjects() }
        thread.quitSafely()
        thread.join(1_000)
    }

    private fun <T> runOnGlAndWait(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()

        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        handler.post {
            result = runCatching(block)
            latch.countDown()
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "GL operation timed out" }
        return result!!.getOrThrow()
    }

    private companion object {
        const val TAG = "CameraGlRenderer"
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val DEFAULT_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        val DEFAULT_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTextureCoord;

            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTextureMatrix * aTextureCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform float uCircleMask;
            uniform vec2 uViewportSize;

            void main() {
                vec4 color = texture2D(sTexture, vTextureCoord);

                if (uCircleMask > 0.5) {
                    vec2 centered = (gl_FragCoord.xy / uViewportSize) - vec2(0.5);
                    float radius = length(centered);
                    float feather = 1.5 / min(uViewportSize.x, uViewportSize.y);
                    float inside = 1.0 - smoothstep(0.5 - feather, 0.5 + feather, radius);
                    color = mix(vec4(0.0, 0.0, 0.0, 1.0), color, inside);
                    color.a = 1.0;
                }

                gl_FragColor = color;
            }
        """

        fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        fun normalizeRightAngle(degrees: Int): Int {
            val normalized = ((degrees % 360) + 360) % 360
            check(normalized % 90 == 0) { "Rotation must be a multiple of 90: $degrees" }
            return normalized
        }
    }
}
