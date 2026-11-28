import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from camarodiagconnectors.j1979_campaign_evidence import decode, vin_match
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
INITIAL = step(1, bytes.fromhex("0100"), bytes.fromhex("410000000000"))
IDENTITY = step(2, bytes.fromhex("0902"), b"\x49\x02\x01" + VIN)
DTC = step(3, b"\x03", b"\x43\x00") + step(4, b"\x07", b"\x47\x00") + step(5, b"\x0A", b"\x4A\x00")
TAIL = (step(6, b"\x02\x02\x00", b"\x42\x02\x00\x00\x00") +
        step(7, b"\x06\x00", b"\x46\x00" + bytes(4)) +
        step(8, b"\x08\x00", b"\x48\x00" + bytes(4)) +
        step(9, b"\x09\x00", b"\x49\x00" + bytes(4)))
CAPTURE = start() + INITIAL + IDENTITY + DTC + TAIL + summary(10, flags=231, steps=9, positive=9)


class CaptureTests(unittest.TestCase):
    def test_complete_integrity_not_hardware_verification(self):
        report = decode(io.BytesIO(CAPTURE))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertFalse(report["physical_vehicle_verified"])
        self.assertEqual(VIN.decode(), report["observed_vin"])
        self.assertEqual("COMPLETE", report["summary"]["end"])
        self.assertEqual("PASSED", report["steps"][0]["status"])

    def test_dtc_codes_distinguish_zero_unavailable_and_observed(self):
        dtc = (step(3, b"\x03", bytes.fromhex("43040123456789ABCDEF")) +
               step(4, b"\x07", bytes.fromhex("7F0711"), 3) +
               step(5, b"\x0A", b"\x4A\x00"))
        report = decode(io.BytesIO(start() + INITIAL + IDENTITY + dtc + TAIL +
                                   summary(10, flags=231, steps=9, positive=8, negative=1)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual(["P0123", "C0567", "B09AB", "U0DEF"], report["steps"][2]["dtc_codes"])
        self.assertIsNone(report["steps"][3]["dtc_codes"])
        self.assertEqual(0x11, report["steps"][3]["negative_response_code"])
        self.assertEqual([], report["steps"][4]["dtc_codes"])
        modes = {row["mode"]: row for row in report["service_observations"]}
        self.assertEqual(1, modes["03"]["passed"])
        self.assertEqual(1, modes["07"]["negative"])
        self.assertEqual(0, modes["07"]["passed"])
        self.assertEqual(1, modes["0A"]["passed"])
        self.assertEqual(len(report["steps"]), sum(row["attempted"] for row in modes.values()))

    def test_numeric_pid_measurement_only_for_verified_known_definitions(self):
        initial = step(1, b"\x01\x00", bytes.fromhex("410008100000"))
        rpm = step(7, b"\x01\x0C", bytes.fromhex("410C1AF8"))
        coolant = step(6, b"\x01\x05", bytes.fromhex("41055A"))
        tail = (step(8, b"\x02\x02\x00", bytes.fromhex("4202000000")) +
                step(9, b"\x06\x00", bytes.fromhex("460000000000")) +
                step(10, b"\x08\x00", bytes.fromhex("480000000000")) +
                step(11, b"\x09\x00", bytes.fromhex("490000000000")))
        report = decode(io.BytesIO(start() + initial + IDENTITY + DTC + coolant + rpm + tail +
            summary(12, flags=231, steps=11, positive=11)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual(50, report["steps"][5]["measurement"]["number"])
        self.assertEqual(1726, report["steps"][6]["measurement"]["number"])
        self.assertIsNone(report["steps"][0]["measurement"])
        self.assertIsNone(report["steps"][7]["measurement"])

    def test_mode06_monitor_records_preserve_encoded_unsigned_words(self):
        tail = (step(6, b"\x02\x02\x00", bytes.fromhex("4202000000")) +
                step(7, b"\x06\x00", bytes.fromhex("460080000000")) +
                step(8, b"\x06\x01", bytes.fromhex("4601010212340000FFFF01030280000001FFFE")) +
                step(9, b"\x08\x00", bytes.fromhex("480000000000")) +
                step(10, b"\x09\x00", bytes.fromhex("490000000000")))
        report = decode(io.BytesIO(start() + INITIAL + IDENTITY + DTC + tail +
            summary(11, flags=231, steps=10, positive=10)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertIsNone(report["steps"][6]["monitor_records"])
        records = report["steps"][7]["monitor_records"]
        self.assertEqual(2, len(records))
        self.assertEqual(0x1234, records[0]["value_encoded"])
        self.assertEqual(0x8000, records[1]["value_encoded"])
        self.assertEqual(1, records[1]["minimum_encoded"])
        self.assertEqual(65534, records[1]["maximum_encoded"])
        self.assertNotIn("passed", records[0])

    def test_mode09_identification_values_and_invalid_ascii(self):
        tail = (step(6, bytes.fromhex("020200"), bytes.fromhex("4202000000")) +
                step(7, bytes.fromhex("0600"), bytes.fromhex("460000000000")) +
                step(8, bytes.fromhex("0800"), bytes.fromhex("480000000000")) +
                step(9, bytes.fromhex("0900"), bytes.fromhex("490014000000")) +
                step(10, bytes.fromhex("0904"), bytes.fromhex("49040143414C31000000000000000000000000")) +
                step(11, bytes.fromhex("0906"), bytes.fromhex("49060201020304FFFFFFFF")))
        report = decode(io.BytesIO(start() + INITIAL + IDENTITY + DTC + tail +
            summary(12, flags=231, steps=11, positive=11)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual([VIN.decode()], report["steps"][1]["vehicle_info"]["values"])
        self.assertEqual(["CAL1"], report["steps"][9]["vehicle_info"]["values"])
        self.assertEqual(["01020304", "FFFFFFFF"], report["steps"][10]["vehicle_info"]["values"])
        bad = step(2, bytes.fromhex("0904"), bytes.fromhex("49040143004131000000000000000000000000"))
        report = decode(io.BytesIO(start() + INITIAL + bad))
        self.assertIsNone(report["steps"][1]["vehicle_info"])

    def test_readiness_ignition_specific_and_unsupported_monitors(self):
        for response, expected in (
            ("410182112105", {"misfire": False, "catalyst": False, "oxygen-sensor": True}),
            ("410100084040", {"particulate-filter": False}),
        ):
            initial = step(1, bytes.fromhex("0100"), bytes.fromhex("410080000000"))
            tail = (step(6, bytes.fromhex("0101"), bytes.fromhex(response)) +
                    step(7, bytes.fromhex("020200"), bytes.fromhex("4202000000")) +
                    step(8, bytes.fromhex("0600"), bytes.fromhex("460000000000")) +
                    step(9, bytes.fromhex("0800"), bytes.fromhex("480000000000")) +
                    step(10, bytes.fromhex("0900"), bytes.fromhex("490000000000")))
            report = decode(io.BytesIO(start() + initial + IDENTITY + DTC + tail +
                summary(11, flags=231, steps=10, positive=10)))
            self.assertTrue(report["capture_integrity_verified"])
            ready = report["steps"][5]["readiness"]
            self.assertEqual(expected, ready["supported_monitors"])
            self.assertEqual(response == "410182112105", ready["mil_on"])
            self.assertEqual(response == "410100084040", ready["compression_ignition"])
            self.assertIsNone(report["steps"][0]["readiness"])

    def test_supported_page_and_freeze_trigger(self):
        report = decode(io.BytesIO(CAPTURE))
        self.assertEqual({"mode": 1, "base": 0, "supported_ids": [], "has_next_page": False},
                         report["steps"][0]["supported_page"])
        self.assertEqual({"present": False, "dtc": None}, report["steps"][5]["freeze_trigger"])
        tail = (step(6, bytes.fromhex("020200"), bytes.fromhex("4202000123")) +
                step(7, bytes.fromhex("020000"), bytes.fromhex("42000000000000")) +
                step(8, bytes.fromhex("0600"), bytes.fromhex("460000000000")) +
                step(9, bytes.fromhex("0800"), bytes.fromhex("480080000000")) +
                step(10, bytes.fromhex("0900"), bytes.fromhex("490000000000")))
        report = decode(io.BytesIO(start() + INITIAL + IDENTITY + DTC + tail +
            summary(11, flags=255, steps=10, positive=10)))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual({"present": True, "dtc": "P0123"}, report["steps"][5]["freeze_trigger"])
        self.assertEqual([1], report["steps"][8]["supported_page"]["supported_ids"])
        self.assertIsNone(report["steps"][1]["supported_page"])

    def test_odometer_high_page_and_legacy_raw_capture(self):
        first = step(1, bytes.fromhex("0100"), bytes.fromhex("410000000001"))
        pages = b"".join(step(i, bytes((1, base)), bytes((0x41, base, 0, 0, 0, 1)))
                         for i, base in enumerate((0x20, 0x40, 0x60, 0x80), 6))
        pages += step(10, bytes.fromhex("01A0"), bytes.fromhex("41A004000000"))
        tail = (step(12, bytes.fromhex("020200"), bytes.fromhex("4202000000")) +
                step(13, bytes.fromhex("0600"), bytes.fromhex("460000000000")) +
                step(14, bytes.fromhex("0800"), bytes.fromhex("480000000000")) +
                step(15, bytes.fromhex("0900"), bytes.fromhex("490000000000")))
        for state in (1, 2):
            odo = step(11, bytes.fromhex("01A6"), bytes.fromhex("41A60012D687"), state)
            report = decode(io.BytesIO(start() + first + IDENTITY + DTC + pages + odo + tail +
                summary(16, flags=231, steps=15, positive=15 if state == 1 else 14, raw=0 if state == 1 else 1)))
            self.assertTrue(report["capture_integrity_verified"])
            measurement = report["steps"][10]["measurement"]
            if state == 1:
                self.assertEqual(123456.7, measurement["number"])
                self.assertEqual("km", measurement["unit"])
            else:
                self.assertIsNone(measurement)
        truncated = step(11, bytes.fromhex("01A6"), bytes.fromhex("41A60012D6"))
        report = decode(io.BytesIO(start() + first + IDENTITY + DTC + pages + truncated))
        self.assertFalse(report["capture_integrity_verified"])

    def test_corruption_and_truncation_keep_prefix(self):
        for tail in (IDENTITY[:-1], IDENTITY[:-1] + bytes([IDENTITY[-1] ^ 1])):
            report = decode(io.BytesIO(start() + INITIAL + tail))
            self.assertFalse(report["capture_integrity_verified"])
            self.assertEqual(1, len(report["steps"]))
            self.assertEqual(1, report["service_observations"][0]["attempted"])
            self.assertEqual(0, report["service_observations"][2]["attempted"])
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

    def test_capture_cannot_upgrade_unknown_or_truncated_record(self):
        for request, response, state in (
            (b"\x01\xA1", b"\x41\xA1\x01", 1),
            (b"\x01\x0C", b"\x41\x0C\x00", 1),
            (b"\x01\x05", b"\x41\x05\x40", 2),
            (b"\x03", b"\x43\x01\x00\x00", 1),
            (b"\x01\xA1", b"\x41\xA1", 2),
        ):
            report = decode(io.BytesIO(start() + INITIAL + step(2, request, response, state)))
            self.assertFalse(report["capture_integrity_verified"])
            self.assertEqual(1, len(report["steps"]))
            self.assertIn("shape", report["error"])

    def test_partial_and_raw_evidence(self):
        # PID 02 is advertised but has no defined Mode 01 width in this codec.
        initial = step(1, b"\x01\x00", b"\x41\x00\x40\x00\x00\x00")
        dtc = step(3, b"\x03", b"\x43\x00") + step(4, b"\x07", b"", 4) + step(5, b"\x0A", b"\x4A\x00")
        data = start() + initial + IDENTITY + dtc + step(6, b"\x01\x02", b"\x41\x02\x01", 2)
        data += summary(7, end=3, flags=3, steps=6, positive=4, raw=1, timeout=1)
        report = decode(io.BytesIO(data))
        self.assertTrue(report["capture_integrity_verified"])
        self.assertEqual("BUDGET", report["summary"]["end"])
        self.assertEqual("RAW", report["steps"][5]["status"])
        self.assertIsNone(report["steps"][3]["response_hex"])

    def test_forged_terminal_summary_and_campaign_order_rejected(self):
        prefix = start() + INITIAL + IDENTITY
        for data in (
            prefix + summary(3),  # omitted mandatory steps, even with correct counts
            prefix + DTC + TAIL + summary(10, flags=255, steps=9, positive=9),
            prefix + DTC + TAIL + summary(10, end=3, flags=231, steps=9, positive=9),
            prefix + step(3, b"\x07", b"\x47\x00") +
                summary(4, end=3, steps=3, positive=3),
            prefix + step(3, b"\x03", b"", 5) +
                summary(4, end=1, steps=3, positive=2),
        ):
            self.assertFalse(decode(io.BytesIO(data))["capture_integrity_verified"])
        fault = prefix + step(3, b"\x03", b"", 5) + summary(4, end=4, steps=3, positive=2)
        self.assertTrue(decode(io.BytesIO(fault))["capture_integrity_verified"])
        initial_refused = start() + step(1, b"\x01\x00", b"\x7F\x01\x11", 3)
        initial_refused += summary(2, end=2, flags=0, steps=1, positive=0, negative=1)
        self.assertTrue(decode(io.BytesIO(initial_refused))["capture_integrity_verified"])

    def test_vin_comparison_is_separate_from_capture_integrity_and_auth(self):
        report = decode(io.BytesIO(CAPTURE))
        self.assertEqual("NOT_REQUESTED", vin_match(report, None))
        self.assertEqual("MATCH", vin_match(report, VIN.decode()))
        self.assertEqual("MISMATCH", vin_match(report, "1FA6P8TH0L5100000"))
        self.assertEqual("UNVERIFIED", vin_match(report, VIN.decode(), require_device_auth=True))
        report["device_credential_authenticated"] = True
        self.assertEqual("MATCH", vin_match(report, VIN.decode(), require_device_auth=True))
        report["capture_integrity_verified"] = False
        self.assertEqual("UNVERIFIED", vin_match(report, VIN.decode()))
        with self.assertRaises(ValueError):
            vin_match(report, "invalid")

    def test_cli_new_file_and_declared_labels(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "capture.cj"
            target = Path(folder) / "report.json"
            source.write_bytes(CAPTURE)
            args = ["--input", str(source), "--output", str(target), "--vehicle", "Camaro 2012",
                    "--ecu", "ECM", "--connector", "bench", "--vin", VIN.decode()]
            result = run(args)
            self.assertEqual(result, json.loads(target.read_text()))
            self.assertEqual("Camaro 2012", result["declared_vehicle"])
            self.assertEqual("MATCH", result["vin_match"])
            self.assertFalse(result["hardware_provenance_verified"])
            with self.assertRaises(FileExistsError):
                run(args)
