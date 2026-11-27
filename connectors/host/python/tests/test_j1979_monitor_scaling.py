import unittest

from camarodiagconnectors.j1979_monitor_scaling import scale_record, from_steps


class MonitorScalingTest(unittest.TestCase):
    def test_historical_catalog_coverage_and_reserved_identifiers(self):
        defined = {i for i in range(256) if scale_record(i, 0, 0, 1) is not None}
        self.assertEqual(len(defined), 79)
        self.assertEqual(defined, set(range(1, 0x3A)) | {
            0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x8A, 0x8B, 0x8C,
            0x8D, 0x8E, 0x90, 0x96, 0x9C, 0x9D, 0xA8, 0xA9,
            0xAF, 0xB0, 0xB1, 0xFD, 0xFE})
        self.assertIsNone(scale_record(0x99, 0, 0, 1))
        self.assertIsNone(scale_record(0xFF, 0, 0, 1))

    def test_offset_and_signed_limits(self):
        cases = [(0x16, 400, 0, 1000, 0, -40, 60, "degC"),
                 (0x39, 32768, 0, 65535, 0, -327.68, 327.67, "%"),
                 (0x90, 65535, 32768, 0, -1, -32768, 0, "ms"),
                 (0xFD, 65535, 32768, 32767, -.001, -32.768, 32.767, "kPa"),
                 (0x2C, 100, 0, 200, 1, 0, 2, "g/cyl")]
        for sid, v, lo, hi, x, a, b, unit in cases:
            with self.subTest(sid=sid):
                s = scale_record(sid, v, lo, hi)
                self.assertAlmostEqual(s["value"], x)
                self.assertAlmostEqual(s["minimum"], a)
                self.assertAlmostEqual(s["maximum"], b)
                self.assertEqual(s["unit"], unit)
                self.assertEqual(s["numeric_relation"], "WITHIN")
                self.assertIsNone(s["monitor_verdict"])

    def test_numeric_comparison_is_not_monitor_verdict(self):
        for v, lo, hi, expected in [(1,2,3,"BELOW"), (4,2,3,"ABOVE"),
                                    (2,2,3,"WITHIN"), (2,3,1,"INVERTED_LIMITS")]:
            s = scale_record(0x24, v, lo, hi)
            self.assertEqual(s["numeric_relation"], expected)
            self.assertIsNone(s["monitor_verdict"])
        self.assertIsNone(scale_record(0x2E, 2, 0, 1))
        self.assertEqual(scale_record(0x2E, 1, 0, 1)["value"], 1)
        for bad in (-1, 65536, True, 1.0):
            with self.assertRaises(ValueError):
                scale_record(0x24, bad, 0, 1)

    def test_raw_records_keep_order_even_for_repeated_tid(self):
        step = {"request_hex":"0621","response_hex":"462180240001000000022180FF000200010003",
                "status":"PASSED"}
        rows = from_steps([step])
        self.assertEqual(len(rows), 2)
        self.assertEqual([r["record_index"] for r in rows], [0,1])
        self.assertEqual([r["test_id"] for r in rows], [128,128])
        self.assertEqual(rows[0]["scaled"]["value"], 1)
        self.assertIsNone(rows[1]["scaled"])
        self.assertEqual(rows[1]["value_encoded"], 2)
        for update in ({"request_hex":"0622"}, {"status":"FAILED"},
                       {"request_hex":"0600"}, {"response_hex":"46218100010002"},
                       {"response_hex":step["response_hex"][:-2]}):
            self.assertEqual(from_steps([{**step, **update}]), [])


if __name__ == "__main__":
    unittest.main()
