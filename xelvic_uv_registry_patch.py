#!/usr/bin/env python3
"""Apply the next Xelvic R2 slice: model-pinned UV asset parsing and layout routing.

Run from the XelvicFilterbase root:
  python .\\xelvic_uv_registry_patch.py             # preflight only
  python .\\xelvic_uv_registry_patch.py --apply     # edit local source files

This script does not run Git, commit, push, or add a production UV layout. It establishes
an independently-pinned UV asset parser/registry; no layout becomes renderable until the app
supplies a trusted SHA-256 and a UV asset verified against the exact task model.
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
from datetime import datetime
from pathlib import Path

UV_MAP_PATH = Path("app/src/main/java/com/example/xelvic/geometry/FaceMeshUvMap.kt")
ROUTER_PATH = Path("app/src/main/java/com/example/xelvic/renderer/RenderBackendRouter.kt")
ROUTER_TEST_PATH = Path("app/src/test/java/com/example/xelvic/renderer/RenderBackendRouterTest.kt")
PARSER_PATH = Path("app/src/main/java/com/example/xelvic/geometry/FaceMeshUvAssetParser.kt")
PARSER_TEST_PATH = Path("app/src/test/java/com/example/xelvic/geometry/FaceMeshUvAssetParserTest.kt")
README_PATH = Path("README.md")

PARSER_SOURCE = r'''package com.example.xelvic.geometry

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * App-owned allow-list entry for a UV-layout asset.
 *
 * expectedSha256 must come from trusted app configuration/release review, not from a .xfilter
 * manifest. The layout asset itself binds its data to the exact Face Landmarker task SHA-256.
 */
data class PinnedFaceMeshUvLayout(
    val layoutId: String,
    val assetPath: String,
    val expectedSha256: String,
    val topologyId: String = FaceMeshTessellation.TOPOLOGY_ID
) {
    init {
        require(layoutId.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) {
            "Invalid pinned UV layout ID"
        }
        require(isSha256(expectedSha256)) {
            "Pinned UV asset SHA-256 must be lowercase hexadecimal"
        }
        require(topologyId == FaceMeshTessellation.TOPOLOGY_ID) {
            "Unsupported UV topology ID"
        }
        require(isSafeAssetPath(assetPath)) {
            "Pinned UV asset path is unsafe"
        }
    }

    private companion object {
        fun isSha256(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        fun isSafeAssetPath(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return false
            val segments = path.split('/')
            return segments.none { it.isBlank() || it == "." || it == ".." } &&
                !Regex("^[A-Za-z]:").containsMatchIn(path)
        }
    }
}

enum class FaceMeshUvAssetFailure {
    UNREGISTERED_LAYOUT,
    ASSET_READ_FAILED,
    INVALID_JSON,
    UNSUPPORTED_ASSET_SCHEMA,
    MISSING_OR_INVALID_FIELD,
    LAYOUT_ID_MISMATCH,
    INVALID_UV_ORIGIN,
    INVALID_COORDINATES
}

data class FaceMeshUvAssetResult(
    val uvMap: FaceMeshUvMap? = null,
    val assetFailure: FaceMeshUvAssetFailure? = null,
    val validationFailure: FaceMeshUvMapFailure? = null
) {
    val isValid: Boolean
        get() = uvMap != null && assetFailure == null && validationFailure == null
}

/**
 * Parser for app-owned UV asset JSON. It deliberately never trusts a digest supplied by the
 * filter package: the expected digest is provided by [PinnedFaceMeshUvLayout].
 *
 * Asset format (schema 1):
 * {
 *   "schemaVersion": 1,
 *   "uvLayoutId": "pinned-layout-id",
 *   "topologyId": "mediapipe-task-face-468-tessellation-v1",
 *   "modelAssetSha256": "<exact face_landmarker.task SHA-256>",
 *   "uvOrigin": "BOTTOM_LEFT",
 *   "coordinates": [{"u": 0.5, "v": 0.5}, ...] // exactly 468 pairs
 * }
 *
 * The file's SHA-256 is computed over its exact bytes and checked against the pinned registry.
 */
object FaceMeshUvAssetParser {
    fun parse(
        bytes: ByteArray,
        pinnedLayout: PinnedFaceMeshUvLayout,
        activeModelAssetSha256: String?
    ): FaceMeshUvAssetResult {
        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return assetFailure(FaceMeshUvAssetFailure.INVALID_JSON)
        }

        val schema = root.opt("schemaVersion") as? Number
        if (schema == null || schema.toInt() != 1 || schema.toDouble() != 1.0) {
            return assetFailure(FaceMeshUvAssetFailure.UNSUPPORTED_ASSET_SCHEMA)
        }

        val layoutId = root.opt("uvLayoutId") as? String
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)
        if (layoutId != pinnedLayout.layoutId) {
            return assetFailure(FaceMeshUvAssetFailure.LAYOUT_ID_MISMATCH)
        }

        val topologyId = root.opt("topologyId") as? String
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)
        val modelSha = root.opt("modelAssetSha256") as? String
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)
        val originText = root.opt("uvOrigin") as? String
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)
        val uvOrigin = try {
            UvOrigin.valueOf(originText)
        } catch (_: Exception) {
            return assetFailure(FaceMeshUvAssetFailure.INVALID_UV_ORIGIN)
        }
        val jsonCoordinates: JSONArray = root.optJSONArray("coordinates")
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)

        val coordinates = ArrayList<FaceUvCoordinate>(jsonCoordinates.length())
        for (index in 0 until jsonCoordinates.length()) {
            val point = jsonCoordinates.optJSONObject(index)
                ?: return assetFailure(FaceMeshUvAssetFailure.INVALID_COORDINATES)
            val u = point.opt("u") as? Number
                ?: return assetFailure(FaceMeshUvAssetFailure.INVALID_COORDINATES)
            val v = point.opt("v") as? Number
                ?: return assetFailure(FaceMeshUvAssetFailure.INVALID_COORDINATES)
            try {
                coordinates += FaceUvCoordinate(u.toFloat(), v.toFloat())
            } catch (_: IllegalArgumentException) {
                return assetFailure(FaceMeshUvAssetFailure.INVALID_COORDINATES)
            }
        }

        val actualAssetSha256 = sha256(bytes)
        val validation = FaceMeshUvMap.validate(
            sourceTopologyId = topologyId,
            expectedTopologyId = pinnedLayout.topologyId,
            sourceModelAssetSha256 = modelSha,
            activeModelAssetSha256 = activeModelAssetSha256,
            sourceUvAssetSha256 = actualAssetSha256,
            expectedUvAssetSha256 = pinnedLayout.expectedSha256,
            coordinates = coordinates,
            uvOrigin = uvOrigin,
            uvLayoutId = layoutId
        )
        return FaceMeshUvAssetResult(
            uvMap = validation.uvMap,
            validationFailure = validation.failure
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun assetFailure(failure: FaceMeshUvAssetFailure) =
        FaceMeshUvAssetResult(assetFailure = failure)
}

/** Loads UV maps only from an app-controlled allow-list, never from arbitrary package paths. */
class FaceMeshUvRegistry(
    private val assetManager: AssetManager,
    pinnedLayouts: Collection<PinnedFaceMeshUvLayout>
) {
    private val layoutsById: Map<String, PinnedFaceMeshUvLayout> =
        pinnedLayouts.associateBy { it.layoutId }.also { map ->
            require(map.size == pinnedLayouts.size) { "Duplicate pinned UV layout ID" }
        }

    fun load(layoutId: String, activeModelAssetSha256: String?): FaceMeshUvAssetResult {
        val pinnedLayout = layoutsById[layoutId]
            ?: return FaceMeshUvAssetResult(assetFailure = FaceMeshUvAssetFailure.UNREGISTERED_LAYOUT)
        return try {
            assetManager.open(pinnedLayout.assetPath).use { input ->
                FaceMeshUvAssetParser.parse(input.readBytes(), pinnedLayout, activeModelAssetSha256)
            }
        } catch (_: Exception) {
            FaceMeshUvAssetResult(assetFailure = FaceMeshUvAssetFailure.ASSET_READ_FAILED)
        }
    }
}
'''

PARSER_TEST_SOURCE = r'''package com.example.xelvic.geometry

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceMeshUvAssetParserTest {
    private val layoutId = "test-face-layout-v1"
    private val modelSha = "a".repeat(64)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun document(
        layout: String = layoutId,
        topology: String = FaceMeshTessellation.TOPOLOGY_ID,
        model: String = modelSha,
        count: Int = FaceMeshTessellation.LANDMARK_COUNT,
        uvOrigin: String = "BOTTOM_LEFT",
        u: Double = 0.5,
        v: Double = 0.5
    ): ByteArray {
        val points = JSONArray()
        repeat(count) { points.put(JSONObject().put("u", u).put("v", v)) }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("uvLayoutId", layout)
            .put("topologyId", topology)
            .put("modelAssetSha256", model)
            .put("uvOrigin", uvOrigin)
            .put("coordinates", points)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun pinned(bytes: ByteArray) = PinnedFaceMeshUvLayout(
        layoutId = layoutId,
        assetPath = "uv_layouts/$layoutId.json",
        expectedSha256 = sha256(bytes)
    )

    @Test
    fun acceptsExactPinnedLayoutAndTaskModel() {
        val bytes = document()
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertTrue(result.isValid)
        assertNull(result.assetFailure)
        assertNull(result.validationFailure)
        assertEquals(layoutId, result.uvMap!!.uvLayoutId)
        assertEquals(FaceMeshTessellation.LANDMARK_COUNT, result.uvMap.vertexCount)
    }

    @Test
    fun rejectsBytesThatDoNotMatchPinnedDigest() {
        val approvedBytes = document()
        val changedBytes = document(v = 0.51)
        val result = FaceMeshUvAssetParser.parse(changedBytes, pinned(approvedBytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.UV_ASSET_FINGERPRINT_MISMATCH, result.validationFailure)
    }

    @Test
    fun rejectsDifferentActiveTaskModel() {
        val bytes = document()
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), "b".repeat(64))

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.MODEL_MISMATCH, result.validationFailure)
    }

    @Test
    fun rejectsDifferentLayoutIdentifier() {
        val bytes = document(layout = "other-layout")
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(document()), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvAssetFailure.LAYOUT_ID_MISMATCH, result.assetFailure)
    }

    @Test
    fun rejectsTopologyMismatch() {
        val bytes = document(topology = "legacy-canonical-face-model")
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.TOPOLOGY_MISMATCH, result.validationFailure)
    }

    @Test
    fun rejectsWrongVertexCount() {
        val bytes = document(count = FaceMeshTessellation.LANDMARK_COUNT - 1)
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.INVALID_VERTEX_COUNT, result.validationFailure)
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        val bytes = document(u = 1.5)
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvAssetFailure.INVALID_COORDINATES, result.assetFailure)
    }

    @Test
    fun rejectsUnknownUvOrigin() {
        val bytes = document(uvOrigin = "CENTER")
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvAssetFailure.INVALID_UV_ORIGIN, result.assetFailure)
    }

    @Test
    fun rejectsInvalidJson() {
        val bytes = "not-json".toByteArray(Charsets.UTF_8)
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(document()), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvAssetFailure.INVALID_JSON, result.assetFailure)
    }
}
'''

README_NOTE = r'''### UV asset loading contract (next R2 gate)

- `FaceMeshUvAssetParser` parses an app-owned UV JSON asset and computes its SHA-256 from the exact file bytes. The expected digest is supplied by `PinnedFaceMeshUvLayout`, an app-controlled allow-list entry, not by the `.xfilter` package.
- Every asset identifies the UV layout, the exact Face Landmarker task bundle SHA-256, topology ID, UV origin and exactly 468 finite normalized coordinates. Model, topology, vertex-count, digest, layout-ID and coordinate failures return explicit blocked results.
- `FaceMeshUvRegistry` loads only allow-listed app asset paths. Arbitrary filter package paths cannot register a UV layout. No production UV layout is registered by this slice because a model-compatible layout has not yet been verified.
- The renderer router now requires the effect's `uvLayout` to equal the validated map's `uvLayoutId`. A missing/unregistered layout remains unavailable; no shader draw, texture mapping or occlusion is claimed.

The legacy `canonical_face_model.obj` is not accepted on landmark count alone. MediaPipe's issue #5760 describes incompatibility between the legacy canonical OBJ and newer Face Landmarker ordering, and the maintainer response notes that the legacy geometry assets are no longer updated for the newer Task API: https://github.com/google-ai-edge/mediapipe/issues/5760. Use only a source whose point ordering/topology is verified against the pinned task bundle, then pin its exact SHA-256.
'''


def read_text(root: Path, relative: Path) -> str:
    path = root / relative
    if not path.is_file():
        raise RuntimeError(f"Required project file not found: {relative}")
    return path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one replacement anchor for {label}; found {count}")
    return source.replace(old, new, 1)


def build_changes(root: Path) -> dict[Path, str]:
    uv_old = read_text(root, UV_MAP_PATH)
    router_old = read_text(root, ROUTER_PATH)
    router_test_old = read_text(root, ROUTER_TEST_PATH)
    readme_old = read_text(root, README_PATH)

    if "val uvLayoutId: String?" in uv_old or "INVALID_LAYOUT_ID" in uv_old:
        raise RuntimeError("FaceMeshUvMap.kt already contains UV layout IDs; inspect current edits before rerunning")
    if "val uvLayoutId: String? = null" in router_old or "UV_LAYOUT_MISMATCH" in router_old:
        raise RuntimeError("RenderBackendRouter.kt already contains UV layout routing; inspect current edits before rerunning")
    if "FaceMeshUvAssetParser" in readme_old:
        raise RuntimeError("README already documents the UV asset parser; refusing duplicate patch")
    if (root / PARSER_PATH).exists() or (root / PARSER_TEST_PATH).exists():
        raise RuntimeError("UV parser source or tests already exist; refusing to overwrite")

    constructor_anchor = '''    val uvAssetSha256: String,
    val uvOrigin: UvOrigin,
    coordinates: List<FaceUvCoordinate>
) {'''
    uv_new = replace_once(
        uv_old,
        constructor_anchor,
        '''    val uvAssetSha256: String,
    val uvOrigin: UvOrigin,
    val uvLayoutId: String?,
    coordinates: List<FaceUvCoordinate>
) {''',
        "UV map layout property",
    )
    validate_anchor = '''            coordinates: List<FaceUvCoordinate>,
            uvOrigin: UvOrigin
        ): FaceMeshUvMapValidation {'''
    uv_new = replace_once(
        uv_new,
        validate_anchor,
        '''            coordinates: List<FaceUvCoordinate>,
            uvOrigin: UvOrigin,
            uvLayoutId: String? = null
        ): FaceMeshUvMapValidation {''',
        "UV validator signature",
    )
    topology_check_anchor = '''            if (expectedTopologyId != FaceMeshTessellation.TOPOLOGY_ID ||
                sourceTopologyId != expectedTopologyId
            ) {
                return FaceMeshUvMapValidation(null, FaceMeshUvMapFailure.TOPOLOGY_MISMATCH)
            }
'''
    uv_new = replace_once(
        uv_new,
        topology_check_anchor,
        topology_check_anchor + '''
            if (uvLayoutId != null && !uvLayoutId.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) {
                return FaceMeshUvMapValidation(null, FaceMeshUvMapFailure.INVALID_LAYOUT_ID)
            }
''',
        "UV layout ID validation",
    )
    constructor_call_anchor = '''                    uvAssetSha256 = sourceUvAssetSha256,
                    uvOrigin = uvOrigin,
                    coordinates = coordinates
'''
    uv_new = replace_once(
        uv_new,
        constructor_call_anchor,
        '''                    uvAssetSha256 = sourceUvAssetSha256,
                    uvOrigin = uvOrigin,
                    uvLayoutId = uvLayoutId,
                    coordinates = coordinates
''',
        "UV map constructor call",
    )
    enum_anchor = '''    UV_ASSET_FINGERPRINT_MISMATCH,
    INVALID_VERTEX_COUNT
'''
    uv_new = replace_once(
        uv_new,
        enum_anchor,
        '''    UV_ASSET_FINGERPRINT_MISMATCH,
    INVALID_VERTEX_COUNT,
    INVALID_LAYOUT_ID
''',
        "UV validation failure enum",
    )

    router_new = replace_once(
        router_old,
        '''data class FaceMeshTextureRequirements(
    val topologyId: String,
    val activeModelAssetSha256: String?,''',
        '''data class FaceMeshTextureRequirements(
    val topologyId: String,
    val uvLayoutId: String?,
    val activeModelAssetSha256: String?,''',
        "mesh texture UV-layout requirement",
    )
    router_new = replace_once(
        router_new,
        '''    UV_MAP_NOT_VALIDATED,
    ACTIVE_MODEL_IDENTITY_MISSING,''',
        '''    UV_MAP_NOT_VALIDATED,
    UV_LAYOUT_MISMATCH,
    ACTIVE_MODEL_IDENTITY_MISSING,''',
        "renderer block reason",
    )
    router_gate_anchor = '''        val uvMap = mesh.uvMap
            ?: return blocked(RenderBackendBlockReason.UV_MAP_NOT_VALIDATED)

        if (mesh.activeModelAssetSha256 == null) {'''
    router_new = replace_once(
        router_new,
        router_gate_anchor,
        '''        val uvMap = mesh.uvMap
            ?: return blocked(RenderBackendBlockReason.UV_MAP_NOT_VALIDATED)

        val requiredUvLayoutId = mesh.uvLayoutId?.takeIf { it.isNotBlank() }
            ?: return blocked(RenderBackendBlockReason.UV_LAYOUT_MISMATCH)
        if (uvMap.uvLayoutId != requiredUvLayoutId) {
            return blocked(RenderBackendBlockReason.UV_LAYOUT_MISMATCH)
        }

        if (mesh.activeModelAssetSha256 == null) {''',
        "renderer UV-layout gate",
    )

    router_test_new = replace_once(
        router_test_old,
        '''        sourceUvAssetSha256 = uvHash,
        expectedUvAssetSha256 = uvHash,
        coordinates = List(FaceMeshTessellation.LANDMARK_COUNT) {
            FaceUvCoordinate(0.5f, 0.5f)
        },
        uvOrigin = UvOrigin.BOTTOM_LEFT
''',
        '''        sourceUvAssetSha256 = uvHash,
        expectedUvAssetSha256 = uvHash,
        coordinates = List(FaceMeshTessellation.LANDMARK_COUNT) {
            FaceUvCoordinate(0.5f, 0.5f)
        },
        uvOrigin = UvOrigin.BOTTOM_LEFT,
        uvLayoutId = "unit-test-layout"
''',
        "router test validated UV layout",
    )
    router_test_new = replace_once(
        router_test_new,
        '''    ) = FaceMeshTextureRequirements(
        topologyId = topology,
        activeModelAssetSha256 = modelSha,''',
        '''    ) = FaceMeshTextureRequirements(
        topologyId = topology,
        uvLayoutId = uvLayoutId,
        activeModelAssetSha256 = modelSha,''',
        "router test requirements factory",
    )
    router_test_new = replace_once(
        router_test_new,
        '''        map: FaceMeshUvMap? = uvMap,
        modelSha: String? = modelHash,
        topology: String = FaceMeshTessellation.TOPOLOGY_ID,''',
        '''        map: FaceMeshUvMap? = uvMap,
        modelSha: String? = modelHash,
        topology: String = FaceMeshTessellation.TOPOLOGY_ID,
        uvLayoutId: String? = "unit-test-layout",''',
        "router test UV layout parameter",
    )
    test_anchor = '''    @Test
    fun differentModelFingerprintBlocksMeshRendering() {'''
    router_test_new = replace_once(
        router_test_new,
        test_anchor,
        '''    @Test
    fun differentUvLayoutBlocksMeshRendering() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.FACE_MESH_TEXTURE,
            meshRequirements(uvLayoutId = "different-layout"),
            capabilities
        )

        assertEquals(RenderBackend.UNAVAILABLE, result.backend)
        assertEquals(RenderBackendBlockReason.UV_LAYOUT_MISMATCH, result.reason)
    }

'''+test_anchor,
        "renderer UV layout regression test",
    )

    readme_anchor = "## Mesh Renderer Gate Hardening — Current Research Status"
    readme_new = replace_once(
        readme_old,
        readme_anchor,
        readme_anchor + "\n\n" + README_NOTE.strip(),
        "README UV registry status",
    )

    if "meshTexture?.let" not in read_text(root, Path("app/src/main/java/com/example/xelvic/packagemanager/FilterPackageManager.kt")):
        raise RuntimeError("Schema-v2 package validation is missing; apply/verify the prior schema patch first")

    return {
        UV_MAP_PATH: uv_new,
        ROUTER_PATH: router_new,
        ROUTER_TEST_PATH: router_test_new,
        PARSER_PATH: PARSER_SOURCE,
        PARSER_TEST_PATH: PARSER_TEST_SOURCE,
        README_PATH: readme_new,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write files; default is check-only")
    args = parser.parse_args()
    root = Path.cwd()

    if not (root / "settings.gradle.kts").is_file() or not (root / "app" / "build.gradle.kts").is_file():
        print("ERROR: run from the project root (settings.gradle.kts and app/build.gradle.kts required).", file=sys.stderr)
        return 2

    try:
        changes = build_changes(root)
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    print("UV registry patch preflight passed. Files planned:")
    for path in changes:
        print(f"  {path}")
    if not args.apply:
        print("\nCheck-only mode; no files changed. Use --apply to write local edits. No Git commands are run.")
        return 0

    backup_dir = Path(tempfile.gettempdir()) / ("xelvic-uv-registry-backup-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    backup_dir.mkdir(parents=True, exist_ok=False)
    for relative, content in changes.items():
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            backup = backup_dir / relative
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(destination, backup)
        destination.write_text(content, encoding="utf-8", newline="\n")

    print(f"\nApplied local UV registry edits. Backups: {backup_dir}")
    print("No Git commands were run; no commit or push was created.")
    print("Verify with: git diff --check, then testDebugUnitTest and assembleDebug from Android Studio Gradle.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
