import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from camarodiagconnectors.j1979_campaign_evidence import decode
from camarodiagconnectors.j1979_campaign_capture_cli import run


def record(kind, seq, body):
    header = struct.pack("<2sBBHH", b"CJ", 1, kind, seq, len(body))
    return header + body + struct.pack("<I", zlib.crc32(header + body))


def start():
    return record(1, 0, struct.pack("<IIBI", 0x7E0, 0x7E8, 0, 180000))


def step(seq, request, response, state=1):
    return record(2, seq, struct.pack("<BBIH", state, len(request), 21, len(response))
                  + request + response)


def summary(seq, end=1, flags=1, steps=2, positive=2, raw=0, negative=0, timeout=0):
    return record(3, seq, struct.pack("<BBIIIII", end, flags, steps, positive, raw, negative, timeout))


VIN = b"1G1FA1RXXR0123456"
INITIAL = step(1, bytes.fromhex("0100"), bytes.fromhex("410000000000"))
IDENTITY = step(2, bytes.fromhex("0902"), b"\x49\x02\x01" + VIN)
DTC = step(3, b"\x03", b"\x43\x00") + step(4, b"\x07", b"\x47\x00") + step(5, b"\x0A", b"\x4A\x00")
TAIL = (step(6, b"\x02\x02\x00", b"\x42\x02\x00\x00\x00") +
        step(7, b"\x06\x00", b"\x46\x00" + bytes(4)) +
        step(8, b"\x08\x00", b"\x48\x00" + bytes(4)) +
        step(9, b"\x09\x00", b"\x49\x00" + bytes(4)))
CAPTURE = start() + INITIAL + IDENTITY + DTC + TAIL + summary(10, flags=231, steps=9, positive=9)


class CaptureTests(unittest.TestCase):
    def test_complete_integrity_not_hardware_verification(self):
        report = decode(io.BytesIO(CAPTURE))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertFalse(report["physical_vehicle_verified"])
        self.assertEqual(VIN.decode(), report["observed_vin"])
        self.assertEqual("COMPLETE", report["summary"]["end"])
        self.assertEqual("PASSED", report["steps"][0]["status"])

    def test_dtc_codes_distinguish_zero_unavailable_and_observed(self):
        dtc = (step(3, b"\x03", bytes.fromhex("43040123456789ABCDEF")) +
               step(4, b"\x07", bytes.fromhex("7F0711"), 3) +
               step(5, b"\x0A", b"\x4A\x00"))
        report = decode(io.BytesIO(start() + INITIAL + IDENTITY + dtc + TAIL +
                                   summary(10, flags=231, steps=9, positive=8, negative=1)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual(["P0123", "C0567", "B09AB", "U0DEF"], report["steps"][2]["dtc_codes"])
        self.assertIsNone(report["steps"][3]["dtc_codes"])
        self.assertEqual(0x11, report["steps"][3]["negative_response_code"])
        self.assertEqual([], report["steps"][4]["dtc_codes"])
        modes = {row["mode"]: row for row in report["service_observations"]}
        self.assertEqual(1, modes["03"]["passed"])
        self.assertEqual(1, modes["07"]["negative"])
        self.assertEqual(0, modes["07"]["passed"])
        self.assertEqual(1, modes["0A"]["passed"])
        self.assertEqual(len(report["steps"]), sum(row["attempted"] for row in modes.values()))

    def test_numeric_pid_measurement_only_for_verified_known_definitions(self):
        initial = step(1, b"\x01\x00", bytes.fromhex("410008100000"))
        rpm = step(7, b"\x01\x0C", bytes.fromhex("410C1AF8"))
        coolant = step(6, b"\x01\x05", bytes.fromhex("41055A"))
        tail = (step(8, b"\x02\x02\x00", bytes.fromhex("4202000000")) +
                step(9, b"\x06\x00", bytes.fromhex("460000000000")) +
                step(10, b"\x08\x00", bytes.fromhex("480000000000")) +
                step(11, b"\x09\x00", bytes.fromhex("490000000000")))
        report = decode(io.BytesIO(start() + initial + IDENTITY + DTC + coolant + rpm + tail +
            summary(12, flags=231, steps=11, positive=11)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual(50, report["steps"][5]["measurement"]["number"])
        self.assertEqual(1726, report["steps"][6]["measurement"]["number"])
        self.assertIsNone(report["steps"][0]["measurement"])
        self.assertIsNone(report["steps"][7]["measurement"])

    def test_corruption_and_truncation_keep_prefix(self):
        for tail in (IDENTITY[:-1], IDENTITY[:-1] + bytes([IDENTITY[-1] ^ 1])):
            report = decode(io.BytesIO(start() + INITIAL + tail))
            self.assertFalse(report["capture_integrity_verified"])
            self.assertEqual(1, len(report["steps"]))
            self.assertEqual(1, report["service_observations"][0]["attempted"])
            self.assertEqual(0, report["service_observations"][2]["attempted"])
            self.assertIsNotNone(report["error"])
        report = decode(io.BytesIO(CAPTURE + b"extra"))
        self.assertFalse(report["capture_integrity_verified"])

    def test_sequence_summary_and_mutation_rejected(self):
        for capture in (
            start() + step(2, b"\x01\x00", b"\x41\x00" + bytes(4)),
            start() + INITIAL + IDENTITY + summary(3, positive=1),
            start() + INITIAL + IDENTITY + summary(3, flags=0),
            start() + step(1, b"\x04", b"\x44"),
            start() + step(1, b"\x08\x01", b"\x48\x01"),
        ):
            self.assertFalse(decode(io.BytesIO(capture))["capture_integrity_verified"])

    def test_capture_cannot_upgrade_unknown_or_truncated_record(self):
        for request, response, state in (
            (b"\x01\xA1", b"\x41\xA1\x01", 1),
            (b"\x01\x0C", b"\x41\x0C\x00", 1),
            (b"\x01\x05", b"\x41\x05\x40", 2),
            (b"\x03", b"\x43\x01\x00\x00", 1),
            (b"\x01\xA1", b"\x41\xA1", 2),
        ):
            report = decode(io.BytesIO(start() + INITIAL + step(2, request, response, state)))
            self.assertFalse(report["capture_integrity_verified"])
            self.assertEqual(1, len(report["steps"]))
            self.assertIn("shape", report["error"])

    def test_partial_and_raw_evidence(self):
        # PID 02 is advertised but has no defined Mode 01 width in this codec.
        initial = step(1, b"\x01\x00", b"\x41\x00\x40\x00\x00\x00")
        dtc = step(3, b"\x03", b"\x43\x00") + step(4, b"\x07", b"", 4) + step(5, b"\x0A", b"\x4A\x00")
        data = start() + initial + IDENTITY + dtc + step(6, b"\x01\x02", b"\x41\x02\x01", 2)
        data += summary(7, end=3, flags=3, steps=6, positive=4, raw=1, timeout=1)
        report = decode(io.BytesIO(data))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual("BUDGET", report["summary"]["end"])
        self.assertEqual("RAW", report["steps"][5]["status"])
        self.assertIsNone(report["steps"][3]["response_hex"])

    def test_forged_terminal_summary_and_campaign_order_rejected(self):
        prefix = start() + INITIAL + IDENTITY
        for data in (
            prefix + summary(3),  # omitted mandatory steps, even with correct counts
            prefix + DTC + TAIL + summary(10, flags=255, steps=9, positive=9),
            prefix + DTC + TAIL + summary(10, end=3, flags=231, steps=9, positive=9),
            prefix + step(3, b"\x07", b"\x47\x00") +
                summary(4, end=3, steps=3, positive=3),
            prefix + step(3, b"\x03", b"", 5) +
                summary(4, end=1, steps=3, positive=2),
        ):
            self.assertFalse(decode(io.BytesIO(data))["capture_integrity_verified"])
        fault = prefix + step(3, b"\x03", b"", 5) + summary(4, end=4, steps=3, positive=2)
        self.assertTrue(decode(io.BytesIO(fault))["capture_integrity_verified"])
        initial_refused = start() + step(1, b"\x01\x00", b"\x7F\x01\x11", 3)
        initial_refused += summary(2, end=2, flags=0, steps=1, positive=0, negative=1)
        self.assertTrue(decode(io.BytesIO(initial_refused))["capture_integrity_verified"])

    def test_cli_new_file_and_declared_labels(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "capture.cj"
            target = Path(folder) / "report.json"
            source.write_bytes(CAPTURE)
            args = ["--input", str(source), "--output", str(target), "--vehicle", "Camaro 2012",
                    "--ecu", "ECM", "--connector", "bench"]
            result = run(args)
            self.assertEqual(result, json.loads(target.read_text()))
            self.assertEqual("Camaro 2012", result["declared_vehicle"])
            self.assertFalse(result["hardware_provenance_verified"])
            with self.assertRaises(FileExistsError):
                run(args)
