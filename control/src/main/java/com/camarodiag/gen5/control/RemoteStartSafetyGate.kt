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

data class RemoteStartAssessment(
    val allowedToRequestOemProcedure: Boolean,
    val blockersFr: List<String>,
    val blockersEn: List<String>
)

/** This is a safety assessment only. It does not crank the engine or bypass OEM immobilizer logic. */
object RemoteStartSafetyGate {
    fun assess(c: RemoteStartConditions): RemoteStartAssessment {
        val blockersFr = mutableListOf<String>()
        val blockersEn = mutableListOf<String>()

        fun block(fr: String, en: String) {
            blockersFr += fr
            blockersEn += en
        }

        if (c.gear != VehicleGear.PARK) block("Boîte non confirmée en position P.", "Transmission is not confirmed in Park.")
        if (c.hoodClosed != VehicleSignal.TRUE) block("Capot fermé non confirmé.", "Hood closure is not confirmed.")
        if (c.doorsLocked != VehicleSignal.TRUE) block("Verrouillage des portes non confirmé.", "Door locks are not confirmed.")
        if (c.hazardsOff != VehicleSignal.TRUE) block("Feux de détresse éteints non confirmés.", "Hazard lights being off is not confirmed.")
        if (c.theftSystemReady != VehicleSignal.TRUE) block("Antidémarrage / antivol non confirmé prêt.", "The theft-deterrent system is not confirmed ready.")
        if (c.criticalFaultsAbsent != VehicleSignal.TRUE) block("Absence de défaut critique non confirmée.", "Absence of critical faults is not confirmed.")
        if (c.vehicleStationary != VehicleSignal.TRUE) block("Immobilité du véhicule non confirmée.", "Vehicle stationary status is not confirmed.")
        if (c.oemRemoteStartEquipped != VehicleSignal.TRUE) block("Option de démarrage à distance OEM non confirmée.", "OEM remote-start equipment is not confirmed.")

        return RemoteStartAssessment(
            allowedToRequestOemProcedure = blockersFr.isEmpty(),
            blockersFr = blockersFr,
            blockersEn = blockersEn
        )
    }
}
