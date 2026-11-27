import json
from pathlib import Path
import tempfile
import unittest

from camarodiagconnectors.offline_cli import run
from camarodiagconnectors.recovery_history import inspect_history
from test_recovery_bundle import IDENTITY, VIN, android_fixture, windows_fixture


class RecoveryHistoryTests(unittest.TestCase):
    def test_android_append_only_history(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a, b = root / "a", root / "b"
            a.mkdir()
            b.mkdir()
            first, _ = android_fixture(a)
            later, _ = android_fixture(b, phases=("BEFORE_ERASE",))
            report = inspect_history([first, later], "android", android_identity=IDENTITY)
            self.assertTrue(report["verified"], report)
            self.assertTrue(report["ordered_prefix_verified"])
            self.assertEqual(1, report["transitions"][0]["added_records"])
            self.assertEqual("ERASE_OR_WRITE_MAY_BE_INCOMPLETE", report["latest_recorded_disposition"])
            self.assertEqual(4096, report["latest_recorded_uncertain_address"])
            self.assertFalse(report["restore_authorized"])
            self.assertFalse(report["current_ecu_state_verified"])
            reversed_report = inspect_history([later, first], "android", android_identity=IDENTITY)
            self.assertFalse(reversed_report["verified"])
            self.assertFalse(reversed_report["ordered_prefix_verified"])

    def test_windows_terminal_recovery_is_recorded_not_authorized(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a, b, c = (root / name for name in ("a", "b", "c"))
            for path in (a, b, c):
                path.mkdir()
            phases = (("TestWriteAccepted", "ok"), ("EraseStarted", ""))
            first, _, _ = windows_fixture(a)
            second, _, _ = windows_fixture(b, phases)
            third, _, _ = windows_fixture(c, phases + (("RecoveryRequired", "fault"),))
            report = inspect_history([first, second, third], "windows", expected_vin=VIN, expected_osid=123)
            self.assertTrue(report["verified"], report)
            self.assertEqual("RECOVERY_REQUIRED", report["latest_recorded_disposition"])
            self.assertEqual(0x1000, report["latest_recorded_uncertain_address"])
            self.assertFalse(report["snapshots"][-1]["sequence_valid"])
            self.assertFalse(report["physical_restore_qualified"])

    def test_duplicate_and_fork_are_distinct_paths_but_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a, b, c = (root / name for name in ("a", "b", "c"))
            for path in (a, b, c):
                path.mkdir()
            first, _, _ = windows_fixture(a, (("TestWriteAccepted", "ok"),))
            duplicate, _, _ = windows_fixture(b, (("TestWriteAccepted", "ok"),))
            fork, _, _ = windows_fixture(c, (("TestWriteAccepted", "different"), ("EraseStarted", "")))
            for other in (duplicate, fork):
                report = inspect_history([first, other], "windows")
                self.assertFalse(report["verified"], report)
                self.assertIn("duplicate, rollback or fork", report["problem"])
                self.assertIsNone(report["latest_recorded_disposition"])

    def test_metadata_and_envelope_changes_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a, b = root / "a", root / "b"
            a.mkdir()
            b.mkdir()
            first, _, _ = windows_fixture(a)
            later, envelope, _ = windows_fixture(b, (("TestWriteAccepted", "ok"),))
            meta = Path(str(later) + ".meta.json")
            original = meta.read_bytes()
            payload = json.loads(original)
            payload["DriverSha256"] = "f" * 64
            meta.write_text(json.dumps(payload))
            self.assertFalse(inspect_history([first, later], "windows")["verified"])
            meta.write_bytes(original)
            envelope.write_bytes(envelope.read_bytes() + b"x")
            self.assertFalse(inspect_history([first, later], "windows")["verified"])

    def test_cli_exports_negative_and_positive_without_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            a, b = root / "a", root / "b"
            a.mkdir()
            b.mkdir()
            first, _, _ = windows_fixture(a)
            later, _, _ = windows_fixture(b, (("TestWriteAccepted", "ok"),))
            output = root / "history.json"
            args = ["verify-recovery-history", "--platform", "windows",
                    "--journal", str(first), "--journal", str(later),
                    "--vin", VIN, "--osid", "123", "--output", str(output)]
            self.assertTrue(run(args)["verified"])
            self.assertEqual("camarodiag-offline-recovery-history-v1", json.loads(output.read_text())["schema"])
            with self.assertRaises(FileExistsError):
                run(args)
            with self.assertRaises(ValueError):
                run(args[:-1] + [str(first)])
            with self.assertRaises(ValueError):
                inspect_history([first, first], "windows")
