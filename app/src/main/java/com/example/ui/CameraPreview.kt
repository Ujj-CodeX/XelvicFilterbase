package com.example.ui

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.camera.view.CameraController as AndroidxCameraController
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.xelvic.camera.CameraController
import com.example.xelvic.ui.CameraPreview as XelvicCameraPreview

/**
 * Top-level CameraPreview composables using CameraX and CameraView (PreviewView)
 * to display the live feed from the device's camera.
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
    XelvicCameraPreview(
        cameraController = cameraController,
        modifier = modifier,
        scaleType = scaleType,
        implementationMode = implementationMode,
        onPreviewViewCreated = onPreviewViewCreated,
        onSizeChanged = onSizeChanged
    )
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    implementationMode: PreviewView.ImplementationMode = PreviewView.ImplementationMode.PERFORMANCE,
    onPreviewViewCreated: ((PreviewView) -> Unit)? = null,
    onSizeChanged: ((width: Int, height: Int) -> Unit)? = null
) {
    XelvicCameraPreview(
        modifier = modifier,
        lensFacing = lensFacing,
        scaleType = scaleType,
        implementationMode = implementationMode,
        onPreviewViewCreated = onPreviewViewCreated,
        onSizeChanged = onSizeChanged
    )
}

@Composable
fun CameraPreview(
    controller: AndroidxCameraController,
    modifier: Modifier = Modifier,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    implementationMode: PreviewView.ImplementationMode = PreviewView.ImplementationMode.PERFORMANCE,
    onPreviewViewCreated: ((PreviewView) -> Unit)? = null,
    onSizeChanged: ((width: Int, height: Int) -> Unit)? = null
) {
    XelvicCameraPreview(
        controller = controller,
        modifier = modifier,
        scaleType = scaleType,
        implementationMode = implementationMode,
        onPreviewViewCreated = onPreviewViewCreated,
        onSizeChanged = onSizeChanged
    )
}
