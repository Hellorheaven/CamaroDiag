import json
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from camarodiagconnectors.j1979_maintenance_journal import (
    HEADER, SCOPE, ROW, CLEAR, EVAP, FLAG_A, FLAG_B, OUTCOMES, REASONS,
    Scope, Event, inspect, replay,
)
from camarodiagconnectors.j1979_maintenance_journal_cli import run, verify_files

VIN = "1G1FA1RXXR0123456"


def response(cmd, operation, position):
    if cmd == "0902":
        return bytes.fromhex("490201") + VIN.encode("ascii")
    if cmd == "03":
        return bytes.fromhex("43010123" if position < 10 else "4300")
    if cmd in ("07", "0A"):
        return bytes((int(cmd, 16)+64, 0))
    return bytes.fromhex({"0101":"410100000000", "010D":"410D00", "010C":"410C0000",
                          "0800":"480080000000", "04":"44", "0801":"4801"}[cmd])


def encode_summary(state):
    a = sum(int(state[key]) << i for i, key in enumerate(FLAG_A))
    b = sum(int(state[key]) << i for i, key in enumerate(FLAG_B))
    return bytes((OUTCOMES.index(state["outcome"]), REASONS.index(state["reason"]),
                  state["observations"], state["nrc"], a, b, 0, 0)) + struct.pack("<I", state["duration_ms"]) + bytes(8)


def encode_record(scope, number, kind, rows=(), summary=None):
    body = SCOPE.pack(scope.operation, int(scope.extended), scope.tx, scope.rx,
                      scope.budget_ms, scope.vin.encode("ascii"))
    if kind != 1:
        body += encode_summary(summary)
        for row in rows:
            req = row.request.ljust(2, b"\0")
            body += ROW.pack(row.stage, row.status, len(row.request), req[0], req[1], 0,
                             len(row.response), row.start, row.end, row.timeout) + row.response
    data = HEADER.pack(b"CM", 1, kind, number, len(rows), 0, len(body)) + body
    return data + struct.pack("<I", zlib.crc32(data))


def fixture(operation=1, *, refusal=False, uncertain=False, remaining=False):
    scope = Scope(operation, False, 0x7E0, 0x7E8, 60000, VIN)
    rows = []
    for i, (stage, cmd) in enumerate(scope.plan):
        payload = response(cmd, operation, i)
        status = 1
        if stage == 6:
            if refusal:
                payload = bytes((0x7F, int(cmd[:2],16), 0x22))
                status = 2
            if uncertain:
                payload = b""
                status = 3
        if remaining and stage == 7 and cmd == "03":
            payload = bytes.fromhex("43010123")
        rows.append(Event(stage,status,bytes.fromhex(cmd),payload,i*10,i*10+5,1800))
        if stage == 6 and (refusal or uncertain):
            break
    baseline = rows[:scope.baseline_boundary]
    intent = rows[:scope.command_boundary]
    terminal = replay(scope,rows,baseline=True,intent=True)
    terminal["physical_stop_declared"] = True
    terminal["recovery_required"] = uncertain
    records = [
        encode_record(scope,0,1),
        encode_record(scope,1,2,baseline,replay(scope,baseline,baseline=False,intent=False)),
        encode_record(scope,2,3,intent,replay(scope,intent,baseline=True,intent=False)),
        encode_record(scope,3,4,rows,terminal),
    ]
    return scope,rows,terminal,records


class MaintenanceJournalTest(unittest.TestCase):
    def test_clear_and_evap(self):
        for operation in (1,2):
            scope, rows, terminal, records = fixture(operation)
            result = inspect(b"".join(records), expected_vin=VIN)
            self.assertEqual(result["records"], ["OPEN","BASELINE","INTENT","TERMINAL"])
            self.assertEqual(result["terminal"]["outcome"],
                             "ACKNOWLEDGED_READBACK" if operation==1 else "ACKNOWLEDGED_EVAP")
            self.assertTrue(result["terminal"]["attempted"])
            self.assertTrue(result["terminal"]["acknowledged"])
            self.assertFalse(result["recovery_required"])
            self.assertFalse(result["permits_automatic_retry"])
            self.assertFalse(result["monitor_completion_verified"])
            self.assertFalse(result["physical_vehicle_verified"])
            self.assertEqual(len(result["observations"]),len(rows))

    def test_negative_and_uncertain(self):
        for operation in (1,2):
            for failure in ("refusal","uncertain"):
                _,_,_,records = fixture(operation, **{failure:True})
                result = inspect(b"".join(records),expected_vin=VIN)
                self.assertEqual(result["terminal"]["outcome"],"REFUSED" if failure=="refusal" else "UNCERTAIN")
                self.assertEqual(result["recovery_required"],failure=="uncertain")
                self.assertFalse(result["terminal"]["acknowledged"])
        _,_,_,records=fixture(1,remaining=True)
        self.assertEqual(inspect(b"".join(records),expected_vin=VIN)["terminal"]["reason"],"DTC_REMAINING")

    def test_open_records_are_not_completion(self):
        _,_,_,records=fixture()
        for count in (1,2,3):
            result=inspect(b"".join(records[:count]),expected_vin=VIN)
            self.assertTrue(result["recovery_required"])
            self.assertFalse(result["terminal_present"])
            self.assertIsNone(result["terminal"])
            self.assertEqual(result["intent_record_present"],count==3)

    def test_malformed_and_forged_records(self):
        scope,rows,terminal,records=fixture()
        complete=b"".join(records)
        corrupt=bytearray(complete);corrupt[15]^=1
        forged=dict(terminal);forged["after_pending"]=False
        replayed=rows.copy()
        replayed[10]=Event(6,1,b"\x04",b"\x44",10000,10005,1800)
        for raw in (b"",complete[:-1],complete+b"\0",bytes(corrupt),
                    records[0]+records[2]+records[3],
                    b"".join(records[:3])+encode_record(scope,3,4,rows,forged),
                    b"".join(records[:3])+encode_record(scope,3,4,replayed,terminal)):
            with self.subTest(size=len(raw)):
                with self.assertRaises(ValueError):
                    inspect(raw,expected_vin=VIN)
        with self.assertRaises(ValueError):
            inspect(complete,expected_vin="1FA6P8TH0L5100000")

    def test_report_reverification(self):
        _,_,_,records=fixture()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            journal,report,verified=(root/x for x in ("record.cm","report.json","verified.json"))
            journal.write_bytes(b"".join(records))
            result=run(["inspect","--journal",str(journal),"--vin",VIN,"--output",str(report)])
            self.assertEqual(verify_files(journal,report,expected_vin=VIN)["replayed"],result)
            check=run(["verify","--journal",str(journal),"--report",str(report),"--vin",VIN,"--output",str(verified)])
            self.assertTrue(check["consistent"])
            result["permits_automatic_retry"]=True
            report.write_text(json.dumps(result))
            with self.assertRaises(ValueError):
                verify_files(journal,report,expected_vin=VIN)
            with self.assertRaises(ValueError):
                run(["inspect","--journal",str(journal),"--vin",VIN,"--output",str(journal)])


if __name__ == "__main__":
    unittest.main()
