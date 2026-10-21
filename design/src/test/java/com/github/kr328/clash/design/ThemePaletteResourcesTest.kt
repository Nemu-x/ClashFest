package com.github.kr328.clash.design

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue
import org.w3c.dom.Element

class ThemePaletteResourcesTest {
    private val resources = listOf(File("src/main/res"), File("design/src/main/res"))
        .first { it.isDirectory }
    private val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File(resources, "values/themes.xml"))

    @Test fun presetAccentsAlwaysCarryMatchingForegroundColors() {
        val styles = document.getElementsByTagName("style")
        var checked = 0
        for (i in 0 until styles.length) {
            val style = styles.item(i) as Element
            val name = style.getAttribute("name")
            if (!name.startsWith("ThemeOverlay_ClashFest_Palette") &&
                !name.startsWith("ThemeOverlay_ClashFest_Sloth_")) continue
            val entries = style.getElementsByTagName("item")
            val colors = (0 until entries.length).associate {
                val entry = entries.item(it) as Element
                entry.getAttribute("name") to entry.textContent.trim()
            }
            for (role in listOf("Primary", "PrimaryContainer", "Secondary", "SecondaryContainer", "Tertiary", "TertiaryContainer")) {
                val background = colors["color$role"]
                val foreground = colors["colorOn$role"]
                assertTrue(background != null && foreground != null, "$name must define both sides of $role")
                assertTrue(contrast(requireNotNull(background), requireNotNull(foreground)) >= 4.5,
                    "$name has unreadable text on $role")
            }
            checked++
        }
        assertTrue(checked == 14, "All seven themed palettes need light and dark checks")
    }

    private fun contrast(a: String, b: String): Double {
        fun luminance(hex: String): Double {
            val rgb = hex.removePrefix("#").toLong(16)
            fun channel(shift: Int): Double {
                val c = ((rgb shr shift) and 255) / 255.0
                return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
            }
            return channel(16) * .2126 + channel(8) * .7152 + channel(0) * .0722
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
