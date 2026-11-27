import dataclasses
import hmac
import io
import struct
import unittest
import zlib

from camarodiagconnectors.j1979_live_native import (
    NativeLiveProfile, decode, hello, proof, HEADER, OBSERVATION, FINAL,
)


VIN = "1G1FA1RXXR0123456"
PROFILE = NativeLiveProfile(0x7E0, 0x7E8, False, VIN, (5, 12), 2, 100, 1000)


def record(kind, seq, body):
    header = HEADER.pack(b"CL", 1, kind, seq, len(body))
    return header + body + struct.pack("<I", zlib.crc32(header + body))


def observation(phase, request, response, start, cycle=0, pid=0, state=1, accepted=1):
    return OBSERVATION.pack(phase, state, accepted, 0, cycle, pid, 0, start, start+5,
                            100, len(response), 5, request) + response


def fixture():
    vin = b"\x49\x02\x01" + VIN.encode("ascii")
    events = [
        observation(1, b"\x09\x02", vin, 0),
        observation(2, b"\x01\x00", bytes.fromhex("410008100000"), 5),
        observation(4, b"\x01\x05", bytes.fromhex("410550"), 10, 1, 5, 2),
        observation(4, b"\x01\x0c", bytes.fromhex("410C1234"), 15, 1, 12, 2),
        observation(4, b"\x01\x05", bytes.fromhex("410551"), 110, 2, 5, 2),
        observation(4, b"\x01\x0c", bytes.fromhex("410C1235"), 115, 2, 12, 2),
        observation(5, b"\x09\x02", vin, 120),
    ]
    bitmap = bytearray(32)
    bitmap[0] = 1 << 5
    bitmap[1] = 1 << 4
    final = FINAL.pack(0, 15, 2, 7, 4, 4, 0, 0, 125, 60,
                       bytes(bitmap), 2, 2, *([0]*30))
    return [(1, PROFILE.capture_plan()), *((2, e) for e in events), (3, final)]


def stream(items):
    return b"".join(record(k, i, b) for i, (k, b) in enumerate(items))


class NativeLiveTests(unittest.TestCase):
    def test_success_and_no_provenance_claim(self):
        report = decode(io.BytesIO(stream(fixture())), PROFILE)
        self.assertIsNone(report["error"])
        self.assertTrue(report["capture_integrity_verified"])
        self.assertTrue(report["summary"]["completed"])
        self.assertEqual(report["summary"]["raw_per_pid"], [{"pid":5,"count":2},{"pid":12,"count":2}])
        self.assertFalse(report["physical_vehicle_verified"])
        self.assertFalse(report["device_credential_authenticated"])

    def test_semantic_tampering_with_valid_crc(self):
        for index, offset, value in [(3,25,6),(4,6,5),(5,8,20),(8,12,5),
                                      (1,3,1),(2,28,0),(7,27,0)]:
            with self.subTest(index=index, offset=offset):
                items = fixture()
                kind, original = items[index]
                body = bytearray(original)
                body[offset] = value
                items[index] = (kind, bytes(body))
                report = decode(io.BytesIO(stream(items)), PROFILE)
                self.assertFalse(report["capture_integrity_verified"])
                self.assertIsNone(report["result"])

    def test_truncation_and_trailing_bytes(self):
        raw = stream(fixture())
        for candidate in (raw[:-1], raw+b"\0", raw[:8], b"", raw[:100]):
            self.assertFalse(decode(io.BytesIO(candidate), PROFILE)["capture_integrity_verified"])

    def test_bad_crc(self):
        raw = bytearray(stream(fixture()))
        raw[12] ^= 1
        self.assertFalse(decode(io.BytesIO(raw))["capture_integrity_verified"])

    def test_profile_binding(self):
        self.assertEqual(NativeLiveProfile.from_auth_profile(PROFILE.auth_profile()), PROFILE)
        self.assertFalse(decode(io.BytesIO(stream(fixture())),
                                dataclasses.replace(PROFILE, pids=(12,5)))["capture_integrity_verified"])
        for other in (dataclasses.replace(PROFILE, pids=(5,5)), dataclasses.replace(PROFILE, pids=(32,)),
                      dataclasses.replace(PROFILE, interval_ms=99), dataclasses.replace(PROFILE, cycles=0),
                      dataclasses.replace(PROFILE, vin="INVALID"), dataclasses.replace(PROFILE, rx=0x7e9)):
            with self.assertRaises(ValueError):
                other.validate()

    def test_cu3_domain_separation_and_exact_scope(self):
        key = bytes(range(32))
        nonce = bytes(range(32,64))
        request = hello(PROFILE, nonce)
        self.assertEqual(len(request),107)
        device = b"\x5a"*32
        transcript = request[4:]+device
        challenge = b"CU\x03\x02"+device+hmac.digest(key,b"L"+transcript,"sha256")
        self.assertEqual(proof(request,challenge,key),
                         b"CU\x03\x03"+hmac.digest(key,b"l"+transcript,"sha256"))
        for invalid in (b"CU\x02\x02"+challenge[4:], challenge[:-1]+bytes((challenge[-1]^1,)),
                        b"CU\x03\x02"+device+hmac.digest(key,b"S"+transcript,"sha256")):
            with self.assertRaises(ValueError):
                proof(request,invalid,key)
        with self.assertRaises(ValueError):
            proof(hello(dataclasses.replace(PROFILE,pids=(12,5)),nonce),challenge,key)

    def test_rejected_final_completion(self):
        items = fixture()
        items.pop(-2)
        self.assertFalse(decode(io.BytesIO(stream(items)))["capture_integrity_verified"])


if __name__ == "__main__":
    unittest.main()
