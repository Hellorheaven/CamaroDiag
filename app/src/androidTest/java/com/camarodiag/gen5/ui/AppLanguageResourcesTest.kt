package com.camarodiag.gen5.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import com.camarodiag.gen5.R
import com.camarodiag.gen5.domain.model.AppLanguage
import org.junit.Rule
import org.junit.Test

class AppLanguageResourcesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualSwitchUpdatesBackupLabelAndLayoutDirectionTogether() {
        val language = mutableStateOf(AppLanguage.FR)
        compose.setContent {
            AppLanguageContent(language.value) {
                val direction = LocalLayoutDirection.current
                Text(stringResource(R.string.cal_archive_verify),
                    Modifier.testTag(if (direction == LayoutDirection.Rtl) "rtl" else "ltr"))
            }
        }
        compose.onNodeWithText("Vérifier le ZIP de sauvegarde du calculateur").assertIsDisplayed()
        compose.onNodeWithTag("ltr").assertIsDisplayed()
        compose.runOnIdle { language.value = AppLanguage.AR }
        compose.onNodeWithText("التحقق من ملف ZIP لنسخة وحدة التحكم الاحتياطية").assertIsDisplayed()
        compose.onNodeWithTag("rtl").assertIsDisplayed()
        compose.runOnIdle { language.value = AppLanguage.EN }
        compose.onNodeWithText("Verify ECU backup ZIP").assertIsDisplayed()
        compose.onNodeWithTag("ltr").assertIsDisplayed()
    }
}
