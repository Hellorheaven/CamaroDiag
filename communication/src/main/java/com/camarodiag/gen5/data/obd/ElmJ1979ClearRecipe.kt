package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ObdClearObservation(val stage: String, val requestHex: String,
    val responseHex: String?, val interpretation: String? = null, val error: String? = null)
data class ObdClearRecipeResult(val outcome: String, val attempted: Boolean,
    val acknowledged: Boolean, val observedVin: String?, val reason: String?,
    val observations: List<ObdClearObservation>, val beforeDtcs: Map<String, List<String>?>,
    val afterDtcs: Map<String, List<String>?>, val protocol: Int,
    val requestCanId: String, val responseCanId: String)

/** Dedicated, operator-authorized emissions reset on a primary physical CAN
 * ECM. The report never equates a missing Mode 0A reply with no permanent DTC. */
internal object ElmJ1979ClearRecipe {
    private val modes = listOf(3, 7, 10)
    private fun key(mode: Int) = ElmJ1979EvapTest.hex(mode, 2)

    private fun dtcs(mode: Int, response: ByteArray): List<String>? {
        val bytes = response.map { it.toInt() and 255 }
        if (bytes.size == 3 && bytes[0] == 0x7F && bytes[1] == mode &&
            bytes[2] !in setOf(0, 0x78)) return null
        require(bytes.size >= 2 && bytes[0] == mode + 0x40)
        val count = bytes[1]
        val data = bytes.drop(2)
        require(data.size == count * 2)
        return data.chunked(2).map { pair ->
            val word = (pair[0] shl 8) or pair[1]
            require(word != 0)
            "PCBU"[word ushr 14].toString() + ((word ushr 12) and 3) +
                ElmJ1979EvapTest.hex(word and 0xFFF, 3)
        }
    }

    suspend fun run(protocol: Int, expectedVin: String, requireCurrent: () -> Unit,
        saveBaseline: suspend (ObdClearRecipeResult) -> Unit,
        exchange: suspend (ByteArray) -> ByteArray): ObdClearRecipeResult {
        require(expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
        val (tx, rx) = ElmJ1979EvapTest.addresses(protocol)
        val width = if (protocol in setOf(7, 9)) 8 else 3
        val before = modes.associate { key(it) to null as List<String>? }.toMutableMap()
        val after = before.toMutableMap()
        val observations = mutableListOf<ObdClearObservation>()
        var vin: String? = null
        var attempted = false
        var ack = false
        fun finish(outcome: String, reason: String? = null) = ObdClearRecipeResult(
            outcome, attempted, ack, vin, reason, observations.toList(), before.toMap(), after.toMap(),
            protocol, ElmJ1979EvapTest.hex(tx, width), ElmJ1979EvapTest.hex(rx, width))

        suspend fun active() {
            currentCoroutineContext().ensureActive()
            requireCurrent()
        }
        suspend fun query(stage: String, request: ByteArray,
            decode: (ByteArray) -> String? = { null }): ByteArray {
            active()
            val hex = ElmJ1979EvapTest.hex(request)
            val index = observations.size
            observations += ObdClearObservation(stage, hex, null)
            try {
                val reply = exchange(request)
                observations[index] = observations[index].copy(responseHex = ElmJ1979EvapTest.hex(reply))
                active()
                observations[index] = observations[index].copy(interpretation = decode(reply))
                return reply
            } catch (error: Exception) {
                observations[index] = observations[index].copy(error = error.javaClass.simpleName)
                throw error
            }
        }
        suspend fun snapshot(stage: String, target: MutableMap<String, List<String>?>) {
            for (mode in modes) {
                try {
                    query(stage, byteArrayOf(mode.toByte())) { response ->
                        val codes = dtcs(mode, response)
                        target[key(mode)] = codes
                        codes?.joinToString(",") ?: "NRC_" + ElmJ1979EvapTest.hex(response[2].toInt() and 255, 2)
                    }
                } catch (_: IllegalArgumentException) {
                    // Malformed decoded record: no count is inferred.
                }
            }
            try {
                query(stage, byteArrayOf(1, 1)) { response ->
                    require(response.size == 6 && response[0] == 0x41.toByte() && response[1] == 1.toByte())
                    val a = response[2].toInt() and 255
                    "MIL=" + (a and 0x80 != 0) + " COUNT=" + (a and 0x7F) +
                        " READINESS=" + ElmJ1979EvapTest.hex(response.copyOfRange(3, 6))
                }
            } catch (_: IllegalArgumentException) {
                // A malformed optional readiness record remains unavailable.
            }
        }
        try {
            val identity = query("PRECONDITION", byteArrayOf(9, 2))
            if (identity.size != 20 || !identity.copyOfRange(0, 3).contentEquals(byteArrayOf(0x49, 2, 1)))
                return finish("NOT_TESTED", "INVALID_VIN")
            vin = identity.copyOfRange(3, 20).toString(Charsets.US_ASCII)
            if (vin != expectedVin) return finish("NOT_TESTED", "VIN_MISMATCH")
            snapshot("BEFORE", before)
            if (before["03"] == null) return finish("NOT_TESTED", "BASELINE_UNAVAILABLE")
            if (!query("PRECONDITION", byteArrayOf(1, 0x0D)).contentEquals(byteArrayOf(0x41, 0x0D, 0)) ||
                !query("PRECONDITION", byteArrayOf(1, 0x0C)).contentEquals(byteArrayOf(0x41, 0x0C, 0, 0)))
                return finish("NOT_TESTED", "STATIONARY_EVIDENCE_REQUIRED")
            saveBaseline(finish("BASELINE_SAVED"))
            active()
            if (!query("RECHECK",byteArrayOf(9,2)).contentEquals(identity))
                return finish("NOT_TESTED","IDENTITY_CHANGED_AFTER_BASELINE")
            if (!query("RECHECK",byteArrayOf(1,0x0D)).contentEquals(byteArrayOf(0x41,0x0D,0)) ||
                !query("RECHECK",byteArrayOf(1,0x0C)).contentEquals(byteArrayOf(0x41,0x0C,0,0)))
                return finish("NOT_TESTED","STATIONARY_EVIDENCE_CHANGED")
            active()
            attempted = true
            observations += ObdClearObservation("RESET", "04", null)
            val reply = exchange(byteArrayOf(4))
            observations[observations.lastIndex] = observations.last().copy(responseHex = ElmJ1979EvapTest.hex(reply))
            if (!reply.contentEquals(byteArrayOf(0x44))) {
                if (reply.size == 3 && reply[0] == 0x7F.toByte() && reply[1] == 4.toByte() &&
                    (reply[2].toInt() and 255) !in setOf(0, 0x78))
                    return finish("REFUSED", "NRC_" + ElmJ1979EvapTest.hex(reply[2].toInt() and 255, 2))
                return finish("UNCERTAIN", "INVALID_OR_INCOMPLETE_ACK")
            }
            ack = true
            snapshot("AFTER", after)
            if (after["03"] == null || after["07"] == null)
                return finish("ACKNOWLEDGED_PARTIAL", "READBACK_UNAVAILABLE")
            return finish("ACKNOWLEDGED_READBACK",
                if (after["03"].orEmpty().isNotEmpty() || after["07"].orEmpty().isNotEmpty()) "DTCS_STILL_PRESENT" else null)
        } catch (cancelled: CancellationException) {
            if (!attempted) throw cancelled
            return finish(if (ack) "ACKNOWLEDGED_PARTIAL" else "UNCERTAIN", "CANCELLED_AFTER_POSSIBLE_TX")
        } catch (error: Exception) {
            return finish(if (ack) "ACKNOWLEDGED_PARTIAL" else if (attempted) "UNCERTAIN" else "NOT_TESTED",
                error.javaClass.simpleName)
        }
    }
}
