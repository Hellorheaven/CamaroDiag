import unittest
from camarodiagconnectors.j1979_vehicle_info import can_info, legacy_info
from camarodiagconnectors.j1979 import scan
from test_j1979 import REPLIES


def can(kind, words):
    return bytes((0x49, kind, len(words))) + b"".join(x.to_bytes(2,"big") for x in words)


def legacy(kind, payload):
    return [bytes((0x49,kind,index//4+1)) + payload[index:index+4]
            for index in range(0,len(payload),4)]


class ExtendedVehicleInfoTests(unittest.TestCase):
    def test_counter_vectors(self):
        words = [65535, 40000, 12, 6, 1, 0] + [0]*10
        record = can_info(8,can(8,words))
        self.assertEqual((65535,40000,16),(record["general_denominator"],record["ignition_cycles"],record["counter_count"]))
        self.assertEqual(2.0,record["monitors"][0]["ratio"])
        self.assertIsNone(record["monitors"][1]["ratio"])
        self.assertIsNone(record["regulatory_verdict"])
        self.assertEqual("secondary-oxygen-bank-2",can_info(8,can(8,words+[0]*4))["monitors"][-1]["monitor"])
        self.assertEqual("fuel-system",can_info(11,can(11,words+[0]*2))["monitors"][-1]["monitor"])
        self.assertEqual("nmhc-catalyst",can_info(11,can(11,words))["monitors"][0]["monitor"])
        for kind,count in ((8,18),(11,20),(8,2),(11,15)):
            with self.assertRaises(ValueError):
                can_info(kind,can(kind,[0]*count))
        for bad in (can(8,words)[:-1],can(11,words),can(8,words)+b"\x00"):
            with self.assertRaises(ValueError):
                can_info(8,bad)

    def test_legacy_fragments(self):
        response=can(11,list(range(18)))
        rows=legacy(11,response[3:])
        self.assertEqual(can_info(11,response),legacy_info(11,9,rows))
        for wrong in (rows[:-1],list(reversed(rows)),rows[:1]+rows[:1]+rows[2:]):
            with self.assertRaises(ValueError):
                legacy_info(11,9,wrong)
        wrong=rows.copy()
        wrong[4]=bytes((0x49,8,5))+wrong[4][3:]
        with self.assertRaises(ValueError):
            legacy_info(11,9,wrong)

    def test_text_and_padding(self):
        serial=b"\x00"*11+b"SER123"
        reply=b"\x49\x0d\x01"+serial
        self.assertEqual(["SER123"],can_info(13,reply)["values"])
        self.assertEqual(can_info(13,reply),legacy_info(13,5,legacy(13,b"\x00"*3+serial)))
        name=b"ECM\x00-Engine Control\x00"
        self.assertEqual(20,len(name))
        self.assertEqual(["ECM-Engine Control"],legacy_info(10,5,legacy(10,name))["values"])
        for malformed in (reply[:-1],reply[:-1]+b"\x00",reply[:2]+b"\x02"+reply[3:]):
            with self.assertRaises(ValueError):
                can_info(13,malformed)

    def test_can_scan_publishes_typed_view_without_rewriting_historical_shape(self):
        replies=dict(REPLIES)
        replies["0900"]=bytes.fromhex("490041000000")
        replies["0908"]=can(8,list(range(16)))
        report=scan(lambda req,_: replies[req.hex().upper()])
        self.assertEqual(1,len(report["extended_vehicle_info"]))
        self.assertEqual(16,report["extended_vehicle_info"][0]["counter_count"])
        row=next(x for x in report["steps"] if x["request_hex"]=="0908")
        self.assertEqual("RAW",row["status"])
        self.assertEqual({"shape":"RAW"},row["application_evidence"])
        replies["0908"]=replies["0908"][:-1]
        report=scan(lambda req,_: replies[req.hex().upper()])
        self.assertEqual([],report["extended_vehicle_info"])
        self.assertEqual("FAILED",next(x for x in report["steps"] if x["request_hex"]=="0908")["status"])


if __name__=="__main__":
    unittest.main()
