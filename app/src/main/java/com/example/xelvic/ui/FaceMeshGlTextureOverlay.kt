package com.example.xelvic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.xelvic.renderer.FaceMeshGlEffect
import com.example.xelvic.renderer.FaceMeshGlOverlayView
import com.example.xelvic.renderer.FaceMeshGlRenderStatus
import com.example.xelvic.renderer.RenderBackendDecision

/**
 * Hosts the GLES face-texture overlay and binds it to the latest snapshot/transform pair.
 * The caller must pass only a FaceMeshGlEffect produced by FaceMeshEffectResolver.
 */
@Composable
internal fun FaceMeshTextureOverlay(
    holder: FaceGeometrySnapshotHolder,
    effect: FaceMeshGlEffect?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onRenderStatus: ((FaceMeshGlRenderStatus, RenderBackendDecision) -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewRef = remember { arrayOfNulls<FaceMeshGlOverlayView>(1) }

    AndroidView(
        factory = { context ->
            FaceMeshGlOverlayView(context).also { view ->
                viewRef[0] = view
                // This GL view may be created after Activity is already RESUMED.
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    view.onResume()
                }
            }
        },
        update = { view ->
            viewRef[0] = view
            view.setDiagnosticsListener { status, decision ->
                view.post { onRenderStatus?.invoke(status, decision) }
            }
            view.setSnapshotHolder(if (enabled) holder else null)
            view.setEffect(if (enabled) effect else null)
        },
        modifier = modifier,
        onRelease = { view ->
            view.setDiagnosticsListener(null)
            view.setSnapshotHolder(null)
            view.setEffect(null)
            view.onPause()
            if (viewRef[0] === view) {
                viewRef[0] = null
            }
        }
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = viewRef[0] ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewRef[0]?.setDiagnosticsListener(null)
            viewRef[0]?.setSnapshotHolder(null)
            viewRef[0]?.setEffect(null)
            viewRef[0]?.onPause()
        }
    }
}
