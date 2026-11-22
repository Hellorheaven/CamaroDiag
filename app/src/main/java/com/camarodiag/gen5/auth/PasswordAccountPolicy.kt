package com.camarodiag.gen5.auth

/** UI-only routing: Google-authenticated identities cannot add or change passwords. */
internal object PasswordAccountPolicy {
    fun isEmailPasswordAccount(providers: Collection<String>): Boolean =
        "password" in providers && "google.com" !in providers

    fun canChange(current: String, replacement: String, confirmation: String): Boolean =
        current.isNotEmpty() && replacement.length >= 8 &&
            replacement != current && replacement == confirmation
}
