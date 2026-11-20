package com.camarodiag.gen5.ui

/** Scope sensor visibility choices to a signed-in account and a specific vehicle. */
internal object SensorSelectionScope {
    fun key(accountUid: String?, vehicleIdentity: String?): String? {
        val account = accountUid?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val vehicle = vehicleIdentity?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return "account_${account}_vehicle_${vehicle}"
    }
}
