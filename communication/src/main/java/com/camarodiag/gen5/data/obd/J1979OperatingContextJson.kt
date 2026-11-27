package com.camarodiag.gen5.data.obd

import org.json.JSONArray
import org.json.JSONObject

/** Stable cross-platform operating-context projection. Nullable fields
 * distinguish unsupported or unverified observations from false and zero. */
object J1979OperatingContextJson {
    private fun n(value:Any?):Any=value?:JSONObject.NULL
    fun encode(p:ObdJ1979OperatingContext.Projection):JSONObject=JSONObject()
        .put("drive_cycle",p.driveCycle?.let { c ->
            JSONObject().put("request_hex",c.requestHex).put("raw_hex",c.rawHex)
                .put("baseline_raw_hex",n(c.baselineRawHex)).put("support_verified",c.supportVerified)
                .put("compression_ignition",c.compressionIgnition).put("consistent",c.consistent)
                .put("monitors",JSONArray(c.monitors.map { m ->
                    JSONObject().put("name",m.name).put("supported",n(m.supported))
                        .put("enabled",n(m.enabled)).put("complete",n(m.complete))
                        .put("enabled_bit",m.enabledBit).put("incomplete_bit",m.incompleteBit)
                        .put("consistent",m.consistent)
                }))
        }?:JSONObject.NULL)
        .put("fuel_trims",JSONArray(p.fuelTrims.map { t ->
            JSONObject().put("request_hex",t.requestHex).put("frame",n(t.frame))
                .put("layout_1d",n(t.layout1d)).put("upper_bank_present",n(t.upperBankPresent))
                .put("signals",JSONArray(t.signals.map { s ->
                    JSONObject().put("bank",s.bank).put("name",s.name).put("raw_encoded",s.rawEncoded)
                        .put("number",n(s.number)).put("unit",s.unit).put("attribution",s.attribution)
                }))
        }))
}
