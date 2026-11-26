package com.camarodiag.gen5.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeUsbFunctionalEvidenceTest {
    private val vin = "1G1FA1RXXR0123456"
    private val state = FunctionalTestState(context=FunctionalTestContext(vin,"USB","CU1",1))
    private fun report(valid: Boolean, observed: String = vin) = JSONObject()
        .put("device_credential_authenticated",true).put("capture_integrity_verified",valid)
        .put("observed_vin",observed)
        .put("summary",JSONObject().put("transport_observed",true).put("pid_inventory_complete",true))
        .put("steps",JSONArray().put(JSONObject().put("request_hex","03").put("status","PASSED")))
    @Test fun explicitDtcEvidenceDoesNotUpgradeMissingModesOrPhysicalProof() {
        val rows=NativeUsbFunctionalEvidence.results(state,report(true)).checks.associateBy { it.key }
        assertEquals(FunctionalTestOutcome.PASSED,rows["native:dtc3"]?.outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED,rows["native:dtc7"]?.outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED,rows["native:physical"]?.outcome)
    }
    @Test fun valuesDisplayOnlyCoherentPositiveMeasurementsAndKeepUnknownDtcModes() {
        val input=report(true)
        val rows=input.getJSONArray("steps")
        rows.getJSONObject(0).put("dtc_codes",JSONArray())
        rows.put(JSONObject().put("request_hex","010C").put("status","PASSED")
            .put("measurement",JSONObject().put("mode",1).put("pid",12).put("name","engine-rpm")
                .put("number",1726).put("unit","rpm")))
        rows.put(JSONObject().put("request_hex","020500").put("status","TIMEOUT")
            .put("measurement",JSONObject().put("mode",2).put("pid",5).put("name","coolant")
                .put("number",50).put("unit","degC")))
        val values=NativeUsbFunctionalEvidence.diagnosticValues(input)!!
        assertEquals(emptyList<String>(),values.dtcs[3])
        assertNull(values.dtcs[7])
        assertEquals(1,values.measurements.size)
        assertEquals(1726.0,values.measurements.single().number,0.0)
        assertNull(NativeUsbFunctionalEvidence.diagnosticValues(input.put("capture_integrity_verified",false)))
    }
    @Test fun attemptedNegativeIsNotUntestedAndCancellationCannotPublishValues() {
        val input=report(true)
        input.getJSONArray("steps").put(JSONObject().put("request_hex","07").put("status","NEGATIVE"))
        val checks=NativeUsbFunctionalEvidence.results(state,input).checks.associateBy { it.key }
        assertEquals(FunctionalTestOutcome.FAILED,checks["native:dtc7"]?.outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED,checks["native:dtca"]?.outcome)
        input.put("cancelled",true)
        assertNull(NativeUsbFunctionalEvidence.diagnosticValues(input))
        val cancelled=NativeUsbFunctionalEvidence.results(state,input)
        assertNull(cancelled.context?.observedVin)
        assertEquals(FunctionalTestOutcome.NOT_TESTED,cancelled.checks.first { it.key=="native:dtc3" }.outcome)
    }
    @Test fun monitorRowsMustMatchPositiveRawReply() {
        val input=report(true)
        val monitor=JSONObject().put("monitor_id",1).put("test_id",1).put("unit_scaling_id",2)
            .put("value_encoded",0x1234).put("minimum_encoded",0).put("maximum_encoded",65535)
        input.getJSONArray("steps").put(JSONObject().put("request_hex","0601").put("status","PASSED")
            .put("response_hex","4601010212340000FFFF").put("monitor_records",JSONArray().put(monitor)))
        assertEquals(0x1234,NativeUsbFunctionalEvidence.diagnosticValues(input)!!.monitors.single().value)
        monitor.put("value_encoded",0x5678)
        assertTrue(NativeUsbFunctionalEvidence.diagnosticValues(input)!!.monitors.isEmpty())
    }
    @Test fun identityValuesMustAgreeWithRawMode09Record() {
        val input=report(true)
        val info=JSONObject().put("info_type",6).put("values",JSONArray().put("01020304").put("FFFFFFFF"))
        input.getJSONArray("steps").put(JSONObject().put("request_hex","0906").put("status","PASSED")
            .put("response_hex","49060201020304FFFFFFFF").put("vehicle_info",info))
        assertEquals(listOf("01020304","FFFFFFFF"),NativeUsbFunctionalEvidence.diagnosticValues(input)!!.identity[6])
        info.put("values",JSONArray().put("00000000").put("FFFFFFFF"))
        assertTrue(NativeUsbFunctionalEvidence.diagnosticValues(input)!!.identity.isEmpty())
    }
    @Test fun inconsistentCampaignAndWrongVinAreNotPasses() {
        val bad=NativeUsbFunctionalEvidence.results(state,report(false)).checks.associateBy { it.key }
        assertEquals(FunctionalTestOutcome.FAILED,bad["native:integrity"]?.outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED,bad["native:pid"]?.outcome)
        val other=NativeUsbFunctionalEvidence.results(state,report(true,"1FA6P8TH0L5100000")).checks
        assertEquals(FunctionalTestOutcome.FAILED,other.first { it.key=="native:vin" }.outcome)
    }
}
