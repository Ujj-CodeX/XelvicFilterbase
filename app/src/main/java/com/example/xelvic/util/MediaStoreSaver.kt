package com.example.xelvic.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object MediaStoreSaver {

    /**
     * Saves a photograph to the Android system gallery under Pictures/Xelvic.
     * Uses MediaStore on modern Android versions without requiring legacy storage permissions.
     */
    suspend fun savePhotoToGallery(context: Context, bitmap: Bitmap): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val filename = "XELVIC_${System.currentTimeMillis()}.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Xelvic")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(Exception("Failed creating MediaStore entry"))

            var success = false
            try {
                val outputStream: OutputStream? = resolver.openOutputStream(uri)
                if (outputStream != null) {
                    outputStream.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    }
                    success = true
                }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
            }

            if (success) {
                Result.success(uri)
            } else {
                Result.failure(Exception("Failed writing image bytes to stream"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
