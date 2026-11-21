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

    /**
     * The product name is a trademark-like proper noun, NOT a translatable
     * word. Languages may change the surrounding grammar but never its spelling,
     * capitalization or add a declension suffix directly to the name.
     */
    @Test fun camaroDiagBrandSpellingIsImmutableInEveryLanguage() {
        val brandVariant = Regex("""(?i)(?:c|k)amarodiag""")
        val splitBrand = Regex("""(?i)camaro[\s_-]+diag""")
        val cyrillicBrand = Regex("""(?iu)камаро[\s_-]*диаг""")
        for (locale in locales) {
            val translated = strings(locale)
            assertEquals("App brand must remain verbatim in $locale",
                "CamaroDiag", translated["app_name"])
            for ((key, value) in translated) {
                assertFalse("Split brand name in $locale/$key",
                    splitBrand.containsMatchIn(value))
                assertFalse("Transliterated brand name in $locale/$key",
                    cyrillicBrand.containsMatchIn(value))
                for (match in brandVariant.findAll(value)) {
                    assertEquals("Brand spelling or case modified in $locale/$key",
                        "CamaroDiag", match.value)
                    val following = value.getOrNull(match.range.last + 1)
                    // A Korean/Japanese/Chinese particle immediately after the
                    // unchanged brand is grammar, NOT a changed spelling.
                    // Reject Latin-letter suffixes attached to the product name.
                    assertFalse("Brand must not carry a Latin-letter suffix in $locale/$key",
                        following != null && (
                            following in 'a'..'z' || following in 'A'..'Z' ||
                                following in '\u00C0'..'\u024F'
                        ))
                }
            }
        }
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

    @Test fun everyLocaleContainsAllLiveSensorLabelsAndPluralResources() {
        val sensors = strings("en").keys.filter { it.startsWith("live_pid_") }.toSet()
        fun plurals(locale: String): Map<String, Element> {
            val nodes = document(File("src/main/res/${directory(locale)}/strings.xml")).getElementsByTagName("plurals")
            return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("name") }
        }
        val expectedPlurals = plurals("en").keys
        for (locale in locales) {
            val labels = strings(locale)
            assertTrue("Missing live sensor label in $locale", sensors.all { !labels[it].isNullOrBlank() })
            val translated = plurals(locale)
            assertEquals("Plural resources differ for $locale", expectedPlurals, translated.keys)
            for ((key, plural) in translated) {
                val items = plural.getElementsByTagName("item")
                val fallback = (0 until items.length).map { items.item(it) as Element }
                    .single { it.getAttribute("quantity") == "other" }
                assertTrue("Empty default plural form for $locale/$key", fallback.textContent.isNotBlank())
            }
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

    @Test fun tlsBridgePairingAndSecurityGuidanceAreLocalizedInAllThirtyLocales() {
        val keys = setOf(
            "cal_j2534_launch_instructions", "cal_j2534_security_notice",
            "cal_pc_pairing_token"
        )
        val english = strings("en")
        assertTrue("English bridge warning must describe mandatory TLS",
            english.getValue("cal_j2534_security_notice").contains("TLS 1.2+"))
        assertFalse("Obsolete plaintext warning must not remain",
            english.getValue("cal_j2534_security_notice").contains("not TLS-encrypted"))
        for (locale in locales) {
            val translated = strings(locale)
            assertTrue("Missing secure bridge guidance in $locale: ${keys - translated.keys}",
                translated.keys.containsAll(keys))
            assertTrue("Empty secure bridge guidance in $locale",
                keys.all { !translated.getValue(it).isBlank() })
            if (locale != "en") {
                assertTrue("Secure bridge warning was not localized in $locale",
                    translated.getValue("cal_j2534_security_notice") !=
                        english.getValue("cal_j2534_security_notice"))
            }
        }
    }

    @Test fun phoneLanguageOptionIsTranslatedInAllThirtyLocales() {
        for (locale in locales) {
            val localized = strings(locale)
            val key = "system_language_follow_phone"
            assertTrue("Missing phone-language label in $locale", key in localized)
            assertTrue("Blank phone-language label in $locale", localized.getValue(key).isNotBlank())
        }
    }

    @Test fun connectionSettingsLabelsExistInEveryDeclaredLocale() {
        val keys = setOf(
            "settings_current_vehicle", "settings_current_adapter",
            "adapter_section_title", "paired_bluetooth_devices",
            "usb_devices_detected", "usb_none_detected",
            "wifi_connection_title", "adapter_ip_address",
            "adapter_tcp_port", "adapter_connect_wifi"
        )
        for (locale in locales) {
            val translated = strings(locale)
            assertTrue(
                "Missing OBD connection labels in $locale: ${keys - translated.keys}",
                translated.keys.containsAll(keys)
            )
            assertTrue(
                "Blank OBD connection label in $locale",
                keys.all { translated.getValue(it).isNotBlank() }
            )
        }
    }

    /** Offline/simulation warnings must be explicit in every advertised language. */
    @Test fun safetyCriticalOfflineAndUnverifiedLabelsExistInEveryLocale() {
        val keys = setOf(
            "obd_connect_adapter_notice", "obd_offline_simulation_notice",
            "obd_vehicle_ecu_identification", "obd_no_live_data",
            "obd_no_dtcs", "obd_no_freeze_frame", "obd_simulation_scope",
            "vehicle_vin_hint", "status_not_verified", "adapter_discovery_only"
        )
        for (locale in locales) {
            val translated = strings(locale)
            assertTrue("Missing simulation/safety labels in $locale: ${keys - translated.keys}",
                translated.keys.containsAll(keys))
            assertTrue("Empty simulation/safety label in $locale",
                keys.all { translated.getValue(it).isNotBlank() })
        }
    }

    /**
     * Three production localization batches: console/disconnects, standard
     * OBD signal names and safety-critical CAN / ECU programming notices.
     * Ensure no language silently loses these explicitly translated resources.
     */
    @Test fun massTranslatedConsoleSensorsAndSafetyKeysExistInEveryLocale() {
        val keys = setOf(
            "can_sniffer_raw_heading", "can_sniffer_export_raw", "can_sniffer_dtc_heading", "connection_lost_bluetooth",
            "connection_lost_vehicle", "connection_lost_usb", "connection_lost_wifi", "connection_lost_pc_bridge",
            "live_sensor_search", "live_sensor_show_all", "can_console_history", "can_console_clear",
            "can_console_empty", "can_console_tx", "can_console_rx", "can_console_system",
            "can_console_input_label", "can_console_sending", "obd_dtc_help_unavailable", "cal_expert_no_write",
            "flexfuel_preflight_write", "status_not_implemented", "can_trace_pid_1f", "can_trace_pid_21",
            "can_trace_pid_2f", "can_trace_pid_31", "can_trace_pid_33", "can_trace_pid_3c",
            "can_trace_pid_3d", "can_trace_pid_3e", "can_trace_pid_3f", "can_trace_pid_47",
            "can_trace_pid_48", "can_trace_pid_49", "can_trace_pid_4a", "can_trace_pid_4b",
            "can_trace_pid_4d", "can_trace_pid_4e", "can_trace_pid_5a", "can_trace_pid_5c",
            "can_trace_pid_5d", "can_trace_pid_5e", "can_trace_pid_61", "can_trace_pid_62",
            "can_trace_pid_63", "live_pid_ethanol", "can_trace_flow_status_cts", "can_trace_flow_status_wait",
            "can_trace_flow_status_overflow", "can_trace_flow_status_reserved", "can_trace_exchange_heading", "can_trace_exchange_completed",
            "can_trace_exchange_pending", "can_trace_exchange_unanswered", "can_console_raw_tx_confirm_title", "can_console_raw_tx_confirm_message",
            "can_console_tx_unverified", "can_trace_clear_filters", "can_trace_filter_invalid", "can_trace_flow_control_heading",
            "cal_expert_block_recovery", "cal_expert_block_driver", "cal_expert_identity_notice", "flexfuel_lfx2012_simulation_warning",
            "cal_expert_block_memory_map", "flexfuel_preflight_backup"
        )
        assertEquals("Unexpected mass-translation catalogue size", 66, keys.size)
        val source = strings("en")
        for (locale in locales) {
            val translated = strings(locale)
            val missing = keys - translated.keys
            assertTrue("Mass translation missing in $locale: ${missing.sorted()}", missing.isEmpty())
            assertTrue("Blank mass translation in $locale",
                keys.all { translated.getValue(it).isNotBlank() })
            if (locale != "en") {
                val warnings = listOf(
                    "connection_lost_vehicle", "cal_expert_no_write",
                    "flexfuel_preflight_write", "can_console_raw_tx_confirm_message"
                )
                assertTrue("Safety notices must not silently reuse English in $locale",
                    warnings.all { translated.getValue(it) != source.getValue(it) })
            }
        }
    }

    @Test fun expertBenchModeHasLocalizedLabelsInEveryLanguage() {
        val labels = setOf(
            "cal_expert_title", "cal_expert_expand", "cal_expert_collapse", "cal_expert_family",
            "cal_expert_chip", "cal_expert_programmer", "cal_expert_access_title", "cal_expert_access_boot",
            "cal_expert_access_nexus", "cal_expert_access_chipoff", "cal_expert_region_title", "cal_expert_region_pflash",
            "cal_expert_region_dflash", "cal_expert_region_eeprom", "cal_expert_blockers", "cal_expert_block_controller",
            "cal_expert_block_chip", "cal_expert_block_programmer", "cal_expert_block_image_missing", "cal_expert_block_image_format"
        )
        assertEquals(20, labels.size)
        for (locale in locales) {
            val translated = strings(locale)
            assertTrue("Missing expert bench labels in $locale: ${labels - translated.keys}",
                translated.keys.containsAll(labels))
            assertTrue("Empty expert bench label in $locale",
                labels.all { translated.getValue(it).isNotBlank() })
        }
    }

    /** Key parity reached by English, French and the twelve established locales. */
    @Test fun completedLegacyLocalesKeepFullEnglishKeyParity() {
        val complete = listOf(
            "en", "fr", "de", "it", "es", "he", "ru", "zh-CN",
            "ja", "ko-KR", "ar", "pt", "nl", "el"
        )
        val english = strings("en").keys
        for (locale in complete) {
            val actual = strings(locale).keys
            assertEquals("Full key coverage regressed in $locale", english, actual)
        }
    }

    @Test fun canTraceFormatsHistoryAndSensorPickerAreLocalizedForAllLanguages() {
        val keys = setOf(
            "can_sniffer_request_examples", "can_sniffer_dtc_service", "can_sniffer_request_history", "can_sniffer_request_history_item",
            "can_sniffer_request_history_response", "can_trace_id_filter", "can_trace_id_hint", "can_trace_data_prefix",
            "can_trace_data_hint", "can_trace_nrc_detail", "can_trace_iso_tp_incomplete_count", "can_trace_flow_control_detail",
            "can_trace_flow_stmin_ms", "can_trace_flow_stmin_us", "can_trace_flow_stmin_reserved", "can_trace_exchange_parameter_id",
            "can_trace_exchange_summary", "can_trace_exchange_request", "can_trace_exchange_response", "can_trace_exchange_elapsed",
            "can_console_input_hint", "can_console_adapter_ack", "saved_vehicle_import_legacy", "saved_vehicle_legacy_default_name",
            "obd_dtc_help_close", "obd_choose_sensors", "obd_filter_sensors", "obd_sensor_ok",
            "obd_sensor_cancel", "obd_sensor_not_measured", "live_selected_sensors"
        )
        assertEquals(31, keys.size)
        for (locale in locales) {
            val resources = strings(locale)
            assertTrue("Missing CAN trace/sensor labels in $locale: ${keys - resources.keys}",
                resources.keys.containsAll(keys))
            assertTrue("Blank CAN trace/sensor label in $locale",
                keys.all { resources.getValue(it).isNotBlank() })
        }
    }


    /** Debug tools and the dormant licensing screen must be translated in all 30 locales. */
    @Test fun privateBuildsAndStagedLicensingLabelsExistInAllLocales() {
        val keys = setOf(
            "tab_debug_builds", "tab_license", "debug_builds_title",
            "debug_builds_refresh", "debug_builds_download",
            "debug_builds_unavailable", "debug_builds_error",
            "license_disabled", "license_buy", "license_refresh",
            "license_free", "license_owner", "license_premium", "license_title"
        )
        assertEquals(14, keys.size)
        val english = strings("en")
        for (locale in locales) {
            val translated = strings(locale)
            assertTrue(
                "Missing licensing/Debug UI labels in $locale: ${keys - translated.keys}",
                translated.keys.containsAll(keys)
            )
            assertTrue("Blank licensing/Debug labels in $locale",
                keys.all { !translated.getValue(it).isBlank() })
            if (locale != "en") {
                assertTrue(
                    "Licensing/Debug UI has no actual translation for $locale",
                    keys.any { translated.getValue(it) != english.getValue(it) }
                )
            }
        }
    }

    /** CamaroDiag is a trademark/product name, never a translatable word. */
    @Test fun productNameCamaroDiagRemainsExactlyUnchangedInAllLocales() {
        val nameVariants = Regex("""(?i)(?:camarodiag|kamarodiag)[a-zà-öø-ÿ]*""")
        for (locale in locales) {
            val localized = strings(locale)
            for ((key, value) in localized) {
                for (match in nameVariants.findAll(value)) {
                    assertEquals(
                        "CamaroDiag product name was translated or declined in $locale/$key",
                        "CamaroDiag",
                        match.value
                    )
                }
            }
        }
    }

}
