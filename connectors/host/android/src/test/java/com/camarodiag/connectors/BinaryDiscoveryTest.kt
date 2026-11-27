package com.camarodiag.connectors

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.function.LongSupplier
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryDiscoveryTest {
    private val replies = arrayOf(
        "PONG CamaroDiagConnectors 0.1.0",
        "CAPS hs_can=disabled sw_can=disabled eeprom_read=disabled eeprom_write=disabled ecu_flash=disabled j2534=disabled",
        "STATUS mode=prototype write=disabled"
    )
    private class Sim : BinaryDiscovery.Link {
        private var pending = ByteArray(0)
        private var cursor = 0
        var commands = 0
        var corrupt = false
        var wrongSequence = false
        var closed = false
        var trailing = false

        override fun write(buffer: ByteArray, timeoutMs: Int) {
            if (buffer.size != 12 || buffer[3].toInt() != commands + 1 || buffer[4].toInt() != commands)
                throw IOException("Invalid request")
            val text = replies[commands].toByteArray(Charsets.US_ASCII)
            pending = ByteArray(text.size + 12)
            buffer.copyInto(pending, 0, 0, 8)
            if (wrongSequence) pending[4] = (pending[4] + 1).toByte()
            pending[6] = text.size.toByte()
            text.copyInto(pending, 8)
            ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(pending.size - 4, UsbFixtures.crc(pending.copyOf(pending.size - 4)).toInt())
            if (corrupt) UsbFixtures.flip(pending, 8)
            if (trailing) pending = pending.copyOf(pending.size + 1)
            cursor = 0
            commands++
        }
        override fun read(buffer: ByteArray, offset: Int, count: Int, timeoutMs: Int): Int {
            val size = minOf(3, count, pending.size - cursor)
            pending.copyInto(buffer, offset, cursor, cursor + size)
            cursor += size
            return size
        }
        override fun checkNoBufferedInput() {
            if (cursor != pending.size) throw IOException("Unexpected buffered data")
        }
        override fun close() { closed = true }
    }

    @JvmStatic fun main(args: Array<String>) {
        val slow = Sim()
        var ticks = 0L
        BinaryDiscovery(slow, LongSupplier { ticks += 500; ticks }).use { d ->
            try { d.verify(); error("Late response accepted") }
            catch (_: SocketTimeoutException) { check(slow.closed && slow.commands == 1) }
        }
        val ok = Sim()
        BinaryDiscovery(ok).use { d ->
            check(d.verify().contentEquals(replies))
            check(ok.commands == 3 && ok.closed)
            UsbFixtures.rejects { d.verify() }
        }
        repeat(3) { scenario ->
            val broken = Sim().apply {
                corrupt = scenario == 0
                wrongSequence = scenario == 1
                trailing = scenario == 2
            }
            BinaryDiscovery(broken).use { d ->
                UsbFixtures.rejects { d.verify() }
                check(broken.closed && broken.commands == 1)
            }
        }
        println("CD/1 discovery Kotlin fixtures passed")
    }
}
