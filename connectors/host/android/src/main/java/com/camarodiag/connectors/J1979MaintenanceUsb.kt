package com.camarodiag.connectors

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier
import java.util.zip.CRC32

/** Single-use CU/4 maintenance and distinct, read-only CU/5 recovery archive.
 * This transport does not expose arbitrary ECU commands or automatic retries.
 */
class J1979MaintenanceUsb @JvmOverloads constructor(
    private val link: J1979UsbCapture.Link,
    clock: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000L }
) {
    private val reader = CuCaptureReader(link, clock, "CU/4-5")
    private var used = false

    class Profile(
        @JvmField val operation: Int, @JvmField val tx: Int, @JvmField val rx: Int,
        @JvmField val extended: Boolean, @JvmField val vin: String, @JvmField val budgetMs: Int
    ) {
        init {
            require(operation in 1..2 &&
                (if (extended) tx == 0x18da10f1 && rx == 0x18daf110 else tx == 0x7e0 && rx == 0x7e8) &&
                budgetMs in 1000..60000 && CuWire.validVin(vin)) {
                "Invalid primary ECM maintenance scope"
            }
        }

        fun encode(): ByteArray = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            .put(operation.toByte()).put(if (extended) 1.toByte() else 0.toByte())
            .putInt(tx).putInt(rx).putInt(budgetMs).put(vin.toByteArray(Charsets.US_ASCII)).array()

        fun view(): Map<String, Any> = linkedMapOf(
            "operation" to if (operation == 1) "CLEAR" else "EVAP",
            "extended" to extended,
            "request_can_id" to tx.toString(16).uppercase(Locale.ROOT),
            "response_can_id" to rx.toString(16).uppercase(Locale.ROOT),
            "budget_ms" to budgetMs.toLong(),
            "expected_vin" to vin
        )
    }

    companion object {
        const val HELLO_SIZE = 67
        const val CHALLENGE_SIZE = 68
        const val PROOF_SIZE = 36
        const val RESULT_SIZE = 12

        private fun checkWire(ok: Boolean, message: String) {
            if (!ok) throw IOException(message)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun hello(profile: Profile, nonce: ByteArray): ByteArray = hello(profile, nonce, false)

        private fun hello(profile: Profile, nonce: ByteArray, archive: Boolean): ByteArray {
            checkWire(nonce.size == 32 && CuWire.nonzero(nonce, 0, 32), "Invalid CU/4-5 nonce")
            return ByteBuffer.allocate(HELLO_SIZE).put(CuWire.prefix(if (archive) 5 else 4, 1))
                .put(profile.encode()).put(nonce).array()
        }

        private fun validateScope(request: ByteArray) {
            val encoded = request.copyOfRange(4, 35)
            val input = CuWire.little(encoded)
            val operation = CuWire.unsigned(input.get())
            val extended = CuWire.unsigned(input.get())
            checkWire(extended <= 1, "Invalid CU/4-5 address mode")
            val tx = input.int
            val rx = input.int
            val budget = input.int
            val vin = ByteArray(17).also { input.get(it) }.toString(Charsets.US_ASCII)
            val canonical = try {
                Profile(operation, tx, rx, extended == 1, vin, budget).encode()
            } catch (failure: IllegalArgumentException) {
                throw IOException("Invalid CU/4-5 scope", failure)
            }
            checkWire(canonical.contentEquals(encoded), "Noncanonical CU/4-5 scope")
        }

        @JvmStatic
        @Throws(IOException::class)
        fun proof(request: ByteArray, challenge: ByteArray, key: ByteArray): ByteArray =
            proof(request, challenge, key, false)

        private fun proof(request: ByteArray, challenge: ByteArray, key: ByteArray, archive: Boolean): ByteArray {
            val version = if (archive) 5 else 4
            checkWire(CuWire.header(request, HELLO_SIZE, version, 1) &&
                CuWire.header(challenge, CHALLENGE_SIZE, version, 2) && key.size == 32 &&
                CuWire.nonzero(request, 35, 67) && CuWire.nonzero(challenge, 4, 36),
                "Invalid CU/4-5 transcript")
            validateScope(request)
            return CuWire.proof(version, request, challenge, key,
                if (archive) 'R' else 'M', if (archive) 'r' else 'm',
                "CU/4-5 device proof rejected", "CU/4-5 cryptography unavailable")
        }
    }

    private fun remaining(start: Long, budget: Int, allowed: BooleanSupplier): Int {
        reader.current(allowed)
        return reader.remaining(start, budget)
    }

    private fun readJournal(
        length: Int, start: Long, budget: Int, allowed: BooleanSupplier,
        raw: OutputStream, copy: ByteArrayOutputStream
    ) {
        checkWire(length in 1..J1979MaintenanceJournal.MAX_JOURNAL, "CM/1 size limit")
        var outstanding = length
        while (outstanding > 0) {
            val chunk = minOf(outstanding, 4096)
            reader.exact(chunk, start, budget, raw, copy, allowed,
                J1979MaintenanceJournal.MAX_JOURNAL.toLong()) { copy.size().toLong() }
            outstanding -= chunk
        }
    }

    private fun finish(raw: OutputStream, previous: String?): String? {
        var error = previous
        try {
            link.close()
        } catch (_: IOException) {
            if (error == null) error = "USB close failed"
        }
        try {
            raw.flush()
        } catch (_: IOException) {
            if (error == null) error = "Journal flush failed"
        }
        return error
    }

    @Synchronized
    @Throws(IOException::class)
    fun run(profile: Profile, key: ByteArray, raw: OutputStream, allowed: BooleanSupplier): MutableMap<String, Any?> =
        run(profile, key, raw, allowed, CuWire.nonce())

    @Synchronized
    @Throws(IOException::class)
    fun run(profile: Profile, key: ByteArray, raw: OutputStream, allowed: BooleanSupplier,
            nonce: ByteArray): MutableMap<String, Any?> {
        checkWire(!used && key.size == 32, "Fresh CU/4 session required")
        val request = hello(profile, nonce)
        used = true
        val began = reader.now()
        var authenticated = false
        var attempted = false
        var sent = false
        var envelopeRecovery: Boolean? = null
        var error: String? = null
        var inspection: Map<String, Any?>? = null
        val copy = ByteArrayOutputStream()
        try {
            var start = reader.now()
            link.write(request, remaining(start, 5000, allowed))
            val challenge = reader.exact(CHALLENGE_SIZE, start, 5000, allowed = allowed)
            val answer = proof(request, challenge, key)
            authenticated = true
            try {
                link.checkNoBufferedInput()
                val timeout = remaining(start, 5000, allowed)
                // Mark attempted before the potentially mutating proof reaches the transport.
                attempted = true
                link.write(answer, timeout)
                sent = true
            } finally {
                answer.fill(0)
            }
            start = reader.now()
            val budget = profile.budgetMs + 40000
            val envelope = reader.exact(RESULT_SIZE, start, budget, allowed = allowed)
            checkWire(CuWire.header(envelope, RESULT_SIZE, 4, 4) &&
                CuWire.unsigned(envelope[4]) in 2..3 &&
                (5..7).all { envelope[it] == 0.toByte() }, "Invalid CU/4 result envelope")
            val length = CuCaptureReader.le32(envelope, 8)
            checkWire(length in 1L..J1979MaintenanceJournal.MAX_JOURNAL.toLong(), "Invalid CU/4 journal length")
            envelopeRecovery = CuWire.unsigned(envelope[4]) and 1 != 0
            readJournal(length.toInt(), start, budget, allowed, raw, copy)
            remaining(start, budget, allowed)
            link.checkNoBufferedInput()
            remaining(start, budget, allowed)
            val result = J1979MaintenanceJournal.inspect(copy.toByteArray(), profile.vin)
            checkWire(profile.view() == result["scope"], "CM/1 differs from authenticated CU/4 scope")
            checkWire(envelopeRecovery == result["recovery_required"], "CU/4 recovery envelope differs from journal")
            inspection = result
        } catch (failure: IOException) {
            error = failure.message ?: "CU/4 exchange failed"
        } finally {
            error = finish(raw, error)
        }
        val cancelled = !allowed.asBoolean
        if (cancelled && error == null) error = "CU/4 context revoked"
        if (error != null || cancelled) inspection = null
        val terminal = inspection?.get("terminal") as? Map<*, *>
        val unknown = attempted && (terminal == null || terminal["outcome"] == "UNCERTAIN")
        return linkedMapOf(
            "schema" to "camarodiag-functional-j1979-native-maintenance-v1",
            "protocol" to "SAE-J1979", "source" to "LIVE_USB_CU4", "scope" to profile.view(),
            "device_credential_authenticated" to authenticated, "proof_transmission_attempted" to attempted,
            "client_proof_sent" to sent, "cancelled" to cancelled,
            "journal_transfer_verified" to (inspection != null), "journal_bytes_received" to copy.size(),
            "terminal_present" to (terminal != null),
            "recovery_required" to (attempted && (inspection == null || inspection["recovery_required"] == true)),
            "command_outcome_unknown" to unknown, "declared_recovery_in_envelope" to envelopeRecovery,
            "inspection" to inspection, "physical_vehicle_verified" to false,
            "evidence_authenticated" to false, "operator_consent_independently_verified" to false,
            "permits_automatic_retry" to false, "permits_programming" to false,
            "host_elapsed_ms" to maxOf(0L, reader.now() - began), "error" to error
        )
    }

    @Synchronized
    @Throws(IOException::class)
    fun runArchive(profile: Profile, key: ByteArray, raw: OutputStream,
                   allowed: BooleanSupplier): MutableMap<String, Any?> =
        runArchive(profile, key, raw, allowed, CuWire.nonce())

    @Synchronized
    @Throws(IOException::class)
    fun runArchive(profile: Profile, key: ByteArray, raw: OutputStream, allowed: BooleanSupplier,
                   nonce: ByteArray): MutableMap<String, Any?> {
        checkWire(!used && key.size == 32, "Fresh CU/5 session required")
        val request = hello(profile, nonce, true)
        used = true
        val began = reader.now()
        var authenticated = false
        var declared: Map<String, Any>? = null
        var inspection: Map<String, Any?>? = null
        var error: String? = null
        val copy = ByteArrayOutputStream()
        try {
            var start = reader.now()
            link.write(request, remaining(start, 5000, allowed))
            val challenge = reader.exact(CHALLENGE_SIZE, start, 5000, allowed = allowed)
            val answer = proof(request, challenge, key, true)
            authenticated = true
            try {
                link.checkNoBufferedInput()
                link.write(answer, remaining(start, 5000, allowed))
            } finally {
                answer.fill(0)
            }
            start = reader.now()
            val budget = 40000
            val head = reader.exact(RESULT_SIZE, start, budget, allowed = allowed)
            val flags = CuWire.unsigned(head[4])
            val records = CuWire.unsigned(head[5])
            checkWire(CuWire.header(head, RESULT_SIZE, 5, 4) && flags and 7.inv() == 0 &&
                records in 1..4 && head[6] == 0.toByte() && head[7] == 0.toByte(),
                "Invalid CU/5 archive header")
            val length = CuCaptureReader.le32(head, 8)
            val terminal = flags and 1 != 0
            val damaged = flags and 2 != 0
            val recovery = flags and 4 != 0
            checkWire(length in 47L..J1979MaintenanceJournal.MAX_JOURNAL.toLong() &&
                (!terminal || records >= 2) && ((terminal && !damaged) || recovery),
                "Invalid CU/5 archive declaration")
            declared = linkedMapOf("bytes" to length.toInt(), "records" to records,
                "terminal_present" to terminal, "damaged_tail" to damaged, "recovery_required" to recovery)
            readJournal(length.toInt(), start, budget, allowed, raw, copy)
            val trailer = reader.exact(12, start, budget, allowed = allowed)
            val original = copy.toByteArray()
            val crc = CRC32().also { it.update(original) }
            checkWire(CuWire.header(trailer, 12, 5, 5) &&
                CuCaptureReader.le32(trailer, 4) == length && CuCaptureReader.le32(trailer, 8) == crc.value,
                "CU/5 closing receipt missing or inconsistent")
            link.checkNoBufferedInput()
            remaining(start, budget, allowed)
            val result = J1979MaintenanceJournal.inspect(original, profile.vin)
            checkWire(profile.view() == result["scope"] && terminal == result["terminal_present"] &&
                (result["records"] as? List<*>)?.size == records &&
                (result["recovery_required"] != true || recovery), "CM/1 archive differs from declaration")
            inspection = result
        } catch (failure: IOException) {
            error = failure.message ?: "CU/5 exchange failed"
        } finally {
            error = finish(raw, error)
        }
        if (!allowed.asBoolean && error == null) error = "CU/5 context revoked"
        if (error != null) inspection = null
        return linkedMapOf(
            "schema" to "camarodiag-j1979-native-maintenance-archive-v1",
            "protocol" to "SAE-J1979", "source" to "READ_ONLY_USB_CU5", "scope" to profile.view(),
            "device_credential_authenticated" to authenticated,
            "archive_transfer_verified" to (inspection != null), "archive_close_receipt_verified" to (inspection != null),
            "journal_bytes_received" to copy.size(), "declared_archive" to declared, "inspection" to inspection,
            "recovery_required" to (inspection == null || declared?.get("recovery_required") == true),
            "maintenance_command_sent" to false, "physical_vehicle_verified" to false,
            "physical_stop_independently_verified" to false, "evidence_authenticated" to false,
            "permits_automatic_retry" to false, "permits_programming" to false,
            "host_elapsed_ms" to maxOf(0L, reader.now() - began), "error" to error
        )
    }
}
