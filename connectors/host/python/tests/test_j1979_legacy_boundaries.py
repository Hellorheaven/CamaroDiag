import unittest
from camarodiagconnectors.j1979_legacy import scan


class LegacyBoundariesTests(unittest.TestCase):
    def test_late_initial_does_not_establish_transport(self):
        now = [0.0]
        sent = []
        def exchange(command, timeout):
            sent.append(command)
            now[0] += timeout
            return "410000000000"
        report = scan(exchange, lambda: self.fail("late protocol probe"), 2,
                      budget=0.1, clock=lambda: now[0])
        self.assertEqual(["0100"], sent)
        self.assertEqual("BUDGET_EXHAUSTED", report["stop_reason"])
        self.assertIsNone(report["observed_protocol"])
        self.assertEqual("LATE_RESPONSE", report["steps"][0]["detail"])
        self.assertEqual([], report["steps"][0]["records_hex"])

    def test_interrupt_preserves_prefix_and_stops(self):
        sent = []
        def exchange(command, timeout):
            sent.append(command)
            if len(sent) == 1:
                return "410000000000"
            raise KeyboardInterrupt()
        report = scan(exchange, lambda: 2, 2)
        self.assertTrue(report["cancelled"])
        self.assertFalse(report["completed"])
        self.assertEqual(["0100", "03"], sent)
        self.assertEqual("PASSED", report["steps"][0]["status"])
        self.assertEqual("CANCELLED", report["steps"][1]["detail"])

    def test_legacy_measurements_keep_anonymous_records_and_reject_bad_width(self):
        replies = {"0100": "410008180000", "0105": "41055A\n410550",
                   "010C": "410C12", "010D": "410D00"}
        report = scan(lambda req, _: replies.get(req, "NO DATA"), lambda: 2, 2)
        rows = {row["request_hex"]: row for row in report["steps"]}
        self.assertNotEqual("PASSED", rows["010C"]["status"])
        values = report["measurements"]
        self.assertEqual([50.0, 40.0], [x["number"] for x in values if x["pid"] == 5])
        self.assertTrue(all(x["responder_attribution"] == "ANONYMOUS" for x in values))
        self.assertIsNone(report["observed_vin"])

    def test_legacy_dtc_zero_padding_and_unknown_modes_stay_distinct(self):
        replies = {"0100": "410000000000", "03": "43012300000000\n43000000000000",
                   "07": "47000000000000"}
        report = scan(lambda req, _: replies.get(req, "NO DATA"), lambda: 2, 2)
        modes = {row["mode"]: row for row in report["dtc_modes"]}
        self.assertEqual([["P0123"], []], modes[3]["anonymous_records"])
        self.assertEqual([[]], modes[7]["anonymous_records"])
        self.assertIsNone(modes[10]["anonymous_records"])
        self.assertEqual("ANONYMOUS", modes[3]["responder_attribution"])

    def test_legacy_monitor_records_and_freeze_trigger_remain_anonymous(self):
        replies = {"0100": "410000002000", "0113": "411301",
                   "020200": "4202000000\n4202000123",
                   "020000": "42000000000000",
                   "050001": "45000180000000", "050101": "4501012A",
                   "0600": "4600FF80000000", "0601": "46018100010002"}
        report = scan(lambda req, _: replies.get(req, "NO DATA"), lambda: 2, 2)
        self.assertEqual([None, "P0123"], [row["dtc"] for row in report["freeze_triggers"]])
        self.assertEqual([False, True], [row["present"] for row in report["freeze_triggers"]])
        oxygen, monitor = report["monitor_records"]
        self.assertEqual((5, 1, 1, "2A"), (oxygen["mode"], oxygen["test_id"],
                         oxygen["sensor_mask"], oxygen["encoded_data_hex"]))
        self.assertEqual((6, 1, 129, 1, 2), (monitor["mode"], monitor["test_id"],
                         monitor["component_and_limit_encoded"], monitor["value_encoded"], monitor["limit_encoded"]))
        self.assertTrue(all(row["responder_attribution"] == "ANONYMOUS"
                            for row in report["monitor_records"]))
        self.assertNotIn("passed", monitor)
