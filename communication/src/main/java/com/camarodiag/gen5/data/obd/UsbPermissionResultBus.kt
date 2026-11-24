package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot result of an Android USB permission dialog. Nothing is replayed
 * after process restart; the user must choose an adapter again in that case.
 */
object UsbPermissionResultBus {
    data class Outcome(val deviceId: Int, val granted: Boolean)
    private val mutableResults = MutableSharedFlow<Outcome>(extraBufferCapacity = 16)
    val results = mutableResults.asSharedFlow()

    internal fun publish(deviceId: Int, granted: Boolean) {
        mutableResults.tryEmit(Outcome(deviceId, granted))
    }
}
