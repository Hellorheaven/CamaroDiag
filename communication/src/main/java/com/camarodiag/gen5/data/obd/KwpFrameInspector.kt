package com.camarodiag.gen5.data.obd

/**
 * Offline inspection of an already delimited ISO 14230-2 physical-address frame.
 * No ECU traffic, initialization, timing, diagnostic authorization or programming.
 * Matches the passive Connectors inspector; ISO 9141 and functional addressing
 * are not covered by this format.
 */
object KwpFrameInspector {
    enum class Error { SIZE, FORMAT, LENGTH, ADDRESS, CHECKSUM }
    data class Frame(val target: Int, val source: Int, val payload: ByteArray)
    sealed interface Inspection {
        data class Valid(val frame: Frame) : Inspection
        data class Invalid(val error: Error) : Inspection
    }

    fun inspect(bytes: ByteArray, expectedTarget: Int, expectedSource: Int): Inspection {
        fun reject(error: Error) = Inspection.Invalid(error)
        if (bytes.size !in 5..260) return reject(Error.SIZE)
        val data = bytes.copyOf()
        fun u(index: Int) = data[index].toInt() and 0xff
        if (u(0) and 0xc0 != 0x80) return reject(Error.FORMAT)
        var header = 3
        var length = u(0) and 0x3f
        if (length == 0) {
            if (data.size < 6) return reject(Error.SIZE)
            header = 4
            length = u(3)
        }
        if (length == 0 || data.size != header + length + 1) return reject(Error.LENGTH)
        if (expectedTarget !in 0..255 || expectedSource !in 0..255 ||
            expectedTarget == expectedSource || u(1) != expectedTarget || u(2) != expectedSource
        ) return reject(Error.ADDRESS)
        val checksum = (0 until data.lastIndex).fold(0) { sum, index -> (sum + u(index)) and 0xff }
        if (checksum != u(data.lastIndex)) return reject(Error.CHECKSUM)
        return Inspection.Valid(Frame(u(1), u(2), data.copyOfRange(header, header + length)))
    }
}
