#!/usr/bin/env python3
"""Prepare Xelvic .xfilter schema-v2 metadata support without committing anything.

Run from the XelvicFilterbase repository root:
  python apply_xelvic_schema_v2.py             # check expected source anchors only
  python apply_xelvic_schema_v2.py --apply     # apply changes and write backups to %TEMP%

The script intentionally does NOT enable mesh-package installation: the GL mesh renderer and
model-compatible UV registry are not yet wired into the live camera path.
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
from datetime import datetime
from pathlib import Path

MODEL_PATH = Path("app/src/main/java/com/example/xelvic/model/FilterDefinition.kt")
MANAGER_PATH = Path("app/src/main/java/com/example/xelvic/packagemanager/FilterPackageManager.kt")
HARDENING_TEST_PATH = Path("app/src/test/java/com/example/FilterPackageManagerHardeningTest.kt")
MODEL_TEST_PATH = Path("app/src/test/java/com/example/xelvic/model/FilterDefinitionSchemaV2Test.kt")
README_PATH = Path("README.md")

MODEL_SOURCE = r'''package com.example.xelvic.model

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
'''

VALIDATE_MANIFEST = r'''    private fun validateManifestDocument(content: String) {
        val root = try {
            JSONObject(content)
        } catch (e: Exception) {
            throw FilterPackageException("Manifest is not valid JSON", e)
        }

        val schemaVersion = root.optInt("schemaVersion", -1)
        val runtimeVersion = root.optInt("minimumRuntimeVersion", -1)
        val id = root.optString("id", "")
        val name = root.optString("name", "")
        val version = root.optString("version", "")

        if (schemaVersion !in 1..FilterDefinition.CURRENT_SUPPORTED_SCHEMA) {
            throw FilterPackageException("Unsupported schema version: $schemaVersion")
        }
        if (runtimeVersion !in 1..FilterDefinition.CURRENT_RUNTIME_VERSION) {
            throw FilterPackageException("Unsupported minimum runtime version: $runtimeVersion")
        }
        if (schemaVersion == 2 && runtimeVersion < 2) {
            throw FilterPackageException("Schema v2 requires minimumRuntimeVersion 2")
        }

        if (!id.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) {
            throw FilterPackageException("Invalid package ID")
        }
        if (name.isBlank() || name.length > 100) {
            throw FilterPackageException("Invalid package name")
        }
        if (version.isBlank() || version.length > 50) {
            throw FilterPackageException("Invalid package version")
        }

        if (schemaVersion == 2) {
            if (root.has("layers")) {
                val v2Layers = root.optJSONArray("layers")
                if (v2Layers == null || v2Layers.length() != 0) {
                    throw FilterPackageException(
                        "Schema v2 mesh filters must not contain legacy layers"
                    )
                }
            }
            if (root.optString("renderer", "") != "FACE_MESH_TEXTURE") {
                throw FilterPackageException(
                    "Schema v2 renderer must be FACE_MESH_TEXTURE"
                )
            }

            val mesh = root.optJSONObject("meshTexture")
                ?: throw FilterPackageException("Schema v2 requires a meshTexture object")
            if (mesh.optString("asset", "").isBlank()) {
                throw FilterPackageException("meshTexture.asset is required")
            }
            val uvLayout = mesh.optString("uvLayout", "")
            if (!uvLayout.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) {
                throw FilterPackageException("Invalid meshTexture.uvLayout")
            }
            if (mesh.optString("blendMode", "") != "NORMAL") {
                throw FilterPackageException(
                    "Unsupported meshTexture.blendMode; only NORMAL is supported"
                )
            }
            val meshOpacity = readFiniteNumber(mesh, "opacity")
            if (meshOpacity !in 0.0..1.0) {
                throw FilterPackageException("meshTexture.opacity must be within [0, 1]")
            }
            if (mesh.has("regionMask") && !mesh.isNull("regionMask") &&
                mesh.optString("regionMask", "").isBlank()
            ) {
                throw FilterPackageException("meshTexture.regionMask cannot be blank")
            }
            return
        }

        if (root.has("renderer") || root.has("meshTexture")) {
            throw FilterPackageException("Schema v1 cannot declare mesh rendering fields")
        }
        val layers = root.optJSONArray("layers")
            ?: throw FilterPackageException("Schema v1 manifest must contain a layers array")
        if (layers.length() !in 1..MAX_LAYERS) {
            throw FilterPackageException(
                "Package must contain between 1 and $MAX_LAYERS layers"
            )
        }

        val allowedAnchors = setOf(
            "FACE_CENTER", "FOREHEAD_TOP", "EYES_CENTER",
            "LEFT_EYE", "RIGHT_EYE", "NOSE", "MOUTH",
            "LEFT_CHEEK", "RIGHT_CHEEK"
        )
        val allowedScaleModes = setOf("FACE_WIDTH", "FACE_HEIGHT", "EYE_DISTANCE")
        val allowedRotationModes = setOf("FACE_ROLL", "EYE_LINE", "FIXED")
        val layerIds = mutableSetOf<String>()

        for (index in 0 until layers.length()) {
            val layer = layers.optJSONObject(index)
                ?: throw FilterPackageException("Layer $index is not an object")
            val layerId = layer.optString("id", "")
            val asset = layer.optString("asset", "")
            val anchor = layer.optString("anchor", "").uppercase(Locale.ROOT)
            val scaleMode = layer.optString("scaleMode", "").uppercase(Locale.ROOT)
            val rotationMode = layer.optString("rotationMode", "").uppercase(Locale.ROOT)

            if (!layerId.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) {
                throw FilterPackageException("Invalid ID in layer $index")
            }
            if (!layerIds.add(layerId)) {
                throw FilterPackageException("Duplicate layer ID: $layerId")
            }
            if (asset.isBlank()) {
                throw FilterPackageException("Layer $layerId has no asset path")
            }
            if (anchor !in allowedAnchors) {
                throw FilterPackageException("Unsupported anchor in layer $layerId")
            }
            if (scaleMode !in allowedScaleModes) {
                throw FilterPackageException("Unsupported scale mode in layer $layerId")
            }
            if (rotationMode !in allowedRotationModes) {
                throw FilterPackageException("Unsupported rotation mode in layer $layerId")
            }

            val scale = readFiniteNumber(layer, "scale")
            val offsetX = readFiniteNumber(layer, "offsetX")
            val offsetY = readFiniteNumber(layer, "offsetY")
            val rotation = readFiniteNumber(layer, "rotationDegrees")
            val opacity = readFiniteNumber(layer, "opacity")
            if (scale <= 0.0 || scale > 10.0) {
                throw FilterPackageException("Invalid scale in layer $layerId")
            }
            if (offsetX !in -5.0..5.0 || offsetY !in -5.0..5.0) {
                throw FilterPackageException("Offsets out of range in layer $layerId")
            }
            if (rotation !in -3600.0..3600.0) {
                throw FilterPackageException("Rotation out of range in layer $layerId")
            }
            if (opacity !in 0.0..1.0) {
                throw FilterPackageException("Opacity out of range in layer $layerId")
            }
            if (!layer.has("zIndex") || layer.isNull("zIndex")) {
                throw FilterPackageException("Layer $layerId is missing zIndex")
            }
            val zIndex = layer.optInt("zIndex", Int.MIN_VALUE)
            if (zIndex !in -1000..1000) {
                throw FilterPackageException("Invalid zIndex in layer $layerId")
            }
        }
    }
'''

VALIDATE_PACKAGE_ASSETS = r'''    private fun validatePackageAssets(
        packageRoot: File,
        definition: FilterDefinition
    ) {
        val thumbnail = resolvePackageFile(
            packageRoot,
            definition.thumbnail,
            setOf("png", "webp")
        )
        validateImageBounds(thumbnail, "thumbnail")

        for (layer in definition.layers) {
            val asset = resolvePackageFile(
                packageRoot,
                layer.asset,
                setOf("png", "webp")
            )
            validateImageBounds(asset, "layer ${layer.id}")
        }

        definition.meshTexture?.let { mesh ->
            val texture = resolvePackageFile(
                packageRoot,
                mesh.asset,
                setOf("png", "webp")
            )
            validateImageBounds(texture, "mesh texture")

            mesh.regionMask?.let { relativePath ->
                val mask = resolvePackageFile(
                    packageRoot,
                    relativePath,
                    setOf("png", "webp")
                )
                validateImageBounds(mask, "mesh region mask")
            }
        }
    }
'''

MODEL_TEST = r'''package com.example.xelvic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterDefinitionSchemaV2Test {

    @Test
    fun schemaV1RemainsCanvasAndKeepsItsLayers() {
        val definition = FilterDefinition.fromJson(
            """
            {
              "schemaVersion": 1,
              "minimumRuntimeVersion": 1,
              "id": "legacy_filter",
              "name": "Legacy Filter",
              "version": "1.0.0",
              "thumbnail": "thumbnail.png",
              "layers": [
                {
                  "id": "layer_1",
                  "asset": "assets/overlay.png",
                  "anchor": "FACE_CENTER",
                  "scaleMode": "FACE_WIDTH",
                  "scale": 1.0,
                  "offsetX": 0.0,
                  "offsetY": 0.0,
                  "rotationMode": "FACE_ROLL",
                  "rotationDegrees": 0.0,
                  "opacity": 1.0,
                  "zIndex": 0
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(FilterRenderer.CANVAS, definition.renderer)
        assertNull(definition.meshTexture)
        assertEquals(1, definition.layers.size)
    }

    @Test
    fun schemaV2ParsesMeshMetadataWithoutLegacyLayers() {
        val definition = FilterDefinition.fromJson(
            """
            {
              "schemaVersion": 2,
              "minimumRuntimeVersion": 2,
              "id": "mesh_filter",
              "name": "Mesh Filter",
              "version": "1.0.0",
              "thumbnail": "thumbnail.png",
              "renderer": "FACE_MESH_TEXTURE",
              "meshTexture": {
                "asset": "assets/face_paint.png",
                "uvLayout": "uv-layout-pending",
                "blendMode": "NORMAL",
                "opacity": 0.85,
                "regionMask": "assets/face_mask.png"
              }
            }
            """.trimIndent()
        )

        assertEquals(FilterRenderer.FACE_MESH_TEXTURE, definition.renderer)
        assertTrue(definition.layers.isEmpty())
        assertEquals("assets/face_paint.png", definition.meshTexture!!.asset)
        assertEquals("uv-layout-pending", definition.meshTexture.uvLayout)
        assertEquals(MeshTextureBlendMode.NORMAL, definition.meshTexture.blendMode)
        assertEquals(0.85f, definition.meshTexture.opacity, 0.0001f)
        assertEquals("assets/face_mask.png", definition.meshTexture.regionMask)
    }

    @Test(expected = IllegalArgumentException::class)
    fun schemaV2RejectsUnknownBlendMode() {
        val root = JSONObject(
            """
            {
              "schemaVersion": 2,
              "minimumRuntimeVersion": 2,
              "id": "mesh_filter",
              "name": "Mesh Filter",
              "renderer": "FACE_MESH_TEXTURE",
              "meshTexture": {
                "asset": "assets/face_paint.png",
                "uvLayout": "uv-layout-pending",
                "blendMode": "SCREEN",
                "opacity": 1.0
              }
            }
            """.trimIndent()
        )
        FilterDefinition.fromJson(root.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun schemaV1CannotOptIntoMeshRenderer() {
        FilterDefinition.fromJson(
            """
            {
              "schemaVersion": 1,
              "minimumRuntimeVersion": 1,
              "id": "legacy_filter",
              "name": "Legacy Filter",
              "renderer": "FACE_MESH_TEXTURE",
              "layers": []
            }
            """.trimIndent()
        )
    }
}
'''

PACKAGE_TEST_INSERT = r'''    @Test
    fun rejectsSchemaV2MeshInstallUntilRendererIsReadyAndPreservesExistingFilter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = prepareManager(context)
        val originalManifest = installedCatManifest(manager)
        val entries = readBundledPackage(context, "cat_face.xfilter")
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))

        manifest.put("schemaVersion", 2)
        manifest.put("minimumRuntimeVersion", 2)
        manifest.put("renderer", "FACE_MESH_TEXTURE")
        manifest.remove("layers")
        manifest.put(
            "meshTexture",
            JSONObject()
                .put("asset", "assets/cat_nose.png")
                .put("uvLayout", "uv-layout-pending")
                .put("blendMode", "NORMAL")
                .put("opacity", 1.0)
        )
        entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)

        val failure = assertThrows(FilterPackageException::class.java) {
            manager.installPackage(ByteArrayInputStream(buildArchive(entries)))
        }

        assertTrue(failure.message.orEmpty().contains("not installable yet"))
        assertEquals(originalManifest, installedCatManifest(manager))
    }

'''

README_NOTE = r'''

### Schema-v2 implementation status (R2 preparation)

The parser and package validator now define an additive `schemaVersion: 2` contract for `FACE_MESH_TEXTURE`, including the texture asset, a UV-layout identifier, `NORMAL` blend mode, bounded opacity, and optional region mask. The existing schema-v1 layer manifest still defaults to Canvas. Schema-v2 image assets are checked with the same canonical-path, image-signature, dimension and pixel-budget rules as legacy assets.

**Mesh packages are deliberately not installable yet.** The installation path fails closed after validation until a model-compatible UV registry and the actual GL renderer are connected to the live camera path. `uvLayout` is metadata only; the placeholder identifier in tests is not a validated UV asset, and no face texture rendering or occlusion is claimed by this task.
'''


def read_text(path: Path) -> str:
    if not path.is_file():
        raise RuntimeError(f"Required project file not found: {path}")
    return path.read_text(encoding="utf-8")


def replace_between(source: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    if source.count(start_marker) != 1:
        raise RuntimeError(f"Expected exactly one start marker for {label}; source may have changed")
    if source.count(end_marker) != 1:
        raise RuntimeError(f"Expected exactly one end marker for {label}; source may have changed")
    start = source.index(start_marker)
    end = source.index(end_marker, start)
    if end <= start:
        raise RuntimeError(f"Invalid replacement boundaries for {label}")
    return source[:start] + replacement + "\n\n" + source[end:]


def build_changes(root: Path) -> dict[Path, str]:
    model_old = read_text(root / MODEL_PATH)
    if "const val CURRENT_SUPPORTED_SCHEMA = 1" not in model_old or "data class FilterDefinition(" not in model_old:
        raise RuntimeError("FilterDefinition.kt is not the expected R&D-branch version; no files changed")

    manager_old = read_text(root / MANAGER_PATH)
    expected_manager_markers = [
        "    private fun validateManifestDocument(content: String) {",
        "    private fun readFiniteNumber(",
        "    private fun validatePackageAssets(",
        "    private fun resolvePackageFile(",
        "            validatePackageAssets(tempDir, definition)",
    ]
    for marker in expected_manager_markers:
        if manager_old.count(marker) != 1:
            raise RuntimeError(f"FilterPackageManager.kt missing expected marker: {marker!r}; no files changed")

    manager_new = replace_between(
        manager_old,
        "    private fun validateManifestDocument(content: String) {",
        "    private fun readFiniteNumber(",
        VALIDATE_MANIFEST,
        "manifest validation",
    )
    manager_new = replace_between(
        manager_new,
        "    private fun validatePackageAssets(",
        "    private fun resolvePackageFile(",
        VALIDATE_PACKAGE_ASSETS,
        "package asset validation",
    )
    install_anchor = "            validatePackageAssets(tempDir, definition)"
    install_guard = install_anchor + r'''

            // Fail closed: this app build has no model-pinned UV registry or live GL mesh renderer.
            if (definition.renderer == com.example.xelvic.model.FilterRenderer.FACE_MESH_TEXTURE) {
                throw FilterPackageException(
                    "FACE_MESH_TEXTURE packages are not installable yet; the UV registry and GL renderer are not enabled in this build"
                )
            }'''
    manager_new = manager_new.replace(install_anchor, install_guard, 1)

    hardening_old = read_text(root / HARDENING_TEST_PATH)
    test_anchor = "    @Test\n    fun invalidReplacementPreservesExistingFilter() {"
    if hardening_old.count(test_anchor) != 1:
        raise RuntimeError("Hardening-test insertion point not found; no files changed")
    hardening_new = hardening_old.replace(test_anchor, PACKAGE_TEST_INSERT + test_anchor, 1)

    readme_old = read_text(root / README_PATH)
    design_heading = "### Proposed schema v2 extension (design only)"
    if readme_old.count(design_heading) != 1:
        raise RuntimeError("README schema-v2 heading not found; no files changed")
    readme_new = readme_old.replace(
        design_heading,
        "### Schema v2 extension (R2 preparation; GL renderer still blocked)",
        1,
    )
    old_design_note = (
        "The following is a concept, not accepted syntax in the current parser or package validator. "
        "Final field names and validation rules must be implemented and tested before authoring v2 packages."
    )
    new_design_note = (
        "The R2-prep slice now parses and validates this schema-v2 metadata shape. It does not assert "
        "that a declared UV layout is compatible with the active model. The package installer deliberately "
        "blocks mesh packages until the pinned UV registry and live GL renderer are connected and validated."
    )
    if readme_new.count(old_design_note) != 1:
        raise RuntimeError("README schema-v2 design note not found; no files changed")
    readme_new = readme_new.replace(old_design_note, new_design_note, 1)
    readme_new = readme_new.replace(
        '  "version": "1.0.0",\n  "minimumRuntimeVersion": 2,',
        '  "version": "1.0.0",\n  "thumbnail": "thumbnail.png",\n  "minimumRuntimeVersion": 2,',
        1,
    )
    preview_heading = "## Preview vs. Still Photo"
    if readme_new.count(preview_heading) != 1:
        raise RuntimeError("README insertion point for schema-v2 status not found; no files changed")
    readme_new = readme_new.replace(preview_heading, README_NOTE.strip() + "\n\n" + preview_heading, 1)

    model_test_path = root / MODEL_TEST_PATH
    if model_test_path.exists():
        raise RuntimeError(f"Refusing to overwrite existing test file: {MODEL_TEST_PATH}")

    return {
        MODEL_PATH: MODEL_SOURCE,
        MANAGER_PATH: manager_new,
        HARDENING_TEST_PATH: hardening_new,
        MODEL_TEST_PATH: MODEL_TEST,
        README_PATH: readme_new,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write changes; default is check-only")
    args = parser.parse_args()
    root = Path.cwd()

    # Some Android Studio checkouts use the IDE-managed Gradle installation and do not
    # include the Gradle Wrapper scripts. The settings file plus app module identify the
    # repository root; the wrapper is not a prerequisite for applying this source patch.
    if not (root / "settings.gradle.kts").is_file() or not (root / "app" / "build.gradle.kts").is_file():
        print(
            "ERROR: current folder is not the XelvicFilterbase root (expected settings.gradle.kts and app/build.gradle.kts).",
            file=sys.stderr,
        )
        return 2

    try:
        changes = build_changes(root)
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    print("Schema-v2 patch preflight passed. Files planned:")
    for path in changes:
        print(f"  {path}")

    if not args.apply:
        print("\nCheck-only mode: nothing was changed. Run with --apply to edit files; no commit or push is performed.")
        return 0

    backup_dir = Path(tempfile.gettempdir()) / ("xelvic-schema-v2-backup-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    backup_dir.mkdir(parents=True, exist_ok=False)
    for relative_path in changes:
        source_path = root / relative_path
        if source_path.exists():
            backup_path = backup_dir / relative_path
            backup_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_path, backup_path)

    for relative_path, content in changes.items():
        destination = root / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content.rstrip() + "\n", encoding="utf-8", newline="\n")

    print(f"\nApplied local working-tree edits. Backups: {backup_dir}")
    print("No Git commands were run; no commit or push was created.")
    if (root / "gradlew.bat").is_file():
        print("Next verify: git diff --check; .\\gradlew.bat testDebugUnitTest; .\\gradlew.bat assembleDebug")
    else:
        print(
            "Next verify: git diff --check, then run Gradle tasks testDebugUnitTest and assembleDebug "
            "from Android Studio's Gradle tool window (this checkout has no gradlew.bat)."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
