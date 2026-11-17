package com.camarodiag.gen5.domain.model

/**
 * GM OBD certification-group provenance for sixth-generation Camaro engines.
 *
 * A source label identifies the certification document to consult. It does not
 * mean that detailed monitor thresholds or service diagnostic procedures have
 * been imported for that engine/year.
 */
object GmGen6MonitorCatalog {
    fun sourceLabel(vehicle: VehicleProfile): String? =
        when {
            vehicle.modelYear == 2016 && vehicle.engine == CamaroEngine.LTG -> "16OBDG03"
            vehicle.modelYear == 2016 && vehicle.engine == CamaroEngine.LGX -> "16OBDG05"
            vehicle.modelYear == 2016 && vehicle.engine == CamaroEngine.LT1 -> "16OBDG07A"
            vehicle.modelYear == 2017 && vehicle.engine in setOf(
                CamaroEngine.LTG,
                CamaroEngine.LGX,
                CamaroEngine.LT1,
                CamaroEngine.LT4
            ) -> "17OBDG03"
            vehicle.modelYear == 2018 && vehicle.engine in setOf(
                CamaroEngine.LTG,
                CamaroEngine.LGX,
                CamaroEngine.LT1,
                CamaroEngine.LT4
            ) -> "18OBDG03A"
            vehicle.modelYear == 2019 && vehicle.engine in setOf(
                CamaroEngine.LTG,
                CamaroEngine.LGX,
                CamaroEngine.LT1,
                CamaroEngine.LT4
            ) -> "19OBDG03A"
            vehicle.modelYear == 2020 && vehicle.engine in setOf(
                CamaroEngine.LTG,
                CamaroEngine.LGX,
                CamaroEngine.LT1,
                CamaroEngine.LT4
            ) -> "20OBDG03A"
            vehicle.modelYear == 2022 && vehicle.engine == CamaroEngine.LTG -> "22OBDG03A"
            vehicle.modelYear == 2022 && vehicle.engine in setOf(
                CamaroEngine.LGX,
                CamaroEngine.LT1,
                CamaroEngine.LT4
            ) -> "22OBDG03B"
            vehicle.modelYear == 2023 && vehicle.engine == CamaroEngine.LTG -> "23OBDG03A Part2"
            vehicle.modelYear == 2023 && vehicle.engine in setOf(
                CamaroEngine.LGX,
                CamaroEngine.LT1,
                CamaroEngine.LT4
            ) -> "23OBDG03B"
            vehicle.modelYear == 2024 && vehicle.engine in setOf(
                CamaroEngine.LGX,
                CamaroEngine.LT1,
                CamaroEngine.LT4
            ) -> "24OBDG03B Part 2"
            else -> null
        }
}
