import copy
import json
import tempfile
import unittest
from pathlib import Path

from camarodiagconnectors.j1979_live import Plan, record
from camarodiagconnectors.j1979_live_inspection import inspect, inspect_file, compare_files
from camarodiagconnectors.j1979_legacy_live import record_legacy
from camarodiagconnectors.j1979_legacy_frames import checksum

VIN="1G1FF3D36C9104199"


class Clock:
    def __init__(self):
        self.t=0
    def now(self):
        return self.t
    def sleep(self,seconds):
        self.t+=seconds


def report():
    clock=Clock()
    def exchange(req,timeout):
        clock.t+=0.01
        return {b"\x09\x02":b"\x49\x02\x01"+VIN.encode(),
                b"\x01\x00":bytes.fromhex("410008100000"),
                b"\x01\x05":bytes.fromhex("410564"),
                b"\x01\x0C":bytes.fromhex("410C1F40")}[req]
    return {"schema":"camaradiag-j1979-live-v1","tx_can_id":"7E0","rx_can_id":"7E8",
            **record(exchange,Plan((5,12),VIN,2,0.1,10),clock=clock.now,sleep=clock.sleep)}


class InspectionTests(unittest.TestCase):
    def test_numeric_replay(self):
        result=inspect(report(),expected_vin=VIN)
        self.assertTrue(result["verified"])
        self.assertTrue(result["completed"])
        self.assertEqual(result["summary"][0]["mean"],60)
        self.assertEqual(result["summary"][1]["mean"],2000)
        self.assertFalse(result["physical_vehicle_verified"])

    def test_tampered_measurement_summary_and_order(self):
        for change in (lambda r:r["observations"][0]["measurement"].update(number=0),
                       lambda r:r["summary"][0].update(mean=0),
                       lambda r:r["observations"].reverse(),
                       lambda r:r.update(rx_can_id="7E9"),
                       lambda r:r.update(physical_vehicle_verified=True),
                       lambda r:r.update(advertised_pids=[5,12,13]),
                       lambda r:r["admission"].pop()):
            r=report()
            change(r)
            with self.assertRaises(ValueError):
                inspect(r)

    def test_report_comparison_not_time_alignment(self):
        with tempfile.TemporaryDirectory() as folder:
            a,b=Path(folder)/"a.json",Path(folder)/"b.json"
            r=report()
            a.write_text(json.dumps(r))
            b.write_text(json.dumps(r,indent=2))
            out=compare_files([a,b])
            self.assertEqual(out["shared_pids"],[5,12])
            self.assertFalse(out["time_aligned"])
            self.assertIsNone(out["diagnostic_verdict"])
            self.assertEqual(out["comparison"][0]["mean_range"],0)
            self.assertEqual(len(inspect_file(a)["source_sha256"]),64)
            with self.assertRaises(ValueError):
                compare_files([a,a])

    def test_legacy_frames_and_vin_replayed(self):
        clock=Clock()
        def frame(payload,source=0x10):
            raw=bytes((0x48,0x6B,source))+payload
            return (raw+bytes((checksum(2,raw),))).hex().upper()
        def exchange(command,timeout):
            clock.t+=0.01
            if command=="0902":
                data=bytes(3)+VIN.encode()
                return "\r".join(frame(bytes((0x49,2,i+1))+data[i*4:i*4+4]) for i in range(5))
            return frame({"0100":bytes.fromhex("410008000000"),
                          "0900":bytes.fromhex("490001C0000000"),
                          "0901":bytes.fromhex("490105"),
                          "0105":bytes.fromhex("410564")}[command])
        r=record_legacy(exchange,lambda timeout:2,2,0x10,Plan((5,),VIN,1,0.1,10),
                        clock=clock.now,sleep=clock.sleep)
        r["schema"]="camarodiag-j1979-legacy-live-v1"
        self.assertTrue(inspect(r)["completed"])
        r["transport_exchanges"][-1]["frames"][0]["raw_hex"]="486B1049020100000000"
        with self.assertRaises(ValueError):
            inspect(r)


if __name__=="__main__":
    unittest.main()
