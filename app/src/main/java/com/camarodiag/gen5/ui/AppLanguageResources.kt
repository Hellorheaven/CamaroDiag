package com.camarodiag.gen5.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.camarodiag.gen5.domain.model.AppLanguage
import java.util.Locale

internal fun Context.forAppLanguage(language: AppLanguage, base: Configuration = resources.configuration): Context {
    val current = base.locales[0]
    val locale = when (language) {
        AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.KO -> Locale.KOREA
        else -> if (current.language == language.code) current else Locale.forLanguageTag(language.code)
    }
    if (current == locale && base == resources.configuration) return this
    return ContextThemeWrapper(this, theme).apply {
        applyOverrideConfiguration(Configuration(base).apply { setLocale(locale) })
    }
}

@Composable
internal fun AppLanguageContent(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val context = remember(base, configuration, language) { base.forAppLanguage(language, configuration) }
    val localized = context.resources.configuration
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides localized,
        LocalLayoutDirection provides if (localized.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content
    )
}
