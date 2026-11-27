import json
from pathlib import Path
import tempfile
import unittest

from camarodiagconnectors.offline_cli import run
from camarodiagconnectors.recovery_history import inspect_history, verify_history_report
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


class RecoveryHistoryReplayTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.a, self.b = self.root / "a", self.root / "b"
        self.a.mkdir()
        self.b.mkdir()
        self.first, _, _ = windows_fixture(self.a)
        self.later, self.envelope, _ = windows_fixture(
            self.b, (("TestWriteAccepted", "ok"), ("EraseStarted", "")))
        self.paths = [self.first, self.later]
        self.report = self.root / "history.json"
        self.history = inspect_history(self.paths, "windows")
        self.assertTrue(self.history["verified"], self.history)
        self.save()

    def save(self):
        self.report.write_text(json.dumps(self.history), encoding="utf-8")

    def verify(self, **options):
        return verify_history_report(self.report, self.paths, "windows", **options)

    def test_replay_independently_bound_identity(self):
        result = self.verify(expected_vin=VIN, expected_osid=123)
        self.assertTrue(result["report_matches"])
        self.assertTrue(result["expected_vin_independently_supplied"])
        self.assertTrue(result["expected_osid_independently_supplied"])
        self.assertFalse(result["restore_authorized"])
        self.assertFalse(result["current_ecu_state_verified"])
        self.assertFalse(result["authenticated"])

    def test_tampered_transition_and_boolean_rejected(self):
        transition = self.history["transitions"][0]
        count = transition["added_records"]
        for value in (count + 1, True, "2"):
            transition["added_records"] = value
            self.save()
            with self.assertRaisesRegex(ValueError, "History report"):
                self.verify()
        transition["added_records"] = count
        self.history["physical_vehicle_verified"] = True
        self.save()
        with self.assertRaisesRegex(ValueError, "physical_vehicle_verified"):
            self.verify()

    def test_report_missing_fields_rejected(self):
        del self.history["snapshots"][0]["journal_sha256"]
        self.save()
        with self.assertRaisesRegex(ValueError, "fields differ"):
            self.verify()

    def test_negative_report_cannot_pass(self):
        self.history["verified"] = False
        self.save()
        with self.assertRaisesRegex(ValueError, "Only verified"):
            self.verify()

    def test_changed_original_sector_rejected(self):
        self.envelope.write_bytes(self.envelope.read_bytes() + b"x")
        with self.assertRaisesRegex(ValueError, "Independent history replay failed"):
            self.verify()

    def test_reordered_journals_rejected(self):
        self.paths.reverse()
        with self.assertRaisesRegex(ValueError, "Independent history replay failed"):
            self.verify()

    def test_independent_identity_mismatch_rejected(self):
        with self.assertRaisesRegex(ValueError, "Independent history replay failed"):
            self.verify(expected_osid=124)

    def test_cli_replay_preserves_report_and_exports_exclusively(self):
        before = self.report.read_bytes()
        output = self.root / "replay.json"
        args = ["verify-recovery-history-report", "--platform", "windows",
                "--journal", str(self.first), "--journal", str(self.later),
                "--report", str(self.report), "--output", str(output)]
        result = run(args)
        self.assertTrue(result["report_matches"])
        self.assertEqual(result, json.loads(output.read_text()))
        self.assertEqual(before, self.report.read_bytes())
        with self.assertRaises(FileExistsError):
            run(args)
        with self.assertRaises(ValueError):
            run(args[:-1] + [str(self.report)])

    def test_report_change_during_replay_is_rejected(self):
        changed = False

        def active():
            nonlocal changed
            if not changed:
                # The first callback precedes the initial stable report read.
                # Delay mutation until the first journal is being inspected.
                active.calls += 1
                if active.calls == 8:
                    self.report.write_bytes(self.report.read_bytes() + b" ")
                    changed = True

        active.calls = 0
        with self.assertRaises(ValueError):
            self.verify(require_current=active)
        self.assertTrue(changed)

    def test_android_history_replay(self):
        first_dir, later_dir = self.root / "android-a", self.root / "android-b"
        first_dir.mkdir()
        later_dir.mkdir()
        first, _ = android_fixture(first_dir)
        later, _ = android_fixture(later_dir, phases=("BEFORE_ERASE",))
        paths = [first, later]
        report = inspect_history(paths, "android", android_identity=IDENTITY)
        self.assertTrue(report["verified"], report)
        self.report.write_text(json.dumps(report), encoding="utf-8")
        replay = verify_history_report(self.report, paths, "android",
                                       android_identity=IDENTITY, expected_vin=VIN)
        self.assertTrue(replay["report_matches"])
        self.assertEqual(4096, replay["latest_recorded_uncertain_address"])
        self.assertFalse(replay["physical_restore_qualified"])
