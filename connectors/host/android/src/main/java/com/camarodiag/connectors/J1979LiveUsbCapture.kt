package com.camarodiag.connectors

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier

/** Approved CU/3 recording with independent CL/1 replay before a completion claim. */
class J1979LiveUsbCapture @JvmOverloads constructor(
    private val link: J1979UsbCapture.Link,
    clock: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000L }
) {
    private val reader = CuCaptureReader(link, clock, "CU/3")
    private var used = false

    @Synchronized
    @Throws(IOException::class)
    fun run(profile: J1979LiveUsbAuth.Profile?, key: ByteArray?, raw: OutputStream?,
            allowed: BooleanSupplier?): J1979LiveNativeReplay.Result =
        runRequest(profile, J1979LiveUsbAuth.hello(profile), key, raw, allowed)

    @Synchronized
    @Throws(IOException::class)
    fun runRequest(profile: J1979LiveUsbAuth.Profile?, hello: ByteArray?, key: ByteArray?,
                   raw: OutputStream?, allowed: BooleanSupplier?): J1979LiveNativeReplay.Result {
        if (used) throw IOException("Fresh CU/3 session required")
        require(profile != null && key != null && key.size == 32 && raw != null && allowed != null) {
            "Invalid CU/3 session parameters"
        }
        if (hello == null || !CuWire.header(hello, J1979LiveUsbAuth.HELLO_SIZE, 3, 1) ||
            !CuWire.nonzero(hello, 4 + J1979LiveUsbAuth.AUTH_SIZE, J1979LiveUsbAuth.HELLO_SIZE) ||
            !hello.copyOfRange(4, 4 + J1979LiveUsbAuth.AUTH_SIZE).contentEquals(profile.encodeAuth()))
            throw IOException("CU/3 hello differs from approved scope")
        used = true
        var authenticated = false
        var sent = false
        var error: String? = null
        val began = reader.now()
        val copy = ByteArrayOutputStream()
        try {
            reader.current(allowed)
            var start = reader.now()
            link.write(hello, reader.remaining(start, 5000))
            val challenge = reader.exact(68, start, 5000, allowed = allowed)
            val proof = J1979LiveUsbAuth.proof(hello, challenge, key)
            authenticated = true
            try {
                reader.current(allowed)
                link.checkNoBufferedInput()
                reader.current(allowed)
                link.write(proof, reader.remaining(start, 5000))
                sent = true
            } finally {
                proof.fill(0)
            }
            start = reader.now()
            val budget = profile.budgetMs + 15000
            var finished = false
            fun exact(count: Int): ByteArray = reader.exact(count, start, budget, raw, copy, allowed,
                J1979LiveNativeReplay.MAX_CAPTURE.toLong()) { copy.size().toLong() }
            for (seq in 0 until J1979LiveNativeReplay.MAX_STEPS + 2) {
                val head = exact(8)
                val length = J1979LiveNativeReplay.header(head, seq)
                val body = J1979LiveNativeReplay.body(head, exact(length + 4), length)
                val kind = CuCaptureReader.u(head[3])
                if (seq == 0) {
                    if (kind != 1 || !body.contentEquals(profile.encode()))
                        throw IOException("CL/1 scope differs from CU/3")
                } else if (kind == 1) throw IOException("Duplicate CL/1 plan")
                if (kind == 3) {
                    reader.current(allowed)
                    link.checkNoBufferedInput()
                    reader.current(allowed)
                    reader.remaining(start, budget)
                    finished = true
                    break
                }
            }
            if (!finished) throw IOException("CL/1 record count exceeded")
        } catch (failure: IOException) {
            error = failure.message ?: "CU/3 exchange failed"
        } finally {
            try { link.close() } catch (failure: IOException) { if (error == null) error = "USB close failed" }
            try { raw.flush() } catch (failure: IOException) { if (error == null) error = "Capture flush failed" }
        }
        val decoded = J1979LiveNativeReplay.decode(ByteArrayInputStream(copy.toByteArray()), profile)
        val cancelled = !allowed.asBoolean
        val verified = authenticated && sent && !cancelled && error == null && decoded.captureIntegrityVerified
        return J1979LiveNativeReplay.Result(decoded.profile, decoded.observationsReceived, verified,
            authenticated, sent, cancelled, "LIVE_USB_CU3", decoded.summary, decoded.result,
            error ?: if (cancelled) "CU/3 cancelled" else decoded.error,
            maxOf(0L, reader.now() - began))
    }
}
