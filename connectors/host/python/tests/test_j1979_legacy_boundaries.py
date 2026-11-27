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
