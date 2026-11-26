import hashlib
import hmac
import io
import struct
import unittest
import zlib

from camarodiagconnectors.j1979_usb_auth import hello
from camarodiagconnectors.j1979_usb_live import run_campaign


def record(kind, seq, body):
    header = struct.pack("<2sBBHH", b"CJ", 1, kind, seq, len(body))
    return header + body + struct.pack("<I", zlib.crc32(header + body))


class FakePort:
    def __init__(self, incoming):
        self.incoming = bytearray(incoming)
        self.writes = []
        self.now = 0.0

    def write(self, data):
        self.writes.append(data)
        return len(data)

    def read(self, size):
        self.now += 0.1
        piece = bytes(self.incoming[:min(size, 7)])
        del self.incoming[:len(piece)]
        return piece


class LiveUsbTests(unittest.TestCase):
    def setUp(self):
        self.key = bytes(range(32))
        self.nonce = b"H" * 32
        request = hello(0x7E0, 0x7E8, False, 10000, nonce=self.nonce)
        self.challenge = b"CU\x01\x02" + b"D" * 32 + hmac.digest(
            self.key, b"S" + request[4:] + b"D" * 32, hashlib.sha256)
        self.start = record(1, 0, struct.pack("<IIBI", 0x7E0, 0x7E8, 0, 10000))
        observation = record(2, 1, struct.pack("<BBIH", 4, 2, 100, 0) + b"\x01\x00")
        summary = record(3, 2, struct.pack("<BBIIIII", 2, 0, 1, 0, 0, 0, 1))
        self.capture = self.start + observation + summary

    def run_fake(self, incoming):
        port = FakePort(incoming)
        raw = io.BytesIO()
        report = run_campaign(port, key=self.key, tx=0x7E0, rx=0x7E8,
            extended=False, budget_ms=10000, capture=raw, clock=lambda: port.now,
            nonce=self.nonce)
        return report, raw.getvalue(), port.writes

    def test_authenticated_partial_vehicle_result(self):
        report, raw, writes = self.run_fake(self.challenge + self.capture)
        self.assertTrue(report["device_credential_authenticated"])
        self.assertTrue(report["capture_integrity_verified"])
        self.assertFalse(report["evidence_authenticated"])
        self.assertEqual(report["summary"]["end"], "INITIAL_UNVERIFIED")
        self.assertEqual(raw, self.capture)
        self.assertEqual(len(writes), 2)

    def test_bad_auth_sends_no_proof(self):
        report, raw, writes = self.run_fake(self.challenge[:-1] + b"X")
        self.assertFalse(report["device_credential_authenticated"])
        self.assertFalse(report["capture_integrity_verified"])
        self.assertEqual(raw, b"")
        self.assertEqual(len(writes), 1)

    def test_truncated_capture_keeps_prefix_and_never_retries(self):
        report, raw, writes = self.run_fake(self.challenge + self.capture[:-1])
        self.assertFalse(report["capture_integrity_verified"])
        self.assertEqual(raw, self.capture[:-1])
        self.assertEqual(len(writes), 2)

    def test_extra_bytes_and_wrong_profile(self):
        report, _, _ = self.run_fake(self.challenge + self.capture + b"X")
        self.assertFalse(report["capture_integrity_verified"])
        other = record(1, 0, struct.pack("<IIBI", 0x7E1, 0x7E9, 0, 10000))
        report, _, _ = self.run_fake(self.challenge + other + self.capture[len(self.start):])
        self.assertFalse(report["capture_integrity_verified"])
