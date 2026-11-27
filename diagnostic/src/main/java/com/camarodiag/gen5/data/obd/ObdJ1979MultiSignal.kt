package com.camarodiag.gen5.data.obd

/** Multi-signal classic Mode 01, explicit historical J1979DA definitions.
 * Unsupported sensor slots and unknown revisions do not become measurements.
 * Field identities deliberately avoid inferring bank layout for narrow/wideband
 * oxygen sensor ordinals. No Mode 02 or J1979-2 applicability is inferred. */
object ObdJ1979MultiSignal {
    private data class Field(val name:String,val unit:String,val offset:Int,
        val width:Int,val multiplier:Int,val divisor:Int,val bias:Int,val bit:Int,
        val signed:Boolean,val unavailable:Long?)
    private data class Definition(val width:Int,val supportMask:Int,val fields:List<Field>)
    data class Signal(val pid:Int,val name:String,val unit:String,val number:Double,val rawEncoded:Long)

    private val definitions = mapOf(
        0x14 to Definition(2,0x00,listOf(
            Field("o2-sensor-1-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            Field("o2-sensor-1-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x15 to Definition(2,0x00,listOf(
            Field("o2-sensor-2-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            Field("o2-sensor-2-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x16 to Definition(2,0x00,listOf(
            Field("o2-sensor-3-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            Field("o2-sensor-3-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x17 to Definition(2,0x00,listOf(
            Field("o2-sensor-4-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            Field("o2-sensor-4-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x18 to Definition(2,0x00,listOf(
            Field("o2-sensor-5-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            Field("o2-sensor-5-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x19 to Definition(2,0x00,listOf(
            Field("o2-sensor-6-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            Field("o2-sensor-6-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x1A to Definition(2,0x00,listOf(
            Field("o2-sensor-7-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            Field("o2-sensor-7-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x1B to Definition(2,0x00,listOf(
            Field("o2-sensor-8-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            Field("o2-sensor-8-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x24 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-1-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        )),
        0x25 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-2-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        )),
        0x26 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-3-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        )),
        0x27 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-4-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        )),
        0x28 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-5-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        )),
        0x29 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-6-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        )),
        0x2A to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-7-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        )),
        0x2B to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-8-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        )),
        0x34 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-1-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        )),
        0x35 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-2-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        )),
        0x36 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-3-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        )),
        0x37 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-4-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        )),
        0x38 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-5-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        )),
        0x39 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-6-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        )),
        0x3A to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-7-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        )),
        0x3B to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            Field("wideband-o2-sensor-8-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        )),
        0x64 to Definition(5,0x00,listOf(
            Field("engine-torque-idle", "%", 0, 1, 1, 1, -125, -1, false, ),
            Field("engine-torque-point-2", "%", 1, 1, 1, 1, -125, -1, false, ),
            Field("engine-torque-point-3", "%", 2, 1, 1, 1, -125, -1, false, ),
            Field("engine-torque-point-4", "%", 3, 1, 1, 1, -125, -1, false, ),
            Field("engine-torque-point-5", "%", 4, 1, 1, 1, -125, -1, false, ),
        )),
        0x66 to Definition(5,0x03,listOf(
            Field("maf-a", "g/s", 1, 2, 1, 32, 0, 0, false, ),
            Field("maf-b", "g/s", 3, 2, 1, 32, 0, 1, false, ),
        )),
        0x67 to Definition(3,0x03,listOf(
            Field("coolant-1", "degC", 1, 1, 1, 1, -40, 0, false, ),
            Field("coolant-2", "degC", 2, 1, 1, 1, -40, 1, false, ),
        )),
        0x68 to Definition(7,0x3F,listOf(
            Field("intake-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, false, ),
            Field("intake-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, false, ),
            Field("intake-temperature-bank-1-sensor-3", "degC", 3, 1, 1, 1, -40, 2, false, ),
            Field("intake-temperature-bank-2-sensor-1", "degC", 4, 1, 1, 1, -40, 3, false, ),
            Field("intake-temperature-bank-2-sensor-2", "degC", 5, 1, 1, 1, -40, 4, false, ),
            Field("intake-temperature-bank-2-sensor-3", "degC", 6, 1, 1, 1, -40, 5, false, ),
        )),
        0x69 to Definition(7,0x3F,listOf(
            Field("egr-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            Field("egr-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            Field("egr-a-error", "%", 3, 1, 100, 128, -100, 2, false, ),
            Field("egr-b-command", "%", 4, 1, 100, 255, 0, 3, false, ),
            Field("egr-b-actual", "%", 5, 1, 100, 255, 0, 4, false, ),
            Field("egr-b-error", "%", 6, 1, 100, 128, -100, 5, false, ),
        )),
        0x6A to Definition(5,0x0F,listOf(
            Field("diesel-intake-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            Field("diesel-intake-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            Field("diesel-intake-b-command", "%", 3, 1, 100, 255, 0, 2, false, ),
            Field("diesel-intake-b-actual", "%", 4, 1, 100, 255, 0, 3, false, ),
        )),
        0x6C to Definition(5,0x0F,listOf(
            Field("throttle-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            Field("throttle-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            Field("throttle-b-command", "%", 3, 1, 100, 255, 0, 2, false, ),
            Field("throttle-b-actual", "%", 4, 1, 100, 255, 0, 3, false, ),
        )),
        0x6D to Definition(11,0x3F,listOf(
            Field("fuel-rail-a-command", "kPa", 1, 2, 10, 1, 0, 0, false, ),
            Field("fuel-rail-a-pressure", "kPa", 3, 2, 10, 1, 0, 1, false, ),
            Field("fuel-rail-a-temperature", "degC", 5, 1, 1, 1, -40, 2, false, ),
            Field("fuel-rail-b-command", "kPa", 6, 2, 10, 1, 0, 3, false, ),
            Field("fuel-rail-b-pressure", "kPa", 8, 2, 10, 1, 0, 4, false, ),
            Field("fuel-rail-b-temperature", "degC", 10, 1, 1, 1, -40, 5, false, ),
        )),
        0x6E to Definition(9,0x0F,listOf(
            Field("injection-pressure-a-command", "kPa", 1, 2, 10, 1, 0, 0, false, ),
            Field("injection-pressure-a-actual", "kPa", 3, 2, 10, 1, 0, 1, false, ),
            Field("injection-pressure-b-command", "kPa", 5, 2, 10, 1, 0, 2, false, ),
            Field("injection-pressure-b-actual", "kPa", 7, 2, 10, 1, 0, 3, false, ),
        )),
        0x70 to Definition(10,0x3F,listOf(
            Field("boost-a-command", "kPa", 1, 2, 1, 32, 0, 0, false, ),
            Field("boost-a-actual", "kPa", 3, 2, 1, 32, 0, 1, false, ),
            Field("boost-b-command", "kPa", 5, 2, 1, 32, 0, 3, false, ),
            Field("boost-b-actual", "kPa", 7, 2, 1, 32, 0, 4, false, ),
        )),
        0x71 to Definition(6,0x3F,listOf(
            Field("vgt-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            Field("vgt-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            Field("vgt-b-command", "%", 3, 1, 100, 255, 0, 3, false, ),
            Field("vgt-b-actual", "%", 4, 1, 100, 255, 0, 4, false, ),
        )),
        0x72 to Definition(5,0x0F,listOf(
            Field("wastegate-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            Field("wastegate-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            Field("wastegate-b-command", "%", 3, 1, 100, 255, 0, 2, false, ),
            Field("wastegate-b-actual", "%", 4, 1, 100, 255, 0, 3, false, ),
        )),
        0x73 to Definition(5,0x03,listOf(
            Field("exhaust-pressure-bank-1", "kPa", 1, 2, 1, 100, 0, 0, false, ),
            Field("exhaust-pressure-bank-2", "kPa", 3, 2, 1, 100, 0, 1, false, ),
        )),
        0x74 to Definition(5,0x03,listOf(
            Field("turbo-a-rpm", "rpm", 1, 2, 10, 1, 0, 0, false, ),
            Field("turbo-b-rpm", "rpm", 3, 2, 10, 1, 0, 1, false, ),
        )),
        0x75 to Definition(7,0x0F,listOf(
            Field("turbo-a-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, false, ),
            Field("turbo-a-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, false, ),
            Field("turbo-a-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, false, ),
            Field("turbo-a-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, false, ),
        )),
        0x76 to Definition(7,0x0F,listOf(
            Field("turbo-b-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, false, ),
            Field("turbo-b-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, false, ),
            Field("turbo-b-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, false, ),
            Field("turbo-b-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, false, ),
        )),
        0x77 to Definition(5,0x0F,listOf(
            Field("charge-air-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, false, ),
            Field("charge-air-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, false, ),
            Field("charge-air-temperature-bank-2-sensor-1", "degC", 3, 1, 1, 1, -40, 2, false, ),
            Field("charge-air-temperature-bank-2-sensor-2", "degC", 4, 1, 1, 1, -40, 3, false, ),
        )),
        0x78 to Definition(9,0x0F,listOf(
            Field("egt-bank-1-sensor-1", "degC", 1, 2, 1, 10, -40, 0, false, ),
            Field("egt-bank-1-sensor-2", "degC", 3, 2, 1, 10, -40, 1, false, ),
            Field("egt-bank-1-sensor-3", "degC", 5, 2, 1, 10, -40, 2, false, ),
            Field("egt-bank-1-sensor-4", "degC", 7, 2, 1, 10, -40, 3, false, ),
        )),
        0x79 to Definition(9,0x0F,listOf(
            Field("egt-bank-2-sensor-1", "degC", 1, 2, 1, 10, -40, 0, false, ),
            Field("egt-bank-2-sensor-2", "degC", 3, 2, 1, 10, -40, 1, false, ),
            Field("egt-bank-2-sensor-3", "degC", 5, 2, 1, 10, -40, 2, false, ),
            Field("egt-bank-2-sensor-4", "degC", 7, 2, 1, 10, -40, 3, false, ),
        )),
        0x7A to Definition(7,0x07,listOf(
            Field("dpf-bank-1-delta", "kPa", 1, 2, 1, 100, 0, 0, true, ),
            Field("dpf-bank-1-inlet", "kPa", 3, 2, 1, 100, 0, 1, false, ),
            Field("dpf-bank-1-outlet", "kPa", 5, 2, 1, 100, 0, 2, false, ),
        )),
        0x7B to Definition(7,0x07,listOf(
            Field("dpf-bank-2-delta", "kPa", 1, 2, 1, 100, 0, 0, true, ),
            Field("dpf-bank-2-inlet", "kPa", 3, 2, 1, 100, 0, 1, false, ),
            Field("dpf-bank-2-outlet", "kPa", 5, 2, 1, 100, 0, 2, false, ),
        )),
        0x7C to Definition(9,0x0F,listOf(
            Field("dpf-bank-1-inlet-temperature", "degC", 1, 2, 1, 10, -40, 0, false, ),
            Field("dpf-bank-1-outlet-temperature", "degC", 3, 2, 1, 10, -40, 1, false, ),
            Field("dpf-bank-2-inlet-temperature", "degC", 5, 2, 1, 10, -40, 2, false, ),
            Field("dpf-bank-2-outlet-temperature", "degC", 7, 2, 1, 10, -40, 3, false, ),
        )),
        0x7F to Definition(13,0x07,listOf(
            Field("engine-total-runtime", "s", 1, 4, 1, 1, 0, 0, false, ),
            Field("engine-idle-runtime", "s", 5, 4, 1, 1, 0, 1, false, ),
            Field("engine-pto-runtime", "s", 9, 4, 1, 1, 0, 2, false, ),
        )),
        0x98 to Definition(9,0x0F,listOf(
            Field("egt-bank-1-sensor-5", "degC", 1, 2, 1, 10, -40, 0, false, ),
            Field("egt-bank-1-sensor-6", "degC", 3, 2, 1, 10, -40, 1, false, ),
            Field("egt-bank-1-sensor-7", "degC", 5, 2, 1, 10, -40, 2, false, ),
            Field("egt-bank-1-sensor-8", "degC", 7, 2, 1, 10, -40, 3, false, ),
        )),
        0x99 to Definition(9,0x0F,listOf(
            Field("egt-bank-2-sensor-5", "degC", 1, 2, 1, 10, -40, 0, false, ),
            Field("egt-bank-2-sensor-6", "degC", 3, 2, 1, 10, -40, 1, false, ),
            Field("egt-bank-2-sensor-7", "degC", 5, 2, 1, 10, -40, 2, false, ),
            Field("egt-bank-2-sensor-8", "degC", 7, 2, 1, 10, -40, 3, false, ),
        )),
    )
    fun width(pid:Int):Int?=definitions[pid]?.width
    fun decodeData(pid:Int,data:List<Int>):List<Signal>? {
        val definition=definitions[pid]?:return null
        if(data.size!=definition.width||data.any { it !in 0..255 })return null
        if(definition.supportMask!=0&&data[0] and definition.supportMask.inv()!=0)return null
        return buildList {
            for(field in definition.fields){
                if(field.bit>=0&&data[0] and (1 shl field.bit)==0)continue
                var raw=0L
                for(i in field.offset until field.offset+field.width)raw=(raw shl 8) or data[i].toLong()
                if(raw==field.unavailable)continue
                val encoded=if(field.signed&&raw>=(1L shl (field.width*8-1)))
                    raw-(1L shl (field.width*8)) else raw
                add(Signal(pid,field.name,field.unit,encoded.toDouble()*field.multiplier/field.divisor+field.bias,raw))
            }
        }
    }
    fun decodeResponse(request:String,response:String?):List<Signal>? {
        if(!request.matches(Regex("01[0-9A-F]{2}"))||response==null)return null
        val pid=request.takeLast(2).toInt(16)
        val width=width(pid)?:return null
        if(response.length!=4+2*width||!response.startsWith("41"+request.takeLast(2))||
            !response.matches(Regex("[0-9A-F]+")))return null
        return decodeData(pid,response.drop(4).chunked(2).map { it.toInt(16) })
    }
}
