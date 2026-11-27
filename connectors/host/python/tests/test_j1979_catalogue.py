import copy
import unittest
from fractions import Fraction

from camarodiagconnectors.j1979_catalogue import Catalogue, project
from camarodiagconnectors.j1979_live import raw_sample, Plan


def fixture():
    # Fictional fixture only: no SAE or OEM definition is asserted.
    return {
        "schema": "camarodiag-j1979-pid-catalogue-v1",
        "edition": "synthetic test",
        "reference_name": "synthetic fixture",
        "reference_sha256": "a"*64,
        "definitions": [{
            "pid": 0xF1, "payload_bytes": 3,
            "protocols": ["CAN", "LEGACY"], "reference": "fixture section 1",
            "signals": [{
                "name": "signed sample", "unit": "fixture unit",
                "bits": {"offset": 8, "width": 16, "order": "LE_BYTES"},
                "signed": True, "scale": [1, 3], "offset": [1, 2],
                "reference": "fixture section 1.1",
                "presence": {"bits": {"offset": 0, "width": 8, "order": "MSB0"},
                             "mask": 128, "equals": 128},
                "unavailable": [65535],
                "range": [[-100, 1], [100, 1]],
            }, {
                "name": "state", "unit": "state",
                "bits": {"offset": 6, "width": 2, "order": "MSB0"},
                "signed": False, "scale": [1, 1], "offset": [0, 1],
                "reference": "fixture section 1.2",
                "enumeration": [{"raw": 1, "label": "fixture state"}]
            }]
        }]
    }


def replay():
    return {"schema": "camarodiag-j1979-live-inspection-v1", "verified": True,
            "raw_only": True, "completed": True, "declared_vin": "1G1YY22G965100001",
            "channel": {"kind": "can", "tx": 2016, "rx": 2024},
            "samples": [
                {"cycle": 1, "pid": 0xF1, "elapsed_ms": 10, "status": "RAW",
                 "response_hex": "41F181FEFF"},
                {"cycle": 2, "pid": 0xF1, "elapsed_ms": 20, "status": "RAW",
                 "response_hex": "41F1010000"},
                {"cycle": 3, "pid": 0xF1, "elapsed_ms": 30, "status": "RAW",
                 "response_hex": "41F180FFFF"},
                {"cycle": 4, "pid": 0xF1, "elapsed_ms": 40, "status": "RAW",
                 "response_hex": "41F18000"},
                {"cycle": 5, "pid": 0xF2, "elapsed_ms": 50, "status": "RAW",
                 "response_hex": "41F200"},
                {"cycle": 6, "pid": 0xF1, "elapsed_ms": 60, "status": "TIMEOUT",
                 "response_hex": None},
            ]}


class CatalogueTests(unittest.TestCase):
    def test_projection_exact_and_absence(self):
        output = project(replay(), Catalogue.parse(fixture()))
        rows = output["rows"]
        self.assertEqual(rows[0]["signals"][0]["value"],
                         {"numerator": "-1", "denominator": "6"})
        self.assertEqual(rows[0]["signals"][1]["enumeration"], "fixture state")
        self.assertEqual(rows[1]["signals"][0]["status"], "NOT_PRESENT")
        self.assertEqual(rows[2]["signals"][0]["status"], "UNAVAILABLE")
        self.assertEqual(rows[3]["projection_status"], "DOCUMENTED_WIDTH_MISMATCH")
        self.assertEqual(rows[4]["projection_status"], "NO_DOCUMENTED_DEFINITION")
        self.assertEqual(rows[5]["projection_status"], "NO_CORRELATED_RAW_PAYLOAD")
        self.assertFalse(output["physical_vehicle_verified"])
        self.assertFalse(output["normative_compliance_verified"])
        self.assertFalse(output["permits_programming"])

    def test_reject_unverified_and_numeric(self):
        for key in ("verified", "raw_only"):
            source = replay()
            source[key] = False
            with self.assertRaises(ValueError):
                project(source, Catalogue.parse(fixture()))

    def test_reject_unknown_definition_fields_and_ambiguous_mapping(self):
        for change in (
            lambda row: row.update(pid=0x20),
            lambda row: row.update(payload_bytes=6),
            lambda row: row.update(formula="eval(data)"),
            lambda row: row["signals"][0]["bits"].update(offset=9),
            lambda row: row["signals"][0].update(scale=[1, 0]),
            lambda row: row["signals"][0]["presence"].update(equals=129),
        ):
            value = fixture()
            change(value["definitions"][0])
            with self.assertRaises(ValueError):
                Catalogue.parse(value)
        value = fixture()
        value["definitions"].append(copy.deepcopy(value["definitions"][0]))
        with self.assertRaises(ValueError):
            Catalogue.parse(value)

    def test_raw_envelope_is_not_numeric_definition(self):
        request = bytes.fromhex("01F1")
        self.assertEqual(raw_sample(request, bytes.fromhex("41F100"))["shape"], "RAW")
        self.assertIsNone(raw_sample(request, bytes.fromhex("41F200")))
        self.assertIsNone(raw_sample(request, bytes.fromhex("41F1")))
        self.assertIsNone(raw_sample(request, bytes.fromhex("7F0178")))
        self.assertEqual(raw_sample(request, bytes.fromhex("7F0112"))["shape"], "NEGATIVE")
        self.assertIsNone(raw_sample(request, bytes.fromhex("41F1000000000000"), legacy=True))
        Plan((0xF1,), "1G1YY22G965100001", raw_only=True).validate()
        with self.assertRaises(ValueError):
            Plan((0x20,), "1G1YY22G965100001", raw_only=True).validate()

if __name__ == "__main__":
    unittest.main()
