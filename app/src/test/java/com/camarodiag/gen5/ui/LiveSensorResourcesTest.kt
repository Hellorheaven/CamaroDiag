package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Prevent new sensor labels from silently falling back to bilingual literals.
 * Only English and French are required until the follow-up translation PR.
 */
class LiveSensorResourcesTest {
    private fun strings(locale: String): Set<String> {
        val file = File("src/main/res/$locale/strings.xml")
        assertTrue("Missing Android resources: ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { index ->
            nodes.item(index).attributes.getNamedItem("name").nodeValue
        }.toSet()
    }

    @Test fun englishAndFrenchHaveMatchingResourceKeys() {
        assertEquals(strings("values"), strings("values-fr"))
    }

    @Test fun everyKnownSensorIsMappedToAnEnglishAndFrenchResource() {
        val english = strings("values")
        val french = strings("values-fr")
        val appSource = File("src/main/java/com/camarodiag/gen5/ui/CamaroDiagApp.kt").readText()
        val aliases = mapOf("SPD" to "speed", "TPS" to "throttle")
        for (sensor in LiveSensorCatalog.known) {
            val resource = "live_pid_" + (aliases[sensor.id] ?: sensor.id.lowercase())
            assertTrue("Missing EN resource for ${sensor.id}: $resource", resource in english)
            assertTrue("Missing FR resource for ${sensor.id}: $resource", resource in french)
            assertTrue(
                "Sensor ${sensor.id} not mapped to Android resource $resource",
                Regex("\\\"" + Regex.escape(sensor.id) + "\\\"\\s+to\\s+R\\.string\\." + resource)
                    .containsMatchIn(appSource)
            )
        }
    }
}
