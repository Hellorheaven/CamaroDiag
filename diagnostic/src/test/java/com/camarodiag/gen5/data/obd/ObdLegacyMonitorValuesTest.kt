package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdLegacyMonitorValuesTest {
    @Test fun standardOxygenTestTypes() {
        for(tid in 1..10) {
            val id="%02X".format(tid)
            val row=ObdLegacyMonitorValues.decode("05"+id+"01",
                "45"+id+"015A"+if(tid>4)"0A64" else "")!!
            val factor=when(tid){5,6->0.004;9,10->0.04;else->0.005}
            val scaled=row.scaled!!
            assertEquals(90*factor,scaled.value,1e-9)
            assertEquals(if(tid>4)"WITHIN" else "NOT_APPLICABLE",scaled.numericRelation)
            if(tid<=4)assertNull(scaled.maximum)
            else assertEquals(100*factor,scaled.maximum!!,1e-9)
        }
    }
    @Test fun unknownAndMalformedRecords() {
        assertNull(ObdLegacyMonitorValues.decode("057001","457001123456")!!.scaled)
        for((request,response) in listOf("050101" to "4501012A0001",
            "050501" to "4505012A","050103" to "4501032A","050101" to "4501022A",
            "050001" to "45000180000000","0601" to "46010124000100000002"))
            assertNull(ObdLegacyMonitorValues.decode(request,response))
    }
    @Test fun legacy06PreservesComponentAndLimitType() {
        val minimum=ObdLegacyMonitorValues.decode("0601","46018100010002")!!
        assertEquals(1,minimum.componentId)
        assertEquals("MINIMUM",minimum.limitType)
        assertEquals("BELOW",minimum.encodedRelation)
        assertNull(minimum.scaled)
        assertEquals("MAXIMUM",ObdLegacyMonitorValues.decode("0601","46010100010002")!!.limitType)
    }
    @Test fun mixedResponsesCannotProjectPartialRecordGroup() {
        val step=ObdLegacyMonitorValues.Observation("050501","PASSED",
            listOf("450501320064","450501330064"))
        assertEquals(listOf(0,1),ObdLegacyMonitorValues.project(listOf(step)).map{it.recordIndex})
        assertTrue(ObdLegacyMonitorValues.project(listOf(step.copy(status="FAILED"))).isEmpty())
        assertTrue(ObdLegacyMonitorValues.project(listOf(step.copy(
            acceptedRecords=step.acceptedRecords+"450501"))).isEmpty())
    }
}
