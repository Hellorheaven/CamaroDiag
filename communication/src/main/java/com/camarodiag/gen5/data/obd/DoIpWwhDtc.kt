package com.camarodiag.gen5.data.obd

/** WWH emissions group 0x33; preserve numeric values, not guessed OEM meanings. */
object DoIpWwhDtc {
    enum class Kind(val subfunction: Int) { MASK(0x42), PERMANENT(0x55), READINESS(0x56) }
    data class Record(val dtc: Int, val status: Int, val severity: Int?)
    data class Report(val kind: Kind, val statusAvailability: Int, val severityAvailability: Int?,
        val format: Int, val readinessGroup: Int?, val records: List<Record>)
    private fun Byte.u8() = toInt() and 255
    fun request(kind: Kind, statusMask: Int = 255, severityMask: Int = 255,
        readinessGroup: Int? = null): ByteArray {
        require(statusMask in 0..255 && severityMask in 0..255)
        require(if (kind == Kind.READINESS) readinessGroup != null && readinessGroup in 0..255 else readinessGroup == null)
        return byteArrayOf(0x19, kind.subfunction.toByte(), 0x33) + when (kind) {
            Kind.MASK -> byteArrayOf(severityMask.toByte(), statusMask.toByte())
            Kind.PERMANENT -> byteArrayOf()
            Kind.READINESS -> byteArrayOf(requireNotNull(readinessGroup).toByte())
        }
    }
    fun decode(kind: Kind, reply: ByteArray, readinessGroup: Int? = null): Report {
        val start = if (kind == Kind.PERMANENT) 5 else 6
        val width = if (kind == Kind.MASK) 5 else 4
        require(reply.size >= start && (reply.size - start) % width == 0 &&
            reply[0].u8() == 0x59 && reply[1].u8() == kind.subfunction && reply[2].u8() == 0x33)
        require(if (kind == Kind.READINESS) readinessGroup == reply[5].u8() else readinessGroup == null)
        val records = (start until reply.size step width).map { offset ->
            val index = offset + if (kind == Kind.MASK) 1 else 0
            Record((reply[index].u8() shl 16) or (reply[index + 1].u8() shl 8) or reply[index + 2].u8(),
                reply[index + 3].u8(), if (kind == Kind.MASK) reply[offset].u8() else null)
        }
        return Report(kind, reply[3].u8(), if (kind == Kind.MASK) reply[4].u8() else null,
            reply[if (kind == Kind.MASK) 5 else 4].u8(), readinessGroup, records)
    }
}
