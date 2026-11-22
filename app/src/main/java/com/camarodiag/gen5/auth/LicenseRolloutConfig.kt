package com.camarodiag.gen5.auth

/**
 * License entitlement enforcement is enabled.
 *
 * Startup verification: a single authenticated Firebase callable checks
 * licenses/{uid} and grants an introductory license automatically unless an
 * administrator explicitly revoked it. No periodic license polling.
 *
 * IMPORTANT: publish only after Firebase Functions, App Check, Firestore and
 * Android have been configured and tested. Without the backend, Premium
 * features fail closed and only live data + DTCs remain available.
 *
 * This does NOT enable payments: checkout and Stripe remain separately
 * disabled until expressly authorised.
 */
object LicenseRolloutConfig {
    const val ENABLED = true
    const val PAYMENTS_ENABLED = false
}
