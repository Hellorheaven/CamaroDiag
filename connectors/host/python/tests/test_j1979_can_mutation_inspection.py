import copy
import json
from pathlib import Path
import tempfile
import unittest

from camarodiagconnectors.j1979_clear import clear_recipe
from camarodiagconnectors.j1979 import evap_recipe
from camarodiagconnectors.j1979_can_mutation_inspection import inspect, CLEAR, EVAP, BASELINE
from camarodiagconnectors.offline_cli import run
from test_j1979_clear import VIN, BEFORE


def capture(clear=True, *, ack=None, extended=False, fail_save=False, movement=False, missing_pending=False):
    reset=False
    baseline=None
    tx="18DA10F1" if extended else "7E0"
    rx="18DAF110" if extended else "7E8"
    meta={"expected_vin":VIN,"tx_can_id":tx,"rx_can_id":rx,"physical_vehicle_verified":False}
    def read(req, timeout):
        command=req.hex().upper()
        if command=="0800":
            return bytes.fromhex("480080000000")
        if reset and command=="03":
            return bytes.fromhex("4300")
        if reset and command=="07" and missing_pending:
            raise TimeoutError()
        if baseline is not None and movement and command=="010D":
            return bytes.fromhex("410D01")
        return BEFORE[command]
    def save(result):
        nonlocal baseline
        if fail_save:
            raise OSError("disk unavailable")
        baseline={"schema":BASELINE,**meta,**copy.deepcopy(result)}
    def transmit():
        nonlocal reset
        if clear:
            assert baseline is not None
        reset=True
        if isinstance(ack,Exception):
            raise ack
        return ack if ack is not None else bytes.fromhex("44" if clear else "4801")
    args=dict(expected_vin=VIN,primary=True,warning_acknowledged=True,confirmed_yes=True)
    result=clear_recipe(read,transmit,save_baseline=save,**args) if clear else evap_recipe(read,transmit,**args)
    return {"schema":CLEAR if clear else EVAP,**meta,**result},baseline


def android(report,baseline):
    def body(source):
        return {"outcome":source["outcome"],"reason":source["reason"],
                "commandAttempted":source["command_attempted"],"acknowledged":source["acknowledged"],
                "observedVin":source["observed_vin"],"elmProtocol":6,
                "requestCanId":"7E0","responseCanId":"7E8",
                "beforeDtcs":source["before_dtcs"],"afterDtcs":source["after_dtcs"],
                "observations":[{"stage":x["stage"],"requestHex":x["request_hex"],
                                 "responseHex":x["response_hex"],"error":x["error"]}
                                for x in source["observations"]]}
    return {"schema":CLEAR,"selectedVin":VIN,"clear":body(report),"clearBaseline":body(baseline)}


class CanMutationInspectionTests(unittest.TestCase):
    def test_reset_prefix_and_permanent_codes(self):
        for extended in (False,True):
            report,baseline=capture(extended=extended)
            result=inspect(report,baseline,expected_vin=VIN)
            self.assertTrue(result["baseline_consistent"])
            self.assertTrue(result["fresh_identity_and_stationary_recorded"])
            self.assertTrue(result["acknowledged"])
            self.assertEqual(result["after_dtcs"]["0A"],["P0123"])
            self.assertFalse(result["durable_before_tx_verified"])
            self.assertFalse(result["permits_automatic_retry"])
            self.assertTrue(inspect(report)["baseline_required_missing"])

    def test_missing_readback_remains_unknown(self):
        report,baseline=capture(missing_pending=True)
        result=inspect(report,baseline)
        self.assertEqual(result["outcome"],"ACKNOWLEDGED_PARTIAL")
        self.assertIsNone(result["after_dtcs"]["07"])

    def test_no_reset_on_failed_preconditions(self):
        for flags in ({"fail_save":True},{"movement":True}):
            report,baseline=capture(**flags)
            result=inspect(report,baseline)
            self.assertFalse(result["command_attempted"])
            self.assertFalse(result["acknowledged"])

    def test_nrc_and_lost_ack(self):
        for reply,outcome in ((b"\x7f\x04\x22","REFUSED"),(TimeoutError(),"UNCERTAIN"),
                              (b"\x44\x00","UNCERTAIN")):
            report,baseline=capture(ack=reply)
            result=inspect(report,baseline)
            self.assertEqual(result["outcome"],outcome)
            self.assertTrue(result["command_attempted"])
            self.assertFalse(result["acknowledged"])

    def test_modified_results_and_extra_command(self):
        for kind in ("ack","dtc","scope","retry","motion","baseline","vin"):
            with self.subTest(kind=kind):
                report,baseline=capture()
                if kind=="ack":
                    report["acknowledged"]=False
                elif kind=="dtc":
                    report["after_dtcs"]["0A"]=[]
                elif kind=="scope":
                    report["tx_can_id"]="7DF"
                elif kind=="retry":
                    report["observations"].append({"stage":"RESET","request_hex":"04","response_hex":"44"})
                elif kind=="motion":
                    next(x for x in report["observations"] if x["stage"]=="RECHECK" and x["request_hex"]=="010D")["response_hex"]="410D01"
                elif kind=="baseline":
                    baseline["observations"][1]["response_hex"]="43010124"
                    baseline["before_dtcs"]["03"]=["P0124"]
                else:
                    report["expected_vin"]="1G1FA1RXXR0123457"
                with self.assertRaises(ValueError):
                    inspect(report,baseline)

    def test_android_embedded_and_windows_baseline(self):
        report,baseline=capture()
        result=inspect(android(report,baseline))
        self.assertEqual(result["platform"],"ANDROID")
        self.assertEqual(result["baseline_origin"],"EMBEDDED_COPY")
        windows={"schema":CLEAR,"expectedVin":VIN,"requestCanId":"7E0",
                 "responseCanId":"7E8","result":report,"baseline":baseline}
        result=inspect(windows)
        self.assertEqual(result["platform"],"J2534")
        self.assertTrue(result["baseline_consistent"])

    def test_evap_ack_refusal_and_unknown(self):
        for ack,outcome in ((None,"ACKNOWLEDGED"),(b"\x7F\x08\x22","REFUSED"),
                            (TimeoutError(),"UNCERTAIN")):
            report,_=capture(clear=False,ack=ack)
            result=inspect(report)
            self.assertEqual(result["outcome"],outcome)
            self.assertFalse(result["monitor_completion_verified"])
            self.assertFalse(result["baseline_required_missing"])

    def test_evap_not_admitted_without_support(self):
        report,_=capture(clear=False)
        report["exchanges"][1]["response_hex"]="480000000000"
        with self.assertRaises(ValueError):
            inspect(report)

    def test_cli_preserves_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            report,baseline=capture()
            src=p/"report.json"
            base=p/"baseline.json"
            dst=p/"inspection.json"
            src.write_text(json.dumps(report))
            base.write_text(json.dumps(baseline))
            result=run(["verify-can-mutation","--report",str(src),"--baseline",str(base),
                        "--vin",VIN,"--output",str(dst)])
            self.assertEqual(result,json.loads(dst.read_text()))
            self.assertEqual(len(result["report_sha256"]),64)
            self.assertEqual(json.loads(src.read_text()),report)
            self.assertEqual(json.loads(base.read_text()),baseline)
            with self.assertRaises(ValueError):
                run(["verify-can-mutation","--report",str(src),"--baseline",str(base),"--output",str(base)])


if __name__=="__main__":
    unittest.main()
