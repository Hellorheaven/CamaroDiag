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


class NativePrefixTests(unittest.TestCase):
    def test_prefix_boundaries(self):
        from camarodiagconnectors.j1979_live_native import inspect_prefix
        items = fixture()
        complete = stream(items[:-1])
        for tail, reason in ((b"", "MISSING_TERMINAL"),
                             (record(3, len(items)-1, items[-1][1])[:5], "TRUNCATED_HEADER"),
                             (record(3, len(items)-1, items[-1][1])[:-1], "TRUNCATED_RECORD")):
            with self.subTest(reason=reason):
                value = inspect_prefix(complete + tail, expected_vin=VIN)
                self.assertTrue(value["prefix_integrity_verified"])
                self.assertFalse(value["capture_integrity_verified"])
                self.assertFalse(value["execution_completed"])
                self.assertFalse(value["terminal_present"])
                self.assertEqual(value["interruption"], reason)
                self.assertEqual(value["complete_observations"], 7)
                self.assertEqual(value["raw_samples_in_prefix"], 4)
                self.assertEqual(value["last_complete_offset"], len(complete))
                self.assertEqual(value["discarded_tail_bytes"], len(tail))
                self.assertTrue(value["initial_identity_verified_in_prefix"])
                self.assertTrue(value["final_identity_observed_in_prefix"])
                self.assertFalse(value["permits_programming"])

    def test_prefix_corruption_is_not_a_truncation(self):
        from camarodiagconnectors.j1979_live_native import inspect_prefix
        items = fixture()
        base = stream(items[:-1])
        invalid_crc = bytearray(base)
        invalid_crc[-1] ^= 1
        wrong_order = items[:-1]
        wrong_order[3], wrong_order[4] = wrong_order[4], wrong_order[3]
        for invalid in (b"", b"CL", bytes(invalid_crc), stream(wrong_order),
                        base+b"X", base+b"CL\x02", base+b"CL\x01\x01",
                        base+b"CL\x01\x03\x00", stream(items), stream(items)+b"\0"):
            with self.subTest(size=len(invalid)):
                with self.assertRaises(ValueError):
                    inspect_prefix(invalid, expected_vin=VIN)
        with self.assertRaises(ValueError):
            inspect_prefix(base, expected_vin="1FA6P8TH0L5100000")

    def test_cross_host_prefix_verifier_rejects_modified_report(self):
        import json
        import tempfile
        from pathlib import Path
        from camarodiagconnectors.j1979_live_native import inspect_prefix_file
        from camarodiagconnectors.j1979_live_native_cli import verify_prefix_files, run
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            raw_path, report_path, verify_path = (folder / x for x in ("original.cl", "partial.json", "verified.json"))
            original = stream(fixture()[:-1])
            raw_path.write_bytes(original)
            report = run(["inspect-prefix", "--capture", str(raw_path), "--vin", VIN,
                          "--output", str(report_path)])
            self.assertEqual(report, inspect_prefix_file(raw_path, expected_vin=VIN))
            self.assertTrue(verify_prefix_files(raw_path, report_path, expected_vin=VIN)["consistent"])
            verified = run(["verify-prefix", "--capture", str(raw_path), "--report", str(report_path),
                            "--vin", VIN, "--output", str(verify_path)])
            self.assertTrue(verified["prefix_integrity_verified"])
            self.assertFalse(verified["execution_completed"])
            self.assertFalse(verified["capture_integrity_verified"])
            for field, value in (("execution_completed", True), ("last_complete_offset", 1),
                                 ("discarded_tail_bytes", 9), ("raw_samples_in_prefix", 999),
                                 ("capture_sha256", "f"*64), ("observations", [])):
                altered = dict(report)
                altered[field] = value
                report_path.write_text(json.dumps(altered), encoding="utf-8")
                with self.assertRaises(ValueError):
                    verify_prefix_files(raw_path, report_path, expected_vin=VIN)
            report_path.write_text(json.dumps(report), encoding="utf-8")
            raw_path.write_bytes(original + b"CL")
            with self.assertRaises(ValueError):
                verify_prefix_files(raw_path, report_path, expected_vin=VIN)

    def test_capacity_terminal_must_be_earned(self):
        from camarodiagconnectors.j1979_live_native import MAX_RAW_BYTES
        profile = dataclasses.replace(PROFILE, pids=tuple(range(1,32)), cycles=200,
                                      interval_ms=100, budget_ms=600000)
        identity = b"\x49\x02\x01" + VIN.encode("ascii")
        items = [(1, profile.capture_plan()),
                 (2, observation(1, b"\x09\x02", identity, 0)),
                 (2, observation(2, b"\x01\x00", bytes.fromhex("4100FFFFFFFE"), 5))]
        now = 10
        raw_bytes = 26
        samples = 0
        counts = [0] * 32
        for cycle in range(1,201):
            for pid in profile.pids:
                if MAX_RAW_BYTES - raw_bytes < 4095:
                    break
                response = bytes((0x41,pid)) + bytes(4093)
                items.append((2, observation(4, bytes((1,pid)), response, now, cycle, pid, 2)))
                raw_bytes += len(response)
                samples += 1
                counts[pid-1] += 1
                now += 5
            else:
                continue
            break
        bitmap = bytearray(32)
        for pid in profile.pids:
            bitmap[pid//8] |= 1 << (pid%8)
        terminal = FINAL.pack(11, 5, samples//31, len(items)-1, samples, samples,
                              0, 0, now, raw_bytes, bytes(bitmap), *counts)
        report = decode(io.BytesIO(stream(items + [(3,terminal)])), profile)
        self.assertTrue(report["capture_integrity_verified"], report["error"])
        self.assertEqual(report["summary"]["stop_reason"], "EVIDENCE_LIMIT")
        self.assertFalse(report["summary"]["completed"])
        self.assertEqual(report["summary"]["raw"], samples)
        # A terminal claiming capacity with only two normal samples is forged.
        forged = fixture()[:5]
        bitmap = bytearray(32)
        bitmap[0], bitmap[1] = 32, 16
        terminal = FINAL.pack(11, 5, 1, 4, 2, 2, 0, 0, 135, 33,
                              bytes(bitmap), 1, 1, *([0]*30))
        self.assertFalse(decode(io.BytesIO(stream(forged + [(3,terminal)])), PROFILE)
                         ["capture_integrity_verified"])


if __name__ == "__main__":
    unittest.main()
