import copy
import hashlib
import hmac
import io
import struct
import unittest
import zlib

from camarodiagconnectors.j1979_freeze import FreezePlan, Read, execute_plan
from camarodiagconnectors.j1979_freeze_native import (
    NativeProfile, hello, proof, decode, HEADER, OBSERVATION, FINAL, OUTCOMES, STOPS, STATES,
)
from camarodiagconnectors.j1979_freeze_native_cli import capture_live
from camarodiagconnectors.j1979_freeze_native_inspection import verify

VIN = "1G1FA1E30D1234567"
KEY = bytes(range(32))
NONCE = bytes(range(1, 33))
SERVER = bytes(range(33, 65))
PROFILE = NativeProfile(0x7e0, 0x7e8, False, FreezePlan(VIN, (0, 1), (12, 13), 60000))


def record(kind, seq, body):
    header = HEADER.pack(b"CF", 1, kind, seq, len(body))
    return header + body + struct.pack("<I", zlib.crc32(header + body))


def response(request):
    if request == b"\x09\x02":
        return b"\x49\x02\x01" + VIN.encode("ascii")
    pid, frame = request[1:]
    data = {0: bytes.fromhex("40180000"), 2: bytes.fromhex("0301"),
            12: bytes.fromhex("1F40"), 13: bytes((80,))}[pid]
    return bytes((0x42, pid, frame)) + data


def fixture(profile=PROFILE, replacement=None):
    observed = []

    def observe(request):
        reply = replacement(request, len(observed)) if replacement else Read(
            "RECEIVED", response(request), 5, True)
        observed.append((request, reply))
        return reply

    result = execute_plan(profile.plan, observe)
    body = [record(1, 0, profile.encode())]
    codes = {v: k for k, v in OUTCOMES.items()}
    for seq, (request, reply) in enumerate(observed, 1):
        payload = reply.response or b""
        data = OBSERVATION.pack(codes[reply.outcome], int(reply.transmission_attempted),
                                len(request), 0, reply.duration_ms, len(payload))
        body.append(record(2, seq, data + request + payload))
    states = {v: k for k, v in STATES.items()}
    stops = {v: k for k, v in STOPS.items()}
    frame_states = [states[f["status"]] for f in result["frames"]]
    flags = int(result["completed"]) | (int(result["identity_rechecked"]) << 1) | (
        int(result["all_frames_stable"]) << 2)
    terminal = FINAL.pack(stops[result["stop_reason"]], len(frame_states), flags, 0,
                          len(observed), sum(r.duration_ms for _, r in observed),
                          *(frame_states + [0] * (8-len(frame_states))))
    body.append(record(3, len(body), terminal))
    return body, result


class NativeFreezeTests(unittest.TestCase):
    def test_scope_roundtrip(self):
        self.assertEqual(NativeProfile.decode(PROFILE.encode()), PROFILE)
        self.assertEqual(len(PROFILE.encode()), 104)
        for index in (13+20, 13+30):
            altered = bytearray(PROFILE.encode())
            altered[index] = 17
            with self.assertRaises(ValueError):
                NativeProfile.decode(bytes(altered))
        with self.assertRaises(ValueError):
            NativeProfile(0x7df, 0x7e8, False, PROFILE.plan).encode()

    def test_proof_binds_scope_and_version(self):
        req = hello(PROFILE, NONCE)
        transcript = req[4:] + SERVER
        challenge = b"CU\x02\x02" + SERVER + hmac.digest(KEY, b"F" + transcript, "sha256")
        self.assertEqual(proof(req, challenge, KEY),
                         b"CU\x02\x03" + hmac.digest(KEY, b"f" + transcript, "sha256"))
        other = NativeProfile(PROFILE.tx, PROFILE.rx, False, FreezePlan(VIN, (0,), (12,), 60000))
        with self.assertRaises(ValueError):
            proof(hello(other, NONCE), challenge, KEY)
        with self.assertRaises(ValueError):
            proof(req, b"CU\x01\x02" + challenge[4:], KEY)

    def test_complete_capture_replay(self):
        records, result = fixture()
        report = decode(io.BytesIO(b"".join(records)), expected=PROFILE)
        self.assertTrue(report["capture_integrity_verified"], report["error"])
        self.assertTrue(report["summary"]["all_frames_stable"])
        self.assertEqual(report["result"]["frames"], result["frames"])
        self.assertFalse(report["physical_vehicle_verified"])
        self.assertFalse(report["evidence_authenticated"])
        self.assertFalse(report["permits_programming"])

    def test_absent_and_changed_trigger(self):
        def absent(request, index):
            return Read("RECEIVED", bytes.fromhex("4202000000") if request == b"\x02\x02\x00"
                        else response(request), 5, True)
        records, _ = fixture(replacement=absent)
        report = decode(io.BytesIO(b"".join(records)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual(report["summary"]["frame_states"], ["ABSENT", "COMPLETE"])

    def test_vin_mismatch_stops_immediately(self):
        records, _ = fixture(replacement=lambda req, index: Read(
            "RECEIVED", bytes.fromhex("490201") + b"1G1FA1E30D7654321", 5, True))
        report = decode(io.BytesIO(b"".join(records)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual(report["summary"]["stop_reason"], "VIN_MISMATCH")
        self.assertEqual(report["observations_received"], 1)

    def test_corruption_truncation_trailing_and_scope_rejected(self):
        records, _ = fixture()
        raw = b"".join(records)
        mutated = bytearray(raw)
        mutated[30] ^= 1
        for candidate in (bytes(mutated), raw[:-1], raw + b"\0", b"".join(records[:-1]),
                          records[0]+records[0]+b"".join(records[1:])):
            with self.subTest(size=len(candidate)):
                self.assertFalse(decode(io.BytesIO(candidate))["capture_integrity_verified"])
        other = NativeProfile(PROFILE.tx, PROFILE.rx, False, FreezePlan(VIN, (1,), (12,), 60000))
        self.assertFalse(decode(io.BytesIO(raw), expected=other)["capture_integrity_verified"])

    def test_forged_terminal_with_correct_crc_rejected(self):
        records, _ = fixture()
        body = bytearray(records[-1][HEADER.size:-4])
        body[12] = 1
        records[-1] = record(3, len(records)-1, body)
        report = decode(io.BytesIO(b"".join(records)))
        self.assertFalse(report["capture_integrity_verified"])
        self.assertIn("independent replay", report["error"])

    def test_forged_order_with_valid_crc_rejected(self):
        records, _ = fixture()
        body = bytearray(records[2][HEADER.size:-4])
        body[OBSERVATION.size+2] = 1
        records[2] = record(2, 2, body)
        self.assertFalse(decode(io.BytesIO(b"".join(records)))["capture_integrity_verified"])

    def test_live_capture_preserves_evidence_without_authenticating_it(self):
        records, _ = fixture()
        req = hello(PROFILE, NONCE)
        challenge = b"CU\x02\x02" + SERVER + hmac.digest(KEY, b"F" + req[4:] + SERVER, "sha256")
        class Port:
            def __init__(self):
                self.data = io.BytesIO(challenge + b"".join(records))
                self.writes = []
            def read(self, size):
                return self.data.read(min(size, 7))
            def write(self, data):
                self.writes.append(data)
                return len(data)
        port, capture = Port(), io.BytesIO()
        report = capture_live(port, PROFILE, KEY, capture, nonce=NONCE)
        self.assertTrue(report["capture_integrity_verified"], report["error"])
        self.assertTrue(report["device_credential_authenticated"])
        self.assertFalse(report["evidence_authenticated"])
        self.assertEqual(len(port.writes), 2)
        self.assertEqual(capture.getvalue(), b"".join(records))

    def test_native_binary_and_json_binding(self):
        records, _ = fixture()
        raw = b"".join(records)
        digest = hashlib.sha256(raw).hexdigest()
        report = decode(io.BytesIO(raw))
        report["capture_sha256"] = digest
        verified = verify(raw, report, expected_vin=VIN, capture_sha256=digest)
        self.assertTrue(verified["verified"])
        self.assertTrue(verified["host_digest_matched"])
        self.assertFalse(verified["credential_independently_verified"])
        for edit in (
            lambda r:r.update(capture_sha256="00"*32),
            lambda r:r.update(device_credential_authenticated=True),
            lambda r:r.update(permits_programming=True),
            lambda r:r["summary"].update(all_frames_stable=False),
            lambda r:r["profile"]["plan"].update(frames=[0]),
            lambda r:r["result"]["steps"][0].update(response_hex="490201"),
        ):
            forged = copy.deepcopy(report)
            edit(forged)
            with self.assertRaises(ValueError):
                verify(raw, forged, capture_sha256=digest)

    def test_bad_auth_never_sends_client_proof(self):
        class Port:
            def __init__(self):
                self.writes = []
                self.data = io.BytesIO(b"CU\x02\x02" + SERVER + bytes(32))
            def read(self, size):
                return self.data.read(size)
            def write(self, data):
                self.writes.append(data)
                return len(data)
        port = Port()
        report = capture_live(port, PROFILE, KEY, io.BytesIO(), nonce=NONCE)
        self.assertFalse(report["capture_integrity_verified"])
        self.assertFalse(report["device_credential_authenticated"])
        self.assertEqual(len(port.writes), 1)


if __name__ == "__main__":
    unittest.main()
