package com.example.xelvic.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.CameraController as AndroidxCameraController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.xelvic.camera.CameraController

/**
 * CameraPreview Composable using CameraX and CameraView (androidx.camera.view.PreviewView)
 * to display the live feed from the device's camera.
 *
 * This component handles:
 * - Proper lifecycle binding and unbinding
 * - AndroidView hosting of PreviewView
 * - Configurable scale types (FILL_CENTER, FIT_CENTER, etc.)
 * - Implementation modes (PERFORMANCE surface view or COMPATIBLE texture view)
 * - Viewfinder dimension change reporting
 * - Integration with Xelvic's native camera pipeline as well as standalone CameraX/CameraView controllers.
 */

/**
 * Primary CameraPreview overload that connects to Xelvic's CameraController.
 */
@Composable
fun CameraPreview(
    cameraController: CameraController,
    modifier: Modifier = Modifier,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    implementationMode: PreviewView.ImplementationMode = PreviewView.ImplementationMode.PERFORMANCE,
    onPreviewViewCreated: ((PreviewView) -> Unit)? = null,
    onSizeChanged: ((width: Int, height: Int) -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.scaleType = scaleType
                this.implementationMode = implementationMode
                cameraController.bindCamera(this)
                onPreviewViewCreated?.invoke(this)
            }
        },
        modifier = modifier
            .testTag("camera_preview")
            .onSizeChanged { size ->
                cameraController.faceAnalyzer.previewWidth = size.width
                cameraController.faceAnalyzer.previewHeight = size.height
                onSizeChanged?.invoke(size.width, size.height)
            }
    )
}

/**
 * Standalone CameraPreview overload using standard CameraX ProcessCameraProvider
 * and CameraView (PreviewView) without needing an external controller.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    implementationMode: PreviewView.ImplementationMode =
        PreviewView.ImplementationMode.COMPATIBLE,
    onPreviewViewCreated: ((PreviewView) -> Unit)? = null,
    onSizeChanged: ((width: Int, height: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            this.scaleType = scaleType
            this.implementationMode = implementationMode
        }
    }

    DisposableEffect(lifecycleOwner, lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
            } catch (e: Exception) {
                android.util.Log.e("CameraPreview", "Failed to bind camera use cases", e)
            }
        }, mainExecutor)

        onDispose {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = {
            onPreviewViewCreated?.invoke(previewView)
            previewView
        },
        modifier = modifier
            .testTag("camera_preview")
            .onSizeChanged { size ->
                onSizeChanged?.invoke(size.width, size.height)
            }
    )
}

/**
 * CameraPreview overload accepting CameraX's built-in CameraView CameraController
 * (e.g., LifecycleCameraController).
 */
@Composable
fun CameraPreview(
    controller: AndroidxCameraController,
    modifier: Modifier = Modifier,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    implementationMode: PreviewView.ImplementationMode = PreviewView.ImplementationMode.PERFORMANCE,
    onPreviewViewCreated: ((PreviewView) -> Unit)? = null,
    onSizeChanged: ((width: Int, height: Int) -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.scaleType = scaleType
                this.implementationMode = implementationMode
                this.controller = controller
                onPreviewViewCreated?.invoke(this)
            }
        },
        modifier = modifier
            .testTag("camera_preview")
            .onSizeChanged { size ->
                onSizeChanged?.invoke(size.width, size.height)
            }
    )
}
