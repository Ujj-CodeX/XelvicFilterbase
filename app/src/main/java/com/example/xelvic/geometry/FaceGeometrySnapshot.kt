package com.example.xelvic.geometry

import java.util.Collections

/**
 * Immutable geometry captured from one face-landmarker result.
 *
 * Landmark x/y are normalized to the upright, unmirrored inference image.
 * z is MediaPipe's relative depth output, not calibrated metric depth.
 * imageWidth/imageHeight describe that upright inference image.
 *
 * rotationDegrees records the source-frame rotation that was applied before inference.
 * isMirrored describes the intended preview/display mirror transform; pixels sent to the
 * landmarker are deliberately not mirrored so subject-left/right blendshapes stay stable.
 *
 * Matrix values preserve MediaPipe's source order (column-major). Consumers must not
 * transpose or interpret the matrix until the renderer's matrix convention is verified.
 */
class FaceGeometrySnapshot(
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val isMirrored: Boolean,
    landmarks: List<FaceVertex>,
    blendshapes: List<BlendShapeValue> = emptyList(),
    facialTransformationMatrix: List<Float>? = null,
    val topologyId: String? = null
) {
    val landmarks: List<FaceVertex> =
        Collections.unmodifiableList(ArrayList(landmarks))

    val blendshapes: List<BlendShapeValue> =
        Collections.unmodifiableList(ArrayList(blendshapes))

    val facialTransformationMatrix: List<Float>? =
        facialTransformationMatrix?.let { matrix ->
            require(matrix.size == 16) {
                "Facial transformation matrix must contain 16 values"
            }
            Collections.unmodifiableList(ArrayList(matrix))
        }

    init {
        require(timestampMs >= 0L) { "Timestamp must be non-negative" }
        require(imageWidth > 0 && imageHeight > 0) {
            "Image dimensions must be positive"
        }
        require(
            rotationDegrees == 0 ||
                rotationDegrees == 90 ||
                rotationDegrees == 180 ||
                rotationDegrees == 270
        ) {
            "Unsupported image rotation: $rotationDegrees"
        }
        require(this.landmarks.isNotEmpty()) {
            "A face snapshot must contain landmarks"
        }
        require(this.landmarks.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) {
            "Landmark coordinates must be finite"
        }
        require(this.blendshapes.all { it.categoryName.isNotBlank() && it.score.isFinite() && it.score in 0f..1f }) {
            "Blendshape categories must be named and scores must be within [0, 1]"
        }
        require(this.facialTransformationMatrix?.all { it.isFinite() } != false) {
            "Facial transformation matrix values must be finite"
        }
    }
}

data class FaceVertex(
    val x: Float,
    val y: Float,
    val z: Float
)

data class BlendShapeValue(
    val categoryName: String,
    val score: Float
)
