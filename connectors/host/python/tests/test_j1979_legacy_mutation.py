import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from camarodiagconnectors.j1979_legacy_frames import checksum, response_frames
from camarodiagconnectors.j1979_legacy_mutation import mutation_recipe
from camarodiagconnectors import j1979_legacy_mutation_cli

VIN = "1G1FA1RXXR0123456"


def frame(protocol, source, payload):
    header = bytes((0x80 | len(payload), 0xF1, source)) if protocol in (4,5) else bytes(
        (0x41 if protocol == 1 else 0x48, 0x6B, source))
    data = header + payload
    return (data + bytes((checksum(protocol, data),))).hex().upper()


class LegacyMutationTests(unittest.TestCase):
    def test_crc_reference_and_all_variants(self):
        self.assertEqual(0x4B, checksum(2, b"123456789"))
        self.assertEqual(0x06, checksum(3, b"\x01\x02\x03"))
        for p in range(1,6):
            raw = frame(p, 0x10, bytes.fromhex("410080000000"))
            records = response_frames(p, "0100", raw)
            self.assertEqual(0x10, records[0].source)
            self.assertEqual(bytes.fromhex("410080000000"), records[0].payload)
            with self.assertRaises(ValueError):
                response_frames(p, "0100", raw[:-2] + f"{int(raw[-2:],16)^1:02X}")
            with self.assertRaises(ValueError):
                response_frames(p, "0100", "410080000000")
            with self.assertRaises(ValueError):
                response_frames(p, "0100", raw + "\rNO DATA")

    def recipe(self, p=2, operation="clear", wrong_vin=False, moving=False,
               extra=False, fail_save=False, missing_ack=False, fail_after=False,
               second_evap_source=False, ok=True, yes=True,
               missing_pending=False, residual_pending=False, changed_scope_after_save=False):
        attempted = False
        saved = False
        sent = []
        vin = bytes(3) + (VIN[:-1]+"7" if wrong_vin else VIN).encode("ascii")
        def encode(*rows):
            return "\r".join(frame(p,s,bytes.fromhex(data) if isinstance(data,str) else data) for s,data in rows) or "NO DATA"
        def read(command, _):
            sent.append(command)
            if attempted and fail_after:
                raise OSError("Lost link after acknowledgment")
            replies = {
                "0100": encode((0x10,"410080000000"),(0x11,"410080000000"),
                              *(([(0x12,"410080000000")]) if extra or (saved and changed_scope_after_save) else [])),
                "0901": encode((0x10,"490105")),
                "0902": encode(*[(0x10,bytes((0x49,2,i+1))+vin[i*4:i*4+4]) for i in range(5)]),
                "03": encode((0x10,"43000000000000" if attempted else "43012300000000"),(0x11,"43000000000000")),
                "07": encode((0x10,"47000000000000"),
                             *(([(0x11,"47012300000000" if attempted and residual_pending else "47000000000000")])
                               if not (attempted and missing_pending) else [])),
                "0A": encode((0x10,"4A012300000000")),
                "0101": encode((0x10,"410100000000")),
                "010D": encode((0x10,"410D01" if moving else "410D00")),
                "010C": encode((0x10,"410C0000")),
                "08000000000000": encode((0x10,"48000080000000"),
                    *(([(0x11,"48000080000000")]) if second_evap_source else []))
            }
            return replies[command]
        def save(result):
            nonlocal saved
            self.assertFalse(result["command_attempted"])
            if fail_save:
                raise OSError("disk unavailable")
            saved=True
        def transmit():
            nonlocal attempted
            self.assertTrue(saved)
            self.assertFalse(attempted)
            attempted=True
            sent.append("04" if operation=="clear" else "08010000000000")
            if operation=="clear":
                return encode((0x10,"44"), *(([(0x11,"44")]) if not missing_ack else []))
            return encode((0x10,"48010000000000"))
        result = mutation_recipe(p,read,lambda:p,transmit,operation=operation,expected_vin=VIN,
            engine_source=0x10,documented_sources=[0x10,0x11],warning_acknowledged=ok,
            confirmed_yes=yes,save_baseline=save)
        return result,sent

    def test_each_route_and_persistent_dtc_observation(self):
        for p in range(1,6):
            result,sent=self.recipe(p)
            self.assertEqual("ACKNOWLEDGED_READBACK", result["outcome"])
            self.assertEqual(["P0123"],result["before_dtcs"]["10"]["03"])
            self.assertEqual([],result["after_dtcs"]["10"]["03"])
            self.assertEqual(["P0123"],result["after_dtcs"]["10"]["0A"])
            self.assertIsNone(result["after_dtcs"]["11"]["0A"])
            self.assertEqual(1,sent.count("04"))
            self.assertFalse(result["whole_vehicle_coverage_verified"])

    def test_scope_motion_vin_and_disk_failure_prevent_tx(self):
        for kwargs in ({"wrong_vin":True},{"moving":True},{"extra":True},{"fail_save":True}):
            result,sent=self.recipe(**kwargs)
            self.assertEqual("NOT_TESTED",result["outcome"])
            self.assertNotIn("04",sent)
        for kwargs in ({"ok":False},{"yes":False}):
            with self.assertRaises(ValueError):
                self.recipe(**kwargs)

    def test_scope_change_after_baseline_prevents_mutation(self):
        result,sent=self.recipe(changed_scope_after_save=True)
        self.assertEqual("NOT_TESTED",result["outcome"])
        self.assertFalse(result["command_attempted"])
        self.assertEqual(3,sent.count("0100"))
        self.assertNotIn("04",sent)

    def test_missing_ack_and_transport_loss_are_not_retried(self):
        for kwargs,outcome in (({"missing_ack":True},"UNCERTAIN"),({"fail_after":True},"ACKNOWLEDGED_PARTIAL")):
            result,sent=self.recipe(**kwargs)
            self.assertEqual(outcome,result["outcome"])
            self.assertEqual(1,sent.count("04"))

    def test_pending_readback_partial_or_residual(self):
        for p in range(1,6):
            result,sent=self.recipe(p,missing_pending=True)
            self.assertEqual("ACKNOWLEDGED_PARTIAL",result["outcome"])
            self.assertEqual("PENDING_READBACK_UNAVAILABLE",result["reason"])
            self.assertIsNone(result["after_dtcs"]["11"]["07"])
            self.assertEqual(1,sent.count("04"))
            result,sent=self.recipe(p,residual_pending=True)
            self.assertEqual("DTCS_STILL_PRESENT",result["reason"])
            self.assertEqual(["P0123"],result["after_dtcs"]["11"]["07"])
            self.assertEqual(1,sent.count("04"))

    def test_evap_is_preparation_not_leak_test(self):
        result,sent=self.recipe(operation="evap")
        self.assertEqual("ACKNOWLEDGED",result["outcome"])
        self.assertFalse(result["leak_test_performed"])
        self.assertEqual(1,sent.count("08010000000000"))
        result,sent=self.recipe(operation="evap",second_evap_source=True)
        self.assertEqual("NOT_TESTED",result["outcome"])
        self.assertNotIn("08010000000000",sent)

    def test_cli_persists_baseline_before_final_and_rejects_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"report.json"
            baseline=Path(str(path)+".baseline.json")
            args=["--port","COM5","--protocol","2","--operation","clear","--vin",VIN,
                  "--engine-source","10","--sources","10,11","--output",str(path),
                  "--confirm-dedicated-adapter"]
            with patch.object(j1979_legacy_mutation_cli,"LegacyElmSerial") as factory:
                answers=iter(["OK","NO"])
                result=j1979_legacy_mutation_cli.run(args,prompt=lambda _:next(answers))
                self.assertEqual("CANCELLED",result["reason"])
                factory.assert_not_called()
                def simulate(operation,**kwargs):
                    kwargs["save_baseline"]({"outcome":"BASELINE_SAVED","command_attempted":False})
                    self.assertTrue(baseline.exists())
                    return {"outcome":"ACKNOWLEDGED_READBACK","command_attempted":True}
                factory.return_value.__enter__.return_value.run_vehicle_mutation.side_effect=simulate
                answers=iter(["OK","YES"])
                report=j1979_legacy_mutation_cli.run(args,prompt=lambda _:next(answers))
                self.assertEqual(report,json.loads(path.read_text()))
                self.assertEqual("BASELINE_SAVED",json.loads(baseline.read_text())["outcome"])
                answers=iter(["OK","YES"])
                with self.assertRaises(FileExistsError):
                    j1979_legacy_mutation_cli.run(args,prompt=lambda _:next(answers))
                self.assertEqual(1,factory.call_count)
