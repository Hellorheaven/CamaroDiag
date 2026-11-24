package com.camarodiag.gen5.data.obd

/**
 * The explicitly operated CAN console is a RAW CAN transport, not a
 * read-only OBD service selector. Reject only invalid Classical CAN wire
 * framing; never silently refuse a valid manufacturer service, CAN ID,
 * actuator command or other payload on the basis of an invented allowlist.
 *
 * The application still needs a physically connected adapter with actual
 * CAN transmit support, and all vehicle-protocol acknowledgements come
 * from the connected controller, not from this policy.
 */
object SafeRawCanTransmitPolicy {
    fun allows(frame: RawCanFrame): Boolean =
        frame.data.size in 1..8 &&
        (if (frame.isExtended) frame.canId in 0L..0x1FFFFFFFL
         else frame.canId in 0L..0x7FFL)
}
