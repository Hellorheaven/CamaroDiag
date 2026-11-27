import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from camarodiagconnectors.j1979_campaign_evidence import decode, vin_match
from camarodiagconnectors.j1979_capture_inspection import (
    WINDOWS_SCHEMA, verify, verify_files,
)
from camarodiagconnectors.offline_cli import run
from test_j1979_campaign_evidence import CAPTURE, VIN


def python_report(raw=CAPTURE, *, live=False):
    report = decode(io.BytesIO(raw))
    report.update(source="LIVE_USB_CU1" if live else "OFFLINE_CAPTURE",
                  declared_vin=VIN.decode(), hardware_provenance_verified=False,
                  device_credential_authenticated=live, cancelled=False,
                  evidence_authenticated=False)
    report["vin_match"] = vin_match(report, VIN.decode(), require_device_auth=live)
    return report


def windows_report(raw=CAPTURE):
    report = python_report(raw, live=True)
    report["schema"] = WINDOWS_SCHEMA
    report["frame_integrity_verified"] = report["capture_integrity_verified"]
    if report["summary"]:
        del report["summary"]["counts"]
    return report


class CaptureInspectionTests(unittest.TestCase):
    def test_python_and_windows_reports_replayed(self):
        for report in (python_report(), python_report(live=True), windows_report()):
            with self.subTest(schema=report["schema"], source=report["source"]):
                verified = verify(CAPTURE, report, expected_vin=VIN.decode())
                self.assertTrue(verified["verified"])
                self.assertTrue(verified["report_consistent"])
                self.assertEqual("MATCH", verified["vin_match"])
                self.assertEqual(hashlib.sha256(CAPTURE).hexdigest(), verified["capture_sha256"])
                self.assertFalse(verified["device_credential_independently_verified"])
                self.assertFalse(verified["physical_vehicle_verified"])
                self.assertFalse(verified["write_authorized"])

    def test_modified_fields_fail_even_with_valid_capture(self):
        mutations = (
            lambda r: r.update(observed_vin="1G1FA1RXXR0123457"),
            lambda r: r.update(request_can_id="7E1"),
            lambda r: r.update(extended=0),
            lambda r: r["steps"][0].update(status="RAW"),
            lambda r: r["steps"][0].update(duration_ms=22),
            lambda r: r["steps"][0].update(sequence=True),
            lambda r: r["steps"][0]["supported_page"].update(supported_ids=[5]),
            lambda r: r["steps"][2].update(dtc_codes=["P0123"]),
            lambda r: r["steps"].reverse(),
            lambda r: r["summary"].update(pid_inventory_complete=False),
            lambda r: r["service_observations"][0].update(passed=999),
            lambda r: r.update(physical_vehicle_verified=True),
            lambda r: r.update(evidence_authenticated=True),
            lambda r: r.update(vin_match="MISMATCH"),
        )
        for mutate in mutations:
            for original in (python_report(), windows_report()):
                report = copy.deepcopy(original)
                mutate(report)
                result = verify(CAPTURE, report)
                self.assertFalse(result["verified"])
                self.assertFalse(result["report_consistent"])
                self.assertTrue(result["differences"])

    def test_truncated_capture_preserves_prefix_without_passing(self):
        truncated = CAPTURE[:-5]
        honest = verify(truncated, python_report(truncated))
        self.assertTrue(honest["report_consistent"])
        self.assertFalse(honest["verified"])
        self.assertFalse(honest["capture_integrity_verified"])
        self.assertGreater(honest["verified_prefix_steps"], 0)
        forged = verify(truncated, python_report())
        self.assertFalse(forged["report_consistent"])

    def test_negative_host_outcome_never_upgraded(self):
        report = python_report(live=True)
        report.update(capture_integrity_verified=False, cancelled=True,
                      error="Cancelled", vin_match="UNVERIFIED")
        result = verify(CAPTURE, report)
        self.assertTrue(result["capture_integrity_verified"])
        self.assertTrue(result["report_consistent"])
        self.assertFalse(result["verified"])

    def test_wrong_vin_and_unverified_credential(self):
        report = windows_report()
        result = verify(CAPTURE, report, expected_vin="1G1FA1RXXR0123457")
        self.assertFalse(result["verified"])
        self.assertEqual("MISMATCH", result["vin_match"])
        report["device_credential_authenticated"] = False
        self.assertFalse(verify(CAPTURE, report)["verified"])

    def test_file_and_cli_exclusive_report(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            raw, report, output = (base / name for name in ("capture.cj", "host.json", "inspection.json"))
            raw.write_bytes(CAPTURE)
            report.write_text(json.dumps(python_report()), encoding="utf-8")
            args = ["verify-j1979-capture", "--capture", str(raw), "--report", str(report),
                    "--vin", VIN.decode(), "--output", str(output)]
            result = run(args)
            self.assertTrue(result["verified"])
            self.assertEqual(hashlib.sha256(report.read_bytes()).hexdigest(), result["report_sha256"])
            with self.assertRaises(FileExistsError):
                run(args)
            with self.assertRaises(ValueError):
                run(args[:-1] + [str(raw)])
            self.assertEqual(CAPTURE, raw.read_bytes())

    def test_duplicate_json_and_nonfinite_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            raw, report = Path(directory) / "capture", Path(directory) / "report"
            raw.write_bytes(CAPTURE)
            for invalid in ('{"schema":1,"schema":2}', '{"value":NaN}', '[]'):
                report.write_text(invalid, encoding="utf-8")
                with self.assertRaises(ValueError):
                    verify_files(raw, report)
