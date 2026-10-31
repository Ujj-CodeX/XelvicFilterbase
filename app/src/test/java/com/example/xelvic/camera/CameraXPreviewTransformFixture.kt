package com.example.xelvic.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.OutputTransform
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Seeds synthetic surface metadata without camera hardware, then lets CameraX calculate the
 * actual PreviewView output transform. Callers must use native Robolectric graphics.
 * This does not validate live viewport negotiation or PreviewView's surface/cache lifecycle.
 */
@androidx.annotation.OptIn(TransformExperimental::class)
internal object CameraXPreviewTransformFixture {
    @SuppressLint("RestrictedApi")
    fun outputTransform(
        context: Context,
        previewResolution: Size,
        previewCropRect: Rect,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean
    ): OutputTransform {
        val previewView = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layout(0, 0, viewWidth, viewHeight)
        }
        val info = SurfaceRequest.TransformationInfo.of(
            Rect(previewCropRect), rotationDegrees, Surface.ROTATION_0,
            true, Matrix(), isFrontCamera
        )
        val previewTransformation = ReflectionHelpers.getField<Any>(previewView, "mPreviewTransform")
        ReflectionHelpers.callInstanceMethod<Void>(
            previewTransformation,
            "setTransformationInfo",
            ClassParameter.from(SurfaceRequest.TransformationInfo::class.java, info),
            ClassParameter.from(Size::class.java, previewResolution),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, isFrontCamera)
        )
        return requireNotNull(previewView.outputTransform) {
            "PreviewView output transform unavailable: rotation=$rotationDegrees " +
                "front=$isFrontCamera crop=$previewCropRect resolution=$previewResolution"
        }
    }
}
