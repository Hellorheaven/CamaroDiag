package com.camarodiag.gen5.ui

import com.camarodiag.gen5.domain.model.DiagnosticDtc

internal object StandardObdDtcInventory {
    fun replace(previous: List<DiagnosticDtc>, fresh: List<DiagnosticDtc>, module: String,
        unavailableModes: Set<Int>): List<DiagnosticDtc> {
        require(fresh.all { it.standardObd && it.module == module })
        val retained = previous.filter { row ->
            !row.standardObd || row.module != module || when (row.statusEn) {
                "Confirmed" -> 3 in unavailableModes
                "Pending" -> 7 in unavailableModes
                "Permanent" -> 10 in unavailableModes
                else -> true
            }
        }
        return retained + fresh
    }

    fun afterClear(previous: List<DiagnosticDtc>, module: String): List<DiagnosticDtc> =
        previous.filterNot { it.standardObd && it.module == module &&
            it.statusEn in setOf("Confirmed", "Pending") }
}
