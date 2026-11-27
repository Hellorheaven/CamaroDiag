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
        inventories = {row["mode"]: row for row in result["inventory"]}
        self.assertEqual("COMPLETE", inventories[1]["state"])
        self.assertEqual([1, 12], inventories[1]["advertised_ids"])
        self.assertEqual("NOT_APPLICABLE", inventories[2]["state"])
        modes = {row["mode"]: row for row in result["dtc_modes"]}
        self.assertEqual([], modes[3]["codes"])
        self.assertIsNone(modes[10]["codes"])
        self.assertEqual(0, next(row for row in result["measurements"] if row["pid"] == 12)["number"])
        self.assertFalse(steps["0101"]["application_evidence"]["readiness"]["mil_on"])

    def test_odometer_only_when_advertised_and_exact_width(self):
        replies = dict(REPLIES, **{"0100": bytes.fromhex("410000000001"),
            "0120": bytes.fromhex("412000000001"), "0140": bytes.fromhex("414000000001"),
            "0160": bytes.fromhex("416000000001"), "0180": bytes.fromhex("418000000001"),
            "01A0": bytes.fromhex("41A004000000"), "01A6": bytes.fromhex("41A60012D687")})
        result = scan(lambda req, _: replies[req.hex().upper()])
        odo = next(row for row in result["steps"] if row["request_hex"] == "01A6")
        self.assertEqual({"name": "odometer", "number": 123456.7, "unit": "km"}, odo["decoded"])
        replies["01A6"] = bytes.fromhex("41A60012D6")
        result = scan(lambda req, _: replies[req.hex().upper()])
        self.assertEqual("FAILED", next(row for row in result["steps"] if row["request_hex"] == "01A6")["status"])

    def test_malformed_or_unfinished_replies(self):
        overrides = dict(REPLIES, **{"0902": b"\x49\x02\x01",
                         "03": b"\x43\x01\x00\x00",
                         "07": b"\x7f\x07\x78",
                         "010C": b"\x41\x0d\x00"})
        result = scan(lambda req, _: overrides[req.hex().upper()])
        self.assertIsNone(result["observed_vin"])
        self.assertIsNone(next(row for row in result["dtc_modes"] if row["mode"] == 3)["codes"])
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
        self.assertIsNone(report["observed_vin"])
        self.assertEqual("LATE_RESPONSE", report["steps"][0]["detail"])
        self.assertIsNone(report["steps"][0]["application_evidence"])

    def test_raw_records_are_not_reported_as_passed(self):
        replies = dict(REPLIES, **{"0900": bytes.fromhex("4900C0000000"),
                                  "0901": bytes.fromhex("490101")})
        result = scan(lambda req, _: replies[req.hex().upper()])
        row = next(x for x in result["steps"] if x["request_hex"] == "0901")
        self.assertEqual("RAW", row["status"])
        self.assertEqual("490101", row["response_hex"])
        self.assertIsNone(row["decoded"])
        self.assertEqual({"shape": "RAW"}, row["application_evidence"])

    def test_interrupt_stops_and_retains_prefix(self):
        sent = []
        def exchange(req, _):
            sent.append(req.hex().upper())
            if len(sent) == 1:
                return REPLIES["0902"]
            raise KeyboardInterrupt()
        report = scan(exchange)
        self.assertEqual(["0902", "03"], sent)
        self.assertFalse(report["completed"])
        self.assertTrue(report["cancelled"])
        self.assertEqual("CANCELLED", report["stop_reason"])
        self.assertEqual(VIN, report["observed_vin"])
        self.assertEqual("CANCELLED", report["steps"][-1]["detail"])

    def test_socket_drains_stale_replies_and_waits_for_final_response(self):
        from unittest.mock import Mock
        channel = LinuxIsoTp.__new__(LinuxIsoTp)
        channel._socket = Mock()
        channel._socket.recv.side_effect = [
            b"\x43\x00", BlockingIOError(), b"\x7F\x03\x78", b"\x43\x00"]
        channel._socket.send.return_value = 1
        self.assertEqual(b"\x43\x00", channel.exchange(b"\x03", 2.5))
        channel._socket.send.assert_called_once_with(b"\x03")
        self.assertEqual(4, channel._socket.recv.call_count)

    def test_socket_refuses_busy_queue_before_tx(self):
        from unittest.mock import Mock
        channel = LinuxIsoTp.__new__(LinuxIsoTp)
        channel._socket = Mock()
        channel._socket.recv.return_value = b"\x43\x00"
        with self.assertRaises(OSError):
            channel.exchange(b"\x03", 2.5)
        channel._socket.send.assert_not_called()

    def test_correct_identifier_with_truncated_pid_is_not_positive(self):
        replies = dict(REPLIES, **{"010C": b"\x41\x0C\x00"})
        report = scan(lambda req, _: replies[req.hex().upper()])
        self.assertEqual("FAILED", next(row for row in report["steps"]
                         if row["request_hex"] == "010C")["status"])

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
