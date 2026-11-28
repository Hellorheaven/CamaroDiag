package com.camarodiag.gen5.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeUsbObdScanProjectionTest {
    private val vin="1G1FA1RXXR0123456"
    private fun fixture():JSONObject {
        val vinRaw="490201"+vin.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it.toInt() and 255) }
        fun row(req:String,res:String,status:String="PASSED")=JSONObject().put("request_hex",req)
            .put("response_hex",res).put("status",status)
        val rows=JSONArray()
            .put(row("0100","410008000000").put("supported_page",JSONObject().put("mode",1).put("base",0)
                .put("supported_ids",JSONArray().put(5)).put("has_next_page",false)))
            .put(row("0902",vinRaw))
            .put(row("03","43010123").put("dtc_codes",JSONArray().put("P0123")))
            .put(row("07","7F0711","NEGATIVE"))
            .put(row("0A","4A00").put("dtc_codes",JSONArray()))
            .put(row("0105","41055A").put("measurement",JSONObject().put("mode",1).put("pid",5)
                .put("name","coolant-temperature").put("number",50).put("unit","degC")))
        return JSONObject().put("source","LIVE_USB_CU1").put("protocol","SAE-J1979")
            .put("request_can_id","7E0").put("response_can_id","7E8").put("extended",false)
            .put("device_credential_authenticated",true).put("capture_integrity_verified",true)
            .put("observed_vin",vin).put("declared_vin",vin).put("vin_match","MATCH")
            .put("summary",JSONObject().put("transport_observed",true).put("pid_inventory_complete",true)
                .put("steps",rows.length()).put("end","BUDGET")).put("steps",rows)
    }
    @Test fun singleEcuScanPreservesUnknownDtcAndRawEvidence() {
        val source=fixture()
        val result=NativeUsbObdScanProjection.project(source,vin,0x7E0,0x7E8,false)!!
        assertEquals("7E8",result.physicalCanResponder)
        assertEquals(setOf(5),result.supportedPids)
        assertEquals("50.0",result.liveData.single().value)
        assertEquals(listOf("P0123"),result.confirmedDtcs.map { it.code })
        assertEquals(setOf(7),result.unverifiedDtcModes)
        assertEquals(setOf(3,10),result.verifiedDtcModes)
        assertNotNull(JSONObject(ObdScanJsonExport.encode(result,0)).optJSONObject("nativeUsbEvidence"))
    }
    @Test fun missingContinuationAndUnadvertisedValuesDoNotBecomeVerified() {
        val input=fixture()
        val rows=input.getJSONArray("steps")
        rows.getJSONObject(0).put("response_hex","410008000001")
            .getJSONObject("supported_page").put("has_next_page",true)
        rows.put(JSONObject().put("request_hex","0140").put("response_hex","414080000000")
            .put("status","PASSED").put("supported_page",JSONObject().put("mode",1).put("base",64)
                .put("supported_ids",JSONArray().put(65)).put("has_next_page",false)))
        rows.put(JSONObject().put("request_hex","010C").put("response_hex","410C1AF8")
            .put("status","PASSED").put("measurement",JSONObject().put("mode",1).put("pid",12)
                .put("name","engine-rpm").put("number",1726).put("unit","rpm")))
        input.getJSONObject("summary").put("steps",rows.length())
        val result=NativeUsbObdScanProjection.project(input,vin,0x7E0,0x7E8,false)!!
        assertEquals(setOf(5),result.supportedPids)
        assertEquals(false,result.pidInventoryComplete)
        assertEquals(listOf(5),result.liveData.map { it.pid })
        assertFalse(result.mode01Reads.single { it.pid==12 }.observations.single().eligibleForDecode)
    }
    @Test fun monitorAndIdentificationCannotBypassInventoryViaNativeDisplay() {
        val input=fixture()
        val rows=input.getJSONArray("steps")
        rows.put(JSONObject().put("request_hex","0601").put("status","PASSED")
            .put("response_hex","4601010212340000FFFF")
            .put("monitor_records",JSONArray().put(JSONObject().put("monitor_id",1)
                .put("test_id",1).put("unit_scaling_id",2).put("value_encoded",0x1234)
                .put("minimum_encoded",0).put("maximum_encoded",65535))))
        rows.put(JSONObject().put("request_hex","0906").put("status","PASSED")
            .put("response_hex","49060101020304").put("vehicle_info",
                JSONObject().put("info_type",6).put("values",JSONArray().put("01020304"))))
        input.getJSONObject("summary").put("steps",rows.length())
        val raw=NativeUsbFunctionalEvidence.diagnosticValues(input)!!
        assertEquals(1,raw.monitors.size)
        assertEquals(listOf("01020304"),raw.identity[6])
        val scoped=NativeUsbFunctionalEvidence.scanValues(input)!!
        assertTrue(scoped.monitors.isEmpty())
        assertTrue(scoped.identity.isEmpty())
        val scan=NativeUsbObdScanProjection.project(input,vin,0x7E0,0x7E8,false)!!
        assertTrue(scan.calibrationVerificationNumbers.isEmpty())
        assertTrue(scan.rawLog.any { it.contains("49060101020304") })
    }
    @Test fun duplicatedRequestsCannotPublishScan() {
        val input=fixture()
        val rows=input.getJSONArray("steps")
        rows.put(rows.getJSONObject(0))
        input.getJSONObject("summary").put("steps",rows.length())
        assertNull(NativeUsbObdScanProjection.project(input,vin,0x7E0,0x7E8,false))
    }
    @Test fun malformedUnrelatedObservationInvalidatesOrdinaryScan() {
        val input=fixture()
        val rows=input.getJSONArray("steps")
        rows.put(JSONObject().put("request_hex","010C").put("response_hex","410CFF")
            .put("status","PASSED"))
        input.getJSONObject("summary").put("steps",rows.length())
        assertNull(NativeUsbObdScanProjection.project(input,vin,0x7E0,0x7E8,false))
    }
    @Test fun incompatibleOrCancelledCaptureCannotBecomeScan() {
        val input=fixture()
        assertNull(NativeUsbObdScanProjection.project(input,vin,0x7E1,0x7E9,false))
        assertNull(NativeUsbObdScanProjection.project(input.put("cancelled",true),vin,0x7E0,0x7E8,false))
        input.put("cancelled",false).put("vin_match","MISMATCH")
        assertNull(NativeUsbObdScanProjection.project(input,vin,0x7E0,0x7E8,false))
    }
}
