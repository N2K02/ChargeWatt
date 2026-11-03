package dev.n2k02.chargewatt

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Keep the design within RemoteViews' supported XML APIs and small home-screen cells.
 * Both themes must have identical field IDs for full and partial widget updates.
 */
class WidgetVisualContractTest {
    private fun xml(name: String): Element {
        val candidates = listOf(File("src/main/res/$name"), File("app/src/main/res/$name"))
        val source = candidates.firstOrNull { it.isFile } ?: error("Missing resource: $name")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(source).documentElement
    }

    @Test fun allWidgetStylesUseChargingIconAndRightAlignedBatteryMetadata() {
        for (theme in listOf("dark", "transparent", "compact")) {
            val root = xml("layout/widget_$theme.xml")
            assertEquals("LinearLayout", root.tagName)
            assertTrue(root.getAttribute("android:background").endsWith("widget_bg_$theme"))
            val imageNodes = root.getElementsByTagName("ImageView")
            val icons = (0 until imageNodes.length).map { imageNodes.item(it) as Element }
            assertEquals(1, icons.count { it.getAttribute("android:id").endsWith("/widget_charge_icon") })
            val nodes = root.getElementsByTagName("TextView")
            val all = (0 until nodes.length).map { nodes.item(it) as Element }
            val ids = all.map { it.getAttribute("android:id") }
            for (id in listOf("widget_temperature", "widget_level", "widget_value", "widget_sub", "widget_voltage")) {
                assertEquals("Missing or duplicate $id in $theme", 1, ids.count { it.endsWith("/$id") })
            }
            assertFalse(ids.any { it.endsWith("/widget_status") })
            assertEquals("Charging state", icons.first { it.getAttribute("android:id").endsWith("/widget_charge_icon") }.getAttribute("android:contentDescription"))
            for (id in listOf("widget_value", "widget_sub", "widget_voltage")) {
                val node = all.first { it.getAttribute("android:id").endsWith("/$id") }
                val expectedSize = if (theme == "compact") "12sp" else "14sp"
                assertEquals(expectedSize, node.getAttribute("android:textSize"))
                assertEquals("sans-serif-condensed", node.getAttribute("android:fontFamily"))
                assertEquals("bold", node.getAttribute("android:textStyle"))
                assertEquals("true", node.getAttribute("android:singleLine"))
                assertEquals("uniform", node.getAttribute("android:autoSizeTextType"))
                assertEquals("9sp", node.getAttribute("android:autoSizeMinTextSize"))
                assertEquals(expectedSize, node.getAttribute("android:autoSizeMaxTextSize"))
            }
        }
    }

    @Test fun glassIsRenderedByCheapDrawableShapesWithoutAnimation() {
        for (theme in listOf("dark", "transparent", "compact")) {
            val root = xml("drawable/widget_bg_$theme.xml")
            assertEquals("shape", root.tagName)
            assertEquals(1, root.getElementsByTagName("gradient").length)
            assertEquals(1, root.getElementsByTagName("stroke").length)
            assertEquals(1, root.getElementsByTagName("corners").length)
            val noBorder = xml("drawable/widget_bg_${theme}_borderless.xml")
            assertEquals(0, noBorder.getElementsByTagName("stroke").length)
            assertEquals(1, noBorder.getElementsByTagName("corners").length)
        }
        val source = listOf(File("src/main/java/dev/n2k02/chargewatt/WidgetReceiver.kt"),
            File("app/src/main/java/dev/n2k02/chargewatt/WidgetReceiver.kt"))
            .first { it.isFile }.readText()
        assertTrue(source.contains("setContentDescription("))
        assertTrue(source.contains("setImageViewResource(R.id.widget_charge_icon"))
        assertTrue(source.contains("ChargeWattSettings.showBorder(context)"))
        assertTrue(source.contains("WidgetTextPolicy.temperature(value)"))
        assertTrue(source.contains("partiallyUpdateAppWidget"))
        assertFalse(source.contains("BitmapFactory"))
        assertFalse(source.contains("ObjectAnimator"))
    }


    @Test fun borderIsEnabledByDefaultButCanBeDisabledAndAllThreeStylesAreSelectable() {
        val settings = listOf(File("src/main/java/dev/n2k02/chargewatt/BatteryReading.kt"),
            File("app/src/main/java/dev/n2k02/chargewatt/BatteryReading.kt")).first { it.isFile }.readText()
        val screen = listOf(File("src/main/java/dev/n2k02/chargewatt/MainActivity.kt"),
            File("app/src/main/java/dev/n2k02/chargewatt/MainActivity.kt")).first { it.isFile }.readText()
        assertTrue(settings.contains("getBoolean(KEY_BORDER, true)"))
        assertTrue(settings.contains("putBoolean(KEY_BORDER, value)"))
        assertTrue(screen.contains("theme == \"compact\""))
        assertTrue(screen.contains("Text(\"Widget border\")"))
        assertTrue(screen.contains("ChargeWattSettings.setShowBorder(context, enabled)"))
    }

    @Test fun noAccidentalExpansionBeyondOneByOne() {
        val provider = xml("xml/chargewatt_widget_info.xml")
        assertEquals("1", provider.getAttribute("android:targetCellWidth"))
        assertEquals("1", provider.getAttribute("android:targetCellHeight"))
        assertEquals("none", provider.getAttribute("android:resizeMode"))
    }
}
