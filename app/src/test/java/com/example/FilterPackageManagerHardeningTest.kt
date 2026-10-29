
package com.example

import android.content.Context
import com.example.xelvic.packagemanager.FilterPackageException
import com.example.xelvic.packagemanager.FilterPackageManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterPackageManagerHardeningTest {

    private fun prepareManager(context: Context): FilterPackageManager {
        val manager = FilterPackageManager(context)
        manager.removeFilter("cat_face")

        val installed = manager.installBundledFiltersIfNecessary()
        assertNotNull(installed.firstOrNull { it.id == "cat_face" })

        return manager
    }

    private fun readBundledPackage(
        context: Context,
        filename: String
    ): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()

        ZipInputStream(
            context.assets.open("filters/$filename")
        ).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)
                    entries[entry.name] = output.toByteArray()
                }

                zip.closeEntry()
            }
        }

        return entries
    }

    private fun buildArchive(
        entries: Map<String, ByteArray>
    ): ByteArray {
        val output = ByteArrayOutputStream()

        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        return output.toByteArray()
    }

    private fun installedCatManifest(
        manager: FilterPackageManager
    ): String {
        val installed = manager.listInstalledFilters()
            .first { it.id == "cat_face" }

        return File(
            installed.packageDirectory,
            "manifest.json"
        ).readText()
    }

    @Test
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

    @Test
    fun invalidReplacementPreservesExistingFilter() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val manager = prepareManager(context)
        val originalManifest = installedCatManifest(manager)

        val entries = readBundledPackage(
            context,
            "cat_face.xfilter"
        )

        val manifest = JSONObject(
            entries.getValue("manifest.json")
                .toString(Charsets.UTF_8)
        )

        // Point one layer at a nonexistent asset.
        manifest.getJSONArray("layers")
            .getJSONObject(0)
            .put("asset", "assets/missing.png")

        entries["manifest.json"] =
            manifest.toString().toByteArray(Charsets.UTF_8)

        val failure = assertThrows(
            FilterPackageException::class.java
        ) {
            manager.installPackage(
                ByteArrayInputStream(buildArchive(entries))
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("Missing package asset")
        )

        // Existing package must still be intact.
        assertEquals(originalManifest, installedCatManifest(manager))

        val installed = manager.listInstalledFilters()
            .first { it.id == "cat_face" }

        assertTrue(
            File(
                installed.packageDirectory,
                "assets/cat_nose.png"
            ).isFile
        )
    }

    @Test
    fun rejectsPathTraversalWithoutDamagingExistingFilter() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val manager = prepareManager(context)
        val originalManifest = installedCatManifest(manager)

        val entries = readBundledPackage(
            context,
            "cat_face.xfilter"
        )

        entries["../../escaped.txt"] =
            "must not be extracted".toByteArray()

        val failure = assertThrows(
            FilterPackageException::class.java
        ) {
            manager.installPackage(
                ByteArrayInputStream(buildArchive(entries))
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("Unsafe archive entry")
                    || failure.message.orEmpty().contains("traversal")
                    || failure.message.orEmpty().contains("Unsafe")
        )

        assertEquals(originalManifest, installedCatManifest(manager))
        assertTrue(
            !File(context.filesDir, "escaped.txt").exists()
        )
    }

    @Test
    fun rejectsOutOfRangeScaleWithoutReplacingExistingFilter() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val manager = prepareManager(context)
        val originalManifest = installedCatManifest(manager)

        val entries = readBundledPackage(
            context,
            "cat_face.xfilter"
        )

        val manifest = JSONObject(
            entries.getValue("manifest.json")
                .toString(Charsets.UTF_8)
        )

        manifest.getJSONArray("layers")
            .getJSONObject(0)
            .put("scale", 999.0)

        entries["manifest.json"] =
            manifest.toString().toByteArray(Charsets.UTF_8)

        assertThrows(
            FilterPackageException::class.java
        ) {
            manager.installPackage(
                ByteArrayInputStream(buildArchive(entries))
            )
        }

        assertEquals(originalManifest, installedCatManifest(manager))
    }
}
