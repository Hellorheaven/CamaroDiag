package com.camarodiag.gen5.ui

/** Counts only positively correlated replies from the approved, session-bound
 * DoIP command sequence. Completion is checked after the routed socket closes.
 * Never turns a partial or cancelled operation into a successful result. */
internal class DoIpCommandProgress(val requested: Int) {
    init { require(requested in 1..16) }

    var verified: Int = 0
        private set

    fun record(index: Int, reply: ByteArray) {
        require(index == verified + 1 && index <= requested && reply.isNotEmpty()) {
            "Invalid DoIP command reply progress."
        }
        verified = index
    }

    fun requireComplete(returnedReplies: Int) {
        check(verified == requested && returnedReplies == requested) {
            "Incomplete DoIP command sequence."
        }
    }
}
