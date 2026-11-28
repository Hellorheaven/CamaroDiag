import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979_clear import clear_recipe
from camarodiagconnectors.j1979 import LinuxIsoTp
from camarodiagconnectors import j1979_clear_cli

VIN = "1G1FA1RXXR0123456"
BEFORE = {"0902": bytes.fromhex("490201") + VIN.encode("ascii"),
          "03": bytes.fromhex("43010123"), "07": bytes.fromhex("4700"),
          "0A": bytes.fromhex("4A010123"), "0101": bytes.fromhex("410101000000"),
          "010D": bytes.fromhex("410D00"), "010C": bytes.fromhex("410C0000")}


class ClearRecipeTests(unittest.TestCase):
    def recipe(self, *, before=None, after=None, expected=VIN, ack=b"\x44",
               primary=True, ok=True, yes=True, fail_save=False, changed_identity=False, changed_motion=False):
        sent = []
        reset = False
        baseline = None

        def read(request, _):
            key = request.hex().upper()
            sent.append(key)
            replies = BEFORE | (before or {})
            if reset:
                replies |= {"03": bytes.fromhex("4300")} | (after or {})
            if baseline is not None and not reset:
                if changed_identity and key=="0902":
                    return bytes.fromhex("490201")+b"1G1FA1RXXR0123457"
                if changed_motion and key=="010D":
                    return bytes.fromhex("410D01")
            reply = replies[key]
            if isinstance(reply, Exception):
                raise reply
            return reply

        def save(report):
            nonlocal baseline
            self.assertFalse(report["command_attempted"])
            self.assertEqual("BASELINE_SAVED",report["outcome"])
            if fail_save:
                raise OSError("disk unavailable")
            baseline=report

        def tx():
            nonlocal reset
            self.assertIsNotNone(baseline)
            sent.append("04")
            reset = True
            if isinstance(ack, Exception):
                raise ack
            return ack

        result = clear_recipe(read, tx, expected_vin=expected, primary=primary,
                              warning_acknowledged=ok, confirmed_yes=yes,save_baseline=save)
        return result, sent

    def test_before_after_and_permanent_records(self):
        report, sent = self.recipe()
        self.assertEqual("ACKNOWLEDGED_READBACK", report["outcome"])
        self.assertEqual(["P0123"], report["before_dtcs"]["03"])
        self.assertEqual([], report["after_dtcs"]["03"])
        self.assertEqual(["P0123"], report["after_dtcs"]["0A"])
        self.assertEqual(1, sent.count("04"))
        self.assertEqual(2, sent.count("0101"))

    def test_rejects_missing_baseline_wrong_vin_and_movement(self):
        for kwargs in ({"before": {"03": TimeoutError()}},
                       {"expected": "1G1FA1RXXR0123457"},
                       {"before": {"010D": bytes.fromhex("410D01")}}):
            report, sent = self.recipe(**kwargs)
            self.assertEqual("NOT_TESTED", report["outcome"])
            self.assertNotIn("04", sent)
        for kwargs in ({"primary": False}, {"ok": False}, {"yes": False}):
            with self.assertRaises(ValueError):
                self.recipe(**kwargs)

    def test_disk_failure_and_fresh_preconditions_block_reset(self):
        for kwargs in ({"fail_save":True},{"changed_identity":True},{"changed_motion":True}):
            result,sent=self.recipe(**kwargs)
            self.assertEqual("NOT_TESTED",result["outcome"])
            self.assertFalse(result["command_attempted"])
            self.assertNotIn("04",sent)

    def test_partial_and_refused_are_not_verified_zero(self):
        report, sent = self.recipe(after={"03": TimeoutError()})
        self.assertEqual("ACKNOWLEDGED_PARTIAL", report["outcome"])
        self.assertIsNone(report["after_dtcs"]["03"])
        self.assertEqual(1, sent.count("04"))
        report, _ = self.recipe(after={"03": BEFORE["03"]})
        self.assertEqual("DTCS_STILL_PRESENT", report["reason"])
        for ack, outcome in ((b"\x7f\x04\x22", "REFUSED"), (TimeoutError(), "UNCERTAIN")):
            report, sent = self.recipe(ack=ack)
            self.assertEqual(outcome, report["outcome"])
            self.assertEqual(1, sent.count("04"))
            self.assertFalse(report["acknowledged"])

    def test_read_only_transport_stays_read_only(self):
        with self.assertRaises(ValueError):
            LinuxIsoTp.__new__(LinuxIsoTp).exchange(b"\x04", 2.5)

    def test_cancel_and_exclusive_report(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "clear.json"
            args = ["--interface", "can0", "--tx", "7E0", "--rx", "7E8",
                    "--vin", VIN, "--output", str(output)]
            with patch.object(j1979_clear_cli, "LinuxIsoTp") as factory:
                replies = iter(["OK", "NO"])
                result = j1979_clear_cli.run(args, prompt=lambda _: next(replies))
                self.assertEqual("CANCELLED", result["reason"])
                factory.assert_not_called()
                report, _ = self.recipe()
                factory.return_value.__enter__.return_value.run_clear.return_value = report
                replies = iter(["OK", "YES"])
                exported = j1979_clear_cli.run(args, prompt=lambda _: next(replies))
                self.assertEqual(exported, json.loads(output.read_text()))
                replies = iter(["OK", "YES"])
                with self.assertRaises(FileExistsError):
                    j1979_clear_cli.run(args, prompt=lambda _: next(replies))
                self.assertEqual(1, factory.call_count)
