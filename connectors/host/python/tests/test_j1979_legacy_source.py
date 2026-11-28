import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979_legacy import scan, allowed_read
from camarodiagconnectors.j1979_legacy_discovery import discover
from camarodiagconnectors.j1979_legacy_frames import checksum
from camarodiagconnectors import j1979_legacy_cli

VIN = "1G1FA1RXXR0123456"


def frame(protocol, source, payload):
    data = bytes.fromhex(payload) if isinstance(payload, str) else payload
    header = bytes((0x80 | len(data), 0xF1, source)) if protocol in (4, 5) else bytes(
        (0x41 if protocol == 1 else 0x48, 0x6B, source))
    raw = header + data
    return (raw + bytes((checksum(protocol, raw),))).hex().upper()


def fixture(protocol):
    def row(source, payload):
        return frame(protocol, source, payload)
    fragments = bytes(3) + VIN.encode("ascii")
    vin_rows = [row(0x10, bytes((0x49, 2, i+1)) + fragments[4*i:4*i+4]) for i in range(5)]
    return {"0100": "\n".join([row(0x10, "410088100000"), row(0x11, "410000080000")]),
            "0900": row(0x10, "490001C0000000"), "0901": row(0x10, "490105"),
            "0902": "\n".join(vin_rows + [row(0x11, "49020100000041")]),
            "03": "\n".join([row(0x10, "43012300000000"), row(0x11, "43045600000000")]),
            "07": row(0x10, "47000000000000"), "0A": row(0x10, "7F0A11"),
            "0101": row(0x10, "410100000000"), "0105": row(0x10, "41055A"),
            "010C": row(0x10, "410C0FA0"), "020200": row(0x10, "4202000000"),
            "0600": row(0x10, "4600FF80000000"), "0601": row(0x10, "46018100010002"),
            "08000000000000": row(0x10, "48000080000000")}


class LegacySourceTests(unittest.TestCase):
    def test_discovery_all_five_variants_and_conflicts(self):
        for protocol in range(1, 6):
            with self.subTest(protocol=protocol):
                sent = []
                raw = "\n".join([frame(protocol, 0x10, "410080000000"),
                    frame(protocol, 0x11, "410040000000"),
                    frame(protocol, 0x11, "410020000000"), frame(protocol, 0x12, "7F0111")])
                result = discover(lambda req, _: sent.append(req) or raw, lambda: protocol, protocol)
                self.assertEqual(["0100"], sent)
                self.assertTrue(result["completed"])
                self.assertEqual(["PASSED", "FAILED", "NOT_TESTED"], [x["status"] for x in result["responders"]])
                self.assertEqual([1], result["responders"][0]["supported_pids"])
                self.assertIsNone(result["responders"][1]["supported_pids"])
                self.assertFalse(result["whole_vehicle_coverage_verified"])

    def test_discovery_checksum_and_protocol_fail_closed(self):
        raw = frame(2, 0x10, "410080000000")
        corrupt = raw[:-2] + f"{int(raw[-2:], 16) ^ 1:02X}"
        report = discover(lambda *_: corrupt, lambda: self.fail("bad frame"), 2)
        self.assertFalse(report["completed"])
        self.assertEqual([], report["responders"])
        report = discover(lambda *_: raw, lambda: 3, 2)
        self.assertEqual("PROTOCOL_MISMATCH", report["stop_reason"])
        self.assertEqual([], report["responders"])

    def test_selected_source_scan_never_imports_other_ecu_values(self):
        for protocol in range(1, 6):
            sent = []
            replies = fixture(protocol)
            def exchange(req, timeout):
                self.assertTrue(allowed_read(req))
                sent.append(req)
                return replies.get(req, "NO DATA")
            result = scan(exchange, lambda: protocol, protocol, source=0x10, expected_vin=VIN)
            self.assertTrue(result["completed"])
            self.assertEqual(VIN, result["observed_vin"])
            self.assertEqual("MATCH", result["vin_match"])
            self.assertEqual(["11"], result["other_response_sources"])
            self.assertEqual(["0100", "0900", "0901", "0902"], sent[:4])
            self.assertNotIn("010D", sent)
            self.assertNotIn("04", sent)
            self.assertNotIn("08010000000000", sent)
            self.assertEqual(["P0123"], result["dtc_modes"][0]["codes"])
            self.assertEqual([], result["dtc_modes"][1]["codes"])
            self.assertIsNone(result["dtc_modes"][2]["codes"])
            values = {row["pid"]: row["number"] for row in result["measurements"]}
            self.assertEqual(50, values[5])
            self.assertEqual(1000, values[12])
            self.assertFalse(result["readiness"]["mil_on"])
            self.assertEqual([1], result["advertised_control_tests"])
            self.assertEqual("10", result["monitor_records"][0]["source"])
            self.assertFalse(result["freeze_triggers"][0]["present"])
            self.assertTrue(all(row["source"] == "10" for row in result["measurements"]))
            dtc = next(row for row in result["steps"] if row["request_hex"] == "03")
            self.assertEqual(["10", "11"], [frame["source"] for frame in dtc["frames"]])
            self.assertEqual(["43012300000000"], dtc["records_hex"])

    def test_wrong_vin_stops_before_dtc_or_live_values(self):
        replies = fixture(2)
        sent = []
        report = scan(lambda req, _: sent.append(req) or replies[req], lambda: 2, 2,
                      source=0x10, expected_vin="1FA6P8TH0L5100000")
        self.assertEqual("VIN_MISMATCH", report["stop_reason"])
        self.assertEqual(["0100", "0900", "0901", "0902"], sent)
        self.assertEqual([], report["measurements"])

    def test_ambiguous_initial_never_falls_back_to_another_source(self):
        reply = "\n".join([frame(2, 0x10, "410080000000"),
                           frame(2, 0x10, "410040000000"), frame(2, 0x11, "410080000000")])
        sent = []
        report = scan(lambda req, _: sent.append(req) or reply, lambda: self.fail("unexpected probe"), 2, source=0x10)
        self.assertFalse(report["completed"])
        self.assertEqual(["0100"], sent)
        self.assertEqual("FAILED", report["steps"][0]["status"])

    def test_another_source_vin_fragments_cannot_complete_selected_vin(self):
        replies = fixture(2)
        replies["0902"] = "\n".join(replies["0902"].splitlines()[1:])
        report = scan(lambda req, _: replies[req], lambda: 2, 2, source=0x10, expected_vin=VIN)
        self.assertEqual("VIN_UNVERIFIED", report["stop_reason"])
        self.assertIsNone(report["observed_vin"])

    def test_cli_discovery_and_selected_reports(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(j1979_legacy_cli, "LegacyElmSerial") as factory:
            session = factory.return_value.__enter__.return_value
            session.exchange.side_effect = lambda req, _: fixture(2).get(req, "NO DATA")
            session.observed_protocol.return_value = 2
            for source in ("discover", "10"):
                output = Path(folder) / (source + ".json")
                args = ["--port", "mock", "--protocol", "2", "--confirm-dedicated-adapter",
                        "--source", source, "--output", str(output)]
                if source == "10":
                    args += ["--vin", VIN]
                report = j1979_legacy_cli.run(args)
                self.assertTrue(report["completed"])
                self.assertEqual(report, json.loads(output.read_text()))
                self.assertTrue(factory.call_args.kwargs["headered"])
                if source == "discover":
                    self.assertEqual(2, len(report["responders"]))
                else:
                    self.assertEqual(VIN, report["observed_vin"])

    def test_cli_rejects_vin_without_selected_source_before_open(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(j1979_legacy_cli, "LegacyElmSerial") as factory:
            output = Path(folder) / "invalid.json"
            with self.assertRaises(SystemExit):
                j1979_legacy_cli.run(["--port", "mock", "--protocol", "2", "--confirm-dedicated-adapter",
                                     "--vin", VIN, "--output", str(output)])
            self.assertFalse(output.exists())
            factory.assert_not_called()
