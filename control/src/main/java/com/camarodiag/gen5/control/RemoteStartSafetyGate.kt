package com.camarodiag.gen5.control

/** Live status must come from verified GM PIDs/ECU data; unknown is never treated as safe. */
enum class VehicleGear { PARK, NEUTRAL, DRIVE, REVERSE, UNKNOWN }
enum class VehicleSignal { TRUE, FALSE, UNKNOWN }

data class RemoteStartConditions(
    val gear: VehicleGear,
    val hoodClosed: VehicleSignal,
    val doorsLocked: VehicleSignal,
    val hazardsOff: VehicleSignal,
    val theftSystemReady: VehicleSignal,
    val criticalFaultsAbsent: VehicleSignal,
    val vehicleStationary: VehicleSignal,
    val oemRemoteStartEquipped: VehicleSignal
)

data class RemoteStartAssessment(val allowedToRequestOemProcedure: Boolean, val blockersFr: List<String>)

/** This is a safety assessment only. It does not crank the engine or bypass OEM immobilizer logic. */
object RemoteStartSafetyGate {
    fun assess(c: RemoteStartConditions): RemoteStartAssessment {
        val blockers = buildList {
            if (c.gear != VehicleGear.PARK) add("Boîte non confirmée en position P.")
            if (c.hoodClosed != VehicleSignal.TRUE) add("Capot fermé non confirmé.")
            if (c.doorsLocked != VehicleSignal.TRUE) add("Verrouillage des portes non confirmé.")
            if (c.hazardsOff != VehicleSignal.TRUE) add("Feux de détresse éteints non confirmés.")
            if (c.theftSystemReady != VehicleSignal.TRUE) add("Antidémarrage / antivol non confirmé prêt.")
            if (c.criticalFaultsAbsent != VehicleSignal.TRUE) add("Absence de défaut critique non confirmée.")
            if (c.vehicleStationary != VehicleSignal.TRUE) add("Immobilité du véhicule non confirmée.")
            if (c.oemRemoteStartEquipped != VehicleSignal.TRUE) add("Option de démarrage à distance OEM non confirmée.")
        }
        return RemoteStartAssessment(blockers.isEmpty(), blockers)
    }
}
