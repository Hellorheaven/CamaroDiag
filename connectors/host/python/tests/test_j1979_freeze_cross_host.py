"""Cross-host freeze-frame evidence regression cases."""
import copy
import unittest

from camarodiagconnectors.j1979_freeze_inspection import inspect
from test_j1979_freeze import campaign, envelope, VIN


def elm_frames(payload, rx="7E8"):
    if len(payload) <= 7:
        parts = [bytes((len(payload),)) + payload]
    else:
        parts = [bytes((0x10 | (len(payload) >> 8), len(payload) & 255)) + payload[:6]]
        offset, sequence = 6, 1
        while offset < len(payload):
            parts.append(bytes((0x20 | sequence,)) + payload[offset:offset+7])
            offset += 7
            sequence = (sequence + 1) & 15
    return "\r".join(rx + " " + part.hex(" ").upper() for part in parts) + "\r>"


def windows():
    report = envelope(campaign())
    del report["interface"]
    report.update(transport="WINDOWS_J2534_ISOTP", adapter="fixture", profile=33)
    return report


def android():
    report = windows()
    report.update(transport="ANDROID_ELM_ISOTP", profile=6,
                  raw_protocol_response="6\r>", observed_protocol=6,
                  adapter_exchanges=[
                      {"request_hex": row["request_hex"],
                       "raw_adapter_response": elm_frames(bytes.fromhex(row["response_hex"]))}
                      for row in report["result"]["steps"]])
    return report


class CrossHostFreezeTests(unittest.TestCase):
    def test_windows_and_integral_numbers(self):
        original = windows()
        for row in original["result"]["steps"]:
            measurement = (row["evidence"] or {}).get("measurement")
            if measurement and measurement["number"] == int(measurement["number"]):
                measurement["number"] = int(measurement["number"])
        checked = inspect(original, expected_vin=VIN)
        self.assertTrue(checked["consistent"])
        self.assertFalse(checked["adapter_transcript_replayed"])
        self.assertFalse(checked["physical_vehicle_verified"])

    def test_wrong_windows_profile_rejected(self):
        original = windows()
        original["profile"] = 34
        with self.assertRaises(ValueError):
            inspect(original)

    def test_android_selected_responder_replay(self):
        checked = inspect(android(), expected_vin=VIN)
        self.assertTrue(checked["adapter_transcript_replayed"])
        self.assertTrue(checked["consistent"])
        self.assertTrue(checked["all_frames_stable"])

    def test_android_wrong_address_rejected(self):
        original = android()
        original["adapter_exchanges"][0]["raw_adapter_response"] = (
            original["adapter_exchanges"][0]["raw_adapter_response"].replace("7E8", "7E9"))
        with self.assertRaises(ValueError):
            inspect(original)

    def test_android_protocol_cannot_be_inferred(self):
        for raw in ("A6\r>", "6\r7\r>", "OK\r>", ""):
            original = android()
            original["raw_protocol_response"] = raw
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                inspect(original)

    def test_android_truncated_and_duplicate_transcripts(self):
        original = android()
        for raw in ("7E8 10 14 49 02 01 31 47 31\r>",
                    "7E8 01 42\r7E8 01 42\r>",
                    "7E8 10 14 49 02 01 31 47 31\r7E8 22 46 41 31 45 33 30 44\r>"):
            altered = copy.deepcopy(original)
            altered["adapter_exchanges"][0]["raw_adapter_response"] = raw
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                inspect(altered)

    def test_adapter_exchange_order_and_payload_checked(self):
        original = android()
        altered = copy.deepcopy(original)
        altered["adapter_exchanges"].reverse()
        with self.assertRaises(ValueError):
            inspect(altered)
        altered = copy.deepcopy(original)
        altered["adapter_exchanges"][1]["raw_adapter_response"] = elm_frames(bytes.fromhex("42000000000000"))
        with self.assertRaises(ValueError):
            inspect(altered)

    def test_claimed_measurement_cannot_be_boolean_or_modified(self):
        original = windows()
        for value in (True, 9999):
            changed = copy.deepcopy(original)
            for step in changed["result"]["steps"]:
                evidence = step["evidence"] or {}
                if "measurement" in evidence:
                    evidence["measurement"]["number"] = value
                    break
            with self.assertRaises(ValueError):
                inspect(changed)


if __name__ == "__main__":
    unittest.main()
