import unittest

from camarodiagconnectors.j1979_legacy_monitor import decode, project


class LegacyMonitorTest(unittest.TestCase):
    def test_mode05_historical_tids_and_limits(self):
        for tid in range(1,11):
            req=bytes((5,tid,1))
            data=bytes((0x45,tid,1,90)) + (bytes((10,100)) if tid>4 else b"")
            record=decode(req,data)
            factor=.004 if tid in (5,6) else .04 if tid in (9,10) else .005
            self.assertAlmostEqual(record["scaled"]["value"],90*factor)
            self.assertEqual(record["scaled"]["unit"],"s" if tid in (5,6,9,10) else "V")
            self.assertEqual(record["scaled"]["numeric_relation"],"WITHIN" if tid>4 else "NOT_APPLICABLE")
            self.assertIsNone(record["monitor_verdict"])
            if tid<=4:
                self.assertIsNone(record["minimum_encoded"])
                self.assertIsNone(record["scaled"]["maximum"])
            else:
                self.assertAlmostEqual(record["scaled"]["minimum"],10*factor)
                self.assertAlmostEqual(record["scaled"]["maximum"],100*factor)

    def test_mode05_raw_and_invalid(self):
        unknown=decode(bytes.fromhex("057001"),bytes.fromhex("457001123456"))
        self.assertIsNone(unknown["scaled"])
        self.assertEqual(unknown["encoded_data_hex"],"123456")
        for req,res in [("050101","4501012A0001"),("050501","4505012A"),
                        ("050103","4501032A"),("050101","4501022A"),
                        ("050001","45000180000000"),("0601","4501012A")]:
            self.assertIsNone(decode(bytes.fromhex(req),bytes.fromhex(res)))

    def test_mode06_limit_direction_not_can_scaling(self):
        for component,kind in ((0x81,"MINIMUM"),(0x01,"MAXIMUM")):
            result=decode(b"\x06\x01",bytes((0x46,1,component,0,1,0,2)))
            self.assertEqual(result["component_id"],1)
            self.assertEqual(result["limit_type"],kind)
            self.assertEqual(result["encoded_relation"],"BELOW")
            self.assertIsNone(result["scaled"])
            self.assertIsNone(result["monitor_verdict"])
        self.assertIsNone(decode(bytes.fromhex("0601"),bytes.fromhex("46010124000100000002")))

    def test_projection_keeps_independent_replies(self):
        row={"request_hex":"050501","status":"PASSED",
             "records_hex":["450501320064","450501330064"]}
        values=project([row])
        self.assertEqual([r["record_index"] for r in values],[0,1])
        self.assertEqual([r["value_encoded"] for r in values],[50,51])
        self.assertEqual(project([{**row,"status":"FAILED"}]),[])
        self.assertEqual(project([{**row,"records_hex":row["records_hex"]+["450501"]}]),[])


if __name__ == "__main__":
    unittest.main()
