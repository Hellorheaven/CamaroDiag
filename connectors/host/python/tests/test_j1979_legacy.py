import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979_legacy import allowed_read, records, scan, LegacyElmSerial
from camarodiagconnectors import j1979_legacy_cli

VIN = "1G1FA1RXXR0123456"
VIN_BYTES = bytes(3) + VIN.encode("ascii")
VIN_ROWS = "\r".join((bytes((0x49, 2, i+1)) + VIN_BYTES[i*4:i*4+4]).hex()
                     for i in range(5))
# 01, 13 (oxygen locations), 1D absent.
REPLIES = {"0100": "410080002000", "03": "43012300000000", "07": "47000000000000",
           "0A": "NO DATA", "0101": "410101000000", "0113": "411301",
           "020200": "4202000000", "050001": "45000180000000",
           "050101": "4501012A", "0600": "4600FF80000000",
           "0601": "46018100010002", "08000000000000": "48000080000000",
           "0900": "490001C0000000", "0901": "490105", "0902": VIN_ROWS}


class LegacyTests(unittest.TestCase):
    def test_each_protocol_and_no_mutation(self):
        for protocol in range(1, 6):
            sent = []
            def exchange(command, timeout):
                sent.append(command)
                self.assertGreater(timeout, 0)
                return REPLIES[command]
            result = scan(exchange, lambda: protocol, protocol)
            self.assertEqual(protocol, result["observed_protocol"])
            self.assertTrue(result["completed"])
            self.assertEqual(VIN, result["anonymous_vin_candidate"])
            self.assertIsNone(result["observed_vin"])
            self.assertEqual("ANONYMOUS", result["responder_attribution"])
            self.assertEqual([1], result["advertised_control_tests"])
            self.assertEqual(set(REPLIES), set(sent))
            self.assertNotIn("04", sent)
            self.assertNotIn("08010000000000", sent)
            self.assertEqual("NOT_TESTED", next(row for row in result["steps"]
                             if row["request_hex"] == "0A")["status"])

    def test_read_allowlist_and_strict_records(self):
        for cmd in ("04", "08010000000000", "0800", "050003", "020201", "22F190"):
            self.assertFalse(allowed_read(cmd))
        for cmd in ("0100", "050001", "0600", "08000000000000"):
            self.assertTrue(allowed_read(cmd))
        self.assertEqual([b"\x43\x00\x00\x00\x00\x00\x00"],
                         records("03", "03\r43 00 00 00 00 00 00\r>"))
        for raw in ("NO DATA", "43000000000000\rCAN ERROR", "7E8 43 00",
                    "4300000000000"):
            with self.assertRaises(ValueError):
                records("03", raw)

    def test_protocol_mismatch_stops_before_other_services(self):
        sent = []
        result = scan(lambda cmd, _: (sent.append(cmd) or REPLIES[cmd]), lambda: 3, 2)
        self.assertEqual(["0100"], sent)
        self.assertEqual("PROTOCOL_MISMATCH", result["stop_reason"])
        self.assertIsNone(result["observed_protocol"])

    def test_incomplete_identity_never_creates_candidate(self):
        replies = REPLIES | {"0902": "\r".join(VIN_ROWS.split("\r")[:-1])}
        result = scan(lambda cmd, _: replies[cmd], lambda: 2, 2)
        self.assertIsNone(result["anonymous_vin_candidate"])
        self.assertEqual("NOT_TESTED", result["steps"][-1]["status"])

    def test_transport_failure_preserves_partial_report(self):
        sent = []
        def exchange(command, _):
            sent.append(command)
            if command == "03":
                raise OSError("prompt timeout")
            return REPLIES[command]
        report = scan(exchange, lambda: 2, 2)
        self.assertEqual(["0100", "03"], sent)
        self.assertEqual("TRANSPORT_ERROR", report["stop_reason"])
        self.assertFalse(report["completed"])

    def test_cli_exclusive_export_and_no_simulated_physical_claim(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "legacy.json"
            args = ["--port", "COM5", "--protocol", "2", "--confirm-dedicated-adapter",
                    "--output", str(output)]
            with patch.object(j1979_legacy_cli, "LegacyElmSerial") as factory:
                session = factory.return_value.__enter__.return_value
                session.exchange.side_effect = lambda cmd, _: REPLIES[cmd]
                session.observed_protocol.return_value = 2
                report = j1979_legacy_cli.run(args)
                self.assertEqual(report, json.loads(output.read_text()))
                self.assertFalse(report["physical_vehicle_verified"])
                with self.assertRaises(FileExistsError):
                    j1979_legacy_cli.run(args)
                self.assertEqual(1, factory.call_count)
