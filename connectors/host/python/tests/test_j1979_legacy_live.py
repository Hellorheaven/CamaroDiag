import csv
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979_live import Plan
from camarodiagconnectors.j1979_legacy_live import LegacyLiveLink,record_legacy
from camarodiagconnectors.j1979_legacy_frames import checksum
from camarodiagconnectors import j1979_legacy_live_cli

VIN="1G1FA1RXXR0123456"


def frame(protocol,source,payload):
    data=bytes.fromhex(payload) if isinstance(payload,str) else payload
    header=bytes((0x80|len(data),0xF1,source)) if protocol>=4 else bytes(
        (0x41 if protocol==1 else 0x48,0x6B,source))
    wire=header+data
    return (wire+bytes((checksum(protocol,wire),))).hex().upper()


def fixture(protocol,vin=VIN):
    def row(payload,source=0x10):return frame(protocol,source,payload)
    data=bytes(3)+vin.encode("ascii")
    vin_rows=[row(bytes((0x49,2,i+1))+data[i*4:i*4+4]) for i in range(5)]
    return {"0100":"\n".join([row("410008100000"),row("410000080000",0x11)]),
            "0900":row("490001C0000000"),"0901":row("490105"),
            "0902":"\n".join(vin_rows+[row("49020100000041",0x11)]),
            "0105":"\n".join([row("41055A"),row("410564",0x11)]),
            "010C":row("410C0FA0")}


class Clock:
    def __init__(self):self.now=0
    def __call__(self):return self.now
    def sleep(self,amount):self.now+=amount


class TestLegacyLive(unittest.TestCase):
    def test_all_five_variants_and_raw_attribution(self):
        for protocol in range(1,6):
            clock=Clock();sent=[];probes=[]
            replies=fixture(protocol)
            def exchange(req,timeout):
                sent.append(req);clock.now+=0.02
                return replies[req]
            def probe(timeout):
                probes.append(timeout);return protocol
            result=record_legacy(exchange,probe,protocol,0x10,Plan((5,12),VIN,2,0.1,30),
                                 clock=clock,sleep=clock.sleep)
            self.assertTrue(result["completed"],result)
            self.assertEqual("FUNCTIONAL_SELECTED_RESPONSE_LIVE",result["scope"])
            self.assertFalse(result["physically_addressed"])
            self.assertEqual(protocol,result["observed_protocol"])
            self.assertEqual(VIN,result["observed_vin"])
            self.assertEqual(["11"],result["other_response_sources"])
            self.assertEqual(2,len(probes))
            self.assertEqual(50,result["summary"][0]["mean"])
            self.assertEqual(1000,result["summary"][1]["mean"])
            vin_rows=[x for x in result["transport_exchanges"] if x["request_hex"]=="0902"]
            self.assertEqual(2,len(vin_rows))
            self.assertEqual(6,len(vin_rows[0]["frames"]))
            self.assertEqual(5,len(vin_rows[0]["selected_records_hex"]))
            self.assertEqual("NORMALIZED_APPLICATION_PAYLOAD",result["response_hex_representation"])
            self.assertEqual(["0100","0900","0901","0902","0100"],sent[:5])
            self.assertNotIn("04",sent)
            self.assertNotIn("08010000000000",sent)

    def test_missing_selected_vin_cannot_be_completed_from_other_source(self):
        replies=fixture(2)
        replies["0902"]="\n".join(replies["0902"].splitlines()[1:])
        result=record_legacy(lambda req,_:replies[req],lambda _:2,2,0x10,Plan((5,),VIN,1))
        self.assertFalse(result["completed"])
        self.assertEqual([],result["observations"])
        self.assertEqual("VIN_FRAGMENT_SEQUENCE_INVALID",result["transport_exchanges"][-1]["detail"])

    def test_protocol_mismatch_stops_before_identity_or_measurement(self):
        sent=[];replies=fixture(2)
        result=record_legacy(lambda req,_:sent.append(req) or replies[req],lambda _:3,2,0x10,Plan((5,),VIN,1))
        self.assertFalse(result["completed"])
        self.assertEqual(["0100"],sent)
        self.assertEqual("PROTOCOL_MISMATCH",result["transport_exchanges"][-1]["detail"])

    def test_no_selected_response_is_gap_not_another_ecu(self):
        clock=Clock();count=0
        replies=fixture(2)
        def exchange(req,timeout):
            nonlocal count
            if req=="0105":
                count+=1
                if count==2:return frame(2,0x11,"41057F")
            return replies[req]
        result=record_legacy(exchange,lambda _:2,2,0x10,Plan((5,),VIN,2,0.1),
                             clock=clock,sleep=clock.sleep)
        self.assertTrue(result["completed"])
        self.assertEqual(["PASSED","TIMEOUT"],[x["status"] for x in result["observations"]])
        self.assertIsNone(result["observations"][1]["measurement"])
        self.assertEqual(50,result["summary"][0]["mean"])

    def test_checksum_and_duplicate_selected_records_fail(self):
        for corrupt in (True,False):
            replies=fixture(2)
            good=frame(2,0x10,"41055A")
            replies["0105"]=good[:-2]+f"{int(good[-2:],16)^1:02X}" if corrupt else good+"\n"+good
            result=record_legacy(lambda req,_:replies[req],lambda _:2,2,0x10,Plan((5,),VIN,1))
            self.assertFalse(result["completed"])
            self.assertIsNone(result["observations"][0]["measurement"])
            self.assertEqual("FAILED",result["transport_exchanges"][-1]["status"])

    def test_initial_identity_and_selected_pids_admission(self):
        for pids,expected,stop in (((5,),"1FA6P8TH0L5100000","VIN_MISMATCH"),
                                   ((13,),VIN,"SELECTED_PID_NOT_VERIFIED")):
            sent=[];replies=fixture(2)
            result=record_legacy(lambda req,_:sent.append(req) or replies[req],lambda _:2,2,0x10,Plan(pids,expected,1))
            self.assertEqual(stop,result["stop_reason"])
            self.assertNotIn("0105",sent)
            self.assertNotIn("010D",sent)

    def test_vin_subqueries_share_one_deadline(self):
        clock=Clock();sent=[];replies=fixture(2)
        def exchange(req,timeout):
            sent.append(req)
            clock.now+=timeout
            return replies[req]
        result=record_legacy(exchange,lambda _:2,2,0x10,Plan((5,),VIN,1,budget=3),clock=clock,sleep=clock.sleep)
        self.assertEqual("BUDGET_EXHAUSTED",result["stop_reason"])
        self.assertEqual(["0100"],sent)
        self.assertEqual("LATE_RESPONSE",result["transport_exchanges"][0]["detail"])

    def test_cli_requires_explicit_scope_and_reserves_both_outputs(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(j1979_legacy_live_cli,"LegacyElmSerial") as factory:
            root=Path(folder);output=root/"live.json";csvfile=root/"live.csv"
            adapter=factory.return_value.__enter__.return_value
            adapter.exchange.side_effect=lambda req,_:fixture(2)[req]
            adapter.observed_protocol.return_value=2
            args=["--port","mock","--protocol","2","--source","10","--vin",VIN,"--pid","05","--cycles","1",
                  "--confirm-dedicated-adapter","--confirm-functional-scope","--output",str(output),"--csv",str(csvfile),"--quiet"]
            with self.assertRaises(SystemExit):
                j1979_legacy_live_cli.run([x for x in args if x!="--confirm-functional-scope"])
            factory.assert_not_called()
            result=j1979_legacy_live_cli.run(args)
            self.assertTrue(result["completed"])
            self.assertEqual(result,json.loads(output.read_text()))
            self.assertTrue(factory.call_args.kwargs["headered"])
            with csvfile.open() as stream:
                rows=list(csv.DictReader(stream))
            self.assertEqual("50.0",rows[0]["value"])
            with self.assertRaises(FileExistsError):
                j1979_legacy_live_cli.run(args)
            self.assertEqual(1,factory.call_count)
