package com.camarodiag.gen5.auth

import java.security.MessageDigest

/**
 * Keeps local account store identifiers stable and filesystem-safe without
 * disclosing the Firebase UID in a SharedPreferences filename.
 */
internal object AccountStorageKeys {
    fun garageName(uid: String?): String {
        if (uid.isNullOrBlank()) return "camarodiag_garage_no_account"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "camarodiag_garage_$digest"
    }
}
