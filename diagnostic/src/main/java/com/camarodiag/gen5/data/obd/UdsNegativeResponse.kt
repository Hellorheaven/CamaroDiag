package com.camarodiag.gen5.data.obd

/**
 * Standardized ISO 14229 negative-response codes.
 *
 * Manufacturer-specific and reserved values deliberately remain unknown rather than
 * being assigned a guessed meaning.
 */
object UdsNegativeResponse {
    private data class Meaning(val name: String, val en: String, val fr: String)

    private val meanings = mapOf(
        0x10 to Meaning("generalReject", "General reject", "Rejet général"),
        0x11 to Meaning("serviceNotSupported", "Service not supported", "Service non pris en charge"),
        0x12 to Meaning("subFunctionNotSupported", "Sub-function not supported", "Sous-fonction non prise en charge"),
        0x13 to Meaning("incorrectMessageLengthOrInvalidFormat", "Incorrect message length or invalid format", "Longueur de message incorrecte ou format invalide"),
        0x14 to Meaning("responseTooLong", "Response too long", "Réponse trop longue"),
        0x21 to Meaning("busyRepeatRequest", "Busy; repeat request", "Occupé ; répéter la demande"),
        0x22 to Meaning("conditionsNotCorrect", "Conditions not correct", "Conditions d'exécution incorrectes"),
        0x24 to Meaning("requestSequenceError", "Request sequence error", "Erreur de séquence de requête"),
        0x25 to Meaning("noResponseFromSubnetComponent", "No response from subnet component", "Aucune réponse d'un composant du sous-réseau"),
        0x26 to Meaning("failurePreventsExecution", "Failure prevents execution", "Une défaillance empêche l'exécution"),
        0x31 to Meaning("requestOutOfRange", "Request out of range", "Requête hors plage"),
        0x33 to Meaning("securityAccessDenied", "Security access denied", "Accès de sécurité refusé"),
        0x35 to Meaning("invalidKey", "Invalid key", "Clé invalide"),
        0x36 to Meaning("exceedNumberOfAttempts", "Exceeded number of attempts", "Nombre de tentatives dépassé"),
        0x37 to Meaning("requiredTimeDelayNotExpired", "Required time delay not expired", "Délai d'attente requis non écoulé"),
        0x70 to Meaning("uploadDownloadNotAccepted", "Upload/download not accepted", "Transfert montant/descendant refusé"),
        0x71 to Meaning("transferDataSuspended", "Transfer data suspended", "Transfert de données suspendu"),
        0x72 to Meaning("generalProgrammingFailure", "General programming failure", "Échec général de programmation"),
        0x73 to Meaning("wrongBlockSequenceCounter", "Wrong block sequence counter", "Compteur de séquence de bloc incorrect"),
        0x78 to Meaning("requestCorrectlyReceivedResponsePending", "Request received; response pending", "Requête reçue ; réponse en attente"),
        0x7E to Meaning("subFunctionNotSupportedInActiveSession", "Sub-function not supported in active session", "Sous-fonction non prise en charge dans la session active"),
        0x7F to Meaning("serviceNotSupportedInActiveSession", "Service not supported in active session", "Service non pris en charge dans la session active"),
        0x81 to Meaning("rpmTooHigh", "Engine speed too high", "Régime moteur trop élevé"),
        0x82 to Meaning("rpmTooLow", "Engine speed too low", "Régime moteur trop faible"),
        0x83 to Meaning("engineIsRunning", "Engine is running", "Le moteur est en marche"),
        0x84 to Meaning("engineIsNotRunning", "Engine is not running", "Le moteur n'est pas en marche"),
        0x85 to Meaning("engineRunTimeTooLow", "Engine run time too low", "Temps de fonctionnement moteur trop court"),
        0x86 to Meaning("temperatureTooHigh", "Temperature too high", "Température trop élevée"),
        0x87 to Meaning("temperatureTooLow", "Temperature too low", "Température trop basse"),
        0x88 to Meaning("vehicleSpeedTooHigh", "Vehicle speed too high", "Vitesse du véhicule trop élevée"),
        0x89 to Meaning("vehicleSpeedTooLow", "Vehicle speed too low", "Vitesse du véhicule trop faible"),
        0x8A to Meaning("throttlePedalTooHigh", "Throttle/pedal position too high", "Position accélérateur/pédale trop élevée"),
        0x8B to Meaning("transmissionRangeNotInNeutral", "Transmission range not in neutral", "Transmission pas au point mort"),
        0x8C to Meaning("transmissionNotInGear", "Transmission not in the required gear", "Transmission dans un rapport non autorisé"),
        0x8D to Meaning("brakePedalNotPressed", "Brake pedal not pressed", "Pédale de frein non enfoncée"),
        0x8F to Meaning("brakeSwitchNotClosed", "Brake switch not closed", "Contacteur de frein non fermé"),
        0x90 to Meaning("shifterLeverNotInPark", "Shifter lever not in park", "Sélecteur de boîte pas en position parking"),
        0x91 to Meaning("torqueConverterClutchLocked", "Torque converter clutch locked", "Embrayage du convertisseur de couple verrouillé"),
        0x92 to Meaning("voltageTooHigh", "Voltage too high", "Tension trop élevée"),
        0x93 to Meaning("voltageTooLow", "Voltage too low", "Tension trop faible")
    )

    fun name(code: Int): String? = meanings[code]?.name

    fun description(code: Int, language: String = "en"): String =
        meanings[code]?.let { if (language.equals("fr", ignoreCase = true)) it.fr else it.en }
            ?: "Unknown or manufacturer-specific NRC 0x%02X".format(java.util.Locale.ROOT, code and 0xFF)
}
