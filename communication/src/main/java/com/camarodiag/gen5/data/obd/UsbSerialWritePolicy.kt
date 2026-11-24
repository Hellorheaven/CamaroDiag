package com.camarodiag.gen5.data.obd

import java.io.IOException

/** A driver accepting only part of an OBD request is NOT a successful write. */
internal object UsbSerialWritePolicy {
    fun requireComplete(written: Int, requested: Int) {
        require(requested > 0) { "USB serial requests must be nonempty." }
        if (written != requested) {
            throw IOException(
                "Incomplete USB serial write: $written bytes accepted of $requested requested"
            )
        }
    }
}
