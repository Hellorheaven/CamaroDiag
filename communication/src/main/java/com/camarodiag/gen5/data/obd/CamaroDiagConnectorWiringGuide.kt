package com.camarodiag.gen5.data.obd

/**
 * Links to the unqualified wiring documentation maintained by Connectors.
 * Never infer power pin numbers, voltage or a programming profile from a
 * service part, vehicle selection, case marking or this documentation.
 */
object CamaroDiagConnectorWiringGuide {
    // Pin the reviewed documentary snapshot; do not silently inherit later pin changes.
    private const val BASE = "https://github.com/Hellorheaven/CamaroDiagConnectors/blob/04883775d7f97de4cd89da0de75c46bf076f3332/docs/user/wiring/"
    const val genericUrl = BASE + "README.md"
    const val catalogueUrl = BASE + "ecu/README.md"

    enum class Reference(val label: String, val url: String) {
        LFX_2012_E39("2012 LFX / E39", BASE + "ecu/2012-lfx-e39.md"),
        LS3_L99_2012_E38("2012 LS3/L99 / E38", BASE + "ecu/2012-ls3-l99-e38.md"),
        E39A_UNQUALIFIED("E39A", BASE + "ecu/e39a.md");

        /** No current wiring document authorizes energization or programming. */
        val qualifiedForConnection: Boolean get() = false
    }

    /** Exact documentary match only; the caller's family is not observed hardware identity. */
    fun findReference(modelYear: Int, engineRpo: String, enteredFamily: String): Reference? {
        val family = enteredFamily.trim().uppercase(java.util.Locale.ROOT)
        val rpo = engineRpo.trim().uppercase(java.util.Locale.ROOT)
        return when {
            family == "E39A" -> Reference.E39A_UNQUALIFIED
            modelYear == 2012 && rpo == "LFX" && family == "E39" -> Reference.LFX_2012_E39
            modelYear == 2012 && rpo in setOf("LS3", "L99") && family == "E38" ->
                Reference.LS3_L99_2012_E38
            else -> null
        }
    }
}
