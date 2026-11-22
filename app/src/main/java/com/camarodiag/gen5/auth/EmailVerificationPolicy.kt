package com.camarodiag.gen5.auth

/** Only the refreshed Firebase server token may confirm an email online. */
internal object EmailVerificationPolicy {
    private val emailProviders = setOf("password", "google.com")

    fun isVerified(claims: Map<String, Any?>): Boolean = claims["email_verified"] == true

    fun requiresVerification(providers: Collection<String>, claims: Map<String, Any?>): Boolean {
        val signedProvider = (claims["firebase"] as? Map<*, *>)?.get("sign_in_provider")
        return (providers.any { it in emailProviders } || signedProvider in emailProviders) && !isVerified(claims)
    }

    // A network failure never grants verification or owner privileges. Preserve
    // existing ordinary offline grace only for the same previously verified email.
    fun allowsOfflineEmail(providers: Collection<String>, email: String?, locallyVerified: Boolean,
        previouslyVerifiedEmail: String?): Boolean =
        providers.none { it in emailProviders } ||
            (locallyVerified && !email.isNullOrBlank() && email == previouslyVerifiedEmail)
}
