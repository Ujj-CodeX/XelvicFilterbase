package com.example.xelvic.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Represents an unpacked and verified filter package in application storage.
 */
data class InstalledFilter(
    val id: String,
    val definition: FilterDefinition,
    val packageDirectory: File,
    val thumbnailFile: File,
    val isBundled: Boolean = false,
    val installedTimestamp: Long = System.currentTimeMillis()
) {
    private val bitmapCache = mutableMapOf<String, Bitmap>()

    /**
     * Resolves an asset file within the package directory.
     * Prevents any path traversal outside the package folder.
     */
    fun resolveAssetFile(relativePath: String): File? {
        val file = File(packageDirectory, relativePath)
        return try {
            val canonicalTarget = file.canonicalFile
            val canonicalDir = packageDirectory.canonicalFile
            if (canonicalTarget.startsWith(canonicalDir) && file.exists()) {
                file
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Loads and caches the Bitmap for a layer asset with safe dimension bounds.
     */
    @Synchronized
    fun getAssetBitmap(relativePath: String): Bitmap? {
        bitmapCache[relativePath]?.let { return it }

        val file = resolveAssetFile(relativePath) ?: return null
        return try {
            // Check dimensions first to avoid OutOfMemoryError on oversized assets
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val maxTargetDimension = 1280
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxTargetDimension ||
                options.outHeight / sampleSize > maxTargetDimension
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            if (bitmap != null) {
                bitmapCache[relativePath] = bitmap
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clears cached bitmaps when memory is low or filter is unselected.
     */
    @Synchronized
    fun clearCache() {
        bitmapCache.values.forEach { if (!it.isRecycled) it.recycle() }
        bitmapCache.clear()
    }
}
