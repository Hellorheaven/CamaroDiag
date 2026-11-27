"""Interrupted CF/1 tests use synthetic ECU responses, never real vehicle data."""
import copy
import hashlib
import io
import struct
import tempfile
import unittest
from pathlib import Path

from camarodiagconnectors.j1979_freeze import Read
from camarodiagconnectors.j1979_freeze_native import HEADER, OBSERVATION, decode
from camarodiagconnectors.j1979_freeze_native_prefix import (
    SCHEMA, inspect_prefix, inspect_prefix_file,
)
from test_j1979_freeze_native import fixture, record, response, VIN


def original():
    return fixture()[0]


class FreezeNativePrefixTests(unittest.TestCase):
    def check_no_promotion(self, report, *, count):
        self.assertEqual(report["schema"], SCHEMA)
        self.assertTrue(report["prefix_integrity_verified"])
        self.assertFalse(report["capture_integrity_verified"])
        self.assertFalse(report["execution_completed"])
        self.assertFalse(report["terminal_present"])
        self.assertFalse(report["physical_vehicle_verified"])
        self.assertFalse(report["hardware_provenance_verified"])
        self.assertFalse(report["evidence_authenticated"])
        self.assertFalse(report["permits_programming"])
        self.assertIsNone(report["diagnostic_verdict"])
        self.assertEqual(report["complete_observations"], count)
        self.assertEqual(len(report["independently_replayed_steps"]), count)

    def test_missing_terminal_with_all_observations_is_not_completed(self):
        parts = original()
        raw = b"".join(parts[:-1])
        result = inspect_prefix(raw, expected_vin=VIN)
        self.check_no_promotion(result, count=len(parts)-2)
        self.assertEqual(result["interruption"], "MISSING_TERMINAL")
        self.assertEqual(result["discarded_tail_bytes"], 0)
        self.assertFalse(result["replay_terminated_by_missing_observation"])
        self.assertEqual(result["last_complete_offset"], len(raw))
        self.assertEqual(result["observed_final_vin_in_prefix"], VIN)
        self.assertTrue(decode(io.BytesIO(raw))["error"])

    def test_plan_only_preserves_context_and_zero_observations(self):
        first = original()[0]
        report = inspect_prefix(first, expected_vin=VIN)
        self.check_no_promotion(report, count=0)
        self.assertEqual(report["complete_records"], 1)
        self.assertEqual(report["visited_frame_numbers_in_prefix"], [])
        self.assertTrue(report["replay_terminated_by_missing_observation"])
        self.assertIsNone(report["observed_initial_vin_in_prefix"])

    def test_truncated_header_does_not_replay_tail(self):
        parts = original()
        raw = b"".join(parts[:4]) + parts[4][:5]
        report = inspect_prefix(raw, expected_vin=VIN)
        self.check_no_promotion(report, count=3)
        self.assertEqual(report["last_complete_offset"], sum(map(len, parts[:4])))
        self.assertEqual(report["discarded_tail_bytes"], 5)
        self.assertEqual(report["interruption"], "TRUNCATED_HEADER")

    def test_truncated_payload_and_crc_not_trusted(self):
        parts = original()
        for cut in (9, 11, len(parts[4])-1):
            raw = b"".join(parts[:4]) + parts[4][:cut]
            result = inspect_prefix(raw, expected_vin=VIN)
            self.check_no_promotion(result, count=3)
            self.assertEqual(result["interruption"], "TRUNCATED_RECORD")
            self.assertEqual(result["discarded_tail_bytes"], cut)

    def test_complete_corruption_fails_even_with_valid_earlier_records(self):
        parts = original()
        corrupted = bytearray(parts[4])
        corrupted[HEADER.size+4] ^= 1
        with self.assertRaises(ValueError):
            inspect_prefix(b"".join(parts[:4]) + corrupted, expected_vin=VIN)

    def test_wrong_record_sequence_and_header_fields(self):
        parts = original()
        for position, replacement in ((2, 2), (3, 1), (4, 9), (5, 3)):
            mutated = bytearray(parts[4][:position+1])
            mutated[position] = replacement
            with self.subTest(position=position), self.assertRaises(ValueError):
                inspect_prefix(b"".join(parts[:4]) + mutated, expected_vin=VIN)

    def test_complete_terminal_requires_full_inspector(self):
        with self.assertRaisesRegex(ValueError, "complete terminal"):
            inspect_prefix(b"".join(original()), expected_vin=VIN)
        with self.assertRaises(ValueError):
            inspect_prefix(b"".join(original()) + b"X", expected_vin=VIN)

    def test_wrong_vin_and_missing_plan_cannot_be_admitted(self):
        parts = original()
        with self.assertRaises(ValueError):
            inspect_prefix(b"".join(parts[:2]), expected_vin="1G1FA1E30D7654321")
        for value in (b"", parts[0][:50], parts[1], b"CF\x01\x01\x00"):
            with self.subTest(size=len(value)), self.assertRaises(ValueError):
                inspect_prefix(value, expected_vin=VIN)

    def test_semantic_replay_checks_request_order_with_valid_crc(self):
        parts = original()
        altered = bytearray(parts[3])
        # An observation uses a 10-byte fixed header, then the request.
        request_offset = HEADER.size + OBSERVATION.size
        altered[request_offset + 1] ^= 0x20
        body = bytes(altered[HEADER.size:-4])
        parts[3] = record(2, 3, body)
        with self.assertRaisesRegex(ValueError, "recipe"):
            inspect_prefix(b"".join(parts[:-1]), expected_vin=VIN)

    def test_no_implicit_last_frame_completion_on_early_stoppage(self):
        parts = original()
        raw = b"".join(parts[:5])
        result = inspect_prefix(raw, expected_vin=VIN)
        self.check_no_promotion(result, count=4)
        self.assertTrue(result["replay_terminated_by_missing_observation"])
        self.assertIsNone(result["observed_final_vin_in_prefix"])

    def test_preexchange_context_failure_is_kept_as_observation(self):
        def replacement(request, index):
            if index == 1:
                return Read("CONTEXT_CHANGED", None, 0, False)
            return Read("RECEIVED", response(request), 5, True)
        parts, _ = fixture(replacement=replacement)
        result = inspect_prefix(b"".join(parts[:-1]), expected_vin=VIN)
        self.check_no_promotion(result, count=2)
        self.assertFalse(result["replay_terminated_by_missing_observation"])
        self.assertEqual(result["independently_replayed_steps"][1]["transport_outcome"], "CONTEXT_CHANGED")

    def test_file_replay_is_pinned_to_original_sha256(self):
        parts = original()
        raw = b"".join(parts[:-1])
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / "interrupted.cf1"
            path.write_bytes(raw)
            document = inspect_prefix_file(path, expected_vin=VIN)
            self.assertEqual(document["capture_sha256"], hashlib.sha256(raw).hexdigest())
            self.check_no_promotion(document, count=len(parts)-2)
            path.write_bytes(raw + b"X")
            self.assertNotEqual(inspect_prefix_file(path, expected_vin=VIN)["capture_sha256"],
                                document["capture_sha256"])


if __name__ == "__main__":
    unittest.main()
