package com.camarodiag.gen5.auth

/**
 * The owner address is never embedded in the public APK.
 * Only a server-issued signed Firebase claim on a Google-authenticated session is accepted.
 * Publishing SHA-256(email) would allow offline guessing and is NOT an access control.
 */
object OwnerFunctionalTestPolicy {
    const val CLAIM = "owner_functional_tests"

    fun isAllowed(providerIds: Collection<String>, signedClaims: Map<String, Any?>): Boolean {
        val firebase = signedClaims["firebase"] as? Map<*, *>
        return "google.com" in providerIds &&
            firebase?.get("sign_in_provider") == "google.com" &&
            signedClaims[CLAIM] == true
    }
}
