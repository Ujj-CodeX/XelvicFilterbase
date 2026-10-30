package com.example.xelvic.geometry

import android.content.res.AssetManager

/** Single source of truth for app-owned face-mesh UV layouts. */
object AppFaceMeshUvLayouts {
    const val CANONICAL_MEDIAPIPE_FACE_468 = "mediapipe_task_face_468_canonical_v1"

    fun createRegistry(assetManager: AssetManager): FaceMeshUvRegistry =
        FaceMeshUvRegistry(
            assetManager,
            listOf(
                PinnedFaceMeshUvLayout(
                    layoutId = CANONICAL_MEDIAPIPE_FACE_468,
                    assetPath = "uv/mediapipe_task_face_468_canonical_v1.json",
                    expectedSha256 = "0ed252df853d7be48694dbaa58892c47769cb4d1d881a3d2785acb12d2437b52"
                )
            )
        )
}
