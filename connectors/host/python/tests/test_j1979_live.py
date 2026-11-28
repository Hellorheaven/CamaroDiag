import csv
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979_live import Plan,record,numeric_pid,csv_rows
from camarodiagconnectors import j1979_live_cli

VIN="1G1FA1RXXR0123456"


class Clock:
    def __init__(self): self.now=0
    def __call__(self): return self.now
    def sleep(self,seconds): self.now+=seconds


class TestLive(unittest.TestCase):
    def plan(self,**kw):
        return Plan(**({"pids":(5,12),"expected_vin":VIN,"cycles":3,"interval":0.5,"budget":30}|kw))
    def normal(self,req):
        if req==b"\x09\x02": return b"\x49\x02\x01"+VIN.encode()
        if req==b"\x01\x00": return bytes.fromhex("410008100000")
        if req==b"\x01\x05": return bytes.fromhex("41055A")
        if req==b"\x01\x0C": return bytes.fromhex("410C0FA0")
        self.fail(f"unexpected {req.hex()}")
    def test_plan_does_not_allow_bitmap_unknown_or_mutating_command(self):
        for pid in (0,1,0x13,0x20,0xFF,True):
            self.assertFalse(numeric_pid(pid))
        for pid in (5,12,0xA6,0x42):
            self.assertTrue(numeric_pid(pid))
        for values in ((5,5),(0x20,),(),(1,)):
            with self.assertRaises(ValueError):
                self.plan(pids=values).validate()
    def test_separate_timestamps_and_numeric_summary(self):
        clock=Clock();sent=[];published=[]
        def exchange(req,timeout):
            sent.append(req.hex().upper());clock.now+=0.05
            return self.normal(req)
        report=record(exchange,self.plan(),clock=clock,sleep=clock.sleep,on_observation=published.append)
        self.assertTrue(report["completed"])
        self.assertEqual(3,report["completed_cycles"])
        self.assertEqual(["0902","0100"],sent[:2])
        self.assertEqual("0902",sent[-1])
        self.assertEqual(6,len(published))
        self.assertEqual([1,1,2,2,3,3],[x["cycle"] for x in published])
        self.assertGreaterEqual(published[2]["started_elapsed_ms"]-published[0]["started_elapsed_ms"],500)
        self.assertEqual(50,report["summary"][0]["mean"])
        self.assertEqual(1000,report["summary"][1]["minimum"])
        self.assertEqual(3,report["summary"][0]["status_counts"]["PASSED"])
        self.assertFalse(report["whole_vehicle_coverage_verified"])
    def test_never_send_unadvertised_pid(self):
        sent=[]
        def exchange(req,timeout):
            sent.append(req)
            return self.normal(req)
        report=record(exchange,self.plan(pids=(5,13)))
        self.assertEqual("SELECTED_PID_NOT_VERIFIED",report["stop_reason"])
        self.assertEqual([b"\x09\x02",b"\x01\x00"],sent)
        self.assertEqual([],report["observations"])
    def test_partial_inventory_can_verify_selected_lower_page(self):
        def exchange(req,timeout):
            if req==b"\x01\x00":return bytes.fromhex("410008100001")
            return self.normal(req)
        clock=Clock()
        report=record(exchange,self.plan(cycles=1),clock=clock,sleep=clock.sleep)
        self.assertTrue(report["completed"])
        self.assertFalse(report["inventory_complete"])
        self.assertEqual(1,len(report["inventory_pages"]))
    def test_timeout_and_negative_never_reuse_last_value(self):
        clock=Clock();count=0
        def exchange(req,timeout):
            nonlocal count
            if req==b"\x01\x05":
                count+=1
                if count==2:raise TimeoutError()
                if count==3:return b"\x7F\x01\x11"
            return self.normal(req)
        report=record(exchange,self.plan(),clock=clock,sleep=clock.sleep)
        self.assertTrue(report["completed"])
        samples=[row for row in report["observations"] if row["pid"]==5]
        self.assertEqual(["PASSED","TIMEOUT","NEGATIVE"],[x["status"] for x in samples])
        self.assertIsNone(samples[1]["measurement"])
        self.assertIsNone(samples[2]["measurement"])
        self.assertEqual(50,report["summary"][0]["mean"])
        data=list(csv_rows(report))
        self.assertIsNone(data[3][6])
    def test_malformed_response_stops_instead_of_switching_ecu(self):
        sent=[]
        def exchange(req,timeout):
            sent.append(req)
            return b"\x41\x0D\x00" if req==b"\x01\x05" else self.normal(req)
        report=record(exchange,self.plan())
        self.assertEqual("INVALID_APPLICATION_RESPONSE",report["stop_reason"])
        self.assertEqual([b"\x09\x02",b"\x01\x00",b"\x01\x05"],sent)
        self.assertIsNone(report["observations"][0]["measurement"])
    def test_final_vin_mismatch_keeps_raw_prefix_but_not_completed(self):
        calls=0
        def exchange(req,timeout):
            nonlocal calls
            if req==b"\x09\x02":
                calls+=1
                if calls>1:return b"\x49\x02\x01"+b"1FA6P8TH0L5100000"
            return self.normal(req)
        report=record(exchange,self.plan(cycles=1))
        self.assertFalse(report["completed"])
        self.assertEqual("VIN_MISMATCH",report["stop_reason"])
        self.assertEqual(2,len(report["observations"]))
        self.assertEqual("FINAL_VIN",report["admission"][-1]["phase"])
    def test_deadline_and_interrupt_preserve_prefix(self):
        clock=Clock()
        def exchange(req,timeout):
            if req==b"\x01\x05":clock.now+=timeout
            return self.normal(req)
        report=record(exchange,self.plan(budget=0.1),clock=clock,sleep=clock.sleep)
        self.assertEqual("BUDGET_EXHAUSTED",report["stop_reason"])
        self.assertEqual("LATE_RESPONSE",report["observations"][0]["detail"])
        self.assertIsNone(report["observations"][0]["measurement"])
        def cancelled(req,timeout):
            if req==b"\x01\x0C":raise KeyboardInterrupt()
            return self.normal(req)
        report=record(cancelled,self.plan())
        self.assertTrue(report["cancelled"])
        self.assertEqual(2,len(report["observations"]))
        self.assertEqual(0,report["completed_cycles"])
    def test_slow_cycle_does_not_burst_replay_missed_schedule(self):
        clock=Clock()
        def exchange(req,timeout):
            clock.now+=0.3
            return self.normal(req)
        report=record(exchange,self.plan(interval=0.1),clock=clock,sleep=clock.sleep)
        samples=report["observations"]
        self.assertGreaterEqual(samples[2]["started_elapsed_ms"]-samples[0]["started_elapsed_ms"],600)
    def test_cli_private_json_and_csv_with_exclusive_creation(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(j1979_live_cli,"LinuxIsoTp") as factory:
            root=Path(folder);output=root/"record.json";csvfile=root/"record.csv"
            factory.return_value.__enter__.return_value.exchange.side_effect=lambda req,timeout:self.normal(req)
            args=["--interface","can0","--tx","7E0","--rx","7E8","--vin",VIN,"--pid","05","--pid","0C",
                  "--cycles","1","--confirm-physical-channel","--output",str(output),"--csv",str(csvfile),"--quiet"]
            report=j1979_live_cli.run(args)
            self.assertEqual(report,json.loads(output.read_text()))
            with csvfile.open() as stream: data=list(csv.DictReader(stream))
            self.assertEqual(["05","0C"],[r["pid_hex"] for r in data])
            self.assertEqual(["50.0","1000.0"],[r["value"] for r in data])
            with self.assertRaises(FileExistsError):j1979_live_cli.run(args)
            self.assertEqual(1,factory.call_count)
