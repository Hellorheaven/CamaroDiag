package com.camarodiag.gen5.ui

import com.camarodiag.gen5.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Non-Android regression tests for system language resolution. */
class AppLanguageFallbackTest {
    @Test fun supportedLanguageIsSelectedFromSystemLocale() {
        assertEquals(AppLanguage.FR, AppLanguage.fromLocale(Locale.forLanguageTag("fr-FR")))
        assertEquals(AppLanguage.DE, AppLanguage.fromLocale(Locale.forLanguageTag("de-DE")))
        assertEquals(AppLanguage.PL, AppLanguage.fromLocale(Locale.forLanguageTag("pl-PL")))
        assertEquals(AppLanguage.AR, AppLanguage.fromLocale(Locale.forLanguageTag("ar-SA")))
        assertEquals(AppLanguage.HE, AppLanguage.fromLocale(Locale.forLanguageTag("he-IL")))
    }

    @Test fun unsupportedLanguageFallsBackToEnglish() {
        assertEquals(AppLanguage.EN, AppLanguage.fromLocale(Locale.forLanguageTag("tr-TR")))
        assertEquals(AppLanguage.EN, AppLanguage.fromLocale(Locale.forLanguageTag("id-ID")))
    }

    @Test fun simplifiedChineseIsLimitedToSupportedChinaLocale() {
        assertEquals(AppLanguage.ZH, AppLanguage.fromLocale(Locale.forLanguageTag("zh-CN")))
        assertEquals(AppLanguage.EN, AppLanguage.fromLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals(AppLanguage.EN, AppLanguage.fromLocale(Locale.forLanguageTag("zh-HK")))
    }
    @Test fun everyDeclaredLanguageHasASystemLocaleMapping() {
        for (language in AppLanguage.entries) {
            val tag = when (language) {
                AppLanguage.ZH -> "zh-CN"
                AppLanguage.KO -> "ko-KR"
                else -> language.code
            }
            assertEquals(
                "Unexpected system locale mapping for ${language.code}",
                language,
                AppLanguage.fromLocale(Locale.forLanguageTag(tag))
            )
        }
    }

    @Test fun legacyHebrewLanguageTagIsAccepted() {
        assertEquals(AppLanguage.HE, AppLanguage.fromLocale(Locale.forLanguageTag("iw-IL")))
    }

}
