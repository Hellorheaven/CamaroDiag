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
