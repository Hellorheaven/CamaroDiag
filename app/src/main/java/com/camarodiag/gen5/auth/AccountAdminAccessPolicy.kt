package com.camarodiag.gen5.auth

/** Controls visibility of administration. The callable still authorizes every request. */
internal object AccountAdminAccessPolicy {
    fun isAllowed(claims: Map<String, Any?>): Boolean {
        val signedProvider = (claims["firebase"] as? Map<*, *>)?.get("sign_in_provider")
        return EmailVerificationPolicy.isVerified(claims) &&
            signedProvider == "google.com" &&
            (claims["admin_accounts"] == true || claims[OwnerFunctionalTestPolicy.CLAIM] == true)
    }
}
