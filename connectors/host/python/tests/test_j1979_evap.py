import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979 import evap_recipe, LinuxIsoTp
from camarodiagconnectors import j1979_evap_cli

VIN = "1G1FA1RXXR0123456"
REPLIES = {"0902": bytes.fromhex("490201") + VIN.encode("ascii"),
           "0800": bytes.fromhex("480080000000"),
           "010D": bytes.fromhex("410D00"), "010C": bytes.fromhex("410C0000")}


class EvapRecipeTests(unittest.TestCase):
    def recipe(self, *, overrides=None, ack=b"\x48\x01", expected=VIN,
               primary=True, ok=True, yes=True):
        sent = []
        replies = REPLIES | (overrides or {})
        def read(request, _):
            key = request.hex().upper()
            sent.append(key)
            return replies[key]
        def tx():
            sent.append("0801")
            if isinstance(ack, Exception):
                raise ack
            return ack
        result = evap_recipe(read, tx, expected_vin=expected, primary=primary,
                             warning_acknowledged=ok, confirmed_yes=yes)
        return result, sent

    def test_one_request_only_and_ack_not_completion(self):
        result, sent = self.recipe()
        self.assertEqual(["0902", "0800", "010D", "010C", "0801"], sent)
        self.assertEqual("ACKNOWLEDGED", result["outcome"])
        self.assertFalse(result["monitor_completion_verified"])
        self.assertEqual("4801", result["exchanges"][-1]["response_hex"])

    def test_failed_preconditions_never_transmit(self):
        for overrides, expected in [
            ({"0800": bytes.fromhex("480000000000")}, VIN),
            ({"010D": bytes.fromhex("410D01")}, VIN),
            ({"010C": bytes.fromhex("410C0001")}, VIN),
            ({}, "1G1FA1RXXR0123457")]:
            result, sent = self.recipe(overrides=overrides, expected=expected)
            self.assertFalse(result["command_attempted"])
            self.assertNotIn("0801", sent)
        for kwargs in ({"primary": False}, {"ok": False}, {"yes": False}):
            with self.assertRaises(ValueError):
                self.recipe(**kwargs)

    def test_refusal_and_uncertain_ack_never_retried(self):
        for ack, outcome in [(b"\x7f\x08\x22", "REFUSED"),
                             (b"\x48\x02", "UNCERTAIN"),
                             (TimeoutError(), "UNCERTAIN")]:
            result, sent = self.recipe(ack=ack)
            self.assertEqual(outcome, result["outcome"])
            self.assertEqual(1, sent.count("0801"))

    def test_generic_exchange_cannot_dispatch_evap(self):
        channel = LinuxIsoTp.__new__(LinuxIsoTp)
        with self.assertRaises(ValueError):
            channel.exchange(b"\x08\x01", 2.5)

    def test_cli_cancellation_and_exclusive_export(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "evap.json"
            args = ["--interface", "can0", "--tx", "7E0", "--rx", "7E8",
                    "--vin", VIN, "--output", str(output)]
            with patch.object(j1979_evap_cli, "LinuxIsoTp") as factory:
                answers = iter(["OK", "NO"])
                result = j1979_evap_cli.run(args, prompt=lambda _: next(answers))
                self.assertEqual("CANCELLED", result["reason"])
                factory.assert_not_called()
                self.assertFalse(output.exists())
                factory.return_value.__enter__.return_value.run_evap.return_value = {
                    "outcome": "ACKNOWLEDGED", "observed_vin": VIN,
                    "command_attempted": True, "exchanges": []}
                answers = iter(["OK", "YES"])
                report = j1979_evap_cli.run(args, prompt=lambda _: next(answers))
                self.assertEqual(report, json.loads(output.read_text()))
                answers = iter(["OK", "YES"])
                with self.assertRaises(FileExistsError):
                    j1979_evap_cli.run(args, prompt=lambda _: next(answers))
                self.assertEqual(1, factory.call_count)
