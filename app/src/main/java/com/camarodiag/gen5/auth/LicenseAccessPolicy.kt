package com.camarodiag.gen5.auth

import java.util.Locale

/**
 * CLIENT UX gating only. The server owns billing, grants and revocation.
 * The app only unlocks premium when BOTH a fresh signed Firebase token and
 * an online Firestore license document agree with the same UID + email.
 * Never grant premium based on offline Firestore cache or a client-side flag.
 */
enum class LicenseLevel { FREE, PREMIUM, OWNER }

data class ServerLicenseRecord(
    val uid: String,
    val emailLower: String,
    val status: String,
    val kind: String,
    val expiresAtEpochMillis: Long?,
    val verifiedFromServer: Boolean
)

object LicenseAccessPolicy {
    const val PREMIUM_CLAIM = "license_active"

    fun normalizeEmail(email: String?): String? =
        email?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.contains("@") }

    fun resolve(
        uid: String?,
        email: String?,
        emailVerified: Boolean,
        providerIds: Collection<String>,
        signedClaims: Map<String, Any?>,
        license: ServerLicenseRecord?,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): LicenseLevel {
        if (uid.isNullOrBlank() || !emailVerified || normalizeEmail(email) == null)
            return LicenseLevel.FREE
        if (OwnerFunctionalTestPolicy.isAllowed(providerIds, signedClaims))
            return LicenseLevel.OWNER
        val verifiedEmail = normalizeEmail(email) ?: return LicenseLevel.FREE
        val validUntil = license?.expiresAtEpochMillis
        if (signedClaims[PREMIUM_CLAIM] == true &&
            license != null && license.verifiedFromServer &&
            license.uid == uid && license.emailLower == verifiedEmail &&
            license.status == "active" &&
            license.kind in setOf("perpetual", "subscription") &&
            (validUntil == null || validUntil > nowEpochMillis)
        ) return LicenseLevel.PREMIUM
        return LicenseLevel.FREE
    }
}
