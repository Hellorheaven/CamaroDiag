package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdJ1979OperatingContextTest {
    private val c=ObdJ1979OperatingContext
    @Test fun trimBanksAndFrameIdentity(){
        for(pid in (6..9).toList()+(0x55..0x58)){
            val bank=if(pid in setOf(6,7,0x55,0x56))1 else 2
            for(mode in 1..2){
                val request=if(mode==1)listOf(mode,pid)else listOf(mode,pid,0)
                val trim=c.decodeTrim(request,listOf(0,255),255)!!
                assertEquals(listOf(bank,bank+2),trim.signals.map { it.bank })
                assertEquals(-100.0,trim.signals[0].number!!,0.0)
                assertEquals(99.21875,trim.signals[1].number!!,0.0)
                assertEquals(true,trim.upperBankVerified)
            }
        }
        assertNull(c.decodeTrim(listOf(2,6,1),listOf(128),255))
        assertNull(c.decodeTrim(listOf(1,6,0),listOf(128),255))
        assertNull(c.decodeTrim(listOf(1,6),listOf(128,144,160),255))
    }
    @Test fun noUpperBankInference(){
        for(layout in listOf(null,0,15)){
            val trim=c.decodeTrim(listOf(1,6),listOf(128,144),layout)!!
            assertEquals(0.0,trim.signals[0].number!!,0.0)
            assertNull(trim.signals[1].number)
            assertEquals(144,trim.signals[1].rawEncoded)
            assertEquals(false,trim.upperBankVerified)
        }
        assertEquals(12.5,c.decodeTrim(listOf(1,6),listOf(128,144),48)!!.signals[1].number!!,0.0)
        assertNull(c.decodeTrim(listOf(1,8),listOf(128,144),48)!!.signals[1].number)
    }
    @Test fun cycleSupportAndEnableAreIndependent(){
        val cycle=c.decodeCycle(listOf(0,17,1,1),listOf(128,7,1,0))!!
        val monitors=cycle.monitors.associateBy { it.name }
        assertEquals(true,monitors.getValue("misfire").enabled)
        assertEquals(false,monitors.getValue("misfire").complete)
        assertEquals(false,monitors.getValue("fuel-system").enabled)
        assertEquals(true,monitors.getValue("fuel-system").complete)
        assertEquals(false,monitors.getValue("heated-catalyst").supported)
        assertNull(monitors.getValue("heated-catalyst").complete)
        assertTrue(cycle.consistent)
        assertFalse(c.decodeCycle(listOf(0,0,2,0),listOf(0,0,1,0))!!.consistent)
        for(data in listOf(listOf(1,0,0,0),listOf(0,128,0,0),listOf(0,8,4,0)))
            assertNull(c.decodeCycle(data,null))
        val unknown=c.decodeCycle(listOf(0,1,1,0),null)!!
        assertFalse(unknown.supportVerified)
        assertTrue(unknown.monitors.all { it.supported==null&&it.complete==null })
    }
    @Test fun layoutAdmissionRequiresAdvertisedAndUniqueResponse(){
        val page=c.Observation("0100","410004000008","PASSED")
        val layout=c.Observation("011D","411D30","PASSED")
        assertEquals(c.Layout("VERIFIED",48),c.resolveLayout(listOf(page,layout)))
        assertEquals(c.Layout("UNVERIFIED",null),c.resolveLayout(listOf(page,layout,layout)))
        assertEquals(c.Layout("UNVERIFIED",null),c.resolveLayout(listOf(page)))
        val absent=page.copy(responseHex="410004000000")
        assertEquals(c.Layout("NOT_ADVERTISED_BY_SELECTED_ECU",null),c.resolveLayout(listOf(absent)))
    }
}
