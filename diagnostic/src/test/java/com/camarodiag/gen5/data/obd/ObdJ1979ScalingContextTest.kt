package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdJ1979ScalingContextTest {
    private fun row(request:String,response:String?,status:String="PASSED") =
        ObdJ1979ScalingContext.Observation(request,response,status)

    private val inventory=listOf(
        row("0100","410000210001"),
        row("0120","412010001001"),
        row("0140","414010030000")
    )
    private val configuration=listOf(row("014F","414F0410004D","RAW"),
        row("0150","415064000000","RAW"))

    @Test fun customRanges() {
        val context=ObdJ1979ScalingContext.build(inventory+configuration)
        assertEquals("ECU_CONFIGURATION",context.configuration(0x4F).state)
        assertEquals("ECU_CONFIGURATION",context.configuration(0x50).state)
        fun value(req:String,res:String,index:Int=0) =
            context.decode(req,res)!!.signals[index].number!!
        assertEquals(770.0,value("010B","410BFF"),1e-9)
        assertEquals(1000.0,value("0110","4110FFFF"),1e-9)
        assertEquals(4.0,value("0144","4144FFFF"),1e-9)
        assertEquals(16.0,value("0124","4124FFFFFFFF",1),1e-9)
        assertEquals(0.0,value("0134","4134FFFF8000",1),1e-9)
        assertEquals(770.0,value("020B00","420B00FF"),1e-9)
    }

    @Test fun customCurrentUsesTwosComplement() {
        val context=ObdJ1979ScalingContext.build(inventory+row("014F","414F0400404D","RAW"))
        for ((encoded, expected) in listOf("0000" to 0.0, "7FFF" to 63.998046875,
            "8000" to -64.0, "9C40" to -49.875, "FFFF" to -0.001953125)) {
            val signal=context.decode("0134","41347D00"+encoded)!!.signals[1]
            assertEquals(expected,signal.number!!,1e-9)
            assertEquals("ECU_CONFIGURATION",signal.scaleSource)
        }
    }

    @Test fun missingSupportIsNotUnsupported() {
        val context=ObdJ1979ScalingContext.build(inventory.take(1))
        assertEquals("UNVERIFIED",context.configuration(0x50).state)
        assertNull(context.decode("0110","41101234")!!.signals.single().number)
    }

    @Test fun verifiedTerminatingPageUsesDefault() {
        val context=ObdJ1979ScalingContext.build(listOf(row("0100","410000210000")))
        assertEquals("DEFAULT_UNSUPPORTED",context.configuration(0x50).state)
        assertEquals(46.6,context.decode("0110","41101234")!!.signals.single().number!!,1e-9)
    }

    @Test fun duplicatesAndMalformedResponsesAreNotConfiguration() {
        val context=ObdJ1979ScalingContext.build(inventory+configuration+
            row("014F","414F0410004D","RAW"))
        assertEquals("UNVERIFIED",context.configuration(0x4F).state)
        val invalid=ObdJ1979ScalingContext.build(inventory+
            row("0150","415064010000","RAW"))
        assertEquals("UNVERIFIED",invalid.configuration(0x50).state)
    }

    @Test fun frameAndServiceMustMatch() {
        val context=ObdJ1979ScalingContext.build(inventory+configuration)
        assertNull(context.decode("020B00","420B01FF"))
        assertNull(context.decode("010B","410CFF"))
        assertNull(context.decode("010B","410BFFFF"))
        assertNull(context.decode("020B01","420B01FF"))
    }

    @Test fun projectionKeepsOnlyObservedSamples() {
        val projection=ObdJ1979ScalingContext.project(inventory+configuration+
            row("010B","410BFF")+row("0110","4110FFFF","FAILED"))
        assertEquals(1,projection.records.size)
        assertEquals(770.0,projection.records.single().signals.single().number!!,1e-9)
    }
}
