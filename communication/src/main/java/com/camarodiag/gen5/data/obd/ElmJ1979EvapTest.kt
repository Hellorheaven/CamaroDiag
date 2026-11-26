package com.camarodiag.gen5.data.obd

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ObdEvapExchange(val requestHex: String, val responseHex: String?)
data class ObdEvapResult(val outcome: String, val attempted: Boolean, val observedVin: String?,
    val reason: String?, val exchanges: List<ObdEvapExchange>,
    val protocol: Int, val requestCanId: String, val responseCanId: String)

/** Separate primary-ECM SAE J1979 CAN recipe, not a functional broadcast
 * or an arbitrary actuator command. ACK is not monitor completion. */
internal object ElmJ1979EvapTest {
    fun addresses(protocol: Int): Pair<Int, Int> = when (protocol) {
        6, 8 -> 0x7E0 to 0x7E8
        7, 9 -> 0x18DA10F1 to 0x18DAF110
        else -> error("A negotiated classical CAN OBD profile is required.")
    }

    fun hex(value: Int, width: Int) = value.toString(16).uppercase(Locale.ROOT).padStart(width, '0')
    fun hex(bytes: ByteArray) = bytes.joinToString("") { hex(it.toInt() and 255, 2) }

    suspend fun run(protocol: Int, expectedVin: String, requireCurrent: () -> Unit,
        exchange: suspend (ByteArray) -> ByteArray): ObdEvapResult {
        require(expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
        val (tx, rx) = addresses(protocol)
        val width = if (protocol in setOf(7, 9)) 8 else 3
        val exchanges = mutableListOf<ObdEvapExchange>()
        var vin: String? = null
        var attempted = false
        fun result(outcome: String, reason: String? = null) = ObdEvapResult(
            outcome, attempted, vin, reason, exchanges.toList(), protocol, hex(tx, width), hex(rx, width))
        suspend fun active() {
            currentCoroutineContext().ensureActive()
            requireCurrent()
        }
        suspend fun query(vararg bytes: Int): ByteArray {
            active()
            val payload = bytes.map(Int::toByte).toByteArray()
            exchanges += ObdEvapExchange(hex(payload), null)
            val reply = exchange(payload)
            exchanges[exchanges.lastIndex] = ObdEvapExchange(hex(payload), hex(reply))
            active()
            return reply
        }
        try {
            val identity = query(9, 2)
            if (identity.size != 20 || !identity.take(3).toByteArray().contentEquals(byteArrayOf(0x49, 2, 1)))
                return result("NOT_TESTED", "INVALID_VIN")
            vin = identity.copyOfRange(3, 20).toString(Charsets.US_ASCII)
            if (vin != expectedVin) return result("NOT_TESTED", "VIN_MISMATCH")
            val bitmap = query(8, 0)
            if (bitmap.size != 6 || bitmap[0] != 0x48.toByte() || bitmap[1] != 0.toByte() ||
                bitmap[2].toInt() and 0x80 == 0) return result("NOT_TESTED", "TID_01_NOT_ADVERTISED")
            if (!query(1, 0x0D).contentEquals(byteArrayOf(0x41, 0x0D, 0)) ||
                !query(1, 0x0C).contentEquals(byteArrayOf(0x41, 0x0C, 0, 0)))
                return result("NOT_TESTED", "STATIONARY_EVIDENCE_REQUIRED")
            active()
            attempted = true
            exchanges += ObdEvapExchange("0801", null)
            val reply = exchange(byteArrayOf(8, 1))
            exchanges[exchanges.lastIndex] = ObdEvapExchange("0801", hex(reply))
            return when {
                reply.contentEquals(byteArrayOf(0x48, 1)) -> result("ACKNOWLEDGED")
                reply.size == 3 && reply[0] == 0x7F.toByte() && reply[1] == 8.toByte() &&
                    (reply[2].toInt() and 255) !in setOf(0, 0x78) ->
                    result("REFUSED", "NRC_" + hex(reply[2].toInt() and 255, 2))
                else -> result("UNCERTAIN", "INVALID_OR_INCOMPLETE_RESPONSE")
            }
        } catch (cancelled: CancellationException) {
            if (!attempted) throw cancelled
            return result("UNCERTAIN", "CANCELLED_AFTER_POSSIBLE_TX")
        } catch (error: Exception) {
            return result(if (attempted) "UNCERTAIN" else "NOT_TESTED", error.javaClass.simpleName)
        }
    }
}
