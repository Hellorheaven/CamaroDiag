import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

from camarodiagconnectors.doip import DoIpError
from camarodiagconnectors.doip_archive import load_scope, verify_archive
from camarodiagconnectors.doip_capture import capture_region, save_capture


class FakeSession:
    def __init__(self, scope, changed=False):
        self.gateway = SimpleNamespace(vin=scope["Vin"], logical_address=scope["Gateway"])
        self.tester, self.ecu = scope["Tester"], scope["Ecu"]
        self.closed = False
        self.reads = []
        self.changed = changed

    def verify_vin(self, vin):
        return vin

    def read_did(self, did):
        return bytes.fromhex("A1B2")

    def exchange(self, request):
        self.reads.append(request)
        address = int.from_bytes(request[2:6], "big")
        count = int.from_bytes(request[6:], "big")
        value = bytes(range(address - 0x100, address - 0x100 + count))
        if self.changed and len(self.reads) > 2:
            value = b"X" * count
        return b"\x63" + value

    def close(self):
        self.closed = True


class DoIpCaptureTests(unittest.TestCase):
    def test_capture_and_exclusive_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile, output = root / "profile.json", root / "capture.zip"
            profile.write_text(json.dumps({"vin": "1G1FA1RXXR0123456", "gateway": "1234",
                "tester": "0E80", "ecu": "1234", "identityDid": "1235", "identityHex": "A1B2",
                "startAddress": "100", "lengthBytes": 4, "blockSize": 2, "region": "CALIBRATION"}))
            scope = load_scope(profile)
            session = FakeSession(scope)
            a, b = capture_region(session, scope)
            self.assertEqual(a, b"\x00\x01\x02\x03")
            self.assertEqual(session.reads, [bytes.fromhex("23140000010002"), bytes.fromhex("23140000010202")] * 2)
            result = save_capture(output, profile, scope, a, b)
            self.assertTrue(result["archive_integrity_verified"])
            self.assertTrue(verify_archive(output, profile)["archive_integrity_verified"])
            self.assertFalse(result["physical_read_verified"])
            with self.assertRaises(FileExistsError):
                save_capture(output, profile, scope, a, b)
            bad = FakeSession(scope, changed=True)
            with self.assertRaises(DoIpError):
                capture_region(bad, scope)
            self.assertTrue(bad.closed)
            self.assertTrue(output.exists())
