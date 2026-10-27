package com.example.xelvic.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Anchors supported by the .xfilter rendering pipeline.
 * All positions are calculated dynamically from ML Kit landmarks and face bounds.
 */
enum class AnchorType {
    FACE_CENTER,
    FOREHEAD_TOP,
    EYES_CENTER,
    LEFT_EYE,
    RIGHT_EYE,
    NOSE,
    MOUTH,
    LEFT_CHEEK,
    RIGHT_CHEEK;

    companion object {
        fun fromString(value: String): AnchorType {
            return try {
                valueOf(value.uppercase())
            } catch (e: Exception) {
                FACE_CENTER
            }
        }
    }
}

/**
 * Scaling mode relative to face dimensions.
 */
enum class ScaleMode {
    FACE_WIDTH,
    FACE_HEIGHT,
    EYE_DISTANCE;

    companion object {
        fun fromString(value: String): ScaleMode {
            return try {
                valueOf(value.uppercase())
            } catch (e: Exception) {
                FACE_WIDTH
            }
        }
    }
}

/**
 * Rotation mode relative to face pose.
 */
enum class RotationMode {
    FACE_ROLL,
    EYE_LINE,
    FIXED;

    companion object {
        fun fromString(value: String): RotationMode {
            return try {
                valueOf(value.uppercase())
            } catch (e: Exception) {
                FACE_ROLL
            }
        }
    }
}

/**
 * Individual layer definition in a filter manifest.
 */
data class FilterLayer(
    val id: String,
    val asset: String,
    val anchor: AnchorType,
    val scaleMode: ScaleMode,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val rotationMode: RotationMode,
    val rotationDegrees: Float,
    val opacity: Float,
    val zIndex: Int
) {
    companion object {
        fun fromJson(json: JSONObject): FilterLayer {
            return FilterLayer(
                id = json.optString("id", "layer_${System.nanoTime()}"),
                asset = json.getString("asset"),
                anchor = AnchorType.fromString(json.optString("anchor", "FOREHEAD_TOP")),
                scaleMode = ScaleMode.fromString(json.optString("scaleMode", "FACE_WIDTH")),
                scale = json.optDouble("scale", 1.0).toFloat(),
                offsetX = json.optDouble("offsetX", 0.0).toFloat(),
                offsetY = json.optDouble("offsetY", 0.0).toFloat(),
                rotationMode = RotationMode.fromString(json.optString("rotationMode", "FACE_ROLL")),
                rotationDegrees = json.optDouble("rotationDegrees", 0.0).toFloat(),
                opacity = json.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                zIndex = json.optInt("zIndex", 0)
            )
        }
    }
}

/**
 * Root filter package manifest.
 */
data class FilterDefinition(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val minimumRuntimeVersion: Int,
    val thumbnail: String,
    val layers: List<FilterLayer>
) {
    companion object {
        const val CURRENT_SUPPORTED_SCHEMA = 1
        const val CURRENT_RUNTIME_VERSION = 1

        fun fromJson(jsonString: String): FilterDefinition {
            val root = JSONObject(jsonString)
            val schema = root.optInt("schemaVersion", 1)
            val minRuntime = root.optInt("minimumRuntimeVersion", 1)
            val id = root.getString("id")
            val name = root.getString("name")
            val version = root.optString("version", "1.0.0")
            val description = root.optString("description", "")
            val thumbnail = root.optString("thumbnail", "thumbnail.png")

            val layersList = mutableListOf<FilterLayer>()
            val layersArray: JSONArray = root.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until layersArray.length()) {
                val layerJson = layersArray.getJSONObject(i)
                layersList.add(FilterLayer.fromJson(layerJson))
            }

            return FilterDefinition(
                schemaVersion = schema,
                id = id,
                name = name,
                version = version,
                description = description,
                minimumRuntimeVersion = minRuntime,
                thumbnail = thumbnail,
                layers = layersList.sortedBy { it.zIndex }
            )
        }
    }
}
