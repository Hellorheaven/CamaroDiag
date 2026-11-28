import copy
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from fractions import Fraction

from camarodiagconnectors.j1979_catalogue import Catalogue, project
from camarodiagconnectors.j1979_live import raw_sample, Plan, record
from camarodiagconnectors.j1979_catalogue_cli import main, build_projection
from camarodiagconnectors.j1979_live_inspection import inspect
from types import SimpleNamespace


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

class CrossHostCatalogueTests(unittest.TestCase):
    """Synthetic fixtures exercise the independent verifier, not vehicle conformance."""

    VIN = "1G1YY22G965100001"

    def original(self):
        now = 0.0
        reads = 0

        def sleep(seconds):
            nonlocal now
            now += seconds

        def exchange(request, timeout):
            nonlocal now, reads
            now += 0.01
            if request == b"\x09\x02":
                return b"\x49\x02\x01" + self.VIN.encode("ascii")
            if request[0] == 1 and request[1] % 32 == 0:
                return bytes((0x41, request[1])) + (
                    bytes.fromhex("0000C000") if request[1] == 0xE0 else bytes.fromhex("00000001"))
            if request == b"\x01\xf1":
                data = ("41F181FEFF", "41F1010000", "41F180FFFF", "41F18000")[reads]
                reads += 1
                return bytes.fromhex(data)
            if request == b"\x01\xf2":
                return bytes.fromhex("41F200")
            raise AssertionError("Unexpected synthetic request")

        result = record(exchange, Plan((0xF1, 0xF2), self.VIN, 4, .1, 30, True),
                        clock=lambda: now, sleep=sleep)
        self.assertTrue(result["completed"])
        return {"schema": "camarodiag-j1979-live-v1", "tx_can_id": "7E0",
                "rx_can_id": "7E8", **result}

    def test_local_report_replayed_and_every_field_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            reference = folder / "reference.txt"
            reference.write_bytes(b"synthetic regression reference; not SAE data")
            pin = hashlib.sha256(reference.read_bytes()).hexdigest()
            definition = fixture()
            definition["reference_sha256"] = pin
            catalogue = folder / "catalogue.json"
            catalogue.write_text(json.dumps(definition), encoding="utf-8")
            report = folder / "raw.json"
            report.write_text(json.dumps(self.original()), encoding="utf-8")
            output = folder / "projection.json"
            args = SimpleNamespace(catalogue=catalogue, reference=reference, reference_sha256=pin,
                                   report=report, vin=self.VIN)
            projection = build_projection(args, local=True)
            self.assertEqual(projection["schema"], "camarodiag-j1979-catalogue-local-projection-v1")
            self.assertFalse(projection["source_replay_verified"])
            self.assertTrue(projection["source_application_replay_verified"])
            self.assertTrue(projection["final_identity_verified"])
            self.assertEqual(projection["rows"][0]["signals"][0]["value"],
                             {"numerator": "-1", "denominator": "6"})
            self.assertEqual(projection["counts"], {
                "PROJECTED": 3, "NO_DOCUMENTED_DEFINITION": 4,
                "DOCUMENTED_WIDTH_MISMATCH": 1})
            command = ["verify-local-projection", "--catalogue", str(catalogue),
                       "--reference", str(reference), "--reference-sha256", pin,
                       "--report", str(report), "--vin", self.VIN, "--projection", str(output)]
            output.write_text(json.dumps(projection), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()) as stream:
                self.assertEqual(main(command), 0)
            self.assertTrue(json.loads(stream.getvalue())["independent_source_replay_performed"])
            for change in (
                lambda x: x.update(source_replay_verified=True),
                lambda x: x.update(physical_vehicle_verified=True),
                lambda x: x.update(final_identity_verified=False),
                lambda x: x["rows"][0]["signals"][0]["value"].update(numerator="0"),
                lambda x: x["channel"].update(rx=2025),
                lambda x: x["counts"].update(PROJECTED=4),
                lambda x: x.update(source_report_sha256="f"*64),
            ):
                altered = copy.deepcopy(projection)
                change(altered)
                output.write_text(json.dumps(altered), encoding="utf-8")
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(main(command), 2)
            output.write_text(json.dumps(projection), encoding="utf-8")
            reference.write_bytes(b"changed")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(main(command), 2)

    def test_source_replay_rejects_ambiguous_report_wrappers_and_aliases(self):
        original = self.original()
        self.assertTrue(inspect(original, expected_vin=self.VIN)["completed"])
        wrapped = {"schema": "functional-test", "live": original}
        self.assertTrue(inspect(wrapped, expected_vin=self.VIN)["completed"])
        with self.assertRaises(ValueError):
            inspect({**wrapped, "liveReport": original}, expected_vin=self.VIN)
        with self.assertRaises(ValueError):
            inspect({**original, "rawOnly": True}, expected_vin=self.VIN)
        modified = copy.deepcopy(original)
        modified["observations"][0]["responseHex"] = modified["observations"][0]["response_hex"]
        with self.assertRaises(ValueError):
            inspect(modified, expected_vin=self.VIN)

    def test_wrapper_cancellation_not_promoted_to_completed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "record.json"
            original = self.original()
            wrapper = {"schema": original["schema"], "tx_can_id": "7E0", "rx_can_id": "7E8",
                       "completed": False, "result": original}
            path.write_text(json.dumps(wrapper), encoding="utf-8")
            from camarodiagconnectors.j1979_catalogue_cli import replay_file
            replay, sha = replay_file(path, self.VIN)
            self.assertFalse(replay["completed"])
            self.assertTrue(replay["final_identity_verified"])
            self.assertEqual(sha, hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
