package com.camarodiag.gen5.auth

/**
 * LICENSING ROLLOUT / KILL SWITCH.
 *
 * Keeping this FALSE means:
 * - no purchase requests or paid-license Firestore lookups;
 * - no premium paywall, including on offline grace sessions;
 * - every authenticated user retains existing standard diagnostic features;
 * - private owner-only Debug / functional tests still require the signed claim.
 *
 * Explicitly enable only after backend deployment, payment compliance review,
 * billing configuration, transaction/revocation tests, and a release decision.
 * This flag is intentionally not remotely configurable or stored in preferences.
 */
object LicenseRolloutConfig {
    const val ENABLED = false
}
