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
                    expectedSha256 = "7263a84a2bf5f023abd5561e5727397ae94422a09566eebbf438c97c5d3b0d61"
                )
            )
        )
}
