import copy
import json
import tempfile
import unittest
from pathlib import Path

from camarodiagconnectors.j1979_drive_cycle import (
    TRIMS, decode_trim, cycle_data, project, legacy_project, trim_layout,
)
from camarodiagconnectors.j1979_live import Plan, record, csv_rows
from camarodiagconnectors.j1979_live_inspection import inspect, compare_files

VIN = "1G1FF3D36C9104199"


def row(req, res, status="PASSED", source=None):
    return {"request_hex": req, "response_hex": res, "status": status,
            "records_hex": [res], "selected_response_source": source}


class OperatingContextTests(unittest.TestCase):
    def test_all_trim_pids_and_frame_echo(self):
        for pid in sorted(TRIMS):
            bank = 1 if pid in (6, 7, 0x55, 0x56) else 2
            for mode in (1, 2):
                req = bytes((mode, pid)) + (b"\x00" if mode == 2 else b"")
                result = decode_trim(req, bytes((0, 255)), 0xFF)
                self.assertEqual([s["bank"] for s in result["signals"]], [bank, bank + 2])
                self.assertEqual([s["number"] for s in result["signals"]], [-100, 99.21875])
                self.assertTrue(result["upper_bank_verified"])
                self.assertEqual(result["frame"], 0 if mode == 2 else None)
        for request in (b"", b"\x01", b"\x01\x06\x00", b"\x02\x06", b"\x02\x06\x01"):
            self.assertIsNone(decode_trim(request, b"\x80", None))
        self.assertIsNone(decode_trim(b"\x01\x06", b"\x80\x90\xA0", None))
        self.assertIsNone(decode_trim(b"\x01\x06", b"\x80", True))

    def test_missing_layout_does_not_make_second_byte_a_value(self):
        for layout in (None, 0, 0x0F):
            result = decode_trim(b"\x01\x06", b"\x80\x90", layout)
            self.assertEqual(result["signals"][0]["number"], 0)
            self.assertIsNone(result["signals"][1]["number"])
            self.assertEqual(result["signals"][1]["raw_encoded"], 144)
            self.assertFalse(result["upper_bank_verified"])
        self.assertEqual(decode_trim(b"\x01\x06", b"\x80\x90", 0x30)["signals"][1]["number"], 12.5)
        self.assertIsNone(decode_trim(b"\x01\x08", b"\x80\x90", 0x30)["signals"][1]["number"])

    def test_monitor_support_enable_and_completion_are_distinct(self):
        baseline = bytes.fromhex("80070100")
        result = cycle_data(bytes.fromhex("00110101"), baseline)
        monitors = {m["name"]: m for m in result["monitors"]}
        self.assertTrue(monitors["misfire"]["enabled"])
        self.assertFalse(monitors["misfire"]["complete"])
        self.assertFalse(monitors["fuel-system"]["enabled"])
        self.assertTrue(monitors["fuel-system"]["complete"])
        self.assertFalse(monitors["heated-catalyst"]["supported"])
        self.assertIsNone(monitors["heated-catalyst"]["complete"])
        self.assertTrue(result["support_verified"])
        self.assertTrue(result["consistent"])

    def test_unverified_and_inconsistent_cycle(self):
        for baseline in (None, bytes.fromhex("00080100")):
            result = cycle_data(bytes.fromhex("00010100"), baseline)
            self.assertFalse(result["support_verified"])
            self.assertTrue(all(m["supported"] is None and m["complete"] is None
                                for m in result["monitors"]))
        result = cycle_data(bytes.fromhex("00000200"), bytes.fromhex("00000100"))
        self.assertFalse(result["consistent"])
        for payload in ("01000000", "00800000", "00001000", "00080400", "00080014"):
            self.assertIsNone(cycle_data(bytes.fromhex(payload), None))

    def test_projection_never_merges_legacy_sources(self):
        steps = [row("011D", "411DFF", source="10"), row("0106", "41068090", source="18")]
        self.assertEqual(legacy_project(steps, "10")["fuel_trims"], [])
        self.assertIsNone(legacy_project(steps, "18")["fuel_trims"][0]["signals"][1]["number"])
        self.assertEqual(legacy_project(steps, None)["fuel_trims"], [])
        steps = [row("020600", "4206018090"), row("0106", "41078090"),
                 row("0106", "41068090", "FAILED")]
        self.assertEqual(project(steps)["fuel_trims"], [])

    def test_layout_requires_exact_admission(self):
        page = row("0100", "410004000008")
        layout = row("011D", "411D30")
        self.assertEqual(trim_layout([page, layout]), {"state":"VERIFIED","raw_encoded":48})
        self.assertEqual(trim_layout([page, layout, layout])["state"], "UNVERIFIED")
        self.assertEqual(trim_layout([page])["state"], "UNVERIFIED")
        self.assertEqual(trim_layout([row("0100", "410004000000")])["state"],
                         "NOT_ADVERTISED_BY_SELECTED_ECU")

    def recording(self, advertised=True):
        t = [0.0]
        queries = []
        samples = iter(("41068090", "410690"))
        def exchange(req, timeout):
            t[0] += 0.01
            queries.append(req.hex().upper())
            if req == b"\x09\x02":
                return b"\x49\x02\x01" + VIN.encode()
            if req == b"\x01\x00":
                return bytes.fromhex("410004000008" if advertised else "410004000000")
            if req == b"\x01\x1d":
                return bytes.fromhex("411D30")
            if req == b"\x01\x06":
                return bytes.fromhex(next(samples))
            raise AssertionError(req)
        result = record(exchange, Plan((6,), VIN, 2, 0.1, 10),
                        clock=lambda:t[0], sleep=lambda seconds:t.__setitem__(0,t[0]+seconds))
        return {"schema":"camarodiag-j1979-live-v1","tx_can_id":"7E0","rx_can_id":"7E8",**result},queries

    def test_record_and_independent_replay(self):
        report, queries = self.recording()
        self.assertTrue(report["completed"])
        self.assertEqual(queries, ["0902","0100","011D","0106","0106","0902"])
        self.assertEqual([s["mean"] for s in report["multi_signal_summary"]], [6.25,12.5])
        self.assertEqual(report["multi_signal_summary"][1]["missing"], 1)
        self.assertEqual(len(list(csv_rows(report))), 4)
        self.assertTrue(inspect(report)["completed"])
        for mutate in (lambda r:r["trim_layout"].update(raw_encoded=255),
                       lambda r:r["observations"][0]["multi_signals"][1].update(number=0),
                       lambda r:r["multi_signal_summary"][1].update(missing=0),
                       lambda r:r["admission"].pop(2)):
            altered = copy.deepcopy(report)
            mutate(altered)
            with self.assertRaises(ValueError):
                inspect(altered)

    def test_compare_preserves_missing_channels(self):
        a, _ = self.recording(True)
        b, _ = self.recording(False)
        with tempfile.TemporaryDirectory() as directory:
            first, second = Path(directory)/"a.json", Path(directory)/"b.json"
            first.write_text(json.dumps(a), encoding="utf-8")
            second.write_text(json.dumps(b), encoding="utf-8")
            compared = compare_files([first, second])
        channels = compared["channel_comparison"]
        self.assertEqual(len(channels), 2)
        self.assertEqual(channels[0]["mean_range"], 0)
        self.assertTrue(channels[0]["all_recordings_have_channel"])
        self.assertFalse(channels[1]["all_recordings_have_channel"])
        self.assertIsNone(channels[1]["recordings"][1])
        self.assertIsNone(channels[1]["mean_range"])
        self.assertFalse(compared["time_aligned"])

    def test_unadvertised_layout_never_queried(self):
        report, queries = self.recording(False)
        self.assertTrue(report["completed"])
        self.assertNotIn("011D", queries)
        self.assertEqual(len(report["multi_signal_summary"]), 1)
        self.assertEqual(report["observations"][0]["detail"], "UPPER_BANK_UNVERIFIED")
        self.assertEqual(report["observations"][0]["response_hex"], "41068090")
        self.assertTrue(inspect(report)["completed"])


if __name__ == "__main__":
    unittest.main()
