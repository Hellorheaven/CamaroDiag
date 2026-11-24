package com.camarodiag.gen5.data.obd

/**
 * USB-serial-for-android returns Unit for UsbSerialPort.write; it does not
 * expose the number of accepted bytes or proof that an ECU received them.
 * Reject empty commands before I/O and rely on driver I/O exceptions.
 */
internal object UsbSerialWritePolicy {
    fun requireNonEmpty(payload: ByteArray) {
        require(payload.isNotEmpty()) { "USB serial requests must be nonempty." }
    }
}
