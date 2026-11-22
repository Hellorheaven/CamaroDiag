package com.camarodiag.gen5.data.obd

/**
 * Fail closed when the user asks to export a *complete* physical CAN archive.
 * An incomplete local archive must never silently fall back to a truncated
 * in-memory RX window and be presented as the entire capture.
 */
object CanConsoleExportPolicy {
    fun canExport(
        isCapturing: Boolean,
        archiveFailed: Boolean,
        hasHistory: Boolean,
        hasRawData: Boolean,
        requiresRawArchive: Boolean = false,
        hasRawArchive: Boolean = false
    ): Boolean = !isCapturing && !archiveFailed && (hasHistory || hasRawData) &&
        (!requiresRawArchive || hasRawArchive)
}
