package com.example.xelvic.packagemanager

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.xelvic.model.FilterDefinition
import com.example.xelvic.model.InstalledFilter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Exception thrown when a .xfilter package fails validation or extraction.
 */
class FilterPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Manages the lifecycle of .xfilter packages: importing, validating, safe extraction,
 * atomic installation, listing, and uninstallation.
 */
class FilterPackageManager(private val context: Context) {

    private val filtersDirectory: File = File(context.filesDir, "installed_filters").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        private const val TAG = "FilterPackageManager"

        // Security Limits
        private const val MAX_ARCHIVE_SIZE_BYTES = 25 * 1024 * 1024L // 25 MB
        private const val MAX_TOTAL_EXTRACTED_BYTES = 50 * 1024 * 1024L // 50 MB
        private const val MAX_ENTRY_COUNT = 100
        private val ALLOWED_EXTENSIONS = setOf("json", "png", "webp")
    }

    /**
     * Initializes pre-bundled filter packages from Android assets (e.g. assets/filters)
     * if they have not been installed yet.
     */
    fun installBundledFiltersIfNecessary(): List<InstalledFilter> {
        try {
            val assetManager = context.assets
            val assetFiles = assetManager.list("filters") ?: emptyArray()
            for (filename in assetFiles) {
                if (filename.endsWith(".xfilter")) {
                    val filterId = filename.substringBeforeLast(".xfilter")
                    val installedDir = File(filtersDirectory, filterId)
                    if (!installedDir.exists()) {
                        Log.i(TAG, "Installing bundled filter: $filename")
                        assetManager.open("filters/$filename").use { inputStream ->
                            installPackage(inputStream, isBundled = true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing bundled filters", e)
        }
        return listInstalledFilters()
    }

    /**
     * Installs a .xfilter package from a Storage Access Framework Uri.
     */
    fun installFromUri(uri: Uri): InstalledFilter {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw FilterPackageException("Could not open input stream from selected file")
        return inputStream.use { installPackage(it, isBundled = false) }
    }

    /**
     * Atomically extracts, validates, and installs a .xfilter archive from an InputStream.
     */
    @Synchronized
    fun installPackage(inputStream: InputStream, isBundled: Boolean = false): InstalledFilter {
        val tempDir = File(filtersDirectory, ".tmp_extract_${System.currentTimeMillis()}_${(1000..9999).random()}")
        if (!tempDir.mkdirs()) {
            throw FilterPackageException("Failed to allocate temporary extraction directory")
        }

        try {
            // 1. Safe extraction with security validations
            extractArchiveSafely(inputStream, tempDir)

            // 2. Validate manifest and schema
            val manifestFile = File(tempDir, "manifest.json")
            if (!manifestFile.exists() || !manifestFile.isFile) {
                throw FilterPackageException("Invalid package: missing manifest.json at archive root")
            }

            val manifestContent = manifestFile.readText()
            val definition: FilterDefinition = try {
                FilterDefinition.fromJson(manifestContent)
            } catch (e: Exception) {
                throw FilterPackageException("Failed to parse manifest.json: ${e.message}", e)
            }

            // Check schema compatibility
            if (definition.schemaVersion > FilterDefinition.CURRENT_SUPPORTED_SCHEMA) {
                throw FilterPackageException("Unsupported schemaVersion (${definition.schemaVersion}). Max supported is ${FilterDefinition.CURRENT_SUPPORTED_SCHEMA}")
            }
            if (definition.minimumRuntimeVersion > FilterDefinition.CURRENT_RUNTIME_VERSION) {
                throw FilterPackageException("Incompatible runtime version (${definition.minimumRuntimeVersion}). Engine runtime is ${FilterDefinition.CURRENT_RUNTIME_VERSION}")
            }

            // Validate ID
            val safeIdRegex = Regex("^[a-zA-Z0-9_-]+$")
            if (!definition.id.matches(safeIdRegex)) {
                throw FilterPackageException("Invalid package ID '${definition.id}'. Must only contain letters, numbers, hyphens or underscores.")
            }

            // Validate Thumbnail
            val thumbnailFile = File(tempDir, definition.thumbnail)
            if (!thumbnailFile.exists() || !thumbnailFile.isFile) {
                throw FilterPackageException("Missing thumbnail image '${definition.thumbnail}' declared in manifest")
            }

            // Validate Layers and Assets
            if (definition.layers.isEmpty()) {
                throw FilterPackageException("Filter package must define at least one layer in 'layers' array")
            }

            for (layer in definition.layers) {
                val assetFile = File(tempDir, layer.asset)
                if (!assetFile.exists() || !assetFile.isFile) {
                    throw FilterPackageException("Missing layer asset '${layer.asset}' declared in layer '${layer.id}'")
                }
            }

            // 3. Atomic Installation to target directory
            val targetDir = File(filtersDirectory, definition.id)
            if (targetDir.exists()) {
                // Remove older version cleanly
                deleteRecursively(targetDir)
            }

            if (!tempDir.renameTo(targetDir)) {
                // Fallback copy if rename across mount fails
                copyDirectoryRecursively(tempDir, targetDir)
                deleteRecursively(tempDir)
            }

            val finalThumbnail = File(targetDir, definition.thumbnail)
            return InstalledFilter(
                id = definition.id,
                definition = definition,
                packageDirectory = targetDir,
                thumbnailFile = finalThumbnail,
                isBundled = isBundled
            )
        } catch (e: Exception) {
            deleteRecursively(tempDir)
            throw if (e is FilterPackageException) e else FilterPackageException("Filter installation failed: ${e.message}", e)
        }
    }

    /**
     * Lists all installed and verified filters in private storage.
     */
    fun listInstalledFilters(): List<InstalledFilter> {
        val results = mutableListOf<InstalledFilter>()
        val packageDirs = filtersDirectory.listFiles { file -> file.isDirectory && !file.name.startsWith(".") } ?: return emptyList()

        for (dir in packageDirs) {
            try {
                val manifestFile = File(dir, "manifest.json")
                if (manifestFile.exists()) {
                    val definition = FilterDefinition.fromJson(manifestFile.readText())
                    val thumbnail = File(dir, definition.thumbnail)
                    results.add(
                        InstalledFilter(
                            id = definition.id,
                            definition = definition,
                            packageDirectory = dir,
                            thumbnailFile = thumbnail,
                            isBundled = false
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid installed filter directory: ${dir.name}", e)
            }
        }
        return results.sortedBy { it.definition.name }
    }

    /**
     * Removes an installed filter from storage.
     */
    fun removeFilter(filterId: String): Boolean {
        val targetDir = File(filtersDirectory, filterId)
        return if (targetDir.exists()) {
            deleteRecursively(targetDir)
        } else {
            false
        }
    }

    /**
     * Extracts ZIP archive with strict path traversal, ZIP Slip, entry limit, and extension checks.
     */
    private fun extractArchiveSafely(inputStream: InputStream, destinationDir: File) {
        val canonicalDest = destinationDir.canonicalFile
        val zipIn = ZipInputStream(inputStream)
        var entry: ZipEntry? = zipIn.nextEntry

        var totalEntries = 0
        var totalExtractedBytes = 0L

        val buffer = ByteArray(8192)

        while (entry != null) {
            totalEntries++
            if (totalEntries > MAX_ENTRY_COUNT) {
                throw FilterPackageException("Archive exceeds maximum entry count of $MAX_ENTRY_COUNT")
            }

            val entryName = entry.name

            // Reject paths with absolute paths or parent directory traversal
            if (entryName.startsWith("/") || entryName.startsWith("\\") || entryName.contains("..")) {
                throw FilterPackageException("Unsafe entry path detected in archive: $entryName")
            }

            val targetFile = File(destinationDir, entryName)
            val canonicalTarget = targetFile.canonicalFile
            if (!canonicalTarget.path.startsWith(canonicalDest.path + File.separator) && canonicalTarget != canonicalDest) {
                throw FilterPackageException("ZIP Slip traversal attempt detected: $entryName")
            }

            if (entry.isDirectory) {
                if (!targetFile.exists() && !targetFile.mkdirs()) {
                    throw FilterPackageException("Failed creating directory: $entryName")
                }
            } else {
                val extension = targetFile.extension.lowercase()
                if (!ALLOWED_EXTENSIONS.contains(extension)) {
                    throw FilterPackageException("Prohibited file extension '.$extension' in archive entry: $entryName")
                }

                val parent = targetFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw FilterPackageException("Failed creating parent directory for: $entryName")
                }

                FileOutputStream(targetFile).use { out ->
                    var len: Int
                    while (zipIn.read(buffer).also { len = it } > 0) {
                        totalExtractedBytes += len
                        if (totalExtractedBytes > MAX_TOTAL_EXTRACTED_BYTES) {
                            throw FilterPackageException("Extracted size exceeds limit of ${MAX_TOTAL_EXTRACTED_BYTES / 1024 / 1024} MB")
                        }
                        out.write(buffer, 0, len)
                    }
                }
            }

            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        return file.delete()
    }

    private fun copyDirectoryRecursively(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) target.mkdirs()
            source.listFiles()?.forEach { child ->
                copyDirectoryRecursively(child, File(target, child.name))
            }
        } else {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
