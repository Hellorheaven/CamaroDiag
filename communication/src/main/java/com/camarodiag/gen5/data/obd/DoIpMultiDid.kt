package com.camarodiag.gen5.data.obd

/** The caller supplies actual DID lengths from the ECU definition; no guessed scaling. */
object DoIpMultiDid {
    data class Field(val did: Int, val length: Int)
    data class Value(val did: Int, val data: ByteArray)
    fun parse(text: String): List<Field> {
        val fields = text.split(',').map { entry ->
            val parts = entry.trim().split(':')
            require(parts.size == 2 && parts[0].matches(Regex("[0-9A-Fa-f]{4}")) &&
                parts[1].matches(Regex("[0-9]{1,4}")))
            Field(parts[0].toInt(16), parts[1].toInt())
        }
        return validate(fields)
    }
    fun validate(fields: List<Field>): List<Field> {
        val frozen = fields.toList()
        require(frozen.size in 2..32 && frozen.all { it.did in 0..65535 && it.length in 1..4096 })
        require(frozen.map { it.did }.distinct().size == frozen.size &&
            1 + frozen.sumOf { 2 + it.length } <= 65536)
        return frozen
    }
    fun request(fields: List<Field>): ByteArray =
        byteArrayOf(0x22) + validate(fields).flatMap {
            listOf((it.did shr 8).toByte(), it.did.toByte())
        }.toByteArray()
    fun decode(fields: List<Field>, reply: ByteArray): List<Value> {
        val layout = validate(fields)
        require(reply.size == 1 + layout.sumOf { 2 + it.length } && reply[0] == 0x62.toByte())
        var offset = 1
        return layout.map {
            require(reply[offset] == (it.did shr 8).toByte() && reply[offset + 1] == it.did.toByte())
            offset += 2
            val value = Value(it.did, reply.copyOfRange(offset, offset + it.length))
            offset += it.length
            value
        }
    }
}
