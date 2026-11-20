package com.camarodiag.gen5.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStorageKeysTest {
    @Test fun sameUidAlwaysUsesSameGarage() {
        assertEquals(
            AccountStorageKeys.garageName("firebase-user-a"),
            AccountStorageKeys.garageName("firebase-user-a")
        )
    }

    @Test fun differentAccountsCannotShareTheirGarageFilename() {
        assertNotEquals(
            AccountStorageKeys.garageName("firebase-user-a"),
            AccountStorageKeys.garageName("firebase-user-b")
        )
    }

    @Test fun namesDoNotExposeRawFirebaseUidOrFilesystemSeparators() {
        val name = AccountStorageKeys.garageName("user@example.com/path")
        assertTrue(name.startsWith("camarodiag_garage_"))
        assertFalse(name.contains("user@example.com"))
        assertFalse(name.contains("/"))
    }

    @Test fun signedOutStoreIsSeparateFromAnyAccount() {
        assertEquals("camarodiag_garage_no_account", AccountStorageKeys.garageName(null))
        assertNotEquals(AccountStorageKeys.garageName(null), AccountStorageKeys.garageName("no_account"))
    }
}
