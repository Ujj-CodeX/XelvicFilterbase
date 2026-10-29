package com.example.xelvic.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Anchors supported by the legacy .xfilter layer pipeline.
 * Positions are calculated dynamically from ML Kit landmarks and face bounds.
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
        fun fromString(value: String): AnchorType = try {
            valueOf(value.uppercase())
        } catch (_: Exception) {
            FACE_CENTER
        }
    }
}

enum class ScaleMode {
    FACE_WIDTH,
    FACE_HEIGHT,
    EYE_DISTANCE;

    companion object {
        fun fromString(value: String): ScaleMode = try {
            valueOf(value.uppercase())
        } catch (_: Exception) {
            FACE_WIDTH
        }
    }
}

enum class RotationMode {
    FACE_ROLL,
    EYE_LINE,
    FIXED;

    companion object {
        fun fromString(value: String): RotationMode = try {
            valueOf(value.uppercase())
        } catch (_: Exception) {
            FACE_ROLL
        }
    }
}

/** Explicit renderer contract for the .xfilter manifest. */
enum class FilterRenderer {
    CANVAS,
    FACE_MESH_TEXTURE
}

/** Supported blend modes are deliberately narrow until the GL pipeline is validated. */
enum class MeshTextureBlendMode {
    NORMAL
}

/**
 * Schema-v2 face-surface texture metadata.
 * Parsing this metadata does not mean its UV layout is trusted or renderable; the UV registry
 * and RenderBackendRouter must validate model/topology compatibility before a future GL draw.
 */
data class MeshTextureDefinition(
    val asset: String,
    val uvLayout: String,
    val blendMode: MeshTextureBlendMode,
    val opacity: Float,
    val regionMask: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): MeshTextureDefinition {
            val asset = json.optString("asset", "")
            require(asset.isNotBlank()) { "meshTexture.asset is required" }

            val uvLayout = json.optString("uvLayout", "")
            require(uvLayout.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) {
                "meshTexture.uvLayout is invalid"
            }

            val blendMode = when (json.optString("blendMode", "")) {
                "NORMAL" -> MeshTextureBlendMode.NORMAL
                else -> throw IllegalArgumentException(
                    "Unsupported meshTexture.blendMode; only NORMAL is supported"
                )
            }

            val opacityValue = json.optDouble("opacity", Double.NaN)
            require(opacityValue.isFinite() && opacityValue in 0.0..1.0) {
                "meshTexture.opacity must be finite and within [0, 1]"
            }

            val regionMask = if (!json.has("regionMask") || json.isNull("regionMask")) {
                null
            } else {
                json.optString("regionMask", "").also { value ->
                    require(value.isNotBlank()) { "meshTexture.regionMask cannot be blank" }
                }
            }

            return MeshTextureDefinition(
                asset = asset,
                uvLayout = uvLayout,
                blendMode = blendMode,
                opacity = opacityValue.toFloat(),
                regionMask = regionMask
            )
        }
    }
}

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

/** Parsed, renderer-independent filter manifest. */
data class FilterDefinition(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val minimumRuntimeVersion: Int,
    val thumbnail: String,
    val layers: List<FilterLayer>,
    val renderer: FilterRenderer = FilterRenderer.CANVAS,
    val meshTexture: MeshTextureDefinition? = null
) {
    companion object {
        const val CURRENT_SUPPORTED_SCHEMA = 2
        const val CURRENT_RUNTIME_VERSION = 2

        fun fromJson(jsonString: String): FilterDefinition {
            val root = JSONObject(jsonString)
            val schema = root.optInt("schemaVersion", 1)
            val minRuntime = root.optInt("minimumRuntimeVersion", 1)
            require(schema in 1..CURRENT_SUPPORTED_SCHEMA) {
                "Unsupported schema version: $schema"
            }
            require(minRuntime in 1..CURRENT_RUNTIME_VERSION) {
                "Unsupported minimum runtime version: $minRuntime"
            }
            require(schema != 2 || minRuntime >= 2) {
                "Schema v2 requires minimumRuntimeVersion 2"
            }

            val renderer: FilterRenderer
            val meshTexture: MeshTextureDefinition?
            when (schema) {
                1 -> {
                    require(!root.has("renderer") && !root.has("meshTexture")) {
                        "Schema v1 cannot declare mesh rendering fields"
                    }
                    renderer = FilterRenderer.CANVAS
                    meshTexture = null
                }
                2 -> {
                    require(root.optString("renderer", "") == "FACE_MESH_TEXTURE") {
                        "Schema v2 renderer must be FACE_MESH_TEXTURE"
                    }
                    val meshJson = root.optJSONObject("meshTexture")
                        ?: throw IllegalArgumentException("Schema v2 requires meshTexture object")
                    meshTexture = MeshTextureDefinition.fromJson(meshJson)
                    renderer = FilterRenderer.FACE_MESH_TEXTURE
                }
                else -> throw IllegalArgumentException("Unsupported schema version: $schema")
            }

            val rawLayers = root.optJSONArray("layers")
            if (schema == 1) {
                require(rawLayers != null) { "Schema v1 requires a layers array" }
            } else {
                require(!root.has("layers") || (rawLayers != null && rawLayers.length() == 0)) {
                    "Schema v2 mesh filters must not contain legacy layers"
                }
            }

            val layersList = mutableListOf<FilterLayer>()
            val layersArray: JSONArray = rawLayers ?: JSONArray()
            for (i in 0 until layersArray.length()) {
                val layerJson = layersArray.getJSONObject(i)
                layersList.add(FilterLayer.fromJson(layerJson))
            }

            return FilterDefinition(
                schemaVersion = schema,
                id = root.getString("id"),
                name = root.getString("name"),
                version = root.optString("version", "1.0.0"),
                description = root.optString("description", ""),
                minimumRuntimeVersion = minRuntime,
                thumbnail = root.optString("thumbnail", "thumbnail.png"),
                layers = layersList.sortedBy { it.zIndex },
                renderer = renderer,
                meshTexture = meshTexture
            )
        }
    }
}
