package com.example.xelvic.camera

import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.util.Size
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.test.core.app.ApplicationProvider
import com.example.xelvic.geometry.FaceGeometryDisplayTransform
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for the CameraX transform consumed by the MediaPipe preview renderer.
 *
 * MediaPipe receives a physically upright bitmap, so its normalized landmarks are expressed in
 * that upright bitmap's pixel space. The tests use CameraX's real ImageProxyTransformFactory and
 * CoordinateTransform APIs and PreviewView.outputTransform calculated from synthetic metadata.
 * No replacement output matrix is constructed by the test.
 *
 * These are unit-level calibration tests. They do not instantiate a camera or prove that a live
 * PreviewView has published the expected OutputTransform; that remains device/integration work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TransformExperimental
class CameraXImageProxyTransformRotationTest {

    @Test
    fun rotationAwareSourceMapsUprightLandmarksForAllRotationsCropsAndCameraFacings() {
        val cropRects = listOf(
            Rect(0, 0, SENSOR_WIDTH, SENSOR_HEIGHT),
            Rect(40, 30, 600, 450)
        )

        RIGHT_ANGLE_ROTATIONS.forEach { rotationDegrees ->
            cropRects.forEach { cropRect ->
                listOf(false, true).forEach { frontFacing ->
                    val imageProxy = fakeImageProxy(
                        width = SENSOR_WIDTH,
                        height = SENSOR_HEIGHT,
                        rotationDegrees = rotationDegrees,
                        cropRect = cropRect
                    )
                    val uprightCropBounds = uprightCropBounds(cropRect, rotationDegrees)
                    val previewWidth = uprightCropBounds.width.toInt() * PREVIEW_SCALE
                    val previewHeight = uprightCropBounds.height.toInt() * PREVIEW_SCALE
                    val sourceTransform = imageProxyTransformFactory(
                        includeRotationDegrees = true
                    ).getOutputTransform(imageProxy)
                    val targetTransform = previewOutputTransform(
                        cropRect = cropRect,
                        rotationDegrees = rotationDegrees,
                        previewWidth = previewWidth,
                        previewHeight = previewHeight,
                        frontFacing = frontFacing
                    )

                    // Production binds Preview and ImageAnalysis in one UseCaseGroup with a shared
                    // ViewPort. OutputTransform only carries ViewPort-size metadata, not identity;
                    // matching metadata is modeled here, while actual binding is integration work.
                    val cameraXTransform = CoordinateTransform(sourceTransform, targetTransform)
                    val androidMatrix = Matrix()
                    cameraXTransform.transform(androidMatrix)

                    val matrixValues = FloatArray(9)
                    androidMatrix.getValues(matrixValues)
                    val productionTransform = FaceGeometryDisplayTransform(matrixValues.toList())

                    uprightMarkers(uprightCropBounds).forEach { marker ->
                        val mappedByCameraX = floatArrayOf(marker.x, marker.y)
                        androidMatrix.mapPoints(mappedByCameraX)

                        val mappedByProductionProjection =
                            productionTransform.mapPixel(marker.x, marker.y)
                        assertNotNull(
                            "Invalid mapped point: rotation=$rotationDegrees crop=$cropRect front=$frontFacing marker=$marker",
                            mappedByProductionProjection
                        )
                        assertEquals(
                            "Matrix serialization X mismatch: rotation=$rotationDegrees crop=$cropRect front=$frontFacing",
                            mappedByCameraX[0],
                            mappedByProductionProjection!!.x,
                            MATRIX_TOLERANCE
                        )
                        assertEquals(
                            "Matrix serialization Y mismatch: rotation=$rotationDegrees crop=$cropRect front=$frontFacing",
                            mappedByCameraX[1],
                            mappedByProductionProjection.y,
                            MATRIX_TOLERANCE
                        )

                        val expected = expectedPreviewPoint(
                            uprightPoint = marker,
                            uprightCropBounds = uprightCropBounds,
                            previewWidth = previewWidth,
                            previewHeight = previewHeight,
                            frontFacing = frontFacing
                        )
                        assertEquals(
                            "Preview X mismatch: rotation=$rotationDegrees crop=$cropRect front=$frontFacing marker=$marker",
                            expected.x,
                            mappedByCameraX[0],
                            PIXEL_TOLERANCE
                        )
                        assertEquals(
                            "Preview Y mismatch: rotation=$rotationDegrees crop=$cropRect front=$frontFacing marker=$marker",
                            expected.y,
                            mappedByCameraX[1],
                            PIXEL_TOLERANCE
                        )
                    }
                }
            }
        }
    }

    @Test
    fun rotationDisabledSourceMatchesUnrotatedInputOnly() {
        val imageProxy = fakeImageProxy(
            width = SENSOR_WIDTH,
            height = SENSOR_HEIGHT,
            rotationDegrees = 0,
            cropRect = Rect(0, 0, SENSOR_WIDTH, SENSOR_HEIGHT)
        )
        val bounds = uprightCropBounds(Rect(0, 0, SENSOR_WIDTH, SENSOR_HEIGHT), 0)
        val previewWidth = bounds.width.toInt() * PREVIEW_SCALE
        val previewHeight = bounds.height.toInt() * PREVIEW_SCALE
        val sourceTransform = imageProxyTransformFactory(
            includeRotationDegrees = false
        ).getOutputTransform(imageProxy)
        val targetTransform = previewOutputTransform(
            cropRect = Rect(0, 0, SENSOR_WIDTH, SENSOR_HEIGHT),
            rotationDegrees = 0,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            frontFacing = false
        )
        val cameraXTransform = CoordinateTransform(sourceTransform, targetTransform)
        val matrix = Matrix()
        cameraXTransform.transform(matrix)

        uprightMarkers(bounds).forEach { marker ->
            val mapped = floatArrayOf(marker.x, marker.y)
            matrix.mapPoints(mapped)
            assertEquals(marker.x * PREVIEW_SCALE, mapped[0], PIXEL_TOLERANCE)
            assertEquals(marker.y * PREVIEW_SCALE, mapped[1], PIXEL_TOLERANCE)
        }
    }

    @Test
    fun rotationDisabledSourceMisMapsAlreadyUprightLandmarksForNonZeroRotations() {
        listOf(90, 180, 270).forEach { rotationDegrees ->
            val cropRect = Rect(0, 0, SENSOR_WIDTH, SENSOR_HEIGHT)
            val imageProxy = fakeImageProxy(
                width = SENSOR_WIDTH,
                height = SENSOR_HEIGHT,
                rotationDegrees = rotationDegrees,
                cropRect = cropRect
            )
            val bounds = uprightCropBounds(cropRect, rotationDegrees)
            val previewWidth = bounds.width.toInt() * PREVIEW_SCALE
            val previewHeight = bounds.height.toInt() * PREVIEW_SCALE
            val sourceTransform = imageProxyTransformFactory(
                includeRotationDegrees = false
            ).getOutputTransform(imageProxy)
            val targetTransform = previewOutputTransform(
                cropRect = cropRect,
                rotationDegrees = rotationDegrees,
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                frontFacing = false
            )
            val cameraXTransform = CoordinateTransform(sourceTransform, targetTransform)
            val matrix = Matrix()
            cameraXTransform.transform(matrix)

            val hasMaterialMismatch = uprightMarkers(bounds).any { marker ->
                val mapped = floatArrayOf(marker.x, marker.y)
                matrix.mapPoints(mapped)
                val expected = expectedPreviewPoint(
                    uprightPoint = marker,
                    uprightCropBounds = bounds,
                    previewWidth = previewWidth,
                    previewHeight = previewHeight,
                    frontFacing = false
                )
                kotlin.math.abs(mapped[0] - expected.x) > MISMATCH_THRESHOLD_PX ||
                    kotlin.math.abs(mapped[1] - expected.y) > MISMATCH_THRESHOLD_PX
            }

            assertTrue(
                "Rotation-disabled source unexpectedly matched upright input for rotation=$rotationDegrees",
                hasMaterialMismatch
            )
        }
    }

    private fun imageProxyTransformFactory(
        includeRotationDegrees: Boolean
    ) = ImageProxyTransformFactory().apply {
        // Mirrors CameraController.geometryImageTransformFactory.
        setUsingCropRect(false)
        setUsingRotationDegrees(includeRotationDegrees)
    }

    private fun previewOutputTransform(
        cropRect: Rect,
        rotationDegrees: Int,
        previewWidth: Int,
        previewHeight: Int,
        frontFacing: Boolean
    ): OutputTransform = CameraXPreviewTransformFixture.outputTransform(
        context = ApplicationProvider.getApplicationContext(),
        previewResolution = Size(SENSOR_WIDTH, SENSOR_HEIGHT),
        previewCropRect = cropRect,
        rotationDegrees = rotationDegrees,
        viewWidth = previewWidth,
        viewHeight = previewHeight,
        isFrontCamera = frontFacing
    )

    private fun expectedPreviewPoint(
        uprightPoint: PointF,
        uprightCropBounds: Bounds,
        previewWidth: Int,
        previewHeight: Int,
        frontFacing: Boolean
    ): PointF {
        val unmirroredX =
            (uprightPoint.x - uprightCropBounds.left) * previewWidth / uprightCropBounds.width
        val x = if (frontFacing) previewWidth - unmirroredX else unmirroredX
        val y =
            (uprightPoint.y - uprightCropBounds.top) * previewHeight / uprightCropBounds.height
        return PointF(x, y)
    }

    private fun uprightCropBounds(cropRect: Rect, rotationDegrees: Int): Bounds {
        val corners = listOf(
            PointF(cropRect.left.toFloat(), cropRect.top.toFloat()),
            PointF(cropRect.right.toFloat(), cropRect.top.toFloat()),
            PointF(cropRect.right.toFloat(), cropRect.bottom.toFloat()),
            PointF(cropRect.left.toFloat(), cropRect.bottom.toFloat())
        ).map { point ->
            rotateRawPoint(point, SENSOR_WIDTH, SENSOR_HEIGHT, rotationDegrees)
        }
        return Bounds(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y }
        )
    }

    private fun rotateRawPoint(
        rawPoint: PointF,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int
    ): PointF = when (rotationDegrees) {
        0 -> PointF(rawPoint.x, rawPoint.y)
        90 -> PointF(rawHeight - rawPoint.y, rawPoint.x)
        180 -> PointF(rawWidth - rawPoint.x, rawHeight - rawPoint.y)
        270 -> PointF(rawPoint.y, rawWidth - rawPoint.x)
        else -> error("Unsupported rotation: $rotationDegrees")
    }

    private fun uprightMarkers(bounds: Bounds): List<PointF> = listOf(
        PointF(bounds.left, bounds.top),
        PointF(bounds.right, bounds.top),
        PointF(bounds.right, bounds.bottom),
        PointF(bounds.left, bounds.bottom),
        PointF(bounds.left + 13f, bounds.top + 29f),
        PointF(bounds.right - 37f, bounds.top + 53f),
        PointF(bounds.right - 61f, bounds.bottom - 23f),
        PointF(bounds.left + 47f, bounds.bottom - 71f)
    )

    private fun fakeImageProxy(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        cropRect: Rect
    ): ImageProxy {
        val imageInfo = Proxy.newProxyInstance(
            ImageInfo::class.java.classLoader,
            arrayOf(ImageInfo::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "getRotationDegrees" -> rotationDegrees
                    "getTimestamp" -> 1L
                    "getSensorToBufferTransformMatrix" -> Matrix()
                    "toString" -> "FakeImageInfo(rotation=$rotationDegrees)"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> throw AssertionError(
                        "Unexpected ImageInfo method requested by CameraX: ${method.name}"
                    )
                }
            }
        ) as ImageInfo

        return Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "getWidth" -> width
                    "getHeight" -> height
                    "getCropRect" -> Rect(cropRect)
                    "getImageInfo" -> imageInfo
                    "getFormat" -> ImageFormat.YUV_420_888
                    "getPlanes" -> emptyArray<ImageProxy.PlaneProxy>()
                    "getImage" -> null
                    "getTimestamp" -> 1L
                    "close" -> null
                    "setCropRect" -> null
                    "toString" -> "FakeImageProxy(${width}x$height, rotation=$rotationDegrees, crop=$cropRect)"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> throw AssertionError(
                        "Unexpected ImageProxy method requested by CameraX: ${method.name}"
                    )
                }
            }
        ) as ImageProxy
    }

    private data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    private companion object {
        const val SENSOR_WIDTH = 640
        const val SENSOR_HEIGHT = 480
        const val PREVIEW_SCALE = 2
        const val PIXEL_TOLERANCE = 0.05f
        const val MATRIX_TOLERANCE = 0.001f
        const val MISMATCH_THRESHOLD_PX = 10f
        val RIGHT_ANGLE_ROTATIONS = listOf(0, 90, 180, 270)
    }
}
