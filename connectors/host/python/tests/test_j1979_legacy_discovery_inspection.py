import copy
import json
import tempfile
from pathlib import Path
import unittest

from camarodiagconnectors.j1979_legacy_discovery import discover
from camarodiagconnectors.j1979_legacy_discovery_inspection import inspect, bind_scan, SCHEMA
from camarodiagconnectors.j1979_legacy import scan
from camarodiagconnectors.offline_cli import run
from test_j1979_legacy_source import frame, fixture, VIN


def evidence(protocol=2,raw=None,observed=None):
    raw=fixture(protocol)["0100"] if raw is None else raw
    observed=protocol if observed is None else observed
    return {"schema":SCHEMA,**discover(lambda *_:raw,lambda:observed,protocol)}


def selected_scan(protocol=2):
    replies=fixture(protocol)
    return {"schema":"camarodiag-functional-j1979-legacy-v1",
            **scan(lambda req,_:replies.get(req,"NO DATA"),lambda:protocol,protocol,
                   source=0x10,expected_vin=VIN)}


class LegacyDiscoveryInspectionTest(unittest.TestCase):
    def test_five_transports_and_conflicting_sources(self):
        for protocol in range(1,6):
            raw="\n".join([frame(protocol,16,"410080000001"),
                           frame(protocol,17,"410040000000"),
                           frame(protocol,17,"410020000000"),
                           frame(protocol,18,"7F0111")])
            result=inspect(evidence(protocol,raw))
            self.assertEqual(result["eligible_sources"],["10"])
            self.assertEqual([r["status"] for r in result["responders"]],
                             ["PASSED","FAILED","NOT_TESTED"])
            self.assertEqual(result["responders"][0]["supported_pids"],[1])
            self.assertTrue(result["responders"][0]["has_next_page"])
            self.assertTrue(result["link_checksum_verified"])
            self.assertFalse(result["automatic_selection_permitted"])

    def test_mismatched_protocol_and_invalid_checksum(self):
        result=inspect(evidence(observed=3))
        self.assertFalse(result["completed"])
        self.assertEqual(result["eligible_sources"],[])
        raw=frame(2,16,"410080000000")
        corrupt=raw[:-2]+f"{int(raw[-2:],16)^1:02X}"
        result=inspect(evidence(raw=corrupt))
        self.assertFalse(result["completed"])
        self.assertEqual(result["eligible_sources"],[])

    def test_claimed_discovery_cannot_override_frames(self):
        for case in ("bitmap","source","frames","complete","coverage"):
            with self.subTest(case=case):
                report=evidence()
                if case=="bitmap":
                    report["responders"][0]["supported_pids"]=[255]
                elif case=="source":
                    report["responders"][0]["source"]="12"
                elif case=="frames":
                    report["steps"][0]["frames"].pop()
                elif case=="complete":
                    report["completed"]=False
                else:
                    report["whole_vehicle_coverage_verified"]=True
                with self.assertRaises(ValueError):
                    inspect(report)

    def test_android_discovery_with_fixed_protocol(self):
        original=evidence()
        rows=[]
        for responder in original["responders"]:
            rows.append({"source":responder["source"],"status":responder["status"],
                         "supportedPidBitmap":responder["frames"][0]["payload_hex"][4:],
                         "rawFrames":[f["raw_hex"] for f in responder["frames"]]})
        report={"schema":"camarodiag-functional-test-v1","legacySourceDiscovery":{
            "selectedProtocol":2,"observedProtocol":2,"rawResponse":original["steps"][0]["raw_response"],
            "rawProtocolResponse":"ATDPN\r2\r>","error":None,
            "verifiedSources":["10","11"],"sources":rows}}
        result=inspect(report)
        self.assertTrue(result["protocol_probe_independently_verified"])
        self.assertEqual(result["eligible_sources"],["10","11"])
        report["legacySourceDiscovery"]["rawProtocolResponse"]="A2"
        with self.assertRaises(ValueError):
            inspect(report)

    def test_driver_discovery_has_no_checksum_claim(self):
        raw=frame(2,16,"410080000000")[:-2]
        report={"schema":SCHEMA,"variant":"Vpw","baud":10400,
                "selectedResponseSource":None,"discovery":{
                "requestHex":"0100","txHex":"686AF10100","hasVerifiedSource":True,
                "sources":[{"responseSource":"10","status":"PASSED","detail":"80000000",
                            "records":[{"source":"10","rawHex":raw,"payloadHex":"410080000000",
                                        "timestamp":12}]}]}}
        result=inspect(report)
        self.assertEqual(result["eligible_sources"],["10"])
        self.assertFalse(result["link_checksum_verified"])
        report["discovery"]["sources"][0]["records"][0]["payloadHex"]="410000000000"
        with self.assertRaises(ValueError):
            inspect(report)

    def test_correlate_discovery_to_selected_scan(self):
        report=selected_scan()
        result=bind_scan(evidence(),report)
        self.assertTrue(result["bitmap_unchanged"])
        self.assertEqual(result["observed_vin"],VIN)
        self.assertFalse(result["current_authorization_verified"])
        changed=evidence(raw=frame(2,16,"410080000000"))
        self.assertFalse(bind_scan(changed,report)["bitmap_unchanged"])
        with self.assertRaises(ValueError):
            bind_scan(evidence(raw=frame(2,17,"410080000000")),report)

    def test_cli_reports_and_output_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            d,s,out=p/"discovery.json",p/"scan.json",p/"output.json"
            d.write_text(json.dumps(evidence()))
            s.write_text(json.dumps(selected_scan()))
            result=run(["compare-legacy-discovery-scan","--discovery",str(d),"--scan",str(s),
                        "--output",str(out)])
            self.assertTrue(result["bitmap_unchanged"])
            self.assertEqual(result,json.loads(out.read_text()))
            with self.assertRaises(ValueError):
                run(["verify-legacy-discovery","--report",str(d),"--output",str(d)])


if __name__=="__main__":
    unittest.main()
