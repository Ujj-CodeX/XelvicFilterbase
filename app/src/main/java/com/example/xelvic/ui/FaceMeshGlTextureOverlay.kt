package com.example.xelvic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.xelvic.renderer.FaceMeshGlEffect
import com.example.xelvic.renderer.FaceMeshGlOverlayView
import com.example.xelvic.renderer.FaceMeshGlRenderStatus
import com.example.xelvic.renderer.FaceMeshGlSurfaceDiagnostics
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
    onRenderStatus: ((FaceMeshGlRenderStatus, RenderBackendDecision) -> Unit)? = null,
    onSurfaceDiagnostics: ((FaceMeshGlSurfaceDiagnostics) -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewRef = remember { arrayOfNulls<FaceMeshGlOverlayView>(1) }
    val currentRenderStatus = rememberUpdatedState(onRenderStatus)
    val currentSurfaceDiagnostics = rememberUpdatedState(onSurfaceDiagnostics)

    AndroidView(
        factory = { context ->
            FaceMeshGlOverlayView(context).also { view ->
                viewRef[0] = view
                // The renderer thread starts immediately; also pause a view mounted while the
                // host is below RESUMED. Callbacks are main-thread/lifetime fenced by the view.
                view.setHostResumed(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                view.setDiagnosticsListener { status, decision ->
                    currentRenderStatus.value?.invoke(status, decision)
                }
                view.setSurfaceDiagnosticsListener { diagnostics ->
                    currentSurfaceDiagnostics.value?.invoke(diagnostics)
                }
            }
        },
        update = { view ->
            viewRef[0] = view
            view.setHostResumed(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            view.setSnapshotHolder(if (enabled) holder else null)
            view.setEffect(if (enabled) effect else null)
        },
        modifier = modifier,
        onRelease = { view ->
            view.releaseOverlay()
            if (viewRef[0] === view) {
                viewRef[0] = null
            }
        }
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = viewRef[0] ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.setHostResumed(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY ->
                    view.setHostResumed(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewRef[0]?.setHostResumed(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // An owner replacement is not AndroidView release. Keep holder/effect/listeners so
            // the replacement RESUMED owner can resume the same view without losing its texture.
            viewRef[0]?.setHostResumed(false)
        }
    }
}
