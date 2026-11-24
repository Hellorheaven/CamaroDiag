import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from camarodiagconnectors.offline_cli import run


class DoIpArchiveTests(unittest.TestCase):
    def test_cross_platform_profile_and_archive_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile, archive = root / "profile.json", root / "backup.zip"
            p = {"vin": "1G1FA1RXXR0123456", "gateway": "1234", "tester": "0E80",
                 "ecu": "1234", "identityDid": "1235", "identityHex": "A1B2",
                 "startAddress": "100", "lengthBytes": 4, "blockSize": 2,
                 "region": "CALIBRATION"}
            profile.write_text(json.dumps(p))
            data = b"ABCD"
            manifest = {"format": "CamaroDiag-DoIP-memory-v1", "transport": "DoIP",
                        "coverage": "SCOPED_REGION_ONLY", "origin": "REPORTED_HARDWARE_READ",
                        "capturedAtUtc": "2026-11-24T12:00:00.1234567+00:00",
                        "scope": {"Vin": p["vin"], "Gateway": 0x1234, "Tester": 0xE80,
                                  "Ecu": 0x1234, "IdentityDid": 0x1235, "IdentityHex": "A1B2",
                                  "Start": 0x100, "Length": 4, "BlockSize": 2,
                                  "Region": "CALIBRATION"},
                        "sha256": hashlib.sha256(data).hexdigest().upper()}

            def save(second):
                with ZipFile(archive, "w") as zip:
                    zip.writestr("manifest.json", json.dumps(manifest))
                    zip.writestr("original.bin", data)
                    zip.writestr("verification.bin", second)

            args = ["verify-doip", "--archive", str(archive), "--profile", str(profile)]
            save(data)
            result = run(args)
            self.assertTrue(result["archive_integrity_verified"])
            self.assertFalse(result["physical_read_verified"])
            self.assertFalse(result["write_authorized"])
            save(b"ABCE")
            with self.assertRaises(ValueError):
                run(args)
            save(data)
            p["ecu"] = "1236"
            profile.write_text(json.dumps(p))
            with self.assertRaises(ValueError):
                run(args)
