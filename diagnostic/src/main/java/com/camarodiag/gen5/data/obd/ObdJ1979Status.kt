package com.camarodiag.gen5.data.obd

/** Non-numeric views of selected classic J1979 Mode 01 parameters.
 * The reported operating state is not evidence of a successful actuation. */
object ObdJ1979Status {
    data class State(val pid: Int, val name: String, val rawEncoded: Int, val code: String)
    private data class Field(val name: String, val offset: Int, val shift: Int, val mask: Int,
        val codes: Map<Int, String>, val support: Int = -1)
    private data class Definition(val width: Int, val reserved: List<Pair<Int, Int>>, val fields: List<Field>)

    private fun codes(vararg values: String) = values.mapIndexed { index, value -> index to value }.toMap()
    private val on = codes("INACTIVE", "ACTIVE")
    private val present = codes("ABSENT", "PRESENT")
    private val loop = codes("RESERVED", "OPEN_LOOP", "CLOSED_LOOP", "FAULT")
    private val fuelLoop = mapOf(0 to "UNAVAILABLE", 1 to "OPEN_WARMUP", 2 to "CLOSED_FEEDBACK",
        4 to "OPEN_DRIVING", 8 to "OPEN_FAULT", 16 to "CLOSED_SENSOR_FAULT")
    private val fuel = codes("UNAVAILABLE", "GASOLINE", "METHANOL", "ETHANOL", "DIESEL", "LPG",
        "CNG", "PROPANE", "ELECTRIC", "BIFUEL_GASOLINE", "BIFUEL_METHANOL", "BIFUEL_ETHANOL",
        "BIFUEL_LPG", "BIFUEL_CNG", "BIFUEL_PROPANE", "BIFUEL_ELECTRIC", "BIFUEL_COMBINED",
        "HYBRID_GASOLINE", "HYBRID_ETHANOL", "HYBRID_DIESEL", "HYBRID_ELECTRIC",
        "HYBRID_COMBINED", "HYBRID_REGENERATION", "BIFUEL_DIESEL")
    private val obd = mapOf(1 to "OBD_II", 2 to "OBD", 3 to "OBD_AND_OBD_II", 4 to "OBD_I",
        5 to "NOT_COMPLIANT", 6 to "EOBD", 7 to "EOBD_OBD_II", 8 to "EOBD_OBD", 9 to "EOBD_OBD_OBD_II",
        10 to "JOBD", 11 to "JOBD_OBD_II", 12 to "JOBD_EOBD", 13 to "JOBD_EOBD_OBD_II",
        17 to "EMD", 18 to "EMD_PLUS", 19 to "HD_OBD_PARTIAL", 20 to "HD_OBD", 21 to "WWH_OBD",
        23 to "HD_EOBD_I_NO_NOX", 24 to "HD_EOBD_I_NOX", 25 to "HD_EOBD_II_NO_NOX",
        26 to "HD_EOBD_II_NOX", 28 to "BR_OBD_I", 29 to "BR_OBD_II", 30 to "KOBD",
        31 to "INDIA_OBD_I", 32 to "INDIA_OBD_II", 33 to "HD_EOBD_VI")
    private val mi = mapOf(0 to "OFF", 1 to "ON_DEMAND", 2 to "SHORT", 3 to "CONTINUOUS",
        14 to "ERROR", 15 to "UNAVAILABLE")
    private val induce = codes("INACTIVE", "ENABLED", "ACTIVE", "UNSUPPORTED")
    private fun oxygen(perBank: Int) = (0..7).map {
        Field("oxygen-bank-${it/perBank+1}-sensor-${it%perBank+1}", 0, it, 1, present)
    }
    private fun nte(prefix: String) = listOf("inside", "outside", "carve-out", "deficiency").mapIndexed { i, name ->
        Field("$prefix-nte-$name", 0, i, 1, on)
    }
    private fun fuelControl() = (0..7).map { i ->
        val name = listOf("pressure", "quantity", "timing", "idle-balance")[i%4]
        Field("fuel-$name-${i/4+1}", 1, i, 1, codes("NOT_CLOSED_LOOP","CLOSED_LOOP"), i)
    }

    private val definitions = mapOf(
        0x03 to Definition(2, emptyList(), listOf(Field("fuel-system-1",0,0,255,fuelLoop),
            Field("fuel-system-2",1,0,255,fuelLoop))),
        0x12 to Definition(1, emptyList(), listOf(Field("secondary-air",0,0,255,
            mapOf(1 to "UPSTREAM",2 to "DOWNSTREAM",4 to "ATMOSPHERE_OR_OFF",8 to "DIAGNOSTIC")))),
        0x13 to Definition(1, emptyList(), oxygen(4)),
        0x1C to Definition(1, emptyList(), listOf(Field("declared-obd-standard",0,0,255,obd))),
        0x1D to Definition(1, emptyList(), oxygen(2)),
        0x1E to Definition(1, listOf(0 to 0xFE), listOf(Field("power-take-off",0,0,1,on))),
        0x51 to Definition(1, emptyList(), listOf(Field("current-fuel-type",0,0,255,fuel))),
        0x70 to Definition(10, listOf(0 to 0xC0,9 to 0xF0), listOf(
            Field("boost-control-a",9,0,3,loop,2),Field("boost-control-b",9,2,3,loop,5))),
        0x71 to Definition(6, listOf(0 to 0xC0,5 to 0xF0), listOf(
            Field("vgt-control-a",5,0,3,loop,2),Field("vgt-control-b",5,2,3,loop,5))),
        0x7D to Definition(1, listOf(0 to 0xF0), nte("nox")),
        0x7E to Definition(1, listOf(0 to 0xF0), nte("pm")),
        0x8B to Definition(7, listOf(0 to 0x80,1 to 0xF0), listOf(
            Field("dpf-regeneration",1,0,1,on,0),
            Field("dpf-regeneration-type",1,1,1,codes("PASSIVE","ACTIVE"),1),
            Field("nox-adsorber-regeneration",1,2,1,on,2),Field("nox-adsorber-desulfurization",1,3,1,on,3))),
        0x8F to Definition(7, listOf(0 to 0xF0,1 to 0xFC,4 to 0xFC), listOf(
            Field("pm-sensor-bank-1-measuring",1,0,1,on,0),Field("pm-sensor-bank-1-regenerating",1,1,1,on,0),
            Field("pm-sensor-bank-2-measuring",4,0,1,on,2),Field("pm-sensor-bank-2-regenerating",4,1,1,on,2))),
        0x90 to Definition(3, listOf(0 to 0x80), listOf(Field("vehicle-mi",0,2,15,mi),
            Field("emissions-readiness",0,6,1,codes("COMPLETE","INCOMPLETE")),
            Field("mi-display-strategy",0,0,3,mapOf(0 to "NONDISCRIMINATORY",1 to "DISCRIMINATORY",3 to "UNAVAILABLE")))),
        0x91 to Definition(5, listOf(0 to 0xF0), listOf(Field("ecu-mi",0,0,15,mi))),
        0x92 to Definition(2, emptyList(), fuelControl()),
        0x94 to Definition(12, listOf(0 to 0xC0,1 to 0x80), listOf(Field("nox-warning",1,0,1,on,0),
            Field("inducement-level-1",1,1,3,induce,0),Field("inducement-level-2",1,3,3,induce,0),
            Field("inducement-level-3",1,5,3,induce,0)))
    )

    fun width(pid: Int): Int? = definitions[pid]?.width

    fun decodeData(pid: Int, data: List<Int>): List<State>? {
        val definition = definitions[pid] ?: return null
        if (data.size != definition.width || data.any { it !in 0..255 }) return null
        if (definition.reserved.any { (offset, mask) -> data[offset] and mask != 0 }) return null
        return definition.fields.mapNotNull { field ->
            if (field.support >= 0 && data[0] and (1 shl field.support) == 0) return@mapNotNull null
            val raw = (data[field.offset] shr field.shift) and field.mask
            State(pid,field.name,raw,field.codes[raw] ?: "UNKNOWN")
        }
    }

    data class Record(val requestHex:String,val states:List<State>)

    fun tryRecord(requestHex:String,responseHex:String?,status:String):Record? {
        if(status !in setOf("RAW","PASSED")||!requestHex.matches(Regex("01[0-9A-Fa-f]{2}"))||
            responseHex==null||responseHex.length>28||responseHex.length%2!=0||
            !responseHex.matches(Regex("[0-9A-Fa-f]+")))return null
        val req=requestHex.chunked(2).map { it.toInt(16) }
        val res=responseHex.chunked(2).map { it.toInt(16) }
        return decodeResponse(req,res)?.let { Record(requestHex.uppercase(java.util.Locale.ROOT),it) }
    }

    fun decodeResponse(request: List<Int>, response: List<Int>): List<State>? {
        if (request.size != 2 || request[0] != 1 || response.size < 2 ||
            response[0] != 0x41 || response[1] != request[1]) return null
        return decodeData(request[1], response.drop(2))
    }
}
