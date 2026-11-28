import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import contextmanager

from camarodiagconnectors.j1979_can_network import (
    CanFrame, decode_frame, discover, functional_request, response_pair, scan_network, validate_targets)
from camarodiagconnectors import j1979_can_network_cli

VIN="1G1FA1RXXR0123456"


def positive(rx, extended=False, bitmap="80000000"):
    return CanFrame(rx,extended,bytes.fromhex("064100"+bitmap+"CC"))


class Link:
    def __init__(self, frames):
        self.frames=iter(frames)
        self.sent=0
    def send_discovery(self,timeout):
        self.sent+=1
    def receive(self,timeout):
        try:
            return next(self.frames)
        except StopIteration:
            raise TimeoutError()


class TestNetwork(unittest.TestCase):
    def test_address_families_and_single_frame_only(self):
        self.assertEqual(0x7DF,functional_request(False).can_id)
        self.assertEqual(0x18DB33F1,functional_request(True).can_id)
        self.assertEqual((0x7E0,0x7E8),response_pair(0x7E8,False))
        self.assertEqual((0x18DA10F1,0x18DAF110),response_pair(0x18DAF110,True))
        self.assertIsNone(response_pair(0x18DAF1F1,True))
        self.assertIsNone(response_pair(0x7E8,True))
        self.assertEqual([1],decode_frame(positive(0x7E8),False)["supported_pids"])
        self.assertIsNone(decode_frame(positive(0x7E8),True))
        self.assertIsNone(decode_frame(CanFrame(0x7E8,False,b"",rtr=True),False))
        self.assertEqual("FAILED",decode_frame(CanFrame(0x7E8,False,b"\x10"*8),False)["status"])
        self.assertEqual("FAILED",decode_frame(CanFrame(0x7E8,False,b"\x06\x41\x00"),False)["status"])

    def test_duplicate_conflict_pending_and_terminal_nrc(self):
        pending=CanFrame(0x7E8,False,bytes.fromhex("037F0178CCCCCCCC"))
        refused=CanFrame(0x7EA,False,bytes.fromhex("037F0111CCCCCCCC"))
        link=Link([pending,positive(0x7E8),positive(0x7E8),positive(0x7E9),
                   positive(0x7E9,bitmap="40000000"),refused,positive(0x123)])
        report=discover(link,False)
        self.assertEqual(1,link.sent)
        self.assertEqual(["7E8"],report["eligible_response_ids"])
        rows={r["rx_can_id"]:r for r in report["responders"]}
        self.assertEqual(3,len(rows["7E8"]["frames"]))
        self.assertEqual("CONFLICTING_RESPONSES",rows["7E9"]["detail"])
        self.assertIsNone(rows["7E9"]["supported_pids"])
        self.assertEqual("NRC_11",rows["7EA"]["detail"])
        self.assertEqual(1,report["ignored_frame_count"])

    def test_frame_limit_cannot_authorize_selected_scans(self):
        report=discover(Link([positive(0x7E8)]*512),False)
        self.assertFalse(report["completed"])
        self.assertEqual("FRAME_LIMIT",report["stop_reason"])
        self.assertEqual([],report["eligible_response_ids"])

    def test_multi_ecu_separate_results_and_no_automatic_selection(self):
        discovery=discover(Link([positive(0x7E8),positive(0x7E9),positive(0x7EA)]),False)
        opened=[]
        @contextmanager
        def open_physical(tx,rx):
            opened.append((tx,rx))
            class Transport:
                def exchange(self,req,timeout):
                    if req==b"\x09\x02":
                        return b"\x49\x02\x01"+VIN.encode()
                    if req==b"\x03":
                        return bytes.fromhex("43010123" if rx==0x7E8 else "43010456")
                    if req in (b"\x07",b"\x0A"):
                        return bytes((req[0]+0x40,0))
                    if req==b"\x02\x02\x00":
                        return b"\x42\x02\x00\x00\x00"
                    return bytes((req[0]+0x40,req[1],0,0,0,0))
            yield Transport()
        result=scan_network(discovery,[0x7E8,0x7E9],open_physical,expected_vin=VIN)
        self.assertEqual([(0x7E0,0x7E8),(0x7E1,0x7E9)],opened)
        self.assertTrue(result["completed"])
        self.assertFalse(result["whole_vehicle_coverage_verified"])
        for row,code in zip(result["ecu_reports"],["P0123","P0456"]):
            step=next(s for s in row["report"]["steps"] if s["request_hex"]=="03")
            self.assertEqual([code],step["application_evidence"]["dtc_codes"])
        self.assertNotIn("7EA",result["selected_response_ids"])

    def test_missing_selected_source_stops_all_before_open(self):
        discovery=discover(Link([positive(0x7E8)]),False)
        result=scan_network(discovery,[0x7E8,0x7E9],lambda *_:self.fail("Unexpected open"),expected_vin=None)
        self.assertEqual("SELECTED_RESPONDER_UNVERIFIED",result["stop_reason"])
        self.assertEqual(["NOT_TESTED","NOT_TESTED"],[x["status"] for x in result["ecu_reports"]])

    def test_mismatched_vin_stops_without_next_ecu(self):
        discovery=discover(Link([positive(0x7E8),positive(0x7E9)]),False)
        opened=[]
        @contextmanager
        def open_physical(tx,rx):
            opened.append(rx)
            class Transport:
                def exchange(self,req,timeout):
                    self_request=req
                    if self_request!=b"\x09\x02":
                        raise AssertionError("No further query after mismatch")
                    return b"\x49\x02\x01"+b"1FA6P8TH0L5100000"
            yield Transport()
        result=scan_network(discovery,[0x7E8,0x7E9],open_physical,expected_vin=VIN)
        self.assertEqual([0x7E8],opened)
        self.assertEqual("VIN_MISMATCH",result["stop_reason"])
        self.assertEqual("NOT_TESTED",result["ecu_reports"][1]["status"])

    def test_cli_reserves_output_and_discovery_only_never_opens_physical(self):
        with tempfile.TemporaryDirectory() as folder, \
                patch.object(j1979_can_network_cli,"LinuxFunctionalCan") as raw, \
                patch.object(j1979_can_network_cli,"LinuxIsoTp") as physical:
            path=Path(folder)/"report.json"
            raw.return_value.__enter__.return_value=Link([positive(0x18DAF110,True)])
            args=["--interface","can0","--addressing","29bit","--discover-only",
                  "--confirm-functional-pid00","--output",str(path)]
            result=j1979_can_network_cli.run(args)
            self.assertEqual(result,json.loads(path.read_text()))
            self.assertEqual(["18DAF110"],result["eligible_response_ids"])
            physical.assert_not_called()
            with self.assertRaises(FileExistsError):
                j1979_can_network_cli.run(args)
            self.assertEqual(1,raw.call_count)

    def test_duplicate_and_invalid_selections_rejected_before_open(self):
        for ids in ([0x7E8,0x7E8],[0x7DF],[0x18DAF110]):
            with self.assertRaises(ValueError):
                validate_targets(ids,False)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"no.json"
            with self.assertRaises(SystemExit):
                j1979_can_network_cli.run(["--interface","can0","--addressing","11bit",
                    "--source","7E8","--confirm-functional-pid00","--output",str(path)])
            self.assertFalse(path.exists())
