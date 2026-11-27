import unittest
import copy

from camarodiagconnectors.j1979_multi_signal import (
    DEFINITIONS, decode_data, decode_response, width, from_steps,
)
from camarodiagconnectors.j1979_live import Plan, record, csv_rows, legacy_numeric_pid
from camarodiagconnectors.j1979_live_inspection import inspect
from camarodiagconnectors.j1979 import scan
from test_j1979 import REPLIES, VIN


class MultiSignalTests(unittest.TestCase):
    def test_each_catalog_entry_exact_width_and_valid_masks(self):
        self.assertEqual(49,len(DEFINITIONS))
        self.assertEqual(144,sum(len(d.fields) for d in DEFINITIONS.values()))
        for pid,d in DEFINITIONS.items():
            with self.subTest(pid=pid):
                self.assertEqual(d.width,width(pid))
                data=bytes([d.support_mask])+bytes(d.width-1)
                signals=decode_data(pid,data)
                self.assertIsNotNone(signals)
                self.assertEqual(len(d.fields),len(signals))
                self.assertEqual(len(signals),len({x["name"] for x in signals}))
                self.assertIsNone(decode_data(pid,data[:-1]))
                self.assertIsNone(decode_data(pid,data+b"\x00"))
                if d.support_mask:
                    self.assertEqual([],decode_data(pid,bytes(d.width)))
                    forbidden=(~d.support_mask)&255
                    if forbidden:
                        self.assertIsNone(decode_data(pid,bytes([forbidden])+data[1:]))

    def test_masked_maf_and_coolant_do_not_invent_second_sensor(self):
        one=decode_data(0x66,bytes.fromhex("010400FFFF"))
        self.assertEqual([("maf-a",32.0,"g/s")],[(x["name"],x["number"],x["unit"]) for x in one])
        two=decode_data(0x67,bytes.fromhex("035A28"))
        self.assertEqual([50.0,0.0],[x["number"] for x in two])
        self.assertEqual([],decode_data(0x67,bytes.fromhex("00FFFF")))
        self.assertIsNone(decode_data(0x67,bytes.fromhex("045A28")))

    def test_narrowband_trim_unavailable_and_wideband_current(self):
        o2=decode_data(0x14,bytes.fromhex("C8FF"))
        self.assertEqual(1,len(o2))
        self.assertEqual(1.0,o2[0]["number"])
        self.assertEqual(0.0,decode_data(0x14,bytes.fromhex("0080"))[1]["number"])
        wb=decode_data(0x34,bytes.fromhex("80007F00"))
        self.assertEqual(1.0,wb[0]["number"])
        self.assertEqual(-1.0,wb[1]["number"])
        self.assertEqual(["ratio","mA"],[x["unit"] for x in wb])

    def test_signed_dpf_and_unsigned_32bit_runtime(self):
        values=decode_data(0x7A,bytes.fromhex("07FF9C006400C8"))
        self.assertEqual([-1.0,1.0,2.0],[x["number"] for x in values])
        runtime=decode_data(0x7F,bytes.fromhex("01FFFFFFFF0000000000000000"))
        self.assertEqual(4294967295,runtime[0]["raw_encoded"])
        self.assertEqual(4294967295.0,runtime[0]["number"])

    def test_exact_response_identity_and_derived_status(self):
        request=bytes.fromhex("0167")
        response=bytes.fromhex("4167035A28")
        self.assertIsNotNone(decode_response(request,response))
        self.assertIsNone(decode_response(request,bytes.fromhex("4168035A28")))
        self.assertIsNone(decode_response(bytes.fromhex("026700"),response))
        step={"request_hex":"0167","response_hex":response.hex().upper(),"status":"RAW"}
        self.assertEqual(2,len(from_steps([step])[0]["signals"]))
        for status in ("FAILED","NOT_TESTED","TIMEOUT","NEGATIVE"):
            self.assertEqual([],from_steps([{**step,"status":status}]))

    def test_physical_scan_records_signals_only_for_advertised_pid(self):
        replies=dict(REPLIES,**{
            "0100":bytes.fromhex("410000000001"),
            "0120":bytes.fromhex("412000000001"),
            "0140":bytes.fromhex("414000000001"),
            "0160":bytes.fromhex("416002000000"),
            "0167":bytes.fromhex("4167035A28")})
        report=scan(lambda req,_:replies[req.hex().upper()])
        self.assertTrue(report["completed"])
        self.assertEqual("0167",report["multi_signal_records"][0]["request_hex"])
        self.assertEqual([50.0,0.0],[x["number"] for x in report["multi_signal_records"][0]["signals"]])
        replies["0167"]=bytes.fromhex("4167045A28")
        report=scan(lambda req,_:replies[req.hex().upper()])
        self.assertEqual([],report["multi_signal_records"])
        self.assertEqual("FAILED",next(s for s in report["steps"] if s["request_hex"]=="0167")["status"])

    def live_report(self,empty=False):
        now=[0.0]
        count=[0]
        def exchange(req,timeout):
            now[0]+=.01
            key=req.hex().upper()
            if key=="0902":return bytes.fromhex("490201")+VIN.encode("ascii")
            if key=="0100":return bytes.fromhex("410000000001")
            if key=="0120":return bytes.fromhex("412000000001")
            if key=="0140":return bytes.fromhex("414000000001")
            if key=="0160":return bytes.fromhex("416002000000")
            if key=="0167":
                count[0]+=1
                return bytes.fromhex("416700FFFF" if empty else "4167035A28" if count[0]==1 else "4167016428")
            raise AssertionError(key)
        result=record(exchange,Plan((0x67,),VIN,2,.1,30),
                      clock=lambda:now[0],sleep=lambda t:now.__setitem__(0,now[0]+t))
        return {"schema":"camarodiag-j1979-live-v1","tx_can_id":"7E0","rx_can_id":"7E8",**result}

    def test_live_statistics_and_csv_do_not_mix_channels(self):
        report=self.live_report()
        self.assertTrue(report["completed"])
        a,b=report["multi_signal_summary"]
        self.assertEqual((2,0,55.0),(a["count"],a["missing"],a["mean"]))
        self.assertEqual((1,1,0.0),(b["count"],b["missing"],b["mean"]))
        self.assertIsNone(report["summary"][0]["mean"])
        rows=list(csv_rows(report))
        self.assertEqual(("signal_name","raw_encoded"),rows[0][-2:])
        self.assertEqual(4,len(rows))
        self.assertEqual("coolant-2",rows[2][-2])
        self.assertEqual(40,rows[2][-1])
        self.assertFalse(self.live_report(True)["completed"])

    def test_replay_rejects_modified_live_channels(self):
        report=self.live_report()
        self.assertTrue(inspect(report)["completed"])
        for change in (
            lambda r:r["observations"][0]["multi_signals"][0].update(number=999),
            lambda r:r["observations"][0]["multi_signals"][0].update(raw_encoded=0),
            lambda r:r["observations"][0]["multi_signals"].reverse(),
            lambda r:r["multi_signal_summary"][0].update(mean=123),
            lambda r:r["multi_signal_summary"][1].update(missing=0),
            lambda r:r["observations"][1]["multi_signals"].append(r["observations"][0]["multi_signals"][1]),
        ):
            modified=copy.deepcopy(report);change(modified)
            with self.assertRaises(ValueError):
                inspect(modified)

    def test_legacy_width_guard(self):
        for pid in (0x14,0x24,0x64,0x66,0x67):
            self.assertTrue(legacy_numeric_pid(pid))
        for pid in (0x68,0x6D,0x78,0x7F):
            self.assertFalse(legacy_numeric_pid(pid))
