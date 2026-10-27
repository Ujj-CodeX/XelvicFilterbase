package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.xelvic.packagemanager.FilterPackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun readStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Xelvic", appName)
    }

    @Test
    fun verifyFilterPackageManagerInit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FilterPackageManager(context)
        assertNotNull(manager)
        val installed = manager.installBundledFiltersIfNecessary()
        assertNotNull(installed)
        assertTrue("Installed filters should contain at least 2 filters", installed.size >= 2)

        val catFace = installed.find { it.id == "cat_face" }
        assertNotNull("Cat Face filter should be installed", catFace)
        assertEquals("Cat Face", catFace!!.definition.name)
        assertEquals(4, catFace.definition.layers.size)

        val butterfly = installed.find { it.id == "butterfly" }
        assertNotNull("Butterfly filter should still be installed", butterfly)
        assertEquals("Butterfly", butterfly!!.definition.name)
    }

    @Test
    fun testCameraViewPreviewViewCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = androidx.camera.view.PreviewView(context).apply {
            scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
            implementationMode = androidx.camera.view.PreviewView.ImplementationMode.PERFORMANCE
        }
        assertNotNull(previewView)
        assertEquals(androidx.camera.view.PreviewView.ScaleType.FILL_CENTER, previewView.scaleType)
        assertEquals(androidx.camera.view.PreviewView.ImplementationMode.PERFORMANCE, previewView.implementationMode)
    }
}

