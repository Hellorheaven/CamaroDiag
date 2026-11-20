package com.camarodiag.gen5.auth

/**
 * The grace period applies only to a Firebase session previously validated
 * online on this device. Revocation cannot be checked without connectivity.
 */
internal object OfflineSessionPolicy {
    const val MAX_OFFLINE_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

    fun allowsOfflineAccess(lastValidatedMillis: Long, nowMillis: Long): Boolean =
        lastValidatedMillis > 0L &&
            nowMillis >= lastValidatedMillis &&
            nowMillis - lastValidatedMillis <= MAX_OFFLINE_MILLIS
}
