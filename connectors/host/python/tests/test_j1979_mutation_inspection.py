import copy
import json
from pathlib import Path
import tempfile
import unittest

from camarodiagconnectors.j1979_legacy_mutation import mutation_recipe
from camarodiagconnectors.j1979_mutation_inspection import inspect, FINAL, BASELINE
from camarodiagconnectors.offline_cli import run
from test_j1979_legacy_mutation import frame, VIN


def capture(protocol=2,operation="clear",*,missing_ack=False,save_failure=False,
            scope_change=False,readback_failure=False):
    attempted=False
    baseline=None
    expected=[0x10,0x11]
    vin=bytes(3)+VIN.encode("ascii")
    meta={"variant":protocol,"operation":operation,"scope":"FUNCTIONAL_VEHICLE_WIDE",
          "expected_vin":VIN,"engine_source":"10","documented_sources":["10","11"]}
    def encode(*rows):
        return "\r".join(frame(protocol,s,bytes.fromhex(p) if isinstance(p,str) else p)
                         for s,p in rows) or "NO DATA"
    def read(command,timeout):
        if attempted and readback_failure:
            raise OSError("Link lost")
        replies={
            "0100":encode((16,"410080000000"),(17,"410080000000"),
                          *([(18,"410080000000")] if baseline is not None and scope_change else [])),
            "0901":encode((16,"490105")),
            "0902":encode(*[(16,bytes((0x49,2,i+1))+vin[i*4:i*4+4]) for i in range(5)]),
            "03":encode((16,"43000000000000" if attempted else "43012300000000"),(17,"43000000000000")),
            "07":encode((16,"47000000000000"),(17,"47000000000000")),
            "0A":encode((16,"4A012300000000")),
            "0101":encode((16,"410100000000")),
            "010D":encode((16,"410D00")),
            "010C":encode((16,"410C0000")),
            "08000000000000":encode((16,"48000080000000"))
        }
        return replies[command]
    def save(result):
        nonlocal baseline
        if save_failure:
            raise OSError("disk unavailable")
        baseline={"schema":BASELINE,**meta,**copy.deepcopy(result)}
    def send():
        nonlocal attempted
        assert baseline is not None and not attempted
        attempted=True
        if operation=="clear":
            return encode((16,"44"),*([] if missing_ack else [(17,"44")]))
        return encode((16,"48010000000000"))
    result=mutation_recipe(protocol,read,lambda:protocol,send,operation=operation,
        expected_vin=VIN,engine_source=16,documented_sources=expected,warning_acknowledged=True,
        confirmed_yes=True,save_baseline=save)
    return {"schema":FINAL,**meta,**result},baseline


def android(report,baseline):
    def body(source):
        return {"scope":source["scope"],"elmProtocol":source["variant"],
                "operation":"CLEAR" if source["operation"]=="clear" else "EVAP_PREPARATION",
                "engineSource":"10","documentedSources":["10","11"],"outcome":source["outcome"],
                "reason":source["reason"],"commandAttempted":source["command_attempted"],
                "observedVin":source["observed_vin"],"acknowledgments":source["acknowledgments"],
                "beforeDtcs":source["before_dtcs"],"afterDtcs":source["after_dtcs"],
                "observations":[{"stage":row["stage"],"requestHex":row["request_hex"],
                    "rawResponse":row["raw_response"],"error":row["error"],
                    "sources":list(dict.fromkeys(f["source"] for f in row["frames"]))}
                    for row in source["observations"]]}
    return {"schema":FINAL,"selectedVin":VIN,"legacyProtocol":report["variant"],
            "legacyOperation":"CLEAR" if report["operation"]=="clear" else "EVAP_PREPARATION",
            "legacyEngineSource":"10","legacyDocumentedSources":["10","11"],
            "legacyMutation":body(report),"legacyBaseline":body(baseline)}


class MutationInspectionTest(unittest.TestCase):
    def test_all_legacy_transports_and_both_operations(self):
        for protocol in range(1,6):
            for operation in ("clear","evap"):
                with self.subTest(protocol=protocol,operation=operation):
                    report,baseline=capture(protocol,operation)
                    result=inspect(report,baseline)
                    self.assertTrue(result["baseline_consistent"])
                    self.assertTrue(result["fresh_scope_recorded"])
                    self.assertTrue(result["complete_acknowledgment"])
                    self.assertFalse(result["whole_vehicle_coverage_verified"])
                    self.assertFalse(result["operator_consent_verified"])
                    self.assertFalse(result["permits_automatic_retry"])
                    if operation=="clear":
                        self.assertEqual(result["after_dtcs"]["10"]["0A"],["P0123"])
                        self.assertIsNone(result["after_dtcs"]["11"]["0A"])
                    else:
                        self.assertFalse(result["leak_test_performed"])

    def test_failed_save_and_changed_scope(self):
        for kwargs in ({"save_failure":True},{"scope_change":True}):
            report,baseline=capture(**kwargs)
            result=inspect(report,baseline)
            self.assertEqual(result["outcome"],"NOT_TESTED")
            self.assertFalse(result["command_attempted"])

    def test_partial_and_lost_ack(self):
        for kwargs,outcome in (({"missing_ack":True},"UNCERTAIN"),
                               ({"readback_failure":True},"ACKNOWLEDGED_PARTIAL")):
            report,baseline=capture(**kwargs)
            result=inspect(report,baseline)
            self.assertEqual(result["outcome"],outcome)
            self.assertTrue(result["command_attempted"])

    def test_corrupted_reports(self):
        for kind in ("scope","vin","ack","dtc","retry","baseline","checksum","source"):
            with self.subTest(kind=kind):
                report,baseline=capture()
                if kind=="scope":
                    report["documented_sources"].append("12")
                elif kind=="vin":
                    report["expected_vin"]="1G1FA1RXXR0123457"
                elif kind=="ack":
                    report["acknowledgments"]["10"]="UNCERTAIN"
                elif kind=="dtc":
                    report["after_dtcs"]["10"]["0A"]=[]
                elif kind=="retry":
                    report["observations"].append(copy.deepcopy(next(
                        x for x in report["observations"] if x["stage"]=="MUTATION")))
                elif kind=="baseline":
                    baseline["observations"][0]["raw_response"]="NO DATA"
                elif kind=="checksum":
                    row=report["observations"][0]
                    row["raw_response"]=row["raw_response"][:-2]+"00"
                else:
                    report["observations"][0]["frames"][0]["source"]="17"
                with self.assertRaises(ValueError):
                    inspect(report,baseline)

    def test_missing_baseline_distinguished_from_ack(self):
        report,_=capture()
        result=inspect(report)
        self.assertTrue(result["baseline_required_missing"])
        self.assertTrue(result["complete_acknowledgment"])
        self.assertFalse(result["durable_before_tx_verified"])

    def test_android_embedded_baseline(self):
        report,baseline=capture()
        result=inspect(android(report,baseline))
        self.assertEqual(result["platform"],"ANDROID")
        self.assertEqual(result["baseline_origin"],"EMBEDDED_COPY")

    def test_cli_output_distinct_from_input(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            report,baseline=capture()
            src,base,dst=p/"report.json",p/"base.json",p/"out.json"
            src.write_text(json.dumps(report))
            base.write_text(json.dumps(baseline))
            result=run(["verify-legacy-mutation","--report",str(src),"--baseline",str(base),
                        "--vin",VIN,"--output",str(dst)])
            self.assertEqual(result,json.loads(dst.read_text()))
            self.assertEqual(len(result["baseline_sha256"]),64)
            with self.assertRaises(ValueError):
                run(["verify-legacy-mutation","--report",str(src),"--baseline",str(base),"--output",str(src)])


if __name__=="__main__":
    unittest.main()
