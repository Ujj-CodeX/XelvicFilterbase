package com.example.xelvic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.xelvic.renderer.FaceMeshGlWireframeOverlayView

/** Opt-in GL wireframe calibration view; no filter texture is rendered by this diagnostic. */
@Composable
fun FaceMeshGlWireframeOverlay(
    holder: FaceGeometrySnapshotHolder,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val glView = remember(context) { FaceMeshGlWireframeOverlayView(context) }

    DisposableEffect(glView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                Lifecycle.Event.ON_DESTROY -> {
                    glView.setSnapshotHolder(null)
                    glView.setOverlayEnabled(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // This overlay may be mounted after Activity ON_RESUME has already fired.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            glView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView.setSnapshotHolder(null)
            glView.setOverlayEnabled(false)
            glView.onPause()
        }
    }

    AndroidView(
        factory = { glView },
        update = { view ->
            view.setOverlayEnabled(enabled)
            view.setSnapshotHolder(if (enabled) holder else null)
        },
        modifier = modifier.testTag("face_mesh_gl_wireframe_overlay")
    )
}
