package com.camarodiag.gen5.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordAccountPolicyTest {
    @Test fun onlyPureEmailPasswordAccountsCanChangePassword() {
        assertTrue(PasswordAccountPolicy.isEmailPasswordAccount(listOf("firebase", "password")))
        for (providers in listOf(
            listOf("google.com"),
            listOf("firebase", "google.com", "password"),
            listOf("firebase"),
            emptyList()
        )) assertFalse(PasswordAccountPolicy.isEmailPasswordAccount(providers))
    }

    @Test fun changeRequiresCurrentPasswordAndTwoMatchingDistinctNewEntries() {
        assertTrue(PasswordAccountPolicy.canChange("existing-pass", "new-secret-123", "new-secret-123"))
        assertFalse(PasswordAccountPolicy.canChange("", "new-secret-123", "new-secret-123"))
        assertFalse(PasswordAccountPolicy.canChange("old", "short", "short"))
        assertFalse(PasswordAccountPolicy.canChange("old", "new-secret-123", "new-secret-124"))
        assertFalse(PasswordAccountPolicy.canChange("same-secret", "same-secret", "same-secret"))
    }
}
