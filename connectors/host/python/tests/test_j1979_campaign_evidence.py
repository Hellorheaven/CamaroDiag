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
INITIAL = step(1, bytes.fromhex("0100"), bytes.fromhex("410080000000"))
IDENTITY = step(2, bytes.fromhex("0902"), b"\x49\x02\x01" + VIN)
CAPTURE = start() + INITIAL + IDENTITY + summary(3)


class CaptureTests(unittest.TestCase):
    def test_complete_integrity_not_hardware_verification(self):
        report = decode(io.BytesIO(CAPTURE))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertFalse(report["physical_vehicle_verified"])
        self.assertEqual(VIN.decode(), report["observed_vin"])
        self.assertEqual("COMPLETE", report["summary"]["end"])
        self.assertEqual("PASSED", report["steps"][0]["status"])

    def test_corruption_and_truncation_keep_prefix(self):
        for tail in (IDENTITY[:-1], IDENTITY[:-1] + bytes([IDENTITY[-1] ^ 1])):
            report = decode(io.BytesIO(start() + INITIAL + tail))
            self.assertFalse(report["capture_integrity_verified"])
            self.assertEqual(1, len(report["steps"]))
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

    def test_partial_and_raw_evidence(self):
        data = start() + INITIAL + step(2, b"\x01\xA1", b"\x41\xA1\x01", 2)
        data += step(3, b"\x07", b"", 4)
        data += summary(4, end=3, steps=3, positive=1, raw=1, timeout=1)
        report = decode(io.BytesIO(data))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual("BUDGET", report["summary"]["end"])
        self.assertEqual("RAW", report["steps"][1]["status"])
        self.assertIsNone(report["steps"][2]["response_hex"])

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
