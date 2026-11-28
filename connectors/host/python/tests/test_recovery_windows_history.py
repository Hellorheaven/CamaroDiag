import copy
import json
from pathlib import Path
import tempfile
import unittest

from camarodiagconnectors.offline_cli import run
from camarodiagconnectors.recovery_history import inspect_history, verify_history_report
from camarodiagconnectors.recovery_windows_history import native_projection, silicon_geometry_matches
from test_recovery_bundle import VIN, windows_fixture


class NativeWindowsHistoryTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.paths = []
        phases = (("TestWriteAccepted", "ok"), ("EraseStarted", ""))
        for ordinal, records in enumerate(((), phases, phases + (("RecoveryRequired", "fault"),))):
            folder = self.root / str(ordinal)
            folder.mkdir()
            journal, _, _ = windows_fixture(folder, records)
            self.paths.append(journal)
        self.report = self.root / "native.json"
        self.details = []
        history = inspect_history(self.paths, "windows", _details=self.details)
        self.assertTrue(history["verified"], history)
        self.native = native_projection(history, self.details)
        self.native["inspectedAtUtc"] = "2026-11-27T12:00:00.1234567+00:00"
        self.save()

    def save(self):
        self.report.write_text(json.dumps(self.native), encoding="utf-8")

    def verify(self, **kwargs):
        return verify_history_report(self.report, self.paths, "windows", **kwargs)

    def test_native_history_replayed_and_identity_bound(self):
        result = self.verify(expected_vin=VIN, expected_osid=123)
        self.assertTrue(result["report_matches"])
        self.assertEqual("camarodiag-windows-recovery-history-v1", result["source_schema"])
        self.assertEqual("RECOVERY_REQUIRED", result["latest_recorded_disposition"])
        self.assertEqual(0x1000, result["latest_recorded_uncertain_address"])
        self.assertFalse(result["current_ecu_state_verified"])
        self.assertFalse(result["restore_authorized"])
        self.assertFalse(result["authenticated"])
        self.assertEqual(0, self.native["snapshots"][-1]["journal"]["preparedSectors"])
        self.assertEqual(1, self.native["snapshots"][-1]["backup"]["expectedSectors"])

    def test_native_format_local_names_and_digest_case(self):
        for row in self.native["snapshots"]:
            row["journalName"] = "renamed.jsonl"
            row["journal"]["sha256"] = row["journal"]["sha256"].upper()
            row["target"]["metadataSnapshotSha256"] = row["target"]["metadataSnapshotSha256"].upper()
        self.save()
        self.assertTrue(self.verify()["report_matches"])

    def test_all_semantic_fields_must_match(self):
        mutations = [
            (("snapshots", 0, "journalBytes"), 1),
            (("snapshots", 0, "journal", "preparedSectors"), True),
            (("snapshots", 1, "target", "osId"), 124),
            (("snapshots", 1, "backup", "siliconGeometryMatches"), True),
            (("snapshots", 1, "backup", "sectors", 0, "length"), 9),
            (("snapshots", 1, "backup", "sectors", 0, "originalSha256"), "0" * 64),
            (("snapshots", 2, "journal", "preparedSectors"), 1),
            (("transitions", 0, "addedRecords"), 999),
            (("latestRecordedState",), "Completed"),
            (("latestRecordedUncertainAddress",), None),
            (("restoreAuthorized",), True),
            (("physicalVehicleVerified",), True),
        ]
        original = copy.deepcopy(self.native)
        for path, replacement in mutations:
            with self.subTest(path=path):
                self.native = copy.deepcopy(original)
                target = self.native
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = replacement
                self.save()
                with self.assertRaises(ValueError):
                    self.verify()

    def test_missing_extra_and_negative_fields_rejected(self):
        original = copy.deepcopy(self.native)
        for key, value in (("verified", False), ("unexpected", True), ("authenticated", None)):
            self.native = copy.deepcopy(original)
            self.native[key] = value
            self.save()
            with self.assertRaises(ValueError):
                self.verify()
        self.native = original
        del self.native["snapshots"][0]["backup"]["sectors"]
        self.save()
        with self.assertRaises(ValueError):
            self.verify()

    def test_timestamp_validation(self):
        for value in (None, "not a date", "2026-11-27T12:00:00", True):
            self.native["inspectedAtUtc"] = value
            self.save()
            with self.assertRaises(ValueError):
                self.verify()

    def test_native_cli_and_original_reports_remain_unchanged(self):
        before = self.report.read_bytes()
        output = self.root / "replay.json"
        args = ["verify-recovery-history-report", "--platform", "windows"]
        for journal in self.paths:
            args += ["--journal", str(journal)]
        args += ["--report", str(self.report), "--vin", VIN, "--output", str(output)]
        verified = run(args)
        self.assertEqual(verified, json.loads(output.read_text()))
        self.assertEqual(before, self.report.read_bytes())
        with self.assertRaises(FileExistsError):
            run(args)

    def test_reordered_and_changed_journals_are_rejected(self):
        original = self.paths
        self.paths = list(reversed(original))
        with self.assertRaises(ValueError):
            self.verify()
        self.paths = original
        self.paths[1].write_bytes(self.paths[1].read_bytes() + b" ")
        with self.assertRaises(ValueError):
            self.verify()

    def test_native_report_cannot_be_used_for_android(self):
        with self.assertRaisesRegex(ValueError, "Windows history requires"):
            verify_history_report(self.report, self.paths, "android")


class SiliconGeometryTests(unittest.TestCase):
    def test_known_partial_erase_ranges(self):
        rows = [{"address": 0x20000, "length": 0x10000},
                {"address": 0x2e0000, "length": 0x20000}]
        self.assertTrue(silicon_geometry_matches(rows))
        self.assertFalse(silicon_geometry_matches([]))
        self.assertFalse(silicon_geometry_matches(rows + rows))
        self.assertFalse(silicon_geometry_matches([{"address": 0x30000, "length": 0x20000}]))
        self.assertFalse(silicon_geometry_matches([{"address": 0x300000, "length": 0x20000}]))
