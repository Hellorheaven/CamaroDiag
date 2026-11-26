package com.camarodiag.gen5.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeUsbFunctionalEvidenceTest {
    private val vin = "2G1FF3D36C9104199"
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
    @Test fun inconsistentCampaignAndWrongVinAreNotPasses() {
        val bad=NativeUsbFunctionalEvidence.results(state,report(false)).checks.associateBy { it.key }
        assertEquals(FunctionalTestOutcome.FAILED,bad["native:integrity"]?.outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED,bad["native:pid"]?.outcome)
        val other=NativeUsbFunctionalEvidence.results(state,report(true,"1FA6P8TH0L5100000")).checks
        assertEquals(FunctionalTestOutcome.FAILED,other.first { it.key=="native:vin" }.outcome)
    }
}
