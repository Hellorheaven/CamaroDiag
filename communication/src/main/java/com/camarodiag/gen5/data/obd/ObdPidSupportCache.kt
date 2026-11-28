package com.camarodiag.gen5.data.obd

/** Cache scoped to the currently selected diagnostic context. */
internal class ObdPidSupportCache {
    data class Inventory(val pids: Set<Int>, val byResponder: Map<String, Set<Int>>) {
        /** When ECU addresses are known, only the ECU that advertised the
         * PID can supply a measurement. A headerless response cannot be
         * attached to a known physical responder. */
        fun accept(pid: Int, rows: List<CamaroUdsProtocol.PidObservation>): List<CamaroUdsProtocol.PidObservation> =
            if (pid !in pids) emptyList()
            else if (byResponder.isEmpty()) rows
            else rows.filter { row -> row.responder?.let { pid in byResponder[it].orEmpty() } == true }
    }

    private data class Entry(val inventory: Inventory, val complete: Boolean, val atMillis: Long)
    private var revision = 0L
    private var entry: Entry? = null

    @Synchronized fun generation(): Long = revision

    @Synchronized fun requireCurrent(expected: Long) {
        if (revision != expected) throw ObdSupersededSessionException(
            java.io.IOException("OBD PID discovery context changed"))
    }

    @Synchronized fun get(expected: Long, nowMillis: Long): Set<Int>? =
        getInventory(expected, nowMillis)?.pids

    @Synchronized fun getInventory(expected: Long, nowMillis: Long): Inventory? {
        requireCurrent(expected)
        return entry?.takeIf { it.complete || nowMillis - it.atMillis in 0L until 10_000L }?.inventory
    }

    @Synchronized fun publish(expected: Long, pids: Set<Int>, complete: Boolean, nowMillis: Long,
        byResponder: Map<String, Set<Int>> = emptyMap()) {
        requireCurrent(expected)
        val frozen = Inventory(pids.toSet(), byResponder.mapValues { it.value.toSet() }.toMap())
        require(frozen.byResponder.values.all { frozen.pids.containsAll(it) })
        entry = Entry(frozen, complete, nowMillis)
    }

    @Synchronized fun invalidate() {
        revision++
        entry = null
    }
}
