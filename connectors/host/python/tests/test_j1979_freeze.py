"""Regression cases for explicit freeze-frame acquisition and offline replay."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979_freeze import (
    FreezePlan, Read, allowed_request, classify, execute_plan, scan_freeze,
)
from camarodiagconnectors.j1979_freeze_inspection import inspect, inspect_file, replay_result
from camarodiagconnectors.j1979_freeze_cli import run

VIN = "1G1FA1E30D1234567"
VIN_REPLY = bytes.fromhex("490201") + VIN.encode("ascii")


def bitmap(*pids):
    bits = 0
    for pid in pids:
        bits |= 1 << (32 - pid)
    return bits.to_bytes(4, "big")


def reply(pid, frame, data):
    return bytes((0x42, pid, frame)) + data


class FakeECU:
    def __init__(self):
        self.requests = []
        self.pages = {(0, 0): bitmap(2, 12, 13, 32),
                      (0, 32): bitmap(1), (1, 0): bitmap(2, 13)}
        self.values = {(0, 12): bytes.fromhex("1F40"),
                       (0, 13): bytes((80,)), (0, 33): bytes.fromhex("0002"),
                       (1, 13): bytes.fromhex("FF00")}
        self.triggers = {0: bytes.fromhex("0301"), 1: bytes.fromhex("0102")}
        self.overrides = {}

    def exchange(self, request, timeout):
        self.requests.append(request)
        if request in self.overrides:
            action = self.overrides[request]
            return action() if callable(action) else action
        if request == b"\x09\x02":
            return VIN_REPLY
        _, pid, frame = request
        if pid == 2:
            return reply(pid, frame, self.triggers[frame])
        if pid % 32 == 0:
            return reply(pid, frame, self.pages[frame, pid])
        return reply(pid, frame, self.values[frame, pid])


def campaign(ecu=None, frames=(0, 1), pids=()):
    ecu = ecu or FakeECU()
    return scan_freeze(ecu.exchange, FreezePlan(VIN, frames, pids))


def envelope(result):
    return {"schema": "camarodiag-functional-j1979-freeze-v1",
            "protocol": "SAE-J1979", "transport": "LINUX_SOCKETCAN_ISOTP",
            "version": "test", "interface": "can0", "tx_can_id": "7E0",
            "rx_can_id": "7E8", "physical_channel_confirmed_by_operator": True,
            "started_at_utc": "2026-01-01T00:00:00+00:00",
            "finished_at_utc": "2026-01-01T00:00:01+00:00",
            "transport_problem": None, "execution_completed": result["completed"],
            "result": result}


class FreezeTests(unittest.TestCase):
    def test_explicit_multi_frame_inventory_and_order(self):
        ecu = FakeECU()
        result = campaign(ecu)
        self.assertTrue(result["all_frames_stable"])
        self.assertEqual([r["status"] for r in result["frames"]], ["COMPLETE", "COMPLETE"])
        self.assertEqual(result["frames"][0]["advertised_pids"], [2, 12, 13, 33])
        self.assertEqual(result["frames"][1]["advertised_pids"], [2, 13])
        self.assertEqual(ecu.requests, [bytes.fromhex(s) for s in (
            "0902", "020000", "020200", "022000", "020C00", "020D00",
            "022100", "020200", "020001", "020201", "020D01", "020201", "0902")])
        self.assertEqual(result["frames"][0]["trigger_dtc"], "P0301")
        self.assertEqual(result["frames"][1]["trigger_dtc"], "P0102")
        optional = next(s for s in result["steps"] if s["request_hex"] == "020D01")
        self.assertEqual(optional["status"], "RAW")
        self.assertEqual(optional["evidence"]["data_hex"], "FF00")
        self.assertNotIn("measurement", optional["evidence"])
        self.assertFalse(result["permits_programming"])

    def test_selected_pid_not_available_in_other_frame(self):
        ecu = FakeECU()
        result = campaign(ecu, pids=(12, 13, 42))
        self.assertEqual(result["frames"][0]["unadvertised_selected_pids"], [42])
        self.assertEqual(result["frames"][1]["unadvertised_selected_pids"], [12, 42])
        self.assertNotIn(bytes.fromhex("020C01"), ecu.requests)
        self.assertNotIn(bytes.fromhex("022A00"), ecu.requests)
        self.assertTrue(result["all_frames_stable"])

    def test_no_implicit_frame_enumeration(self):
        ecu = FakeECU()
        result = campaign(ecu, frames=(1,))
        self.assertEqual(len(result["frames"]), 1)
        self.assertTrue(all(len(r) == 2 or r[-1] == 1 for r in ecu.requests))

    def test_zero_trigger_prevents_data_reads(self):
        ecu = FakeECU()
        ecu.triggers[0] = b"\0\0"
        result = campaign(ecu, frames=(0,))
        self.assertEqual(result["frames"][0]["status"], "ABSENT")
        self.assertEqual(ecu.requests, [bytes.fromhex(s) for s in ("0902", "020000", "020200", "0902")])
        self.assertIsNone(result["frames"][0]["trigger_dtc"])

    def test_wrong_frame_echo_is_failed(self):
        ecu = FakeECU()
        ecu.overrides[bytes.fromhex("020D01")] = bytes.fromhex("420D0050")
        result = campaign(ecu, frames=(1,))
        self.assertEqual(result["frames"][0]["status"], "PARTIAL")
        self.assertFalse(result["all_frames_stable"])
        row = result["steps"][3]
        self.assertEqual(row["detail"], "WRONG_SERVICE_PID_OR_FRAME")
        self.assertIsNone(row["evidence"])

    def test_truncated_bitmap_and_last_page_continuation(self):
        invalid = classify(bytes.fromhex("020001"), Read("RECEIVED", bytes.fromhex("42000100"), 2, True))
        self.assertEqual(invalid["detail"], "INVALID_SUPPORT_BITMAP")
        invalid = classify(bytes.fromhex("02E001"), Read("RECEIVED", bytes.fromhex("42E00100000001"), 2, True))
        self.assertEqual(invalid["status"], "FAILED")

    def test_bad_vin_stops_before_frame(self):
        ecu = FakeECU()
        ecu.overrides[b"\x09\x02"] = bytes.fromhex("490201") + b"1G1FA1E30D7654321"
        result = campaign(ecu)
        self.assertEqual(result["stop_reason"], "VIN_MISMATCH")
        self.assertEqual(result["unvisited_frames"], [0, 1])
        self.assertEqual(ecu.requests, [b"\x09\x02"])

    def test_changed_trigger_rejected(self):
        ecu = FakeECU()
        triggers = iter((bytes.fromhex("4202000301"), bytes.fromhex("4202000302")))
        ecu.overrides[bytes.fromhex("020200")] = lambda: next(triggers)
        result = campaign(ecu, frames=(0,))
        self.assertEqual(result["frames"][0]["status"], "CHANGED")
        self.assertFalse(result["all_frames_stable"])

    def test_timeout_continues_only_with_explicit_next_frame(self):
        ecu = FakeECU()
        def timeout():
            raise TimeoutError()
        ecu.overrides[bytes.fromhex("020000")] = timeout
        result = campaign(ecu)
        self.assertEqual([f["status"] for f in result["frames"]], ["UNAVAILABLE", "COMPLETE"])
        self.assertFalse(result["all_frames_stable"])

    def test_cancel_and_budget_do_not_claim_transmission(self):
        result = execute_plan(FreezePlan(VIN), lambda req: Read("BUDGET", None, 0, False))
        self.assertEqual(result["stop_reason"], "BUDGET_EXHAUSTED")
        self.assertFalse(result["steps"][0]["transmission_attempted"])
        def interrupt(*args):
            raise KeyboardInterrupt()
        result = scan_freeze(interrupt, FreezePlan(VIN))
        self.assertEqual(result["stop_reason"], "CANCELLED")
        self.assertTrue(result["steps"][0]["transmission_attempted"])

    def test_freeze_route_is_admitted_before_transmission(self):
        ecu = FakeECU()
        plan = FreezePlan(VIN)
        for invalid in (0, -1, 21, float("inf"), float("nan"), "2.5"):
            with self.subTest(timeout=invalid):
                result = scan_freeze(ecu.exchange, plan, request_timeout=lambda request: invalid)
                self.assertEqual(result["stop_reason"], "CONTEXT_CHANGED")
                self.assertFalse(result["steps"][0]["transmission_attempted"])
                self.assertEqual(ecu.requests, [])
        def broken_timeout(request):
            raise OSError("No active adapter timing profile")
        result = scan_freeze(ecu.exchange, plan, request_timeout=broken_timeout)
        self.assertEqual(result["stop_reason"], "CONTEXT_CHANGED")
        self.assertFalse(result["steps"][0]["transmission_attempted"])
        self.assertEqual(ecu.requests, [])

    def test_freeze_context_revoked_during_exchange_never_accepts_reply(self):
        ecu = FakeECU()
        state = {"active": True}
        def require_current():
            if not state["active"]:
                raise RuntimeError("vehicle or ECU changed")
        def exchange(request, timeout):
            value = ecu.exchange(request, timeout)
            state["active"] = False
            return value
        report = scan_freeze(exchange, FreezePlan(VIN), require_current=require_current)
        self.assertEqual(ecu.requests, [b"\\x09\\x02"])
        self.assertEqual(report["stop_reason"], "CONTEXT_CHANGED")
        self.assertIsNone(report["initial_vin"])
        self.assertTrue(report["steps"][0]["transmission_attempted"])
        self.assertIsNone(report["steps"][0]["response_hex"])
        self.assertFalse(report["identity_rechecked"])

    def test_freeze_transport_timeout_after_revoke_is_not_safe_retry(self):
        state = {"active": True}
        def require_current():
            if not state["active"]:
                raise OSError("session revoked")
        def exchange(request, timeout):
            state["active"] = False
            raise TimeoutError("response after route loss")
        report = scan_freeze(exchange, FreezePlan(VIN), require_current=require_current)
        self.assertEqual(report["steps"][0]["transport_outcome"], "CONTEXT_CHANGED")
        self.assertTrue(report["steps"][0]["transmission_attempted"])
        self.assertFalse(report["completed"])

    def test_read_only_boundaries_and_invalid_plans(self):
        for request in ("04", "0800", "22F190", "020002"):
            self.assertFalse(allowed_request(bytes.fromhex(request), (0, 1)))
        for frames in ((), (0, 0), (True,), (256,), tuple(range(9))):
            with self.subTest(frames=frames), self.assertRaises(ValueError):
                FreezePlan(VIN, frames).validate()
        for pids in ((0,), (2,), (32,), (12, 12)):
            with self.subTest(pids=pids), self.assertRaises(ValueError):
                FreezePlan(VIN, (0,), pids).validate()
        with self.assertRaises(ValueError):
            Read([], None, 0, False).validate()


class ReplayTests(unittest.TestCase):
    def test_replay_and_expected_identity(self):
        original = envelope(campaign())
        verification = inspect(original, expected_vin=VIN)
        self.assertTrue(verification["consistent"])
        self.assertTrue(verification["all_frames_stable"])
        self.assertFalse(verification["physical_vehicle_verified"])
        self.assertFalse(verification["timing_independently_verified"])
        with self.assertRaises(ValueError):
            inspect(original, expected_vin="1G1FA1E30D7654321")

    def test_changed_summary_response_order_and_flags_rejected(self):
        result = campaign()
        edits = [
            lambda r: r["frames"][0].update(trigger_dtc="P0001"),
            lambda r: r["steps"][3].update(response_hex="42000000000000"),
            lambda r: r["steps"].reverse(),
            lambda r: r["steps"].append(copy.deepcopy(r["steps"][-1])),
            lambda r: r.update(permits_programming=True),
            lambda r: r.update(duration_ms=-1),
        ]
        for edit in edits:
            candidate = copy.deepcopy(result)
            edit(candidate)
            with self.assertRaises(ValueError):
                replay_result(candidate)

    def test_close_error_invalidates_execution_not_raw_capture(self):
        original = envelope(campaign())
        original.update(transport_problem="TRANSPORT_ERROR", execution_completed=False)
        verification = inspect(original)
        self.assertFalse(verification["execution_completed"])
        self.assertTrue(verification["replayed"]["completed"])

    def test_file_and_cli_exclusive_output(self):
        class FakeLink:
            def __init__(self, *args):
                pass
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
            def run_freeze(self, plan):
                return scan_freeze(FakeECU().exchange, plan)
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / "report.json"
            args = ["--interface", "can0", "--tx", "7E0", "--rx", "7E8",
                    "--vin", VIN, "--frame", "00", "--frame", "01",
                    "--confirm-physical-channel", "--output", str(output)]
            with patch("camarodiagconnectors.j1979_freeze_cli.LinuxIsoTp", FakeLink):
                report = run(args)
                with self.assertRaises(FileExistsError):
                    run(args)
            self.assertEqual(json.loads(output.read_text()), report)
            self.assertTrue(inspect_file(output)["consistent"])
            self.assertEqual(len(inspect_file(output)["source_sha256"]), 64)


if __name__ == "__main__":
    unittest.main()
