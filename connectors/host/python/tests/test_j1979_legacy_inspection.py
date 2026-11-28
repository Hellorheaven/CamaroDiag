import copy
import json
import tempfile
import unittest
from pathlib import Path

from camarodiagconnectors.j1979_legacy import scan
from camarodiagconnectors.j1979_legacy_inspection import inspect, inspect_file
from camarodiagconnectors.offline_cli import run
from test_j1979_legacy_source import fixture, frame, VIN

SCHEMA = "camarodiag-functional-j1979-legacy-v1"


def report(protocol=2, expected_vin=VIN, replies=None):
    replies = fixture(protocol) if replies is None else replies
    return {"schema": SCHEMA, **scan(lambda cmd, timeout: replies.get(cmd, "NO DATA"),
                                     lambda: protocol, protocol, source=0x10,
                                     expected_vin=expected_vin)}


def find_step(data, command):
    return next(row for row in data["steps"] if row["request_hex"] == command)


def driver_report():
    source = report()
    rows = []
    for row in source["steps"]:
        responses = [
            {"source": f["source"], "rawHex": f["raw_hex"][:-2],
             "payloadHex": f["payload_hex"], "timestamp": 1}
            for f in row.get("frames", []) if f["source"] == "10"
        ]
        rows.append({"requestHex": row["request_hex"],
                     "txHex": "686AF1" + row["request_hex"],
                     "responses": responses, "status": row["status"],
                     "detail": row["detail"], "durationMs": row["duration_ms"]})
    return {"schema": SCHEMA, "variant": "Vpw", "baud": 10400,
            "selectedResponseSource": "10",
            "result": {"transportObserved": True, "completed": True,
                       "stopReason": None, "selectedResponseSource": "10",
                       "declaredVin": VIN, "observedVin": VIN, "vinMatch": "MATCH",
                       "durationMs": source["duration_ms"], "steps": rows}}


class LegacyInspectionTest(unittest.TestCase):
    def test_five_legacy_variants(self):
        for protocol in range(1,6):
            with self.subTest(protocol=protocol):
                result = inspect(report(protocol), expected_vin=VIN)
                self.assertTrue(result["verified"])
                self.assertTrue(result["completed"])
                self.assertEqual(result["observed_vin"], VIN)
                self.assertEqual(result["other_response_sources"], ["11"])
                self.assertEqual(result["dtc_modes"][0]["codes"], ["P0123"])
                self.assertEqual(result["dtc_modes"][1]["codes"], [])
                self.assertIsNone(result["dtc_modes"][2]["codes"])
                self.assertTrue(result["link_checksum_verified"])
                self.assertFalse(result["physical_vehicle_verified"])
                self.assertFalse(result["physically_addressed"])
                self.assertEqual(result["monitor_records"][0]["value_encoded"], 1)
                self.assertIn("contextual_measurements", result["checked_summary_fields"])

    def test_raw_checksum_and_accepted_record_tampering(self):
        data = report()
        raw = find_step(data, "0105")["raw_response"]
        find_step(data, "0105")["raw_response"] = raw[:-2] + f"{int(raw[-2:],16)^1:02X}"
        with self.assertRaises(ValueError):
            inspect(data)
        data = report()
        find_step(data, "03")["records_hex"] = ["43045600000000"]
        with self.assertRaises(ValueError):
            inspect(data)

    def test_injected_or_missing_requests(self):
        data = report()
        injected = copy.deepcopy(find_step(data, "0105"))
        injected["request_hex"] = "04"
        data["steps"].append(injected)
        with self.assertRaises(ValueError):
            inspect(data)
        data = report()
        data["steps"].remove(find_step(data,"0105"))
        with self.assertRaises(ValueError):
            inspect(data)
        data = report()
        new = copy.deepcopy(find_step(data,"0105"))
        new.update(request_hex="010D", raw_response=frame(2,0x10,"410D40"),
                   records_hex=["410D40"],
                   frames=[{"source":"10","raw_hex":frame(2,0x10,"410D40"),
                            "payload_hex":"410D40"}])
        data["steps"].append(new)
        with self.assertRaises(ValueError):
            inspect(data)

    def test_claimed_measurement_and_inventory_alterations(self):
        for change in ("measurements","dtc_modes","inventory","contextual_measurements",
                       "readiness","advertised_control_tests"):
            with self.subTest(change=change):
                data = report()
                if change == "measurements":
                    data[change][0]["number"] += 1
                elif change == "dtc_modes":
                    data[change][0]["codes"] = ["P0456"]
                elif change == "inventory":
                    data[change][0]["advertised_ids"].append(0xFF)
                elif change == "contextual_measurements":
                    data[change]["configuration"][0]["state"] = "UNVERIFIED"
                elif change == "readiness":
                    data[change]["mil_on"] = True
                else:
                    data[change].append(3)
                with self.assertRaises(ValueError):
                    inspect(data)

    def test_partial_vin_mismatch_is_not_physical_proof(self):
        data = report(expected_vin="1FA6P8TH0L5100000")
        result = inspect(data)
        self.assertFalse(result["completed"])
        self.assertEqual(result["vin_match"],"MISMATCH")
        self.assertEqual(result["stop_reason"],"VIN_MISMATCH")
        self.assertFalse(result["physical_vehicle_verified"])
        self.assertEqual(result["measurements"], [])
        with self.assertRaises(ValueError):
            inspect(data,expected_vin=VIN)

    def test_anonymous_report_and_forged_physical_claim_rejected(self):
        data = report()
        data["selected_response_source"] = None
        with self.assertRaises(ValueError):
            inspect(data)
        data = report()
        data["physical_vehicle_verified"] = True
        with self.assertRaises(ValueError):
            inspect(data)

    def test_j2534_driver_payload_proof_is_not_checksum_proof(self):
        data = driver_report()
        result = inspect(data, expected_vin=VIN)
        self.assertTrue(result["completed"])
        self.assertFalse(result["link_checksum_verified"])
        self.assertEqual(result["dtc_modes"][0]["codes"], ["P0123"])
        step = next(row for row in data["result"]["steps"] if row["requestHex"] == "0105")
        step["responses"][0]["payloadHex"] = "410540"
        with self.assertRaises(ValueError):
            inspect(data)

    def test_android_source_report_camel_case(self):
        data = report()
        data["schema"] = "camarodiag-functional-j1979-legacy-source-v1"
        data["rawProtocolResponse"] = "ATDPN\r2\r>"
        for step in data["steps"]:
            step["acceptedRecords"] = step.pop("records_hex")
        result = inspect(data)
        self.assertEqual(result["vin_match"], "MATCH")
        data["rawProtocolResponse"] = "3"
        with self.assertRaises(ValueError):
            inspect(data)

    def test_cli_writes_new_report_without_changing_input(self):
        with tempfile.TemporaryDirectory() as folder:
            src = Path(folder) / "source.json"
            dst = Path(folder) / "verified.json"
            src.write_text(json.dumps(report()))
            original = src.read_bytes()
            direct = inspect_file(src, expected_vin=VIN)
            self.assertEqual(len(direct["source_sha256"]),64)
            result = run(["verify-legacy","--report",str(src),"--vin",VIN,"--output",str(dst)])
            self.assertEqual(result,direct)
            self.assertEqual(json.loads(dst.read_text()), direct)
            self.assertEqual(src.read_bytes(), original)
            with self.assertRaises(ValueError):
                run(["verify-legacy","--report",str(src),"--output",str(src)])


if __name__ == "__main__":
    unittest.main()
