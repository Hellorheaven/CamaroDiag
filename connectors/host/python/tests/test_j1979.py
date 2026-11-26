import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979 import allowed_read, physical_pair, scan, LinuxIsoTp
from camarodiagconnectors import j1979_cli


VIN = "1G1FA1RXXR0123456"
REPLIES = {
    "0902": bytes.fromhex("490201") + VIN.encode("ascii"),
    "03": bytes.fromhex("4300"),
    "07": bytes.fromhex("4700"),
    "0A": bytes.fromhex("7F0A11"),
    "0100": bytes.fromhex("410080100000"),
    "0101": bytes.fromhex("410100000000"),
    "010C": bytes.fromhex("410C0000"),
    "020200": bytes.fromhex("4202000000"),
    "0600": bytes.fromhex("460080000000"),
    "0601": bytes.fromhex("460101240064000000C8"),
    "0800": bytes.fromhex("480080000000"),
    "0900": bytes.fromhex("490040000000"),
}


class J1979Tests(unittest.TestCase):
    def test_physical_pairs_and_read_scope(self):
        self.assertTrue(physical_pair(0x7E0, 0x7E8))
        self.assertTrue(physical_pair(0x18DA10F1, 0x18DAF110))
        self.assertFalse(physical_pair(0x7DF, 0x7E8))
        self.assertFalse(physical_pair(0x7E0, 0x7E9))
        self.assertFalse(physical_pair(0x18DAFFF1, 0x18DAF1FF))
        for req in ("04", "0801", "080000", "020201", "27"):
            self.assertFalse(allowed_read(bytes.fromhex(req)))
        for req in ("0100", "0800", "08E0", "020200", "0A"):
            self.assertTrue(allowed_read(bytes.fromhex(req)))

    def test_scan_only_advertised_reads(self):
        sent = []
        def exchange(request, timeout):
            key = request.hex().upper()
            sent.append(key)
            self.assertGreater(timeout, 0)
            self.assertLessEqual(timeout, 2.5)
            return REPLIES[key]
        result = scan(exchange)
        self.assertEqual(VIN, result["observed_vin"])
        self.assertEqual(["0902", "03", "07", "0A"], sent[:4])
        self.assertEqual(set(REPLIES), set(sent))
        self.assertTrue(result["completed"])
        self.assertEqual([1], result["advertised_control_tests"])
        steps = {step["request_hex"]: step for step in result["steps"]}
        self.assertEqual("NOT_TESTED", steps["0A"]["status"])
        self.assertEqual("PASSED", steps["03"]["status"])
        self.assertEqual(100, steps["0601"]["decoded"][0]["raw"])
        self.assertNotIn("0801", sent)

    def test_malformed_or_unfinished_replies(self):
        overrides = dict(REPLIES, **{"0902": b"\x49\x02\x01",
                         "03": b"\x43\x01\x00\x00",
                         "07": b"\x7f\x07\x78",
                         "010C": b"\x41\x0d\x00"})
        result = scan(lambda req, _: overrides[req.hex().upper()])
        self.assertIsNone(result["observed_vin"])
        steps = {row["request_hex"]: row for row in result["steps"]}
        for key in ("0902", "03", "07", "010C"):
            self.assertEqual("FAILED", steps[key]["status"])

    def test_transport_failure_stops_and_preserves_evidence(self):
        sent = []
        def exchange(req, _):
            sent.append(req.hex().upper())
            if len(sent) == 1:
                return REPLIES["0902"]
            raise OSError("connection lost")
        report = scan(exchange)
        self.assertEqual(["0902", "03"], sent)
        self.assertEqual(VIN, report["observed_vin"])
        self.assertFalse(report["completed"])
        self.assertEqual("TRANSPORT_ERROR", report["stop_reason"])

    def test_budget_stops_without_further_tx(self):
        now = [0.0]
        sent = []
        def exchange(req, timeout):
            sent.append(req.hex().upper())
            now[0] += timeout
            return REPLIES["0902"]
        report = scan(exchange, budget=0.1, clock=lambda: now[0])
        self.assertEqual(["0902"], sent)
        self.assertEqual("BUDGET_EXHAUSTED", report["stop_reason"])

    def test_invalid_channel_never_opens_socket(self):
        with patch("camarodiagconnectors.j1979.socket.socket") as factory:
            with self.assertRaises(ValueError):
                LinuxIsoTp("can0", 0x7DF, 0x7E8)
            factory.assert_not_called()

    def test_cli_exclusive_file_and_report(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "report.json"
            args = ["--interface", "can0", "--tx", "7E0", "--rx", "7E8",
                    "--confirm-physical-channel", "--output", str(output)]
            with patch.object(j1979_cli, "LinuxIsoTp") as factory:
                factory.return_value.__enter__.return_value.exchange.side_effect = (
                    lambda req, _: REPLIES[req.hex().upper()])
                result = j1979_cli.run(args)
                self.assertEqual(VIN, result["observed_vin"])
                self.assertFalse(result["physical_vehicle_verified"])
                self.assertEqual(result, json.loads(output.read_text()))
                with self.assertRaises(FileExistsError):
                    j1979_cli.run(args)
                self.assertEqual(1, factory.call_count)


if __name__ == "__main__":
    unittest.main()
