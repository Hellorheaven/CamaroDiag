package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.CamaroGeneration
import com.camarodiag.gen5.domain.model.VehicleProfile

/**
 * Coverage catalogue for Camaro Gen 5 / Gen 6 calibration work.
 *
 * Controller labels are the nominal families already associated with the vehicle
 * profiles. They are NOT a substitute for reading the actual ECU CAL ID, CVN,
 * hardware number and operating-system identifier from the vehicle.
 *
 * Binary memory access, table definitions and flashing remain disabled until an
 * OEM-/controller-specific protocol and recovery procedure have been verified.
 */
data class CamaroCalibrationProfile(
    val engine: CamaroEngine,
    val generation: CamaroGeneration,
    val modelYears: IntRange,
    val nominalControllerFamily: String,
    val identificationRequired: Boolean = true,
    val supportsStandardCalIdRead: Boolean = true,
    val supportsStandardCvnRead: Boolean = true,
    val supportsBinaryRead: Boolean = false,
    val supportsTableEditing: Boolean = false,
    val supportsEcuWrite: Boolean = false,
    val notesFr: String,
    val notesEn: String
)

object CamaroCalibrationCatalog {

    val profiles: List<CamaroCalibrationProfile> = listOf(
        profile(
            CamaroEngine.LLT, CamaroGeneration.GEN5, 2010..2011,
            "Bosch E69",
            "Camaro Gen 5 V6 LLT. Famille nominale du profil véhicule ; relever CAL ID, CVN et références matériel/logiciel réelles avant toute opération binaire.",
            "Gen 5 Camaro LLT V6. Nominal vehicle-profile family; read the actual CAL ID, CVN and hardware/software identifiers before any binary operation."
        ),
        profile(
            CamaroEngine.LFX, CamaroGeneration.GEN5, 2012..2015,
            "ACDelco E39 / E39A",
            "Camaro Gen 5 V6 LFX. E39/E39A reste une indication de profil ; distinguer les OS et calibrations à partir des identifiants lus sur l'ECU.",
            "Gen 5 Camaro LFX V6. E39/E39A is a profile hint; distinguish OS and calibration using identifiers read from the ECU."
        ),
        profile(
            CamaroEngine.LS3, CamaroGeneration.GEN5, 2010..2015,
            "ACDelco E38",
            "Camaro Gen 5 V8 LS3 atmosphérique, généralement associé à la boîte manuelle. Confirmer le numéro matériel, l'OS et le CAL ID sur l'ECU installé.",
            "Gen 5 naturally aspirated LS3 V8, generally paired with the manual transmission. Confirm installed ECU hardware number, OS and CAL ID."
        ),
        profile(
            CamaroEngine.L99, CamaroGeneration.GEN5, 2010..2015,
            "ACDelco E38",
            "Camaro Gen 5 V8 L99 avec AFM et boîte automatique. Ne pas réutiliser une calibration LS3 : vérifier RPO, boîte et identifiants ECU.",
            "Gen 5 L99 V8 with AFM and automatic transmission. Do not reuse an LS3 calibration; verify RPO, transmission and ECU identifiers."
        ),
        profile(
            CamaroEngine.LSA, CamaroGeneration.GEN5, 2012..2015,
            "ACDelco E67",
            "Camaro Gen 5 ZL1 V8 compressé LSA. Vérifier la calibration liée au compresseur, au carburant, au marché et à la version logicielle.",
            "Gen 5 ZL1 supercharged LSA V8. Verify calibration for supercharger, fuel, market and software revision."
        ),
        profile(
            CamaroEngine.LS7, CamaroGeneration.GEN5, 2014..2015,
            "ACDelco E38",
            "Camaro Gen 5 Z/28 V8 LS7. Profil distinct LS3/L99 malgré la famille de calculateur indiquée ; identification logicielle obligatoire.",
            "Gen 5 Z/28 LS7 V8. Separate profile from LS3/L99 despite the nominal controller family; software identification is mandatory."
        ),
        profile(
            CamaroEngine.LTG, CamaroGeneration.GEN6, 2016..2020,
            "GM E80 (RPO LTG)",
            "Camaro Gen 6 2.0 Turbo LTG (années 2016 à 2020 dans la matrice de couverture). Le bulletin GM PIP5313B cite l'E80 sur Camaro LTG 2016–2017 ; confirmer les années suivantes par référence et CAL ID.",
            "Gen 6 2.0 Turbo LTG Camaro (2016–2020 coverage range). GM bulletin PIP5313B identifies E80 on 2016–2017 Camaro LTG; verify later years by part number and CAL ID."
        ),
        profile(
            CamaroEngine.LGX, CamaroGeneration.GEN6, 2016..2024,
            "GM E92 / E93 (variant-dependent)",
            "Camaro Gen 6 V6 LGX. Le profil peut varier selon année, marché et version ; confirmer le calculateur réel et le CVN avant de charger une définition de tables.",
            "Gen 6 LGX V6 Camaro. Profile may vary by year, market and revision; confirm the actual controller and CVN before loading a table definition."
        ),
        profile(
            CamaroEngine.LT1, CamaroGeneration.GEN6, 2016..2024,
            "GM E92",
            "Camaro Gen 6 V8 LT1 atmosphérique. Distinguer les versions de calibration par CAL ID/CVN, année, marché, boîte et références matérielles.",
            "Gen 6 naturally aspirated LT1 V8 Camaro. Distinguish calibration revisions by CAL ID/CVN, year, market, transmission and hardware numbers."
        ),
        profile(
            CamaroEngine.LT4, CamaroGeneration.GEN6, 2017..2024,
            "GM E92",
            "Camaro Gen 6 ZL1 V8 LT4 compressé. Ne pas mélanger avec LT1 ; valider les références et les données de suralimentation propres à la calibration.",
            "Gen 6 ZL1 supercharged LT4 V8 Camaro. Do not mix with LT1; validate part numbers and calibration-specific boost data."
        )
    )

    fun forVehicle(vehicle: VehicleProfile): CamaroCalibrationProfile? =
        profiles.firstOrNull { it.engine == vehicle.engine && vehicle.modelYear in it.modelYears }

    fun forYear(modelYear: Int): List<CamaroCalibrationProfile> =
        profiles.filter { modelYear in it.modelYears }

    private fun profile(
        engine: CamaroEngine,
        generation: CamaroGeneration,
        years: IntRange,
        controller: String,
        notesFr: String,
        notesEn: String
    ) = CamaroCalibrationProfile(
        engine = engine,
        generation = generation,
        modelYears = years,
        nominalControllerFamily = controller,
        notesFr = notesFr,
        notesEn = notesEn
    )
}
