package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.*

object CamaroUdsProtocol {

    fun evaluateAdapterProbe(
        deviceName: String,
        macAddress: String,
        atzResp: String,
        stiResp: String,
        atalResp: String,
        stp61Resp: String
    ): AdapterCapabilities {
        val logs = listOf(
            "> ATZ  -> $atzResp",
            "> STI  -> $stiResp",
            "> ATAL -> $atalResp",
            "> STP 61 -> $stp61Resp"
        )

        val isStn = stiResp.contains("STN", ignoreCase = true) ||
            atzResp.contains("OBDLink", ignoreCase = true) ||
            deviceName.contains("OBDLink", ignoreCase = true) ||
            deviceName.contains("vLinker", ignoreCase = true)

        val supportsSwCan = isStn && (stp61Resp.contains("OK", ignoreCase = true) || stiResp.contains("STN2255") || stiResp.contains("STN1170"))
        val supportsLong = atalResp.contains("OK", ignoreCase = true)

        val tier = when {
            isStn && supportsSwCan -> AdapterTier.PRO_STN
            supportsLong && !atzResp.contains("v2.1", ignoreCase = true) -> AdapterTier.GENUINE_ELM
            else -> AdapterTier.CLONE_ELM
        }

        return AdapterCapabilities(
            deviceName = deviceName,
            macAddress = macAddress,
            tier = tier,
            firmwareId = if (stiResp.isNotBlank() && !stiResp.contains("?")) stiResp else atzResp,
            supportsLongFrames = supportsLong,
            supportsSwCan = supportsSwCan,
            supportsCanHeaders = true,
            rawProbeLog = logs
        )
    }

    fun buildTopology(vehicle: VehicleProfile, adapter: AdapterCapabilities): List<EcuModule> {
        // Standard powertrain OBD access depends on a selected CAN-capable adapter.
        // BCM/TCM/IPC/EBCM/SDM remain undiscovered until a VIN-specific profile exists.
        val standardObdAvailable =
            adapter.tier != AdapterTier.DISCONNECTED && adapter.supportsCanHeaders
        val ecmStatus = if (standardObdAvailable) {
            ModuleStatus.ACCESSIBLE
        } else {
            ModuleStatus.UNSUPPORTED_BY_ADAPTER
        }
        val ecmTxHeader = if (standardObdAvailable) "7DF" else ""
        val ecmRxHeader = if (standardObdAvailable) "7E8" else ""

        if (vehicle.generation == CamaroGeneration.GEN6) {
            return listOf(
                EcuModule("ECM", "ECM", "Engine Control Module (${vehicle.engine.ecmType})", "Calculateur moteur (${vehicle.engine.ecmType})", ecmTxHeader, ecmRxHeader, CanBusType.HS_CAN, ecmStatus, notesEn = "Standard OBD-II functional request/response example (7DF/7E8 on 11-bit CAN). This does not define a GM enhanced physical diagnostic address or authorize enhanced services.", notesFr = "Exemple de requête/réponse OBD-II fonctionnelle (7DF/7E8 sur CAN 11 bits). Ceci ne définit pas une adresse physique GM étendue et n'autorise pas les services constructeur."),
                EcuModule("TCM", "TCM", "Transmission Control Module", "Calculateur de transmission", "", "", CanBusType.UNKNOWN, if (vehicle.transmission == TransmissionType.MANUAL) ModuleStatus.NOT_EQUIPPED else ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Module address and supported services must be discovered on the vehicle; not assumed from Gen 5 topology.", notesFr = "Adresse et services à découvrir sur le véhicule ; aucune adresse Gen 5 n'est réutilisée par hypothèse."),
                EcuModule("BCM", "BCM", "Body Control Module", "Calculateur habitacle", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = if (vehicle.modelYear <= 2018) "GM bulletin 07-08-49-020M lists the BCM as the Camaro odometer storage module through 2018. Diagnostic address and read DID still require VIN-specific verification." else "For 2019–2024, confirm the odometer storage architecture and diagnostic identifiers for this VIN before reading.", notesFr = if (vehicle.modelYear <= 2018) "Le bulletin GM 07-08-49-020M désigne le BCM comme module de stockage du kilométrage Camaro jusqu'en 2018. L'adresse de diagnostic et le DID de lecture restent à vérifier selon le VIN." else "Pour 2019–2024, confirmer l'architecture de stockage et les identifiants de diagnostic de ce VIN avant toute lecture."),
                EcuModule("IPC", "IPC", "Instrument Panel Cluster", "Combiné d'instruments", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Secondary odometer storage is described in 2012 Camaro service information; verify the exact module data and VIN relationship before comparing.", notesFr = "Le stockage secondaire du kilométrage est décrit dans la documentation d'atelier Camaro 2012 ; vérifier les données du module et la relation VIN avant comparaison."),
                EcuModule("EBCM", "ABS", "Electronic Brake Control Module", "Calculateur ABS / StabiliTrak", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Enhanced module scan not yet confirmed for this VIN.", notesFr = "Le balayage constructeur de ce module n'est pas encore confirmé pour ce VIN."),
                EcuModule("SDM", "SDM", "Sensing and Diagnostic Module", "Calculateur airbags", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Safety module access is read-only and vehicle-specific.", notesFr = "L'accès au calculateur de sécurité est spécifique au véhicule et en lecture seule.")
            )
        }
        val tcmStatus = if (vehicle.transmission == TransmissionType.AUTOMATIC) ModuleStatus.DISCOVERY_REQUIRED else ModuleStatus.NOT_EQUIPPED
        return listOf(
            EcuModule("ECM", "ECM", "Engine Control Module (${vehicle.engine.ecmType})", "Calculateur moteur (${vehicle.engine.ecmType})", ecmTxHeader, ecmRxHeader, CanBusType.HS_CAN, ecmStatus, notesEn = "Standard OBD-II functional request/response example (7DF/7E8 on 11-bit CAN). This does not define a GM enhanced physical diagnostic address or authorize enhanced services.", notesFr = "Exemple de requête/réponse OBD-II fonctionnelle (7DF/7E8 sur CAN 11 bits). Ceci ne définit pas une adresse physique GM étendue et n'autorise pas les services constructeur."),
            EcuModule("TCM", "TCM", "Transmission Control Module", "Calculateur de boîte", "", "", CanBusType.UNKNOWN, tcmStatus, notesEn = "Presence, address and supported data must be discovered on the vehicle.", notesFr = "Présence, adresse et données prises en charge à identifier sur le véhicule."),
            EcuModule("EBCM", "ABS", "Electronic Brake Control Module", "Calculateur ABS / StabiliTrak", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "GM enhanced addressing is not assumed; perform a verified module scan.", notesFr = "L'adressage GM étendu n'est pas supposé ; effectuer une identification réelle du calculateur."),
            EcuModule("BCM", "BCM", "Body Control Module", "Calculateur habitacle", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "GM bulletin 07-08-49-020I identifies the BCM as the Camaro odometer storage module for 2010–2015. Exact diagnostic addressing and read DID are not assumed.", notesFr = "Le bulletin GM 07-08-49-020I identifie le BCM comme module de stockage du kilométrage Camaro 2010–2015. L'adressage de diagnostic et le DID de lecture ne sont pas supposés."),
            EcuModule("IPC", "IPC", "Instrument Panel Cluster", "Combiné d'instruments", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "2012 Camaro service information describes the IPC as secondary odometer storage. Exact read data and address must be verified.", notesFr = "La documentation Camaro 2012 décrit l'IPC comme stockage kilométrique secondaire. L'adresse et les données de lecture doivent être vérifiées."),
            EcuModule("SDM", "SDM", "Sensing and Diagnostic Module", "Calculateur airbags", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Safety module access is vehicle-specific and must remain read-only.", notesFr = "L'accès au calculateur de sécurité est spécifique au véhicule et doit rester en lecture seule.")
        )
    }

    fun parsePidBytes(response: String, pid: String): IntArray? {
        val cleaned = response.uppercase().replace(Regex("[^0-9A-F]"), "")
        val marker = "41${pid.uppercase().padStart(2, '0')}"
        val index = cleaned.indexOf(marker)
        if (index < 0) return null
        val payload = cleaned.substring(index + marker.length)
        if (payload.length < 2 || payload.length % 2 != 0) return null
        return runCatching { payload.chunked(2).map { it.toInt(16) }.toIntArray() }.getOrNull()
    }

    fun parseOdometerPidA6(response: String): Double? = parseOdometerPid(response, "A6")

    fun parseOdometerPidD3(response: String): Double? = parseOdometerPid(response, "D3")

    private fun parseOdometerPid(response: String, pid: String): Double? {
        val cleaned = response.uppercase().replace(Regex("[^0-9A-F]"), "")
        val marker = cleaned.indexOf("41$pid")
        if (marker < 0 || cleaned.length < marker + 12) return null
        return runCatching {
            val raw = cleaned.substring(marker + 4, marker + 12).toLong(16)
            raw / 10.0
        }.getOrNull()
    }


}
