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
            Field("o2-sensor-1-voltage", "V", 0, 1, 1, 200, 0, -1, false, null),
            Field("o2-sensor-1-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x15 to Definition(2,0x00,listOf(
            Field("o2-sensor-2-voltage", "V", 0, 1, 1, 200, 0, -1, false, null),
            Field("o2-sensor-2-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x16 to Definition(2,0x00,listOf(
            Field("o2-sensor-3-voltage", "V", 0, 1, 1, 200, 0, -1, false, null),
            Field("o2-sensor-3-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x17 to Definition(2,0x00,listOf(
            Field("o2-sensor-4-voltage", "V", 0, 1, 1, 200, 0, -1, false, null),
            Field("o2-sensor-4-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x18 to Definition(2,0x00,listOf(
            Field("o2-sensor-5-voltage", "V", 0, 1, 1, 200, 0, -1, false, null),
            Field("o2-sensor-5-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x19 to Definition(2,0x00,listOf(
            Field("o2-sensor-6-voltage", "V", 0, 1, 1, 200, 0, -1, false, null),
            Field("o2-sensor-6-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x1A to Definition(2,0x00,listOf(
            Field("o2-sensor-7-voltage", "V", 0, 1, 1, 200, 0, -1, false, null),
            Field("o2-sensor-7-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x1B to Definition(2,0x00,listOf(
            Field("o2-sensor-8-voltage", "V", 0, 1, 1, 200, 0, -1, false, null),
            Field("o2-sensor-8-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255L),
        )),
        0x24 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-1-voltage", "V", 2, 2, 1, 8192, 0, -1, false, null),
        )),
        0x25 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-2-voltage", "V", 2, 2, 1, 8192, 0, -1, false, null),
        )),
        0x26 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-3-voltage", "V", 2, 2, 1, 8192, 0, -1, false, null),
        )),
        0x27 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-4-voltage", "V", 2, 2, 1, 8192, 0, -1, false, null),
        )),
        0x28 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-5-voltage", "V", 2, 2, 1, 8192, 0, -1, false, null),
        )),
        0x29 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-6-voltage", "V", 2, 2, 1, 8192, 0, -1, false, null),
        )),
        0x2A to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-7-voltage", "V", 2, 2, 1, 8192, 0, -1, false, null),
        )),
        0x2B to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-8-voltage", "V", 2, 2, 1, 8192, 0, -1, false, null),
        )),
        0x34 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-1-current", "mA", 2, 2, 1, 256, -128, -1, false, null),
        )),
        0x35 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-2-current", "mA", 2, 2, 1, 256, -128, -1, false, null),
        )),
        0x36 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-3-current", "mA", 2, 2, 1, 256, -128, -1, false, null),
        )),
        0x37 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-4-current", "mA", 2, 2, 1, 256, -128, -1, false, null),
        )),
        0x38 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-5-current", "mA", 2, 2, 1, 256, -128, -1, false, null),
        )),
        0x39 to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-6-current", "mA", 2, 2, 1, 256, -128, -1, false, null),
        )),
        0x3A to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-7-current", "mA", 2, 2, 1, 256, -128, -1, false, null),
        )),
        0x3B to Definition(4,0x00,listOf(
            Field("wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, null),
            Field("wideband-o2-sensor-8-current", "mA", 2, 2, 1, 256, -128, -1, false, null),
        )),
        0x64 to Definition(5,0x00,listOf(
            Field("engine-torque-idle", "%", 0, 1, 1, 1, -125, -1, false, null),
            Field("engine-torque-point-2", "%", 1, 1, 1, 1, -125, -1, false, null),
            Field("engine-torque-point-3", "%", 2, 1, 1, 1, -125, -1, false, null),
            Field("engine-torque-point-4", "%", 3, 1, 1, 1, -125, -1, false, null),
            Field("engine-torque-point-5", "%", 4, 1, 1, 1, -125, -1, false, null),
        )),
        0x66 to Definition(5,0x03,listOf(
            Field("maf-a", "g/s", 1, 2, 1, 32, 0, 0, false, null),
            Field("maf-b", "g/s", 3, 2, 1, 32, 0, 1, false, null),
        )),
        0x67 to Definition(3,0x03,listOf(
            Field("coolant-1", "degC", 1, 1, 1, 1, -40, 0, false, null),
            Field("coolant-2", "degC", 2, 1, 1, 1, -40, 1, false, null),
        )),
        0x68 to Definition(7,0x3F,listOf(
            Field("intake-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, false, null),
            Field("intake-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, false, null),
            Field("intake-temperature-bank-1-sensor-3", "degC", 3, 1, 1, 1, -40, 2, false, null),
            Field("intake-temperature-bank-2-sensor-1", "degC", 4, 1, 1, 1, -40, 3, false, null),
            Field("intake-temperature-bank-2-sensor-2", "degC", 5, 1, 1, 1, -40, 4, false, null),
            Field("intake-temperature-bank-2-sensor-3", "degC", 6, 1, 1, 1, -40, 5, false, null),
        )),
        0x69 to Definition(7,0x3F,listOf(
            Field("egr-a-command", "%", 1, 1, 100, 255, 0, 0, false, null),
            Field("egr-a-actual", "%", 2, 1, 100, 255, 0, 1, false, null),
            Field("egr-a-error", "%", 3, 1, 100, 128, -100, 2, false, null),
            Field("egr-b-command", "%", 4, 1, 100, 255, 0, 3, false, null),
            Field("egr-b-actual", "%", 5, 1, 100, 255, 0, 4, false, null),
            Field("egr-b-error", "%", 6, 1, 100, 128, -100, 5, false, null),
        )),
        0x6A to Definition(5,0x0F,listOf(
            Field("diesel-intake-a-command", "%", 1, 1, 100, 255, 0, 0, false, null),
            Field("diesel-intake-a-actual", "%", 2, 1, 100, 255, 0, 1, false, null),
            Field("diesel-intake-b-command", "%", 3, 1, 100, 255, 0, 2, false, null),
            Field("diesel-intake-b-actual", "%", 4, 1, 100, 255, 0, 3, false, null),
        )),
        0x6C to Definition(5,0x0F,listOf(
            Field("throttle-a-command", "%", 1, 1, 100, 255, 0, 0, false, null),
            Field("throttle-a-actual", "%", 2, 1, 100, 255, 0, 1, false, null),
            Field("throttle-b-command", "%", 3, 1, 100, 255, 0, 2, false, null),
            Field("throttle-b-actual", "%", 4, 1, 100, 255, 0, 3, false, null),
        )),
        0x6D to Definition(11,0x3F,listOf(
            Field("fuel-rail-a-command", "kPa", 1, 2, 10, 1, 0, 0, false, null),
            Field("fuel-rail-a-pressure", "kPa", 3, 2, 10, 1, 0, 1, false, null),
            Field("fuel-rail-a-temperature", "degC", 5, 1, 1, 1, -40, 2, false, null),
            Field("fuel-rail-b-command", "kPa", 6, 2, 10, 1, 0, 3, false, null),
            Field("fuel-rail-b-pressure", "kPa", 8, 2, 10, 1, 0, 4, false, null),
            Field("fuel-rail-b-temperature", "degC", 10, 1, 1, 1, -40, 5, false, null),
        )),
        0x6E to Definition(9,0x0F,listOf(
            Field("injection-pressure-a-command", "kPa", 1, 2, 10, 1, 0, 0, false, null),
            Field("injection-pressure-a-actual", "kPa", 3, 2, 10, 1, 0, 1, false, null),
            Field("injection-pressure-b-command", "kPa", 5, 2, 10, 1, 0, 2, false, null),
            Field("injection-pressure-b-actual", "kPa", 7, 2, 10, 1, 0, 3, false, null),
        )),
        0x70 to Definition(10,0x3F,listOf(
            Field("boost-a-command", "kPa", 1, 2, 1, 32, 0, 0, false, null),
            Field("boost-a-actual", "kPa", 3, 2, 1, 32, 0, 1, false, null),
            Field("boost-b-command", "kPa", 5, 2, 1, 32, 0, 3, false, null),
            Field("boost-b-actual", "kPa", 7, 2, 1, 32, 0, 4, false, null),
        )),
        0x71 to Definition(6,0x3F,listOf(
            Field("vgt-a-command", "%", 1, 1, 100, 255, 0, 0, false, null),
            Field("vgt-a-actual", "%", 2, 1, 100, 255, 0, 1, false, null),
            Field("vgt-b-command", "%", 3, 1, 100, 255, 0, 3, false, null),
            Field("vgt-b-actual", "%", 4, 1, 100, 255, 0, 4, false, null),
        )),
        0x72 to Definition(5,0x0F,listOf(
            Field("wastegate-a-command", "%", 1, 1, 100, 255, 0, 0, false, null),
            Field("wastegate-a-actual", "%", 2, 1, 100, 255, 0, 1, false, null),
            Field("wastegate-b-command", "%", 3, 1, 100, 255, 0, 2, false, null),
            Field("wastegate-b-actual", "%", 4, 1, 100, 255, 0, 3, false, null),
        )),
        0x73 to Definition(5,0x03,listOf(
            Field("exhaust-pressure-bank-1", "kPa", 1, 2, 1, 100, 0, 0, false, null),
            Field("exhaust-pressure-bank-2", "kPa", 3, 2, 1, 100, 0, 1, false, null),
        )),
        0x74 to Definition(5,0x03,listOf(
            Field("turbo-a-rpm", "rpm", 1, 2, 10, 1, 0, 0, false, null),
            Field("turbo-b-rpm", "rpm", 3, 2, 10, 1, 0, 1, false, null),
        )),
        0x75 to Definition(7,0x0F,listOf(
            Field("turbo-a-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, false, null),
            Field("turbo-a-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, false, null),
            Field("turbo-a-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, false, null),
            Field("turbo-a-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, false, null),
        )),
        0x76 to Definition(7,0x0F,listOf(
            Field("turbo-b-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, false, null),
            Field("turbo-b-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, false, null),
            Field("turbo-b-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, false, null),
            Field("turbo-b-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, false, null),
        )),
        0x77 to Definition(5,0x0F,listOf(
            Field("charge-air-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, false, null),
            Field("charge-air-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, false, null),
            Field("charge-air-temperature-bank-2-sensor-1", "degC", 3, 1, 1, 1, -40, 2, false, null),
            Field("charge-air-temperature-bank-2-sensor-2", "degC", 4, 1, 1, 1, -40, 3, false, null),
        )),
        0x78 to Definition(9,0x0F,listOf(
            Field("egt-bank-1-sensor-1", "degC", 1, 2, 1, 10, -40, 0, false, null),
            Field("egt-bank-1-sensor-2", "degC", 3, 2, 1, 10, -40, 1, false, null),
            Field("egt-bank-1-sensor-3", "degC", 5, 2, 1, 10, -40, 2, false, null),
            Field("egt-bank-1-sensor-4", "degC", 7, 2, 1, 10, -40, 3, false, null),
        )),
        0x79 to Definition(9,0x0F,listOf(
            Field("egt-bank-2-sensor-1", "degC", 1, 2, 1, 10, -40, 0, false, null),
            Field("egt-bank-2-sensor-2", "degC", 3, 2, 1, 10, -40, 1, false, null),
            Field("egt-bank-2-sensor-3", "degC", 5, 2, 1, 10, -40, 2, false, null),
            Field("egt-bank-2-sensor-4", "degC", 7, 2, 1, 10, -40, 3, false, null),
        )),
        0x7A to Definition(7,0x07,listOf(
            Field("dpf-bank-1-delta", "kPa", 1, 2, 1, 100, 0, 0, true, null),
            Field("dpf-bank-1-inlet", "kPa", 3, 2, 1, 100, 0, 1, false, null),
            Field("dpf-bank-1-outlet", "kPa", 5, 2, 1, 100, 0, 2, false, null),
        )),
        0x7B to Definition(7,0x07,listOf(
            Field("dpf-bank-2-delta", "kPa", 1, 2, 1, 100, 0, 0, true, null),
            Field("dpf-bank-2-inlet", "kPa", 3, 2, 1, 100, 0, 1, false, null),
            Field("dpf-bank-2-outlet", "kPa", 5, 2, 1, 100, 0, 2, false, null),
        )),
        0x7C to Definition(9,0x0F,listOf(
            Field("dpf-bank-1-inlet-temperature", "degC", 1, 2, 1, 10, -40, 0, false, null),
            Field("dpf-bank-1-outlet-temperature", "degC", 3, 2, 1, 10, -40, 1, false, null),
            Field("dpf-bank-2-inlet-temperature", "degC", 5, 2, 1, 10, -40, 2, false, null),
            Field("dpf-bank-2-outlet-temperature", "degC", 7, 2, 1, 10, -40, 3, false, null),
        )),
        0x7F to Definition(13,0x07,listOf(
            Field("engine-total-runtime", "s", 1, 4, 1, 1, 0, 0, false, null),
            Field("engine-idle-runtime", "s", 5, 4, 1, 1, 0, 1, false, null),
            Field("engine-pto-runtime", "s", 9, 4, 1, 1, 0, 2, false, null),
        )),
        0x98 to Definition(9,0x0F,listOf(
            Field("egt-bank-1-sensor-5", "degC", 1, 2, 1, 10, -40, 0, false, null),
            Field("egt-bank-1-sensor-6", "degC", 3, 2, 1, 10, -40, 1, false, null),
            Field("egt-bank-1-sensor-7", "degC", 5, 2, 1, 10, -40, 2, false, null),
            Field("egt-bank-1-sensor-8", "degC", 7, 2, 1, 10, -40, 3, false, null),
        )),
        0x99 to Definition(9,0x0F,listOf(
            Field("egt-bank-2-sensor-5", "degC", 1, 2, 1, 10, -40, 0, false, null),
            Field("egt-bank-2-sensor-6", "degC", 3, 2, 1, 10, -40, 1, false, null),
            Field("egt-bank-2-sensor-7", "degC", 5, 2, 1, 10, -40, 2, false, null),
            Field("egt-bank-2-sensor-8", "degC", 7, 2, 1, 10, -40, 3, false, null),
        )),
        0x6B to Definition(5,0xFF,listOf(
            Field("egr-temperature-a", "degC", 1, 1, 1, 1, -40, 0, false, null),
            Field("egr-temperature-c", "degC", 2, 1, 1, 1, -40, 1, false, null),
            Field("egr-temperature-b", "degC", 3, 1, 1, 1, -40, 2, false, null),
            Field("egr-temperature-d", "degC", 4, 1, 1, 1, -40, 3, false, null),
        )),
        0x6F to Definition(3,0x0F,listOf(
            Field("compressor-inlet-pressure-a", "kPa", 1, 1, 1, 1, 0, 0, false, null),
            Field("compressor-inlet-pressure-b", "kPa", 2, 1, 1, 1, 0, 1, false, null),
        )),
        0x81 to Definition(41,0x1F,listOf(
            Field("aecd-1-timer-1", "s", 1, 4, 1, 1, 0, 0, false, null),
            Field("aecd-1-timer-2", "s", 5, 4, 1, 1, 0, 0, false, null),
            Field("aecd-2-timer-1", "s", 9, 4, 1, 1, 0, 1, false, null),
            Field("aecd-2-timer-2", "s", 13, 4, 1, 1, 0, 1, false, null),
            Field("aecd-3-timer-1", "s", 17, 4, 1, 1, 0, 2, false, null),
            Field("aecd-3-timer-2", "s", 21, 4, 1, 1, 0, 2, false, null),
            Field("aecd-4-timer-1", "s", 25, 4, 1, 1, 0, 3, false, null),
            Field("aecd-4-timer-2", "s", 29, 4, 1, 1, 0, 3, false, null),
            Field("aecd-5-timer-1", "s", 33, 4, 1, 1, 0, 4, false, null),
            Field("aecd-5-timer-2", "s", 37, 4, 1, 1, 0, 4, false, null),
        )),
        0x82 to Definition(41,0x1F,listOf(
            Field("aecd-6-timer-1", "s", 1, 4, 1, 1, 0, 0, false, null),
            Field("aecd-6-timer-2", "s", 5, 4, 1, 1, 0, 0, false, null),
            Field("aecd-7-timer-1", "s", 9, 4, 1, 1, 0, 1, false, null),
            Field("aecd-7-timer-2", "s", 13, 4, 1, 1, 0, 1, false, null),
            Field("aecd-8-timer-1", "s", 17, 4, 1, 1, 0, 2, false, null),
            Field("aecd-8-timer-2", "s", 21, 4, 1, 1, 0, 2, false, null),
            Field("aecd-9-timer-1", "s", 25, 4, 1, 1, 0, 3, false, null),
            Field("aecd-9-timer-2", "s", 29, 4, 1, 1, 0, 3, false, null),
            Field("aecd-10-timer-1", "s", 33, 4, 1, 1, 0, 4, false, null),
            Field("aecd-10-timer-2", "s", 37, 4, 1, 1, 0, 4, false, null),
        )),
        0x83 to Definition(9,0x0F,listOf(
            Field("nox-bank-1-sensor-1", "ppm", 1, 2, 1, 1, 0, 0, false, null),
            Field("nox-bank-1-sensor-2", "ppm", 3, 2, 1, 1, 0, 1, false, null),
            Field("nox-bank-2-sensor-1", "ppm", 5, 2, 1, 1, 0, 2, false, null),
            Field("nox-bank-2-sensor-2", "ppm", 7, 2, 1, 1, 0, 3, false, null),
        )),
        0x84 to Definition(1,0x00,listOf(
            Field("intake-manifold-surface-temperature", "degC", 0, 1, 1, 1, -40, -1, false, null),
        )),
        0x85 to Definition(10,0x0F,listOf(
            Field("reagent-consumption", "L/h", 1, 2, 1, 200, 0, 0, false, null),
            Field("reagent-demand", "L/h", 3, 2, 1, 200, 0, 1, false, null),
            Field("reagent-level", "%", 5, 1, 100, 255, 0, 2, false, null),
            Field("nox-warning-runtime", "s", 6, 4, 1, 1, 0, 3, false, null),
        )),
        0x87 to Definition(5,0x03,listOf(
            Field("manifold-pressure-a", "kPa", 1, 2, 1, 32, 0, 0, false, null),
            Field("manifold-pressure-b", "kPa", 3, 2, 1, 32, 0, 1, false, null),
        )),
        0x89 to Definition(41,0x1F,listOf(
            Field("aecd-11-timer-1", "s", 1, 4, 1, 1, 0, 0, false, null),
            Field("aecd-11-timer-2", "s", 5, 4, 1, 1, 0, 0, false, null),
            Field("aecd-12-timer-1", "s", 9, 4, 1, 1, 0, 1, false, null),
            Field("aecd-12-timer-2", "s", 13, 4, 1, 1, 0, 1, false, null),
            Field("aecd-13-timer-1", "s", 17, 4, 1, 1, 0, 2, false, null),
            Field("aecd-13-timer-2", "s", 21, 4, 1, 1, 0, 2, false, null),
            Field("aecd-14-timer-1", "s", 25, 4, 1, 1, 0, 3, false, null),
            Field("aecd-14-timer-2", "s", 29, 4, 1, 1, 0, 3, false, null),
            Field("aecd-15-timer-1", "s", 33, 4, 1, 1, 0, 4, false, null),
            Field("aecd-15-timer-2", "s", 37, 4, 1, 1, 0, 4, false, null),
        )),
        0x8A to Definition(41,0x1F,listOf(
            Field("aecd-16-timer-1", "s", 1, 4, 1, 1, 0, 0, false, null),
            Field("aecd-16-timer-2", "s", 5, 4, 1, 1, 0, 0, false, null),
            Field("aecd-17-timer-1", "s", 9, 4, 1, 1, 0, 1, false, null),
            Field("aecd-17-timer-2", "s", 13, 4, 1, 1, 0, 1, false, null),
            Field("aecd-18-timer-1", "s", 17, 4, 1, 1, 0, 2, false, null),
            Field("aecd-18-timer-2", "s", 21, 4, 1, 1, 0, 2, false, null),
            Field("aecd-19-timer-1", "s", 25, 4, 1, 1, 0, 3, false, null),
            Field("aecd-19-timer-2", "s", 29, 4, 1, 1, 0, 3, false, null),
            Field("aecd-20-timer-1", "s", 33, 4, 1, 1, 0, 4, false, null),
            Field("aecd-20-timer-2", "s", 37, 4, 1, 1, 0, 4, false, null),
        )),
        0x8B to Definition(7,0x7F,listOf(
            Field("dpf-regeneration-trigger", "%", 2, 1, 100, 255, 0, 4, false, null),
            Field("dpf-regeneration-interval", "min", 3, 2, 1, 1, 0, 5, false, null),
            Field("dpf-regeneration-distance", "km", 5, 2, 1, 1, 0, 6, false, null),
        )),
        0x8C to Definition(17,0xFF,listOf(
            Field("oxygen-concentration-bank-1-sensor-1", "%", 1, 2, 1526, 1000000, 0, 0, false, null),
            Field("oxygen-concentration-bank-1-sensor-2", "%", 3, 2, 1526, 1000000, 0, 1, false, null),
            Field("oxygen-concentration-bank-2-sensor-1", "%", 5, 2, 1526, 1000000, 0, 2, false, null),
            Field("oxygen-concentration-bank-2-sensor-2", "%", 7, 2, 1526, 1000000, 0, 3, false, null),
            Field("oxygen-lambda-bank-1-sensor-1", "ratio", 9, 2, 122, 1000000, 0, 4, false, null),
            Field("oxygen-lambda-bank-1-sensor-2", "ratio", 11, 2, 122, 1000000, 0, 5, false, null),
            Field("oxygen-lambda-bank-2-sensor-1", "ratio", 13, 2, 122, 1000000, 0, 6, false, null),
            Field("oxygen-lambda-bank-2-sensor-2", "ratio", 15, 2, 122, 1000000, 0, 7, false, null),
        )),
        0x8D to Definition(1,0x00,listOf(
            Field("absolute-throttle-g", "%", 0, 1, 100, 255, 0, -1, false, null),
        )),
        0x8E to Definition(1,0x00,listOf(
            Field("engine-friction-torque", "%", 0, 1, 1, 1, -125, -1, false, null),
        )),
        0x8F to Definition(7,0x0F,listOf(
            Field("pm-sensor-bank-1-output", "%", 2, 2, 1, 100, 0, 1, false, null),
            Field("pm-sensor-bank-2-output", "%", 5, 2, 1, 100, 0, 3, false, null),
        )),
        0x93 to Definition(3,0x01,listOf(
            Field("cumulative-continuous-mi", "h", 1, 2, 1, 1, 0, 0, false, null),
        )),
        0x94 to Definition(12,0x3F,listOf(
            Field("reagent-quality-counter", "h", 2, 2, 1, 1, 0, 1, false, null),
            Field("reagent-consumption-counter", "h", 4, 2, 1, 1, 0, 2, false, null),
            Field("reagent-dosing-counter", "h", 6, 2, 1, 1, 0, 3, false, null),
            Field("egr-valve-counter", "h", 8, 2, 1, 1, 0, 4, false, null),
            Field("nox-monitoring-counter", "h", 10, 2, 1, 1, 0, 5, false, null),
        )),
        0x9C to Definition(17,0xFF,listOf(
            Field("oxygen-concentration-bank-1-sensor-3", "%", 1, 2, 1526, 1000000, 0, 0, false, null),
            Field("oxygen-concentration-bank-1-sensor-4", "%", 3, 2, 1526, 1000000, 0, 1, false, null),
            Field("oxygen-concentration-bank-2-sensor-3", "%", 5, 2, 1526, 1000000, 0, 2, false, null),
            Field("oxygen-concentration-bank-2-sensor-4", "%", 7, 2, 1526, 1000000, 0, 3, false, null),
            Field("oxygen-lambda-bank-1-sensor-3", "ratio", 9, 2, 122, 1000000, 0, 4, false, null),
            Field("oxygen-lambda-bank-1-sensor-4", "ratio", 11, 2, 122, 1000000, 0, 5, false, null),
            Field("oxygen-lambda-bank-2-sensor-3", "ratio", 13, 2, 122, 1000000, 0, 6, false, null),
            Field("oxygen-lambda-bank-2-sensor-4", "ratio", 15, 2, 122, 1000000, 0, 7, false, null),
        )),
    )
    fun width(pid:Int):Int?=definitions[pid]?.width
    fun decodeData(pid:Int,data:List<Int>):List<Signal>? {
        val definition=definitions[pid]?:return null
        if(data.size!=definition.width||data.any { it !in 0..255 })return null
        if(definition.supportMask!=0&&data[0] and definition.supportMask.inv()!=0)return null
        val alternate=when(pid){0x6B->4;0x6F->2;else->0}
        if(alternate!=0 && (data[0] and (data[0] shr alternate) and ((1 shl alternate)-1))!=0)return null
        return buildList {
            for(field in definition.fields){
                var multiplier=field.multiplier
                if(alternate!=0){
                    if(data[0] and (1 shl (field.bit+alternate))!=0)multiplier=if(pid==0x6B)4 else 8
                    else if(data[0] and (1 shl field.bit)==0)continue
                }else if(field.bit>=0&&data[0] and (1 shl field.bit)==0)continue
                var raw=0L
                for(i in field.offset until field.offset+field.width)raw=(raw shl 8) or data[i].toLong()
                if(raw==field.unavailable)continue
                val encoded=if(field.signed&&raw>=(1L shl (field.width*8-1)))
                    raw-(1L shl (field.width*8)) else raw
                add(Signal(pid,field.name,field.unit,encoded.toDouble()*multiplier/field.divisor+field.bias,raw))
            }
        }
    }
    data class Record(val requestHex:String,val signals:List<Signal>)
    fun tryRecord(request:String,response:String?,status:String):Record? {
        if(status !in setOf("RAW","PASSED"))return null
        return decodeResponse(request,response)?.let { Record(request,it) }
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
