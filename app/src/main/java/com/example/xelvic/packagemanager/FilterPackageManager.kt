package com.example.xelvic.packagemanager

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.BuildConfig
import com.example.xelvic.model.FilterDefinition
import com.example.xelvic.model.FilterRenderer
import com.example.xelvic.renderer.DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID
import com.example.xelvic.renderer.DIAGNOSTIC_MOUSTACHE_FILTER_ID
import com.example.xelvic.model.InstalledFilter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.util.Locale


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

    private fun copyArchiveWithLimit(
        inputStream: InputStream,
        destination: File
    ) {
        val buffer = ByteArray(8192)
        var totalBytes = 0L

        FileOutputStream(destination).use { output ->
            while (true) {
                val count = inputStream.read(buffer)

                if (count < 0) break
                if (count == 0) continue

                totalBytes += count

                if (totalBytes > MAX_ARCHIVE_SIZE_BYTES) {
                    throw FilterPackageException(
                        "Archive exceeds the 25 MB compressed-size limit"
                    )
                }

                output.write(buffer, 0, count)
            }
        }

        if (totalBytes == 0L) {
            throw FilterPackageException("The selected package is empty")
        }
    }

    private fun validateManifestDocument(content: String) {
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


    private fun readFiniteNumber(
        json: JSONObject,
        key: String
    ): Double {
        if (!json.has(key) || json.isNull(key)) {
            throw FilterPackageException("Missing required numeric field: $key")
        }

        val value = try {
            json.getDouble(key)
        } catch (e: Exception) {
            throw FilterPackageException("Invalid numeric field: $key", e)
        }

        if (!value.isFinite()) {
            throw FilterPackageException("Non-finite numeric field: $key")
        }

        return value
    }


    private fun validatePackageAssets(
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


    private fun resolvePackageFile(
        packageRoot: File,
        relativePath: String,
        allowedExtensions: Set<String>
    ): File {
        if (
            relativePath.isBlank() ||
            relativePath.startsWith("/") ||
            relativePath.contains('\\') ||
            Regex("^[A-Za-z]:").containsMatchIn(relativePath)
        ) {
            throw FilterPackageException("Unsafe package asset path")
        }

        val segments = relativePath.split('/')

        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw FilterPackageException("Unsafe package asset path")
        }

        val root = packageRoot.canonicalFile
        val file = File(packageRoot, relativePath).canonicalFile

        if (!file.path.startsWith(root.path + File.separator)) {
            throw FilterPackageException("Asset escapes the package directory")
        }

        if (!file.isFile) {
            throw FilterPackageException("Missing package asset: $relativePath")
        }

        if (file.extension.lowercase(Locale.ROOT) !in allowedExtensions) {
            throw FilterPackageException("Unsupported asset type: $relativePath")
        }

        return file
    }

    private fun validateImageBounds(
        file: File,
        description: String
    ) {
        if (!file.isFile) {
            throw FilterPackageException(
                "$description is missing or is not a file"
            )
        }

        // Verify the actual binary image signature rather than relying
        // exclusively on BitmapFactory.Options.outMimeType.
        val header = ByteArray(12)

        val bytesRead = FileInputStream(file).use { input ->
            var total = 0

            while (total < header.size) {
                val count = input.read(
                    header,
                    total,
                    header.size - total
                )

                if (count < 0) break
                if (count == 0) continue

                total += count
            }

            total
        }

        val pngSignature = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A
        )

        val isPng = bytesRead >= pngSignature.size &&
                pngSignature.indices.all { index ->
                    header[index] == pngSignature[index]
                }

        val isWebP = bytesRead >= 12 &&
                header[0] == 'R'.code.toByte() &&
                header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() &&
                header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() &&
                header[9] == 'E'.code.toByte() &&
                header[10] == 'B'.code.toByte() &&
                header[11] == 'P'.code.toByte()

        // Require the file extension to agree with its actual signature.
        val extension = file.extension.lowercase(Locale.ROOT)

        val formatMatches = when (extension) {
            "png" -> isPng
            "webp" -> isWebP
            else -> false
        }

        if (!formatMatches) {
            throw FilterPackageException(
                "$description has an invalid or mismatched image format"
            )
        }

        // Ask Android's image decoder for dimensions.
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, options)

        val width = options.outWidth
        val height = options.outHeight

        if (width <= 0 || height <= 0) {
            throw FilterPackageException(
                "$description is not a decodable image"
            )
        }

        if (
            width > MAX_IMAGE_DIMENSION ||
            height > MAX_IMAGE_DIMENSION
        ) {
            throw FilterPackageException(
                "$description exceeds maximum dimensions " +
                        "${MAX_IMAGE_DIMENSION}x${MAX_IMAGE_DIMENSION}"
            )
        }

        if (width.toLong() * height.toLong() > MAX_IMAGE_PIXELS) {
            throw FilterPackageException(
                "$description exceeds pixel budget"
            )
        }
    }

    companion object {
        private const val TAG = "FilterPackageManager"

        // Security Limits
        private const val MAX_ARCHIVE_SIZE_BYTES = 25 * 1024 * 1024L // 25 MB
        private const val MAX_TOTAL_EXTRACTED_BYTES = 50 * 1024 * 1024L // 50 MB
        private const val MAX_ENTRY_COUNT = 100
        private val ALLOWED_EXTENSIONS = setOf("json", "png", "webp")

        private const val MAX_LAYERS = 32
        private const val MAX_IMAGE_DIMENSION = 2048
        private const val MAX_IMAGE_PIXELS = 4_194_304L
    }


    fun installBundledFiltersIfNecessary(): List<InstalledFilter> {
        val assetManager = context.assets

        val assetFiles = try {
            assetManager.list("filters") ?: emptyArray()
        } catch (e: Exception) {
            Log.e(TAG, "Could not list bundled filters", e)
            return listInstalledFilters()
        }

        for (filename in assetFiles.filter { it.endsWith(".xfilter") }.sorted()) {
            val filterId = filename.removeSuffix(".xfilter")
            val installedDir = File(filtersDirectory, filterId)

            if (installedDir.exists()) continue

            try {
                assetManager.open("filters/$filename").use { inputStream ->
                    installPackage(inputStream, isBundled = true)
                }

                Log.i(TAG, "Successfully installed bundled filter: $filename")
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to install bundled filter '$filename': ${e.message}",
                    e
                )
                System.err.println(
                    "BUNDLED FILTER FAILURE: $filename\n${e.stackTraceToString()}"
                )
            }
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
    fun installPackage(
        inputStream: InputStream,
        isBundled: Boolean = false
    ): InstalledFilter {

        val token = "${System.currentTimeMillis()}_${(1000..9999).random()}"

        val tempDir = File(filtersDirectory, ".tmp_extract_$token")
        val archiveFile = File(filtersDirectory, ".tmp_archive_$token")

        var backupDir: File? = null
        var targetDir: File? = null

        var oldVersionMoved = false
        var newVersionCommitted = false

        if (!tempDir.mkdirs()) {
            throw FilterPackageException(
                "Failed to create temporary extraction directory"
            )
        }

        try {
            // 1. Bound the actual incoming archive size before extraction.
            copyArchiveWithLimit(inputStream, archiveFile)

            // 2. Extract into an isolated staging directory.
            FileInputStream(archiveFile).use { archive ->
                extractArchiveSafely(archive, tempDir)
            }

            // 3. Require the manifest at the package root.
            val manifestFile = File(tempDir, "manifest.json")

            if (!manifestFile.isFile) {
                throw FilterPackageException(
                    "Package must contain a root manifest.json"
                )
            }

            val manifestContent = manifestFile.readText(Charsets.UTF_8)

            // Validate raw JSON before the model parser can apply defaults.
            validateManifestDocument(manifestContent)

            val definition = try {
                FilterDefinition.fromJson(manifestContent)
            } catch (e: Exception) {
                throw FilterPackageException(
                    "Invalid manifest: ${e.message}",
                    e
                )
            }

            if (
                definition.schemaVersion >
                FilterDefinition.CURRENT_SUPPORTED_SCHEMA
            ) {
                throw FilterPackageException(
                    "Unsupported schema version: ${definition.schemaVersion}"
                )
            }

            if (
                definition.minimumRuntimeVersion >
                FilterDefinition.CURRENT_RUNTIME_VERSION
            ) {
                throw FilterPackageException(
                    "Package requires a newer Xelvic runtime"
                )
            }

            // 4. Validate every referenced file before replacing anything.
            validatePackageAssets(tempDir, definition)

            // Permit only the repository-bundled debug fixture while canonical UV registration is pending.
            // User-imported and all other mesh packages remain fail-closed until their UV layout is pinned.
            if (definition.renderer == FilterRenderer.FACE_MESH_TEXTURE) {
                val mesh = definition.meshTexture
                val isBundledDiagnosticFixture =
                    BuildConfig.DEBUG &&
                    isBundled &&
                    definition.id == DIAGNOSTIC_MOUSTACHE_FILTER_ID &&
                    mesh != null &&
                    mesh.uvLayout == DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID &&
                    mesh.asset == "assets/moustache.png" &&
                    mesh.regionMask == null

                if (!isBundledDiagnosticFixture) {
                    throw FilterPackageException(
                        "FACE_MESH_TEXTURE packages are not installable yet except for the exact bundled debug moustache fixture; imported packages require a verified model-pinned UV layout"
                    )
                }
            }

            // 5. Stage the existing installation as a rollback backup.
            val target = File(filtersDirectory, definition.id)
            targetDir = target

            if (target.exists()) {
                val backup = File(
                    filtersDirectory,
                    ".tmp_backup_${definition.id}_$token"
                )

                if (!target.renameTo(backup)) {
                    throw FilterPackageException(
                        "Could not stage the existing filter for replacement"
                    )
                }

                backupDir = backup
                oldVersionMoved = true
            }

            // 6. Commit the validated package by renaming within the same parent.
            if (!tempDir.renameTo(target)) {
                throw FilterPackageException(
                    "Could not commit the new package installation"
                )
            }

            newVersionCommitted = true

            // The new installation is committed. The old version is no longer
            // required for rollback, but cleanup failure must not undo the commit.
            if (oldVersionMoved) {
                oldVersionMoved = false

                backupDir?.let { backup ->
                    if (backup.exists() && !deleteRecursively(backup)) {
                        Log.w(
                            TAG,
                            "New package installed, but old backup cleanup failed"
                        )
                    }
                }
            }

            return InstalledFilter(
                id = definition.id,
                definition = definition,
                packageDirectory = target,
                thumbnailFile = File(target, definition.thumbnail),
                isBundled = isBundled
            )

        } catch (e: Exception) {

            // Restore the previous valid package if replacement did not commit.
            if (
                !newVersionCommitted &&
                oldVersionMoved &&
                backupDir != null &&
                targetDir != null &&
                !targetDir.exists()
            ) {
                if (backupDir.renameTo(targetDir)) {
                    oldVersionMoved = false
                } else {
                    Log.e(
                        TAG,
                        "CRITICAL: Failed to restore previous package. " +
                                "Backup retained at ${backupDir.absolutePath}",
                        e
                    )
                }
            }

            throw if (e is FilterPackageException) {
                e
            } else {
                FilterPackageException(
                    "Filter installation failed: ${e.message}",
                    e
                )
            }

        } finally {
            if (tempDir.exists()) {
                deleteRecursively(tempDir)
            }

            if (archiveFile.exists() && !archiveFile.delete()) {
                Log.w(TAG, "Could not remove temporary archive")
            }

            // Never delete a backup if rollback failed.
            if (
                newVersionCommitted &&
                backupDir != null &&
                backupDir.exists()
            ) {
                deleteRecursively(backupDir)
            }
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
    private fun extractArchiveSafely(
        inputStream: InputStream,
        destinationDir: File
    ) {
        val canonicalDest = destinationDir.canonicalFile
        val seenPaths = mutableSetOf<String>()
        val buffer = ByteArray(8192)

        var totalEntries = 0
        var totalExtractedBytes = 0L

        ZipInputStream(inputStream).use { zipIn ->
            var entry = zipIn.nextEntry

            while (entry != null) {
                totalEntries++

                if (totalEntries > MAX_ENTRY_COUNT) {
                    throw FilterPackageException(
                        "Archive exceeds $MAX_ENTRY_COUNT entries"
                    )
                }

                val rawName = entry.name

                if (
                    rawName.isBlank() ||
                    rawName.startsWith("/") ||
                    rawName.startsWith("\\") ||
                    rawName.contains('\\') ||
                    Regex("^[A-Za-z]:").containsMatchIn(rawName)
                ) {
                    throw FilterPackageException("Unsafe archive entry path")
                }

                val cleanName = if (entry.isDirectory) {
                    rawName.removeSuffix("/")
                } else {
                    rawName
                }

                val segments = cleanName.split('/')

                if (
                    cleanName.isBlank() ||
                    segments.any { it.isBlank() || it == "." || it == ".." }
                ) {
                    throw FilterPackageException("Unsafe archive entry: $rawName")
                }

                val targetFile = File(destinationDir, cleanName).canonicalFile

                if (
                    !targetFile.path.startsWith(
                        canonicalDest.path + File.separator
                    )
                ) {
                    throw FilterPackageException("Archive path traversal detected")
                }

                val pathKey = targetFile.path.lowercase(Locale.ROOT)

                if (!seenPaths.add(pathKey)) {
                    throw FilterPackageException(
                        "Duplicate archive entry: $rawName"
                    )
                }

                if (entry.isDirectory) {
                    if (!targetFile.exists() && !targetFile.mkdirs()) {
                        throw FilterPackageException(
                            "Could not create archive directory"
                        )
                    }
                } else {
                    val extension = targetFile.extension.lowercase(Locale.ROOT)

                    if (extension !in ALLOWED_EXTENSIONS) {
                        throw FilterPackageException(
                            "Unsupported archive entry type: $rawName"
                        )
                    }

                    val parent = targetFile.parentFile

                    if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                        throw FilterPackageException(
                            "Could not create parent directory"
                        )
                    }

                    FileOutputStream(targetFile).use { output ->
                        while (true) {
                            val count = zipIn.read(buffer)

                            if (count < 0) break
                            if (count == 0) continue

                            totalExtractedBytes += count

                            if (totalExtractedBytes > MAX_TOTAL_EXTRACTED_BYTES) {
                                throw FilterPackageException(
                                    "Extracted archive exceeds the 50 MB limit"
                                )
                            }

                            output.write(buffer, 0, count)
                        }
                    }
                }

                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
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
