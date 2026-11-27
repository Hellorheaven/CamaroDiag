import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from camarodiagconnectors.connector_checkpoint import (
    STAGES,Record,decode,successor,classify,inspect_file,inspect_history,verify_report
)

TX="11"*16
MANIFEST="22"*32


def wire(stage,sequence,tx=TX,manifest=MANIFEST):
    raw=b"CDJ\x01"+bytes((STAGES.index(stage)+1,0,0,0))+struct.pack("<I",sequence)
    raw+=bytes.fromhex(tx)+bytes.fromhex(manifest)
    return raw+struct.pack("<I",zlib.crc32(raw))


class CheckpointTests(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root=Path(folder.name)

    def save(self,stage,seq,tx=TX):
        path=self.root/(stage+"-"+str(seq)+".bin")
        path.write_bytes(wire(stage,seq,tx))
        return path

    def test_reachable_stages_and_crc(self):
        for stage in STAGES:
            seq=1 if stage=="ABORTED" else 2 if stage=="RECOVERY_REQUIRED" else STAGES.index(stage)
            raw=wire(stage,seq)
            record=decode(raw)
            self.assertIsNotNone(record)
            self.assertEqual(record.stage,stage)
            self.assertIsNone(decode(raw+b"\x00"))
            self.assertIsNone(decode(raw[:-1]))
            self.assertIsNone(decode(raw[:-1]+bytes((raw[-1]^1,))))
        self.assertIsNone(decode(wire("COMPLETED",2)))
        self.assertIsNone(decode(wire("PREPARED",0,tx="00"*16)))
        self.assertIsNone(decode(wire("RECOVERY_REQUIRED",1)))

    def test_complete_history_never_grants_recovery(self):
        paths=[self.save(stage,i) for i,stage in enumerate(STAGES[:8])]
        result=inspect_history(paths,TX,MANIFEST)
        self.assertTrue(result["complete_history"])
        self.assertEqual(result["disposition"],"SAFE_BUT_BLOCKED")
        self.assertFalse(result["permits_programming"])
        self.assertFalse(result["authenticated"])
        self.assertFalse(result["permits_automatic_recovery"])

    def test_abort_and_critical_fault(self):
        prep=self.save("PREPARED",0)
        abort=self.save("ABORTED",1)
        self.assertTrue(inspect_history([prep,abort])["complete_history"])
        erase=self.save("ERASE_INTENT",1)
        fault=self.save("RECOVERY_REQUIRED",2)
        r=inspect_history([prep,erase,fault])
        self.assertTrue(r["complete_history"])
        self.assertEqual(r["disposition"],"RECOVERY_REQUIRED")
        self.assertFalse(inspect_history([prep,fault])["consistent"])
        self.assertFalse(inspect_history([prep,abort,fault])["consistent"])

    def test_suffix_order_and_identity(self):
        erase=self.save("ERASE_INTENT",1)
        program=self.save("PROGRAM_INTENT",2)
        r=inspect_history([erase,program])
        self.assertTrue(r["consistent"])
        self.assertFalse(r["complete_history"])
        self.assertFalse(r["starts_at_prepared"])
        self.assertFalse(inspect_history([program,erase])["consistent"])
        self.assertFalse(inspect_history([erase,erase])["consistent"])
        other=self.save("VERIFY_INTENT",3,"33"*16)
        self.assertFalse(inspect_history([erase,program,other])["consistent"])
        self.assertEqual(inspect_file(erase,"33"*16)["inspection"]["status"],"IDENTITY_MISMATCH")

    def test_cross_platform_report_verification(self):
        path=self.save("ERASE_INTENT",1)
        base=inspect_file(path,TX,MANIFEST)
        report=self.root/"report.json"
        windows={"schema":base["schema"],"snapshotSha256":base["snapshot_sha256"].upper(),
                 "expectedTransaction":TX,"expectedManifest":MANIFEST,
                 "inspection":{"status":"RecoveryRequired","record":{"stage":"EraseIntent","sequence":1,
                    "transactionHex":TX,"manifestSha256":MANIFEST},"authenticated":False,
                    "permitsProgramming":False,"permitsAutomaticRecovery":False}}
        report.write_text(json.dumps(windows))
        self.assertTrue(verify_report(path,report)["report_matches"])
        windows["inspection"]["record"]["sequence"]=2
        report.write_text(json.dumps(windows))
        with self.assertRaises(ValueError):
            verify_report(path,report)
        windows["inspection"]["record"]["sequence"]=1
        windows["inspection"]["permitsProgramming"]=True
        report.write_text(json.dumps(windows))
        with self.assertRaises(ValueError):
            verify_report(path,report)

    def test_corrupt_stable_record_remains_exportable_not_safe(self):
        path=self.root/"corrupt.bin"
        path.write_bytes(bytes(64))
        r=inspect_file(path)
        self.assertEqual(r["inspection"]["status"],"UNAVAILABLE_OR_CORRUPT")
        self.assertIsNotNone(r["snapshot_sha256"])
        self.assertIsNone(r["inspection"]["record"])
        path.write_bytes(bytes(65))
        self.assertIsNone(inspect_file(path)["snapshot_sha256"])
