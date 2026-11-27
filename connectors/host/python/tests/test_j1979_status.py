import unittest

from camarodiagconnectors.j1979_status import DEFINITIONS,decode_data,decode_response,from_steps
from camarodiagconnectors.j1979 import scan
from test_j1979 import REPLIES


class StatusTests(unittest.TestCase):
    def test_categorical_catalog_widths_and_reserved_bits(self):
        for pid,(width,reserved,_) in DEFINITIONS.items():
            with self.subTest(pid=pid):
                self.assertIsNotNone(decode_data(pid,bytes(width)))
                self.assertIsNone(decode_data(pid,bytes(width-1)))
                self.assertIsNone(decode_data(pid,bytes(width+1)))
                for offset,mask in reserved:
                    data=bytearray(width)
                    data[offset]=mask
                    self.assertIsNone(decode_data(pid,bytes(data)))

    def test_fuel_and_unknown_codes_are_never_numeric_measurements(self):
        records=decode_data(3,bytes([2,3]))
        self.assertEqual(["CLOSED_FEEDBACK","UNKNOWN"],[r["state"] for r in records])
        self.assertEqual([2,3],[r["raw_encoded"] for r in records])
        self.assertTrue(all("number" not in r for r in records))
        self.assertEqual("HYBRID_REGENERATION",decode_data(0x51,b"\x16")[0]["state"])
        self.assertEqual("UNKNOWN",decode_data(0x51,b"\xff")[0]["state"])

    def test_oxygen_layout_distinguishes_two_and_four_banks(self):
        data=b"\x10"
        two=decode_data(0x13,data)
        four=decode_data(0x1D,data)
        self.assertEqual("oxygen-bank-2-sensor-1",next(r["name"] for r in two if r["state"]=="PRESENT"))
        self.assertEqual("oxygen-bank-3-sensor-1",next(r["name"] for r in four if r["state"]=="PRESENT"))

    def test_boost_and_vgt_respect_support_masks(self):
        for pid,width in ((0x70,10),(0x71,6)):
            data=bytes([0x04])+bytes(width-2)+bytes([0x0E])
            values=decode_data(pid,data)
            self.assertEqual(1,len(values))
            self.assertEqual("CLOSED_LOOP",values[0]["state"])
            self.assertEqual([],decode_data(pid,bytes(width)))
            self.assertIsNone(decode_data(pid,data[:-1]+b"\x80"))

    def test_aftertreatment_states_and_supported_slots(self):
        values=decode_data(0x8B,bytes.fromhex("07070000000000"))
        self.assertEqual(["ACTIVE","ACTIVE","ACTIVE"],[v["state"] for v in values])
        self.assertEqual([],decode_data(0x8B,bytes.fromhex("000F0000000000")))
        self.assertIsNone(decode_data(0x8B,bytes.fromhex("07100000000000")))
        pm=decode_data(0x8F,bytes.fromhex("01030000030000"))
        self.assertEqual(["pm-sensor-bank-1-measuring","pm-sensor-bank-1-regenerating"],
                         [v["name"] for v in pm])

    def test_diesel_regulation_and_nox_warning(self):
        values=decode_data(0x92,bytes.fromhex("8180"))
        self.assertEqual(["NOT_CLOSED_LOOP","CLOSED_LOOP"],[v["state"] for v in values])
        warning=decode_data(0x94,bytes([1,0x73])+bytes(10))
        self.assertEqual(["ACTIVE","ENABLED","ACTIVE","UNSUPPORTED"],[v["state"] for v in warning])

    def test_response_correlation_and_status(self):
        req,res=b"\x01\x51",b"\x41\x51\x04"
        self.assertEqual("DIESEL",decode_response(req,res)[0]["state"])
        self.assertIsNone(decode_response(req,b"\x41\x52\x04"))
        self.assertIsNone(decode_response(b"\x02\x51\x00",res))
        step={"request_hex":"0151","response_hex":"415104","status":"RAW"}
        self.assertEqual(1,len(from_steps([step])))
        for status in ("FAILED","NOT_TESTED","TIMEOUT","NEGATIVE"):
            self.assertEqual([],from_steps([{**step,"status":status}]))

    def test_scan_integration_and_malformed_record(self):
        replies=dict(REPLIES,**{"0100":bytes.fromhex("410020000000"),"0103":bytes.fromhex("41030208")})
        report=scan(lambda req,_:replies[req.hex().upper()])
        self.assertEqual(["CLOSED_FEEDBACK","OPEN_FAULT"],
                         [v["state"] for v in report["status_records"][0]["states"]])
        replies["0103"]=bytes.fromhex("410302")
        report=scan(lambda req,_:replies[req.hex().upper()])
        self.assertEqual([],report["status_records"])
        self.assertEqual("FAILED",next(s for s in report["steps"] if s["request_hex"]=="0103")["status"])


if __name__=="__main__":
    unittest.main()
