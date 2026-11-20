package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guard the 24 EU languages and the six additional supported languages.
 * Partial translations may fall back to English, but must never contain broken resources.
 */
class LocaleResourcesIntegrityTest {
    private val locales = listOf(
        "en", "fr", "de", "it", "es", "he", "ru", "zh-CN", "ja", "ko-KR",
        "ar", "pt", "nl", "el", "bg", "hr", "cs", "da", "et", "fi",
        "hu", "ga", "lv", "lt", "mt", "pl", "ro", "sk", "sl", "sv"
    )
    private val euLanguages = setOf(
        "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu",
        "ga", "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv"
    )

    private fun document(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).also {
            it.documentElement.normalize()
        }

    private fun directory(tag: String) = when (tag) {
        "en" -> "values"
        "he" -> "values-iw"
        "zh-CN" -> "values-zh-rCN"
        "ko-KR" -> "values-ko-rKR"
        else -> "values-$tag"
    }

    private fun strings(tag: String): Map<String, String> {
        val file = File("src/main/res/${directory(tag)}/strings.xml")
        assertTrue("Missing strings.xml for $tag: ${file.path}", file.isFile)
        val nodes = document(file).getElementsByTagName("string")
        val values = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val key = element.getAttribute("name")
            assertTrue("Empty resource key in $tag", key.isNotBlank())
            assertFalse("Duplicate resource $key in $tag", values.containsKey(key))
            values[key] = element.textContent
        }
        return values
    }

    @Test fun allEuLanguagesAreDeclaredAndHaveResourceFiles() {
        val file = File("src/main/res/xml/locales_config.xml")
        val nodes = document(file).getElementsByTagName("locale")
        val configured = (0 until nodes.length).map {
            (nodes.item(it) as Element).getAttribute("android:name")
        }
        assertEquals("Unexpected or duplicate locale declarations", locales.toSet(), configured.toSet())
        assertEquals("Duplicate locale declarations", locales.size, configured.size)
        assertEquals(24, euLanguages.size)
        assertTrue(configured.map { it.substringBefore('-') }.toSet().containsAll(euLanguages))
        locales.forEach { strings(it) }
    }

    @Test fun translatedKeysExistInEnglishAndFormatArgumentsMatch() {
        val english = strings("en")
        // Android format arguments: numbered placeholders must retain their type.
        val placeholder = Regex("""%([1-9][0-9]*[$])?[-+# 0,(]*[0-9]*(?:\.[0-9]+)?[a-zA-Z]""")
        fun arguments(value: String): List<String> =
            placeholder.findAll(value.replace("%%", "")).map { it.value }.sorted().toList()
        for (locale in locales.filterNot { it == "en" }) {
            for ((key, value) in strings(locale)) {
                assertTrue("Unknown resource $key in $locale", key in english)
                assertEquals("Format arguments differ for $locale/$key", arguments(english.getValue(key)), arguments(value))
            }
        }
    }
    @Test fun allDeclaredLocalesTranslatePrimaryNavigation() {
        val navigationKeys = setOf(
            "tab_vehicle", "tab_connect", "tab_live", "tab_dtcs", "tab_more",
            "tab_modules", "tab_mileage", "tab_calibration", "tab_obd_scan",
            "tab_guide", "tab_controls", "tab_settings"
        )
        for (locale in locales) {
            val translated = strings(locale)
            assertTrue(
                "Missing navigation translations for $locale: ${navigationKeys - translated.keys}",
                translated.keys.containsAll(navigationKeys)
            )
            assertTrue(
                "Blank navigation label in $locale",
                navigationKeys.all { translated.getValue(it).isNotBlank() }
            )
        }
    }

    /**
     * Release gate for #71/#72: each declared locale must provide every English
     * string key. The current incomplete locales intentionally fail this test
     * until real translations are committed; copying English text is not completion.
     */
    @Test fun allAdvertisedLocalesCoverEveryStringKey() {
        val source = strings("en").keys
        for (locale in locales.filterNot { it == "en" }) {
            val missing = source - strings(locale).keys
            assertTrue(
                "Incomplete translations for $locale: ${missing.size} missing keys, " +
                    missing.sorted().take(20).joinToString(", "),
                missing.isEmpty()
            )
        }
    }

    @Test fun diagnosticCoreLabelsAreTranslatedInEveryLocale() {
        val required = setOf(
            "common_connect", "common_refresh", "status_offline",
            "obd_not_available", "obd_run_full_scan", "obd_vin",
            "obd_protocol", "obd_dtc_title", "obd_adapter_voltage",
            "obd_supported_pids"
        )
        for (locale in locales) {
            val localized = strings(locale)
            assertTrue(
                "Missing localized diagnostic labels in $locale: ${required - localized.keys}",
                localized.keys.containsAll(required)
            )
            assertTrue(
                "Empty localized diagnostic label in $locale",
                required.all { localized.getValue(it).isNotBlank() }
            )
        }
    }

    @Test fun ecuIdentityAndDtcStatusLabelsExistInEveryLocale() {
        val keys = setOf(
            "obd_ecu_name", "obd_none_identified", "obd_data_unavailable",
            "obd_returned_live_data", "obd_pending", "obd_permanent",
            "obd_confirmed", "obd_freeze_frame", "obd_raw_scan_log",
            "obd_powertrain_ecm"
        )
        for (locale in locales) {
            val translations = strings(locale)
            assertTrue(
                "Missing ECU/DTC labels in $locale: ${keys - translations.keys}",
                translations.keys.containsAll(keys)
            )
            assertTrue(
                "Blank ECU/DTC label in $locale",
                keys.all { translations.getValue(it).isNotBlank() }
            )
        }
    }

    @Test fun vehicleSetupAndReadinessLabelsExistInEveryLocale() {
        val keys = setOf(
            "vehicle_setup_header", "vehicle_generation", "vehicle_model_year",
            "vehicle_engine", "vehicle_transmission", "vehicle_market_region",
            "vehicle_vin", "obd_scan_title", "obd_readiness", "obd_mode06_results"
        )
        for (locale in locales) {
            val translations = strings(locale)
            assertTrue(
                "Missing vehicle/OBD labels in $locale: ${keys - translations.keys}",
                translations.keys.containsAll(keys)
            )
            assertTrue(
                "Blank vehicle/OBD label in $locale",
                keys.all { translations.getValue(it).isNotBlank() }
            )
        }
    }

}
