"""Synthetic definitions only: test projection, not SAE formula conformance."""
import copy
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from camarodiagconnectors.j1979_catalogue_cli import main
from camarodiagconnectors.j1979_freeze import Read
from camarodiagconnectors.j1979_freeze_catalogue import FreezeCatalogue, project_freeze, SCHEMA
from camarodiagconnectors.j1979_freeze_native import decode
from test_j1979_freeze_native import fixture, response, VIN


def definition(pid, width, offset):
    return {"pid": pid, "payload_bytes": width, "protocols": ["CAN"], "reference": "synthetic",
            "signals": [{"name": "synthetic measurement", "unit": "fixture", "bits": {
                "offset": 0, "width": width * 8, "order": "MSB0"},
                "signed": False, "scale": [1, 2], "offset": [offset, 1], "reference": "test only"}]}


def catalogue(pin="a"*64):
    return {"schema": SCHEMA, "edition": "synthetic", "reference_name": "synthetic",
            "reference_sha256": pin, "frames": [
                {"frame": 0, "definitions": [definition(12, 2, 0), definition(13, 1, 0)]},
                {"frame": 1, "definitions": [definition(12, 2, 100)]}]}


def capture(replacement=None):
    raw = b"".join(fixture(replacement=replacement)[0])
    value = decode(io.BytesIO(raw))
    value["capture_sha256"] = hashlib.sha256(raw).hexdigest()
    return raw, value


class FreezeCatalogueTests(unittest.TestCase):
    def test_same_pid_distinct_frames(self):
        _, source = capture()
        result = project_freeze(source, FreezeCatalogue.parse(catalogue()), "b"*64, VIN)
        self.assertTrue(result["final_identity_verified"])
        self.assertFalse(result["physical_vehicle_verified"])
        self.assertFalse(result["normative_compliance_verified"])
        self.assertFalse(result["permits_programming"])
        rows = {(r["frame"], r["pid"]): r for r in result["rows"]}
        self.assertEqual(rows[(0,12)]["signals"][0]["value"], {"numerator":"4000","denominator":"1"})
        self.assertEqual(rows[(1,12)]["signals"][0]["value"], {"numerator":"4100","denominator":"1"})
        self.assertEqual(rows[(1,13)]["projection_status"], "NO_DOCUMENTED_DEFINITION")
        self.assertEqual(result["counts"], {"PROJECTED":3,"NO_DOCUMENTED_DEFINITION":1})

    def test_changed_trigger_suppresses_interpretation(self):
        triggers = 0
        def replacement(request, index):
            nonlocal triggers
            payload = response(request)
            if request == bytes((2,2,0)):
                triggers += 1
                if triggers == 2:
                    payload = bytes.fromhex("4202000302")
            return Read("RECEIVED",payload,5,True)
        _, source = capture(replacement)
        result = project_freeze(source, FreezeCatalogue.parse(catalogue()), "b"*64, VIN)
        self.assertTrue(source["capture_integrity_verified"])
        self.assertFalse(result["frames"][0]["trigger_stable"])
        self.assertTrue(all(r["projection_status"] == "TRIGGER_NOT_STABLE" and not r["signals"]
                            for r in result["rows"] if r["frame"] == 0))

    def test_final_vin_mismatch_suppresses_all_interpretation(self):
        reads = 0
        def replacement(request, index):
            nonlocal reads
            payload = response(request)
            if request == bytes((9,2)):
                reads += 1
                if reads == 2:
                    payload = bytes.fromhex("490201") + b"1G1FA1E30D7654321"
            return Read("RECEIVED",payload,5,True)
        _, source = capture(replacement)
        result = project_freeze(source, FreezeCatalogue.parse(catalogue()), "b"*64, VIN)
        self.assertFalse(result["final_identity_verified"])
        self.assertTrue(result["rows"])
        self.assertTrue(all(r["projection_status"] == "IDENTITY_NOT_RECHECKED"
                            and not r["signals"] for r in result["rows"]))

    def test_wrong_width_and_unverified(self):
        value = catalogue()
        value["frames"][0]["definitions"][0] = definition(12,1,0)
        _, source = capture()
        result = project_freeze(source, FreezeCatalogue.parse(value), "b"*64, VIN)
        self.assertEqual(result["rows"][0]["projection_status"], "DOCUMENTED_WIDTH_MISMATCH")
        with self.assertRaises(ValueError):
            project_freeze(source, FreezeCatalogue.parse(value), "b"*64, "1G1FA1E30D7654321")
        source["capture_integrity_verified"] = False
        with self.assertRaises(ValueError):
            project_freeze(source, FreezeCatalogue.parse(value), "b"*64, VIN)

    def test_schema_and_frame_limits(self):
        for edit in (
            lambda c: c.update(schema="camarodiag-j1979-pid-catalogue-v1"),
            lambda c: c["frames"].append(copy.deepcopy(c["frames"][0])),
            lambda c: c["frames"][0]["definitions"].append(definition(12,2,0)),
            lambda c: c["frames"][0]["definitions"][0].update(pid=2),
            lambda c: c["frames"][0]["definitions"][0].update(protocols=["LEGACY"]),
            lambda c: c["frames"][0].update(frame=256),
        ):
            value = catalogue()
            edit(value)
            with self.assertRaises(ValueError):
                FreezeCatalogue.parse(value)

    def test_cli_projection_and_independent_verification(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            ref, cat, source, output = (root/x for x in ("ref","cat.json","source.cf","projection.json"))
            ref.write_bytes(b"synthetic fixture")
            pin = hashlib.sha256(ref.read_bytes()).hexdigest()
            cat.write_text(json.dumps(catalogue(pin)))
            source.write_bytes(capture()[0])
            args = ["--catalogue", str(cat), "--reference", str(ref), "--reference-sha256",pin,
                    "--capture",str(source),"--vin",VIN]
            self.assertEqual(main(["project-native-freeze",*args,"--output",str(output)]),0)
            verify = ["verify-native-freeze-projection",*args,"--projection",str(output)]
            self.assertEqual(main(verify),0)
            changed = json.loads(output.read_text())
            changed["rows"][0]["signals"][0]["value"]["numerator"] = "0"
            output.write_text(json.dumps(changed))
            self.assertNotEqual(main(verify),0)
            self.assertNotEqual(main(["project-native-freeze",*args,"--output",str(output)]),0)
            source.write_bytes(source.read_bytes()[:-1])
            self.assertNotEqual(main(["project-native-freeze",*args,"--output",str(root/"bad.json")]),0)


if __name__ == "__main__":
    unittest.main()
