package com.camarodiag.gen5.data.obd

/**
 * Fail closed when the user asks to export a *complete* physical CAN archive.
 * An incomplete local archive must never silently fall back to a truncated
 * in-memory RX window and be presented as the entire capture.
 */
object CanConsoleExportPolicy {
    /** A disconnected in-progress capture is incomplete; a finished archive stays intact. */
    fun archiveFailedAfterDisconnect(alreadyFailed: Boolean, wasCapturing: Boolean): Boolean =
        alreadyFailed || wasCapturing

    /**
     * An operator Stop may export the capture made up to that point only when
     * the writer really completed its final flush and close. If cancellation
     * suppressed an IOException from close, the archive is still incomplete.
     */
    fun archiveFailedAfterStop(
        alreadyFailed: Boolean,
        writerCompleted: Boolean
    ): Boolean = alreadyFailed || !writerCompleted

    fun canExport(
        isCapturing: Boolean,
        archiveFailed: Boolean,
        hasHistory: Boolean,
        hasRawData: Boolean,
        requiresRawArchive: Boolean = false,
        hasRawArchive: Boolean = false,
        isSendingRequest: Boolean = false
    ): Boolean = !isCapturing && !isSendingRequest && !archiveFailed &&
        (hasHistory || hasRawData) &&
        (!requiresRawArchive || hasRawArchive)
}
