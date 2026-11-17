package com.camarodiag.gen5.domain.model

import java.util.Locale

/**
 * Evidence levels are intentionally explicit: a normalized SAE label is not an
 * OEM repair definition, and a GM definition is not portable across calibrations.
 */
enum class DtcDefinitionSource {
    SAE_J2012_GENERIC,
    GM_SERVICE_INFORMATION,
    GM_BULLETIN,
    VEHICLE_VALIDATED_CAPTURE,
    UNVERIFIED
}

enum class DtcEvidenceStatus {
    DOCUMENTED,
    VALIDATED_ON_VEHICLE,
    GENERIC_ONLY,
    DISCOVERY_REQUIRED
}

data class DtcDefinitionEvidence(
    val source: DtcDefinitionSource,
    val status: DtcEvidenceStatus,
    val modelYears: IntRange?,
    val engineRpos: Set<String>,
    val transmission: TransmissionType?,
    val moduleId: String,
    val hardwarePartNumber: String?,
    val operatingSystemId: String?,
    val calibrationId: String?,
    val calibrationVerificationNumber: String?,
    val vin: String?,
    val reference: String
) {
    fun validationIssues(vehicle: VehicleProfile, respondingModuleId: String): List<String> = buildList {
        if (moduleId.isBlank() || !moduleId.equals(respondingModuleId, ignoreCase = true)) {
            add("The reporting ECU does not match the DTC definition.")
        }
        if (modelYears == null) add("A documented model-year range is missing.")
        else if (vehicle.modelYear !in modelYears) add("Model year is outside the documented range.")
        if (engineRpos.isEmpty()) add("Documented engine RPO coverage is missing.")
        else if (vehicle.engine.rpo !in engineRpos) add("Engine RPO is not covered.")
        if (transmission == null) add("A validated transmission applicability is missing.")
        else if (vehicle.transmission != transmission) add("Transmission does not match.")
        if (hardwarePartNumber.isNullOrBlank()) add("ECU hardware part number is missing.")
        if (operatingSystemId.isNullOrBlank()) add("ECU operating-system identifier is missing.")
        if (calibrationId.isNullOrBlank()) add("ECU calibration ID is missing.")
        if (calibrationVerificationNumber.isNullOrBlank()) add("ECU CVN is missing.")
        val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")
        if (vin.isNullOrBlank() || !vin.uppercase(Locale.ROOT).matches(vinPattern)) {
            add("A validated 17-character VIN is missing.")
        } else if (vehicle.vin.uppercase(Locale.ROOT) != vin.uppercase(Locale.ROOT)) {
            add("VIN does not match the selected vehicle.")
        }
        if (reference.isBlank()) add("A traceable technical source reference is missing.")
        if (status == DtcEvidenceStatus.DISCOVERY_REQUIRED) add("This definition is not approved for diagnostic interpretation.")
    }

    fun isUsableForOemInterpretation(
        vehicle: VehicleProfile,
        respondingModuleId: String
    ): Boolean =
        source in setOf(
            DtcDefinitionSource.GM_SERVICE_INFORMATION,
            DtcDefinitionSource.GM_BULLETIN,
            DtcDefinitionSource.VEHICLE_VALIDATED_CAPTURE
        ) &&
            status in setOf(DtcEvidenceStatus.DOCUMENTED, DtcEvidenceStatus.VALIDATED_ON_VEHICLE) &&
            validationIssues(vehicle, respondingModuleId).isEmpty()
}

/** Module inventory is a discovery plan, never a claim that every trim has every ECU. */
data class DtcModuleFamily(
    val id: String,
    val nameEn: String,
    val nameFr: String,
    val optionalEquipment: Boolean,
    val discoveryRequired: Boolean = true
)

enum class EngineInductionType { NATURALLY_ASPIRATED, TURBOCHARGED, SUPERCHARGED }
enum class EngineFuelSystemType { PORT_INJECTION, DIRECT_INJECTION }

data class CamaroEngineDtcProfile(
    val rpo: String,
    val cylinderCount: Int,
    val bankCount: Int,
    val induction: EngineInductionType,
    val fuelSystem: EngineFuelSystemType,
    val ecmFamily: String,
    val modelYears: IntRange
)

data class DtcModuleCatalogue(
    val moduleId: String,
    val evidenceStatus: DtcEvidenceStatus,
    val hasVehicleSpecificDefinitions: Boolean,
    val noteEn: String,
    val noteFr: String
)

object DtcCatalogRegistry {
    val supportedEngineRpos: Set<String> = CamaroEngine.entries.map { it.rpo }.toSet()

    val engineProfiles: Map<String, CamaroEngineDtcProfile> = listOf(
        CamaroEngineDtcProfile("LLT", 6, 2, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.DIRECT_INJECTION, "Bosch E69", 2010..2011),
        CamaroEngineDtcProfile("LFX", 6, 2, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.DIRECT_INJECTION, "ACDelco E39/E39A", 2012..2015),
        CamaroEngineDtcProfile("LS3", 8, 2, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.PORT_INJECTION, "ACDelco E38", 2010..2015),
        CamaroEngineDtcProfile("L99", 8, 2, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.PORT_INJECTION, "ACDelco E38", 2010..2015),
        CamaroEngineDtcProfile("LSA", 8, 2, EngineInductionType.SUPERCHARGED, EngineFuelSystemType.PORT_INJECTION, "ACDelco E67", 2012..2015),
        CamaroEngineDtcProfile("LS7", 8, 2, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.PORT_INJECTION, "ACDelco E38", 2014..2015),
        CamaroEngineDtcProfile("LTG", 4, 1, EngineInductionType.TURBOCHARGED, EngineFuelSystemType.DIRECT_INJECTION, "GM E80", 2016..2020),
        CamaroEngineDtcProfile("LGX", 6, 2, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.DIRECT_INJECTION, "GM E92/E93", 2016..2024),
        CamaroEngineDtcProfile("LT1", 8, 2, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.DIRECT_INJECTION, "GM E92", 2016..2024),
        CamaroEngineDtcProfile("LT4", 8, 2, EngineInductionType.SUPERCHARGED, EngineFuelSystemType.DIRECT_INJECTION, "GM E92", 2017..2024)
    ).associateBy { it.rpo }

    fun engineProfile(engine: CamaroEngine): CamaroEngineDtcProfile? =
        engineProfiles[engine.rpo]?.takeIf { it.modelYears == engine.years }

    val moduleFamilies: List<DtcModuleFamily> = listOf(
        DtcModuleFamily("ECM", "Engine Control Module", "Calculateur moteur", false),
        DtcModuleFamily("PCM", "Powertrain Control Module", "Calculateur groupe motopropulseur", false),
        DtcModuleFamily("TCM", "Transmission Control Module", "Calculateur de transmission", true),
        DtcModuleFamily("EBCM", "Electronic Brake Control Module", "Calculateur ABS / StabiliTrak", false),
        DtcModuleFamily("BCM", "Body Control Module", "Calculateur habitacle", false),
        DtcModuleFamily("IPC", "Instrument Panel Cluster", "Combiné d'instruments", false),
        DtcModuleFamily("SDM", "Sensing and Diagnostic Module", "Calculateur airbags", false),
        DtcModuleFamily("HVAC", "Heating, Ventilation and Air Conditioning", "Calculateur de climatisation", true),
        DtcModuleFamily("PSCM", "Power Steering Control Module", "Calculateur de direction assistée", true),
        DtcModuleFamily("RCDLR", "Remote Control Door Lock Receiver", "Récepteur de télécommande", true),
        DtcModuleFamily("RADIO", "Radio / Infotainment", "Radio / multimédia", true),
        DtcModuleFamily("AMP", "Audio Amplifier", "Amplificateur audio", true),
        DtcModuleFamily("DDM", "Driver Door Module", "Calculateur de porte conducteur", true),
        DtcModuleFamily("PDM", "Passenger Door Module", "Calculateur de porte passager", true),
        DtcModuleFamily("FPCM", "Fuel Pump Control Module", "Calculateur de pompe à carburant", true),
        DtcModuleFamily("PARK_ASSIST", "Park Assist Control Module", "Aide au stationnement", true),
        DtcModuleFamily("HMI", "Human Machine Interface", "Interface multimédia", true)
    )

    /**
     * Only normalized descriptions are supplied here. These descriptions must
     * never be displayed as proof of a GM-specific failure mechanism.
     */
    val saeGenericDescriptions: Map<String, Pair<String, String>> = mapOf(
        "P0010" to ("Intake camshaft actuator control circuit (Bank 1)" to "Circuit de commande de l'actionneur d'arbre à cames d'admission (banc 1)"),
        "P0011" to ("Intake camshaft timing over-advanced / system performance (Bank 1)" to "Calage d'admission trop avancé / performance du système (banc 1)"),
        "P0013" to ("Exhaust Camshaft Position Actuator Circuit (Bank 1)" to "Circuit de commande de l'actionneur d'arbre à cames d'échappement (banc 1)"),
        "P0017" to ("Crankshaft Position - Exhaust Camshaft Position Correlation (Bank 1, Sensor B)" to "Corrélation position vilebrequin - arbre à cames d'échappement (banc 1, capteur B)"),
        "P0018" to ("Crankshaft Position - Intake Camshaft Position Correlation (Bank 2, Sensor A)" to "Corrélation position vilebrequin - arbre à cames d'admission (banc 2, capteur A)"),
        "P0019" to ("Crankshaft Position - Exhaust Camshaft Position Correlation (Bank 2, Sensor B)" to "Corrélation position vilebrequin - arbre à cames d'échappement (banc 2, capteur B)"),
        "P0020" to ("Intake Camshaft Position Actuator Circuit (Bank 2)" to "Circuit de commande de l'actionneur d'arbre à cames d'admission (banc 2)"),
        "P0021" to ("Intake Camshaft Position Timing Over-Advanced / System Performance (Bank 2)" to "Calage d'admission trop avancé / performance du système (banc 2)"),
        "P0023" to ("Exhaust Camshaft Position Actuator Circuit (Bank 2)" to "Circuit de commande de l'actionneur d'arbre à cames d'échappement (banc 2)"),
        "P0024" to ("Exhaust Camshaft Position Timing Over-Advanced / System Performance (Bank 2)" to "Calage d'échappement trop avancé / performance du système (banc 2)"),
        "P0030" to ("O2 Sensor Heater Control Circuit (Bank 1, Sensor 1)" to "Circuit de commande du chauffage de sonde à oxygène (banc 1, sonde 1)"),
        "P0036" to ("O2 Sensor Heater Control Circuit (Bank 1, Sensor 2)" to "Circuit de commande du chauffage de sonde à oxygène (banc 1, sonde 2)"),
        "P0050" to ("O2 Sensor Heater Control Circuit (Bank 2, Sensor 1)" to "Circuit de commande du chauffage de sonde à oxygène (banc 2, sonde 1)"),
        "P0014" to ("Exhaust camshaft timing over-advanced / system performance (Bank 1)" to "Calage d'échappement trop avancé / performance du système (banc 1)"),
        "P0016" to ("Crankshaft / camshaft position correlation (Bank 1, Sensor A)" to "Corrélation vilebrequin / arbre à cames (banc 1, capteur A)"),
        "P0017" to ("Crankshaft / camshaft position correlation (Bank 1, Sensor B)" to "Corrélation vilebrequin / arbre à cames (banc 1, capteur B)"),
        "P0018" to ("Crankshaft / camshaft position correlation (Bank 2, Sensor A)" to "Corrélation vilebrequin / arbre à cames (banc 2, capteur A)"),
        "P0019" to ("Crankshaft / camshaft position correlation (Bank 2, Sensor B)" to "Corrélation vilebrequin / arbre à cames (banc 2, capteur B)"),
        "P0101" to ("Mass or Volume Air Flow Circuit Range/Performance" to "Débitmètre d'air : plage / performance"),
        "P0102" to ("Mass or Volume Air Flow Circuit Low Input" to "Débitmètre d'air : signal trop faible"),
        "P0103" to ("Mass or Volume Air Flow Circuit High Input" to "Débitmètre d'air : signal trop élevé"),
        "P0113" to ("Intake Air Temperature Sensor 1 Circuit High" to "Sonde de température d'air admission : signal élevé"),
        "P0128" to ("Coolant Thermostat Below Regulating Temperature" to "Température du liquide sous le seuil de régulation du thermostat"),
        "P0171" to ("System Too Lean (Bank 1)" to "Mélange trop pauvre (banc 1)"),
        "P0172" to ("System Too Rich (Bank 1)" to "Mélange trop riche (banc 1)"),
        "P0174" to ("System Too Lean (Bank 2)" to "Mélange trop pauvre (banc 2)"),
        "P0175" to ("System Too Rich (Bank 2)" to "Mélange trop riche (banc 2)"),
        "P0300" to ("Random / Multiple Cylinder Misfire Detected" to "Ratés d'allumage aléatoires / multiples"),
        "P0301" to ("Cylinder 1 Misfire Detected" to "Ratés d'allumage cylindre 1"),
        "P0302" to ("Cylinder 2 Misfire Detected" to "Ratés d'allumage cylindre 2"),
        "P0303" to ("Cylinder 3 Misfire Detected" to "Ratés d'allumage cylindre 3"),
        "P0304" to ("Cylinder 4 Misfire Detected" to "Ratés d'allumage cylindre 4"),
        "P0305" to ("Cylinder 5 Misfire Detected" to "Ratés d'allumage cylindre 5"),
        "P0306" to ("Cylinder 6 Misfire Detected" to "Ratés d'allumage cylindre 6"),
        "P0307" to ("Cylinder 7 Misfire Detected" to "Ratés d'allumage cylindre 7"),
        "P0308" to ("Cylinder 8 Misfire Detected" to "Ratés d'allumage cylindre 8"),
        "P0420" to ("Catalyst System Efficiency Below Threshold (Bank 1)" to "Efficacité du catalyseur sous le seuil (banc 1)"),
        "P0430" to ("Catalyst System Efficiency Below Threshold (Bank 2)" to "Efficacité du catalyseur sous le seuil (banc 2)"),
        "P0442" to ("EVAP System Small Leak Detected" to "Circuit EVAP : petite fuite détectée"),
        "P0455" to ("EVAP System Large Leak Detected" to "Circuit EVAP : fuite importante détectée"),
        "P0496" to ("EVAP Flow During Non-Purge Condition" to "Débit EVAP détecté hors phase de purge"),
        "P0507" to ("Idle Control System RPM Higher Than Expected" to "Régime de ralenti supérieur à la consigne"),
        "P06DD" to ("Engine Oil Pressure Control Solenoid Performance" to "Performance de la commande de pression d'huile moteur"),
        "P0087" to ("Fuel Rail/System Pressure Too Low" to "Pression de rampe / circuit carburant trop faible"),
        "P0089" to ("Fuel Pressure Regulator Performance" to "Performance du régulateur de pression carburant"),
        "P0191" to ("Fuel Rail Pressure Sensor Circuit Range/Performance" to "Capteur de pression de rampe : plage / performance"),
        "P0299" to ("Turbocharger / Supercharger Underboost" to "Sous-pression de suralimentation"),
        "P1101" to ("Intake Air Flow System Performance" to "Performance du système de mesure du débit d'air")
    )

    fun genericDescription(code: String): Pair<String, String>? =
        saeGenericDescriptions[code.trim().uppercase(Locale.ROOT)]

    fun evidenceStatus(vehicle: VehicleProfile, moduleId: String): DtcEvidenceStatus =
        if (moduleId.equals("ECM", true) && CamaroDtcCatalog.isKnownLfxProfile(vehicle)) {
            DtcEvidenceStatus.DOCUMENTED
        } else {
            DtcEvidenceStatus.GENERIC_ONLY
        }

    fun catalogueFor(moduleId: String, vehicle: VehicleProfile): DtcModuleCatalogue {
        val id = moduleId.trim().uppercase(Locale.ROOT)
        val known = moduleFamilies.any { it.id == id }
        val detailed = id == "ECM" && CamaroDtcCatalog.isKnownLfxProfile(vehicle)
        return DtcModuleCatalogue(
            moduleId = id,
            evidenceStatus = when {
                !known -> DtcEvidenceStatus.DISCOVERY_REQUIRED
                detailed -> DtcEvidenceStatus.DOCUMENTED
                else -> DtcEvidenceStatus.GENERIC_ONLY
            },
            hasVehicleSpecificDefinitions = detailed,
            noteEn = when {
                !known -> "Unknown ECU identity: retain raw DTC only until the responding module is identified."
                detailed -> "Vehicle-specific ECM explanations are available for the documented LFX model-year range."
                else -> "Only normalized SAE descriptions are available for this ECU/vehicle combination; no GM module-specific definition is loaded."
            },
            noteFr = when {
                !known -> "Calculateur non identifié : conserver uniquement le DTC brut jusqu'à identification du module répondant."
                detailed -> "Les interprétations moteur spécifiques sont disponibles pour la plage d'années LFX documentée."
                else -> "Seuls les libellés SAE normalisés sont disponibles pour cette combinaison calculateur/véhicule ; aucune définition GM propre au module n'est chargée."
            }
        )
    }
}
