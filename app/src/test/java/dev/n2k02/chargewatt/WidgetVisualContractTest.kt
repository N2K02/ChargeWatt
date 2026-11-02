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

    @Test fun bothThemesUseConsistentTypographyAndReadableSourceBadge() {
        for (theme in listOf("dark", "transparent")) {
            val root = xml("layout/widget_$theme.xml")
            assertEquals("LinearLayout", root.tagName)
            assertTrue(root.getAttribute("android:background").endsWith("widget_bg_$theme"))
            val nodes = root.getElementsByTagName("TextView")
            val all = (0 until nodes.length).map { nodes.item(it) as Element }
            val ids = all.map { it.getAttribute("android:id") }
            for (id in listOf("widget_status", "widget_value", "widget_sub", "widget_voltage")) {
                assertEquals("Missing or duplicate $id in $theme", 1, ids.count { it.endsWith("/$id") })
            }
            assertFalse(ids.any { it.endsWith("/widget_level") })
            val status = all.first { it.getAttribute("android:id").endsWith("/widget_status") }
            assertEquals("match_parent", status.getAttribute("android:layout_width"))
            assertEquals("@drawable/widget_source_badge_$theme", status.getAttribute("android:background"))
            assertEquals("uniform", status.getAttribute("android:autoSizeTextType"))
            assertEquals("Wireless Qi", status.getAttribute("android:text"))
            for (id in listOf("widget_value", "widget_sub", "widget_voltage")) {
                val node = all.first { it.getAttribute("android:id").endsWith("/$id") }
                assertEquals("14sp", node.getAttribute("android:textSize"))
                assertEquals("sans-serif-condensed", node.getAttribute("android:fontFamily"))
                assertEquals("bold", node.getAttribute("android:textStyle"))
                assertEquals("true", node.getAttribute("android:singleLine"))
                assertEquals("uniform", node.getAttribute("android:autoSizeTextType"))
                assertEquals("10sp", node.getAttribute("android:autoSizeMinTextSize"))
                assertEquals("14sp", node.getAttribute("android:autoSizeMaxTextSize"))
            }
            val pill = xml("drawable/widget_source_badge_$theme.xml")
            assertEquals("shape", pill.tagName)
            assertEquals(1, pill.getElementsByTagName("stroke").length)
            assertEquals(1, pill.getElementsByTagName("corners").length)
        }
    }

    @Test fun glassIsRenderedByCheapDrawableShapesWithoutAnimation() {
        for (theme in listOf("dark", "transparent")) {
            val root = xml("drawable/widget_bg_$theme.xml")
            assertEquals("shape", root.tagName)
            assertEquals(1, root.getElementsByTagName("gradient").length)
            assertEquals(1, root.getElementsByTagName("stroke").length)
            assertEquals(1, root.getElementsByTagName("corners").length)
        }
        val source = listOf(File("src/main/java/dev/n2k02/chargewatt/WidgetReceiver.kt"),
            File("app/src/main/java/dev/n2k02/chargewatt/WidgetReceiver.kt"))
            .first { it.isFile }.readText()
        assertTrue(source.contains("setContentDescription("))
        assertTrue(source.contains("partiallyUpdateAppWidget"))
        assertFalse(source.contains("BitmapFactory"))
        assertFalse(source.contains("ObjectAnimator"))
    }

    @Test fun noAccidentalExpansionBeyondOneByOne() {
        val provider = xml("xml/chargewatt_widget_info.xml")
        assertEquals("1", provider.getAttribute("android:targetCellWidth"))
        assertEquals("1", provider.getAttribute("android:targetCellHeight"))
        assertEquals("none", provider.getAttribute("android:resizeMode"))
    }
}
