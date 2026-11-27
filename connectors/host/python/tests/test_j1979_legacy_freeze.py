import copy
import unittest

from camarodiagconnectors.j1979_freeze import FreezePlan
from camarodiagconnectors.j1979_legacy_freeze import acquire
from camarodiagconnectors.j1979_legacy_freeze_inspection import inspect
from camarodiagconnectors.j1979_legacy_frames import checksum
from camarodiagconnectors.j1979_legacy_inspection import _tx_frame

VIN = "1G1FA1E30D1234567"
PLAN = FreezePlan(VIN, (0, 2), (12, 13), 60000)
TIME = "2026-11-27T08:00:00+00:00"


class Adapter:
    def __init__(self, protocol, vin=VIN):
        self.protocol = protocol
        self.vin = vin
        self.commands = []
        self.probes = 0
        self.extra = False
        self.bad_checksum = False
        self.reverse_vin = False
        self.zero_trigger = False

    def frame(self, payload, source=0x10):
        if self.protocol >= 4:
            head = bytes((0x80 | len(payload), 0xf1, source))
        else:
            head = bytes((0x41 if self.protocol == 1 else 0x48, 0x6b, source))
        frame = head + payload
        return (frame + bytes((checksum(self.protocol, frame),))).hex().upper()

    def query(self, command, timeout):
        self.commands.append(command)
        assert 0 < timeout <= 4
        if command == "0100":
            rows = [bytes.fromhex("410080000000")]
        elif command == "0900":
            rows = [bytes.fromhex("490001C0000000")]
        elif command == "0901":
            rows = [bytes.fromhex("490105")]
        elif command == "0902":
            data = b"\0\0\0" + self.vin.encode("ascii")
            rows = [bytes((0x49, 2, i+1)) + data[i*4:i*4+4] for i in range(5)]
            if self.reverse_vin:
                rows.reverse()
        else:
            mode, pid, frame = bytes.fromhex(command)
            assert mode == 2 and frame in PLAN.frames
            data = {0: bytes.fromhex("40180000"), 2: bytes.fromhex("0000" if self.zero_trigger else "0301"),
                    12: bytes.fromhex("1F40"), 13: bytes((42,))}[pid]
            rows = [bytes((0x42, pid, frame)) + data]
        records = [self.frame(row) for row in rows]
        if self.extra:
            records.append(self.frame(rows[0], 0x18))
        if self.bad_checksum:
            records[0] = records[0][:-2] + f"{int(records[0][-2:], 16)^1:02X}"
        return "\r".join(records) + "\r>"

    def probe(self, timeout):
        self.probes += 1
        assert 0 < timeout <= 2
        return f"ATDPN\r{self.protocol}\r>"


def fixture(protocol=2, adapter=None):
    adapter = adapter or Adapter(protocol)
    evidence = acquire(adapter.query, adapter.probe, protocol, 0x10, PLAN)
    return {"schema": "camarodiag-functional-j1979-legacy-freeze-v1",
            "protocol": "SAE-J1979", "transport": "ELM_SERIAL_LEGACY_HEADERED",
            "version": "test", "port": "test", "baud": 38400,
            "functional_scope_confirmed_by_operator": True,
            "dedicated_adapter_confirmed_by_operator": True,
            "started_at_utc": TIME, "finished_at_utc": TIME,
            "transport_problem": None,
            "execution_completed": evidence["result"]["completed"] and
                evidence["observed_protocol"] == protocol, **evidence}


def windows(report):
    report = copy.deepcopy(report)
    protocol = report["expected_protocol"]
    report.update(transport="WINDOWS_J2534_LEGACY", adapter="test driver",
                  j2534_protocol_id={1:2,2:1,3:3,4:4,5:4}[protocol],
                  baud=41600 if protocol == 1 else 10400,
                  initialization_evidence="0808" if protocol in (3,4) else
                  "83F110C10808" if protocol == 5 else None,
                  physical_vehicle_verified=False, whole_vehicle_coverage_verified=False,
                  response_attribution="J2534_DRIVER_MANAGED_SELECTED_HEADER")
    for key in ("port","dedicated_adapter_confirmed_by_operator","observed_protocol","other_response_sources"):
        del report[key]
    rows = []
    for row in report["transport_exchanges"]:
        if row["request_hex"] == "ATDPN":
            continue
        for frame in row["frames"]:
            frame["raw_hex"] = frame["raw_hex"][:-2]
            frame.update(timestamp=0, in_frame_response_hex=None)
        row["tx_hex"] = _tx_frame(bytes.fromhex(row["request_hex"]),protocol).hex().upper()
        del row["raw_response"]
        del row["selected_records_hex"]
        rows.append(row)
    report["transport_exchanges"] = rows
    return report


class LegacyFreezeTests(unittest.TestCase):
    def test_five_legacy_profiles_and_exact_order(self):
        for protocol in range(1, 6):
            with self.subTest(protocol=protocol):
                adapter = Adapter(protocol)
                report = fixture(protocol, adapter)
                verified = inspect(report, expected_vin=VIN)
                self.assertTrue(verified["all_frames_stable"])
                self.assertEqual(adapter.probes, 2)
                self.assertEqual(adapter.commands[:4], ["0100","0900","0901","0902"])
                self.assertEqual(adapter.commands[-4:], ["0100","0900","0901","0902"])
                self.assertNotIn("020001", adapter.commands)
                self.assertFalse(verified["physically_addressed"])
                self.assertFalse(verified["physical_vehicle_verified"])
                self.assertFalse(verified["permits_programming"])

    def test_windows_driver_and_android_evidence(self):
        for protocol in range(1, 6):
            report = fixture(protocol)
            verified = inspect(windows(report))
            self.assertTrue(verified["all_frames_stable"])
            self.assertFalse(verified["driver_checksums_independently_verified"])
            android = copy.deepcopy(report)
            android.update(transport="ANDROID_ELM_LEGACY_HEADERED", adapter="test")
            for key in ("port","baud","dedicated_adapter_confirmed_by_operator"):
                del android[key]
            for row in android["transport_exchanges"]:
                row.pop("observed_protocol",None)
            self.assertTrue(inspect(android)["all_frames_stable"])

    def test_other_ecu_not_used(self):
        adapter = Adapter(2)
        adapter.extra = True
        report = fixture(2,adapter)
        self.assertEqual(report["other_response_sources"],["18"])
        self.assertTrue(inspect(report)["all_frames_stable"])

    def test_vin_mismatch_stops_before_mode02(self):
        adapter = Adapter(2, "1G1FA1E30D7654321")
        report = fixture(2,adapter)
        self.assertEqual(report["result"]["stop_reason"],"VIN_MISMATCH")
        self.assertFalse(any(command.startswith("02") for command in adapter.commands))
        self.assertFalse(inspect(report)["all_frames_stable"])

    def test_bad_checksum_and_out_of_order_fragments(self):
        for attribute in ("bad_checksum","reverse_vin"):
            adapter = Adapter(2)
            setattr(adapter,attribute,True)
            report = fixture(2,adapter)
            self.assertFalse(report["execution_completed"])
            self.assertFalse(any(command.startswith("02") for command in adapter.commands))

    def test_forged_result_and_trace_are_rejected(self):
        report = fixture()
        changes = [
            lambda r:r.update(physically_addressed=True),
            lambda r:r.update(selected_response_source="18"),
            lambda r:r["transport_exchanges"][0].update(raw_response="NO DATA"),
            lambda r:r["transport_exchanges"].pop(1),
            lambda r:r["result"].update(all_frames_stable=False),
            lambda r:r["transport_exchanges"][5].update(logical_query=0),
        ]
        for change in changes:
            forged=copy.deepcopy(report)
            change(forged)
            with self.assertRaises(ValueError):
                inspect(forged)

    def test_driver_header_substitution(self):
        report=windows(fixture())
        report["transport_exchanges"][0]["tx_hex"]="616AF10100"
        with self.assertRaises(ValueError):
            inspect(report)

    def test_zero_trigger_never_reads_values(self):
        adapter=Adapter(2)
        adapter.zero_trigger=True
        report=fixture(2,adapter)
        self.assertNotIn("020C00",adapter.commands)
        self.assertNotIn("020D02",adapter.commands)
        self.assertTrue(inspect(report)["all_frames_stable"])


if __name__ == "__main__":
    unittest.main()
