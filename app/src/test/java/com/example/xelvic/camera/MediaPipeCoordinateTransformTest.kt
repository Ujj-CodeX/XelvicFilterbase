package com.example.xelvic.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import android.util.Size
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FlashState
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImmutableImageInfo
import androidx.camera.core.impl.TagBundle
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.example.xelvic.geometry.FaceGeometryDisplayTransform
import com.example.xelvic.geometry.FaceGeometryRenderFrame
import com.example.xelvic.geometry.FaceGeometrySnapshot
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvMap
import com.example.xelvic.geometry.FaceUvCoordinate
import com.example.xelvic.geometry.FaceVertex
import com.example.xelvic.geometry.UvOrigin
import com.example.xelvic.perf.AdaptivePerformanceController
import com.example.xelvic.perf.PerformanceMonitor
import com.example.xelvic.renderer.FaceMeshGlFrameBuilder
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [MediaPipeCoordinateTransformTest.UnusedFaceDetectionShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@androidx.annotation.OptIn(TransformExperimental::class, ExperimentalGetImage::class)
class MediaPipeCoordinateTransformTest {
    private lateinit var context: Context
    private lateinit var analyzer: FaceAnalyzer
    private lateinit var controller: CameraController

    private val modelHash = "a".repeat(64)
    private val layoutId = "coordinate-transform-test"
    private val uvMap = FaceMeshUvMap.validate(
        sourceTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
        expectedTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
        sourceModelAssetSha256 = modelHash,
        activeModelAssetSha256 = modelHash,
        sourceUvAssetSha256 = "b".repeat(64),
        expectedUvAssetSha256 = "b".repeat(64),
        coordinates = List(FaceMeshTessellation.LANDMARK_COUNT) {
            FaceUvCoordinate(0.25f, 0.75f)
        },
        uvOrigin = UvOrigin.TOP_LEFT,
        uvLayoutId = layoutId
    ).uvMap!!

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val monitor = PerformanceMonitor(context)
        analyzer = FaceAnalyzer(CoordinateMapper(), monitor, AdaptivePerformanceController(monitor)) {}
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle = LifecycleRegistry(this)
        }
        controller = CameraController(context, owner, analyzer)
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.release()
        if (::analyzer.isInitialized) analyzer.release()
    }

    @Test
    fun uprightZeroDegreeLandmarksKeepViewportCropAndFrontCameraMirror() = verifyRotation(0)

    @Test
    fun uprightNinetyDegreeLandmarksKeepViewportCropAndFrontCameraMirror() = verifyRotation(90)

    @Test
    fun uprightOneHundredEightyDegreeLandmarksKeepViewportCropAndFrontCameraMirror() = verifyRotation(180)

    @Test
    fun uprightTwoHundredSeventyDegreeLandmarksKeepViewportCropAndFrontCameraMirror() = verifyRotation(270)

    private fun verifyRotation(rotationDegrees: Int) {
        val factory = ReflectionHelpers.getField<ImageProxyTransformFactory>(
            controller,
            "geometryImageTransformFactory"
        )
        assertFalse("Inference uses the full bitmap, not crop-relative coordinates", factory.isUsingCropRect)
        assertTrue("The source space must describe the physically upright bitmap", factory.isUsingRotationDegrees)

        for (crop in listOf(Rect(0, 0, BUFFER_WIDTH, BUFFER_HEIGHT), Rect(80, 60, 560, 360))) {
            val markers = listOf(
                Marker(Color.RED, crop.left, crop.top),
                Marker(Color.GREEN, crop.right - 1, crop.top),
                Marker(Color.BLUE, crop.left, crop.bottom - 1),
                Marker(Color.YELLOW, crop.right - 1, crop.bottom - 1),
                Marker(Color.MAGENTA, crop.left + crop.width() / 3, crop.top + crop.height() / 4),
                // Outside the offset crop: inference still uses full-image coordinates.
                Marker(Color.CYAN, 10, 15)
            )
            val pixels = IntArray(BUFFER_WIDTH * BUFFER_HEIGHT) { Color.BLACK }
            for (y in crop.top until crop.bottom) {
                for (x in crop.left until crop.right) pixels[y * BUFFER_WIDTH + x] = CROP_COLOR
            }
            markers.forEach { pixels[it.y * BUFFER_WIDTH + it.x] = it.color }
            val source = Bitmap.createBitmap(pixels, BUFFER_WIDTH, BUFFER_HEIGHT, Bitmap.Config.ARGB_8888)
            var upright: Bitmap? = null

            try {
                // Match the unchanged provider's physical bitmap rotation; do not rotate landmarks.
                val bitmap = if (rotationDegrees == 0) source else Bitmap.createBitmap(
                    source, 0, 0, source.width, source.height,
                    Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true
                )
                upright = bitmap
                val uprightPixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(uprightPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

                // Read the rotated crop and markers from rendered pixels, independently of CameraX matrices.
                val uprightCrop = Rect(bitmap.width, bitmap.height, 0, 0)
                uprightPixels.forEachIndexed { index, color ->
                    if (color == CROP_COLOR) {
                        val x = index % bitmap.width
                        val y = index / bitmap.width
                        uprightCrop.left = minOf(uprightCrop.left, x)
                        uprightCrop.top = minOf(uprightCrop.top, y)
                        uprightCrop.right = maxOf(uprightCrop.right, x + 1)
                        uprightCrop.bottom = maxOf(uprightCrop.bottom, y + 1)
                    }
                }
                val points = markers.map { marker ->
                    val index = uprightPixels.indexOf(marker.color)
                    assertTrue("Physical rotation must retain marker ${marker.color}", index >= 0)
                    // CameraX transforms image coordinates, so use each pixel's center, not its edge.
                    floatArrayOf(index % bitmap.width + 0.5f, index / bitmap.width + 0.5f)
                }
                val proxy = MetadataImageProxy(crop, rotationDegrees)
                val viewWidth = uprightCrop.width() * 3 / 2
                val viewHeight = uprightCrop.height() * 3 / 2

                for (isFrontCamera in listOf(false, true)) {
                    val target = previewOutputTransform(crop, rotationDegrees, isFrontCamera, viewWidth, viewHeight)
                    ReflectionHelpers.setField(controller, "cachedGeometryPreviewTransform", target)
                    val transform = requireNotNull(
                        ReflectionHelpers.callInstanceMethod<FaceGeometryDisplayTransform?>(
                            controller,
                            "computeGeometryDisplayTransform",
                            ClassParameter.from(ImageProxy::class.java, proxy)
                        )
                    )
                    val cameraXMatrix = Matrix()
                    CoordinateTransform(factory.getOutputTransform(proxy), target).transform(cameraXMatrix)
                    val matrixPreviewPoints = points.flatMap { it.toList() }.toFloatArray()
                    cameraXMatrix.mapPoints(matrixPreviewPoints)
                    val productionPoint = FloatArray(2)
                    points.forEachIndexed { index, point ->
                        assertTrue(transform.mapPixelInto(point[0], point[1], productionPoint))
                        assertEquals("CameraX Matrix -> production X parity", matrixPreviewPoints[index * 2],
                            productionPoint[0], EPSILON)
                        assertEquals("CameraX Matrix -> production Y parity", matrixPreviewPoints[index * 2 + 1],
                            productionPoint[1], EPSILON)
                    }
                    val snapshot = FaceGeometrySnapshot(
                        timestampMs = 100L,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        rotationDegrees = rotationDegrees,
                        isMirrored = isFrontCamera,
                        landmarks = List(FaceMeshTessellation.LANDMARK_COUNT) { index ->
                            val point = points[index % points.size]
                            FaceVertex(point[0] / bitmap.width, point[1] / bitmap.height, -0.01f)
                        },
                        topologyId = FaceMeshTessellation.TOPOLOGY_ID,
                        modelAssetSha256 = modelHash
                    )
                    val positions = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2)
                    assertTrue(
                        FaceMeshGlFrameBuilder.update(
                            renderFrame = FaceGeometryRenderFrame(snapshot, transform),
                            uvMap = uvMap,
                            requiredUvLayoutId = layoutId,
                            requiredModelAssetSha256 = modelHash,
                            requiredTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                            positionsNdc = positions,
                            textureCoordinates = FloatArray(positions.size),
                            mappedPoint = FloatArray(2)
                        )
                    )
                    val expectedPreviewPoints = FloatArray(points.size * 2)
                    points.forEachIndexed { index, point ->
                        val x = (point[0] - uprightCrop.left) * viewWidth / uprightCrop.width()
                        val y = (point[1] - uprightCrop.top) * viewHeight / uprightCrop.height()
                        expectedPreviewPoints[index * 2] = if (isFrontCamera) viewWidth - x else x
                        expectedPreviewPoints[index * 2 + 1] = y
                    }
                    for (index in snapshot.landmarks.indices) {
                        val pointOffset = (index % points.size) * 2
                        val label = "rotation=$rotationDegrees front=$isFrontCamera crop=$crop vertex=$index"
                        assertEquals(label + " CameraX -> GLES X parity",
                            2f * matrixPreviewPoints[pointOffset] / viewWidth - 1f,
                            positions[index * 2], EPSILON)
                        assertEquals(label + " CameraX -> GLES Y parity",
                            1f - 2f * matrixPreviewPoints[pointOffset + 1] / viewHeight,
                            positions[index * 2 + 1], EPSILON)
                        assertEquals(label, 2f * expectedPreviewPoints[pointOffset] / viewWidth - 1f,
                            positions[index * 2], EPSILON)
                        assertEquals(label, 1f - 2f * expectedPreviewPoints[pointOffset + 1] / viewHeight,
                            positions[index * 2 + 1], EPSILON)
                    }

                    if (rotationDegrees != 0) {
                        // Negative control: CameraX's unrotated source misinterprets upright coordinates.
                        val unrotatedFactory = ImageProxyTransformFactory().apply {
                            setUsingCropRect(false)
                            setUsingRotationDegrees(false)
                        }
                        val wrongPoints = points.flatMap { it.toList() }.toFloatArray()
                        CoordinateTransform(unrotatedFactory.getOutputTransform(proxy), target).mapPoints(wrongPoints)
                        assertTrue(
                            "Disabling source rotation must fail this non-square fixture at $rotationDegrees degrees",
                            wrongPoints.indices.any { abs(wrongPoints[it] - expectedPreviewPoints[it]) > EPSILON }
                        )
                    }
                }
            } finally {
                if (upright != null && upright !== source) upright.recycle()
                source.recycle()
            }
        }
    }

    private fun previewOutputTransform(
        sourceCrop: Rect,
        rotationDegrees: Int,
        isFrontCamera: Boolean,
        viewWidth: Int,
        viewHeight: Int
    ): OutputTransform = CameraXPreviewTransformFixture.outputTransform(
        context = context,
        previewResolution = Size(BUFFER_WIDTH * 2, BUFFER_HEIGHT * 2),
        previewCropRect = Rect(sourceCrop.left * 2, sourceCrop.top * 2, sourceCrop.right * 2, sourceCrop.bottom * 2),
        rotationDegrees = rotationDegrees,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        isFrontCamera = isFrontCamera
    )

    /**
     * CameraController requires FaceAnalyzer, but this test never performs ML Kit inference.
     * Keep that unrelated dependency inert: close is allowed; any inference call fails the test.
     * CameraX, CameraController and all coordinate/render calculations remain unshadowed.
     */
    @Implements(value = FaceDetection::class, isInAndroidSdk = false)
    class UnusedFaceDetectionShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun getClient(options: FaceDetectorOptions): FaceDetector = Proxy.newProxyInstance(
                FaceDetector::class.java.classLoader,
                arrayOf(FaceDetector::class.java)
            ) { _, method, _ ->
                check(method.name == "close") {
                    "Coordinate transform test must not call FaceDetector.${method.name}"
                }
                null
            } as FaceDetector
        }
    }

    private data class Marker(val color: Int, val x: Int, val y: Int)

    private class MetadataImageProxy(cropRect: Rect, rotationDegrees: Int) : ImageProxy {
        private var crop = Rect(cropRect)
        private val info = ImmutableImageInfo.create(
            TagBundle.emptyBundle(), 100L, rotationDegrees, Matrix(), FlashState.UNKNOWN
        )

        override fun getWidth(): Int = BUFFER_WIDTH
        override fun getHeight(): Int = BUFFER_HEIGHT
        override fun getCropRect(): Rect = Rect(crop)
        override fun setCropRect(rect: Rect?) {
            crop = rect?.let(::Rect) ?: Rect(0, 0, BUFFER_WIDTH, BUFFER_HEIGHT)
        }
        override fun getImageInfo(): ImageInfo = info
        override fun getFormat(): Int = ImageFormat.YUV_420_888
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = emptyArray()
        override fun getImage(): Image? = null
        override fun close() = Unit
    }

    private companion object {
        const val BUFFER_WIDTH = 640
        const val BUFFER_HEIGHT = 480
        const val CROP_COLOR = 0xFF202020.toInt()
        const val EPSILON = 0.0001f
    }
}
