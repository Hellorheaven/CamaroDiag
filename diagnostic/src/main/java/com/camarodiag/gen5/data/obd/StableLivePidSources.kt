package com.camarodiag.gen5.data.obd

/** One observed source per PID per connection. Never silently splice another
 * ECU into a live signal history; conflicting duplicates are not measurements. */
class StableLivePidSources {
    private val pinned = mutableMapOf<Int, String?>()

    fun select(pid: Int, observations: List<CamaroUdsProtocol.PidObservation>): CamaroUdsProtocol.PidObservation? {
        val grouped = observations.groupBy { it.responder }
        val valid = grouped.mapNotNull { (address, rows) ->
            rows.distinctBy { it.bytes }.singleOrNull()?.let { address to it }
        }.toMap()
        if (pinned.containsKey(pid)) return valid[pinned[pid]]
        val physical = grouped.keys.filterNotNull().sorted()
        val selected = if (physical.isNotEmpty()) {
            val preferred = physical.firstOrNull { it == "7E8" || it == "18DAF110" } ?: physical.first()
            valid[preferred]
        } else valid[null]
        if (selected != null) pinned[pid] = selected.responder
        return selected
    }
}
