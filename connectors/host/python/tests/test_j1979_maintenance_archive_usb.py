"""Independent host/connector CU/5 fixtures; no serial hardware required."""
import hashlib
import hmac
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from camarodiagconnectors.j1979_maintenance_archive_usb import capture_archive
from camarodiagconnectors.j1979_maintenance_journal import inspect, inspect_prefix
from camarodiagconnectors.j1979_maintenance_journal_cli import run, verify_files
from camarodiagconnectors.j1979_maintenance_usb import scope_bytes
from test_j1979_maintenance_journal import fixture, VIN

KEY = bytes(range(32))
HOST = b"H"*32
DEVICE = b"D"*32


def packet(scope, journal, *, terminal=True, damaged=False, recovery=False, records=4):
    """Build the envelope independently from the implementation."""
    hello = b"CU\x05\x01" + scope_bytes(scope) + HOST
    transcript = hello[4:] + DEVICE
    challenge = b"CU\x05\x02" + DEVICE + hmac.digest(KEY, b"R"+transcript, "sha256")
    proof = b"CU\x05\x03" + hmac.digest(KEY, b"r"+transcript, "sha256")
    flags = int(terminal) | int(damaged)*2 | int(recovery)*4
    header = b"CU\x05\x04" + bytes((flags, records, 0, 0)) + struct.pack("<I",len(journal))
    receipt = b"CU\x05\x05" + struct.pack("<II",len(journal),zlib.crc32(journal))
    return hello,challenge,proof,header+journal+receipt


class Serial:
    def __init__(self, hello, challenge, proof, body, *, chunk=7, fail_write=0, initial_trailing=b""):
        self.expected = (hello,proof)
        self.responses = (challenge+initial_trailing,body)
        self.writes = []
        self.buffer = b""
        self.chunk = chunk
        self.fail_write = fail_write
        self.elapsed = 0.0
        self.reads = 0

    @property
    def in_waiting(self):
        return len(self.buffer)

    def clock(self):
        # Advance time when the fake hardware is read or written rather
        # than on each clock check. A missing receipt terminates by deadline.
        return self.elapsed

    def write(self, data):
        self.elapsed += .001
        self.writes.append(data)
        index = len(self.writes)-1
        if index >= len(self.expected):
            raise AssertionError("Unexpected retry or maintenance command")
        if data != self.expected[index]:
            raise AssertionError("Different CU/5 transcript")
        if len(self.writes)==self.fail_write:
            return len(data)-1
        self.buffer = self.responses[index]
        return len(data)

    def read(self, n):
        self.elapsed += .002 if self.buffer else .5
        self.reads += 1
        take = min(n,self.chunk,len(self.buffer))
        result,self.buffer = self.buffer[:take],self.buffer[take:]
        return result


class ArchiveTest(unittest.TestCase):
    def acquire(self, scope, packets, *, chunk=7, fail_write=0, initial_trailing=b"", output=None, current=None):
        serial = Serial(*packets,chunk=chunk,fail_write=fail_write,initial_trailing=initial_trailing)
        if output is None:
            output = io.BytesIO()
        allowed = (lambda: True) if current is None else lambda: current(serial)
        result = capture_archive(serial,scope,KEY,output,nonce=HOST,clock=serial.clock,current=allowed)
        self.assertFalse(result["maintenance_command_sent"])
        self.assertFalse(result["physical_vehicle_verified"])
        self.assertFalse(result["physical_stop_independently_verified"])
        self.assertFalse(result["permits_automatic_retry"])
        self.assertFalse(result["permits_programming"])
        self.assertFalse(result["evidence_authenticated"])
        return result,serial,output

    def test_complete_and_uncertain_journals(self):
        for op in (1,2):
            for scenario in ({},{"refusal":True},{"uncertain":True}):
                scope,_,_,records = fixture(op,**scenario)
                raw = b"".join(records)
                recovery = bool(scenario.get("uncertain"))
                for chunk in (1,7,16,256,1024):
                    with self.subTest(operation=op,scenario=scenario,chunk=chunk):
                        result,serial,output = self.acquire(scope,packet(scope,raw,recovery=recovery),chunk=chunk)
                        self.assertTrue(result["device_credential_authenticated"])
                        self.assertTrue(result["archive_transfer_verified"])
                        self.assertTrue(result["archive_close_receipt_verified"])
                        self.assertEqual(result["recovery_required"],recovery)
                        self.assertEqual(result["inspection"],inspect(raw,expected_vin=VIN))
                        self.assertEqual(output.getvalue(),raw)
                        self.assertEqual(result["journal_bytes_received"],len(raw))
                        self.assertEqual(len(serial.writes),2)

    def test_original_prefixes_and_damaged_tail(self):
        for op in (1,2):
            scope,_,_,records=fixture(op)
            for count in (1,2,3):
                for damaged in (False,True):
                    raw=b"".join(records[:count])
                    result,serial,output=self.acquire(scope,packet(scope,raw,terminal=False,damaged=damaged,
                                                                  recovery=True,records=count))
                    self.assertTrue(result["archive_transfer_verified"])
                    self.assertTrue(result["archive_close_receipt_verified"])
                    self.assertTrue(result["recovery_required"])
                    self.assertFalse(result["inspection"]["terminal_present"])
                    self.assertEqual(result["declared_archive"]["damaged_tail"],damaged)
                    self.assertEqual(output.getvalue(),raw)
                    self.assertEqual(len(serial.writes),2)

    def test_bad_proof_and_version(self):
        scope,_,_,records=fixture()
        packets=packet(scope,b"".join(records))
        for challenge in (packets[1][:-1]+bytes((packets[1][-1]^1,)),
                          b"CU\x04\x02"+packets[1][4:],
                          packets[1][:4]+bytes(32)+packets[1][36:]):
            result,serial,output=self.acquire(scope,(packets[0],challenge,*packets[2:]))
            self.assertFalse(result["device_credential_authenticated"])
            self.assertFalse(result["archive_transfer_verified"])
            self.assertEqual(len(serial.writes),1)
            self.assertEqual(output.getvalue(),b"")
        result,serial,_=self.acquire(scope,packets,initial_trailing=b"X")
        self.assertFalse(result["archive_transfer_verified"])
        self.assertEqual(len(serial.writes),1)

    def test_partial_write_or_revocation(self):
        scope,_,_,records=fixture()
        packets=packet(scope,b"".join(records))
        for fail in (1,2):
            result,serial,output=self.acquire(scope,packets,fail_write=fail)
            self.assertFalse(result["archive_transfer_verified"])
            self.assertTrue(result["recovery_required"])
            self.assertEqual(len(serial.writes),fail)
            self.assertEqual(output.getvalue(),b"")
        for limit in (0,1,10,50,100):
            result,serial,_=self.acquire(scope,packets,current=lambda s:s.reads<limit)
            self.assertFalse(result["archive_transfer_verified"])
            self.assertTrue(result["recovery_required"])
            self.assertLessEqual(len(serial.writes),2)

    def test_closing_receipt_and_truncation(self):
        scope,_,_,records=fixture()
        raw=b"".join(records)
        packets=packet(scope,raw)
        body=packets[-1]
        for count in (0,1,5,11,12,13,20,len(body)-13,len(body)-12,len(body)-1):
            result,serial,output=self.acquire(scope,(*packets[:3],body[:count]))
            self.assertFalse(result["archive_transfer_verified"])
            self.assertFalse(result["archive_close_receipt_verified"])
            self.assertTrue(result["recovery_required"])
            self.assertEqual(len(serial.writes),2)
            self.assertEqual(output.getvalue(),raw[:max(0,min(len(raw),count-12))])
        for offset in range(12):
            corrupt=bytearray(body);corrupt[len(body)-12+offset]^=1
            result,_,output=self.acquire(scope,(*packets[:3],bytes(corrupt)))
            self.assertFalse(result["archive_transfer_verified"])
            self.assertFalse(result["archive_close_receipt_verified"])
            self.assertEqual(output.getvalue(),raw)
        result,_,_=self.acquire(scope,(*packets[:3],body+b"X"))
        self.assertFalse(result["archive_transfer_verified"])

    def test_declarations_do_not_override_replay(self):
        scope,_,_,records=fixture()
        raw=b"".join(records)
        for options in ({"records":3},{"terminal":False,"recovery":True},{"damaged":True},
                        {"records":0},{"records":5}):
            result,_,_=self.acquire(scope,packet(scope,raw,**options))
            self.assertFalse(result["archive_transfer_verified"])
            self.assertTrue(result["recovery_required"])
        scope,_,_,records=fixture(uncertain=True)
        result,_,_=self.acquire(scope,packet(scope,b"".join(records),recovery=False))
        self.assertFalse(result["archive_transfer_verified"])
        # A fully consistent CRC cannot establish semantic correctness.
        corrupt=bytearray(b"".join(records));corrupt[15]^=1
        result,_,_=self.acquire(scope,packet(scope,bytes(corrupt),recovery=True))
        self.assertFalse(result["archive_transfer_verified"])

    def test_persistence_failure(self):
        class Broken(io.BytesIO):
            def write(self,data):
                return super().write(data[:-1])
        scope,_,_,records=fixture()
        result,serial,_=self.acquire(scope,packet(scope,b"".join(records)),output=Broken())
        self.assertFalse(result["archive_transfer_verified"])
        self.assertTrue(result["recovery_required"])
        self.assertEqual(len(serial.writes),2)

    def test_offline_prefix_inspection_and_verification(self):
        scope,_,_,records=fixture()
        prefix=b"".join(records[:3])
        for tail in (b"",records[3][:1],records[3][:5],records[3][:12],records[3][:-1]):
            raw=prefix+tail
            report=inspect_prefix(raw,expected_vin=VIN)
            self.assertTrue(report["prefix_integrity_verified"])
            self.assertFalse(report["record_consistency_verified"])
            self.assertTrue(report["recovery_required"])
            self.assertEqual(report["verified_prefix_bytes"],len(prefix))
            self.assertEqual(report["unverified_trailing_bytes"],len(tail))
        for raw in (b"".join(records),prefix+b"X",prefix+b"CM\x02",prefix+records[3]+b"X"):
            with self.assertRaises(ValueError):
                inspect_prefix(raw,expected_vin=VIN)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            journal=root/"partial.cm"
            journal.write_bytes(prefix+records[3][:-1])
            report=root/"partial.json"
            output=root/"verified.json"
            run(["inspect-prefix","--journal",str(journal),"--vin",VIN,"--output",str(report)])
            verification=run(["verify-prefix","--journal",str(journal),"--vin",VIN,
                              "--report",str(report),"--output",str(output)])
            self.assertTrue(verification["consistent"])
            self.assertTrue(verification["recovery_required"])
            self.assertEqual(verification["journal_sha256"],hashlib.sha256(journal.read_bytes()).hexdigest())
            value=json.loads(report.read_text());value["unverified_trailing_bytes"]=0
            report.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                verify_files(journal,report,expected_vin=VIN,prefix=True)


if __name__ == "__main__":
    unittest.main()
