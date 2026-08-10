package io.webarmour.camerapipelinelab.ui


import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CircularVideoMessagePreview(
    onPreviewAvailable: (
        surface: Surface,
        width: Int,
        height: Int,
        displayRotation: Int,
    ) -> Unit,
    onPreviewDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootView = LocalView.current

    val currentOnPreviewAvailable by rememberUpdatedState(
        onPreviewAvailable
    )

    val currentOnPreviewDestroyed by rememberUpdatedState(
        onPreviewDestroyed
    )

    val currentDisplayRotation by rememberUpdatedState(
        rootView.display?.rotation
            ?: Surface.ROTATION_0
    )

    AndroidView(
        modifier = modifier.clip(
            CircleShape
        ),
        factory = { context ->
            TextureView(context).apply {
                clipToOutline = true

                outlineProvider = object : ViewOutlineProvider() {

                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) {
                        outline.setOval(
                            0,
                            0,
                            view.width,
                            view.height,
                        )
                    }
                }

                surfaceTextureListener =
                    object : TextureView.SurfaceTextureListener {

                        private var previewSurface: Surface? = null

                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val surface = Surface(
                                surfaceTexture
                            )

                            previewSurface = surface

                            currentOnPreviewAvailable(
                                surface,
                                width,
                                height,
                                currentDisplayRotation,
                            )
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val surface =
                                previewSurface
                                    ?: return

                            currentOnPreviewAvailable(
                                surface,
                                width,
                                height,
                                currentDisplayRotation,
                            )
                        }

                        override fun onSurfaceTextureDestroyed(
                            surfaceTexture: SurfaceTexture,
                        ): Boolean {
                            currentOnPreviewDestroyed()

                            previewSurface?.release()
                            previewSurface = null

                            return true
                        }

                        override fun onSurfaceTextureUpdated(
                            surfaceTexture: SurfaceTexture,
                        ) = Unit
                    }
            }
        },
    )
}