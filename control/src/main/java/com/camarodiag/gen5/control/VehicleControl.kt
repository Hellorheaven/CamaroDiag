package com.camarodiag.gen5.control

import com.camarodiag.gen5.domain.model.VehicleProfile

/**
 * GM body controls are deliberately capability-gated. No undocumented CAN/UDS
 * frames are sent: an action becomes executable only after a VIN/year/module-specific
 * command definition has been verified and supplied by a validated profile.
 */
enum class VehicleControlId { WINDOWS, SUNROOF, DOOR_LOCKS, LIGHTS, HORN, MIRRORS, TRUNK, REMOTE_START }
enum class ControlAvailability { NOT_VERIFIED, AVAILABLE, NOT_EQUIPPED }

data class VehicleControlCapability(
    val id: VehicleControlId,
    val titleFr: String,
    val titleEn: String,
    val availability: ControlAvailability = ControlAvailability.NOT_VERIFIED,
    val explanationFr: String,
    val explanationEn: String,
    val vehicleContext: String = ""
)

class VehicleControlCatalog {
    fun forVehicle(vehicle: VehicleProfile): List<VehicleControlCapability> {
        val vehicleContext = "Camaro " + vehicle.modelYear + " • " + vehicle.engine.rpo + " • " + vehicle.market.code
        return listOf(
        VehicleControlCapability(VehicleControlId.WINDOWS, "Vitres", "Windows", explanationFr = "Commande GM à identifier et valider pour le BCM de ce VIN. Aucune trame universelle n'est supposée.", explanationEn = "GM command must be identified and validated for this VIN's BCM. No universal frame is assumed."),
        VehicleControlCapability(VehicleControlId.SUNROOF, "Toit ouvrant", "Sunroof", explanationFr = "Disponible uniquement si le véhicule en est équipé et si le BCM expose une commande validée.", explanationEn = "Only if equipped and a validated BCM command is available."),
        VehicleControlCapability(VehicleControlId.DOOR_LOCKS, "Verrouillage", "Door locks", explanationFr = "Commande de carrosserie protégée : adressage, sécurité et conditions véhicule à vérifier.", explanationEn = "Protected body command: addressing, security and vehicle conditions must be verified."),
        VehicleControlCapability(VehicleControlId.LIGHTS, "Éclairage", "Lights", explanationFr = "Les fonctions d'éclairage dépendent du BCM, de la finition et du marché.", explanationEn = "Lighting functions depend on BCM, trim and market."),
        VehicleControlCapability(VehicleControlId.HORN, "Klaxon", "Horn", explanationFr = "Action sonore non activable sans procédure GM vérifiée et confirmation utilisateur.", explanationEn = "Audible action stays disabled without a verified GM procedure and user confirmation."),
        VehicleControlCapability(VehicleControlId.MIRRORS, "Rétroviseurs", "Mirrors", explanationFr = "Commande à valider selon équipement et version BCM.", explanationEn = "Command must be validated for equipment and BCM version."),
        VehicleControlCapability(VehicleControlId.TRUNK, "Coffre", "Trunk", explanationFr = "Commande à valider selon carrosserie, BCM et état du véhicule.", explanationEn = "Command must be validated for body style, BCM and vehicle state."),
        VehicleControlCapability(VehicleControlId.REMOTE_START, "Démarrage à distance", "Remote start", explanationFr = "Non commandable par une trame OBD générique. Utiliser uniquement le système OEM homologué et ses interverrouillages (boîte en P, frein de stationnement, capot fermé, absence de défaut critique). CamaroDiag n'envoie aucune commande de démarrage.", explanationEn = "Not safely commandable with a generic OBD frame. Use only the OEM-approved remote-start system and its interlocks (Park, parking brake, hood closed, no critical faults). CamaroDiag does not send an engine-start command.")
        ).map { it.copy(vehicleContext = vehicleContext) }
    }
}
