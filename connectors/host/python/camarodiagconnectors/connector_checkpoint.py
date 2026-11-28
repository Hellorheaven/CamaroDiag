"""Offline CDJ/1 inspection and explicit checkpoint history comparison.

The embedded journal writes one current record, not an authenticated history.
A collection supplied by an operator can establish only local consistency
of the selected files. No connection, recovery or write can be authorized.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
import hashlib
from pathlib import Path
import re
import struct
import zlib

from .recovery_bundle import _regular, _json

RECORD_SIZE = 64
STAGES = ("PREPARED", "ERASE_INTENT", "PROGRAM_INTENT", "VERIFY_INTENT", "VERIFIED",
          "RESTART_INTENT", "POSTCHECK", "COMPLETED", "ABORTED", "RECOVERY_REQUIRED")
TERMINAL = {"COMPLETED", "ABORTED", "RECOVERY_REQUIRED"}
SAFE = {"PREPARED", "ABORTED", "COMPLETED"}
MAX_HISTORY = 8
MAX_REPORT_BYTES = 64 * 1024


@dataclass(frozen=True)
class Record:
    stage: str
    sequence: int
    transaction_hex: str
    manifest_sha256: str

    def valid(self):
        if self.stage not in STAGES or type(self.sequence) is not int:
            return False
        if not isinstance(self.transaction_hex,str) or not re.fullmatch(r"[0-9a-f]{32}", self.transaction_hex):
            return False
        if not isinstance(self.manifest_sha256,str) or not re.fullmatch(r"[0-9a-f]{64}", self.manifest_sha256):
            return False
        if not int(self.transaction_hex,16) or not int(self.manifest_sha256,16):
            return False
        if self.stage == "ABORTED":
            return self.sequence == 1
        if self.stage == "RECOVERY_REQUIRED":
            return 2 <= self.sequence <= 7
        return self.sequence == STAGES.index(self.stage)


def identity(value, length):
    if value is None:
        return None
    if not isinstance(value,str) or not re.fullmatch(r"[0-9a-fA-F]{%d}" % length,value):
        raise ValueError("Expected complete hexadecimal identity")
    return value.lower()


def decode(raw):
    if not isinstance(raw,bytes) or len(raw)!=RECORD_SIZE:
        return None
    if raw[:4]!=b"CDJ\x01" or raw[5:8]!=bytes(3) or not 1<=raw[4]<=10:
        return None
    if struct.unpack_from("<I",raw,60)[0] != zlib.crc32(raw[:60]):
        return None
    record=Record(STAGES[raw[4]-1],struct.unpack_from("<I",raw,8)[0],raw[12:28].hex(),raw[28:60].hex())
    return record if record.valid() else None


def successor(previous, current):
    if not previous.valid() or not current.valid() or previous.stage in TERMINAL:
        return False
    if (previous.sequence+1!=current.sequence or previous.transaction_hex!=current.transaction_hex
            or previous.manifest_sha256!=current.manifest_sha256):
        return False
    if current.stage=="ABORTED":
        return previous.stage=="PREPARED"
    if current.stage=="RECOVERY_REQUIRED":
        return previous.stage not in SAFE
    return STAGES.index(current.stage)==STAGES.index(previous.stage)+1


def classify(record, expected_transaction=None, expected_manifest=None):
    tx=identity(expected_transaction,32)
    manifest=identity(expected_manifest,64)
    if record is None:
        return "UNAVAILABLE_OR_CORRUPT"
    if tx is not None and record.transaction_hex!=tx or manifest is not None and record.manifest_sha256!=manifest:
        return "IDENTITY_MISMATCH"
    return "SAFE_BUT_BLOCKED" if record.stage in SAFE else "RECOVERY_REQUIRED"


def _flags():
    return {"authenticated":False,"physical_vehicle_verified":False,
            "permits_programming":False,"permits_automatic_recovery":False}


def _read(path):
    raw,snapshot=_regular(Path(path),RECORD_SIZE)
    if len(raw)!=RECORD_SIZE:
        raise ValueError("Expected exactly 64 checkpoint bytes")
    return raw,snapshot


def _acquire(path):
    first,snapshot=_read(path)
    second,current=_read(path)
    if snapshot!=current or first!=second:
        raise ValueError("Checkpoint changed during inspection")
    return first,snapshot


def _report(path,raw,expected_transaction,expected_manifest):
    record=decode(raw)
    status=classify(record,expected_transaction,expected_manifest)
    return {"schema":"camarodiag-offline-connector-checkpoint-v1","platform":"HOST",
            "file_name":Path(path).name,"snapshot_sha256":hashlib.sha256(raw).hexdigest(),
            "expected_transaction":expected_transaction,"expected_manifest":expected_manifest,
            "inspection":{"status":status,"record":asdict(record) if record is not None else None,
                          **_flags()},
            "problem":None,**_flags()}


def inspect_file(path,expected_transaction=None,expected_manifest=None):
    tx=identity(expected_transaction,32)
    manifest=identity(expected_manifest,64)
    try:
        raw,_=_acquire(path)
    except (OSError,ValueError) as exc:
        return {"schema":"camarodiag-offline-connector-checkpoint-v1","platform":"HOST",
                "file_name":Path(path).name,"snapshot_sha256":None,"expected_transaction":tx,
                "expected_manifest":manifest,"inspection":{"status":"UNAVAILABLE_OR_CORRUPT","record":None,**_flags()},
                "problem":str(exc),**_flags()}
    return _report(path,raw,tx,manifest)


def _get(obj,name):
    camel=re.sub(r"_([a-z])",lambda m:m[1].upper(),name)
    return obj.get(name,obj.get(camel))


def _enum(value):
    if not isinstance(value,str):
        raise ValueError("Invalid checkpoint enum")
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])","_",value).upper().replace("POST_CHECK","POSTCHECK")


def verify_report(path,report_path):
    """Match Android/Windows/host JSON to a fresh stable raw checkpoint.

    A matching imported report is not proof that either file came from a
    connector, or that the selected transaction is the current transaction.
    """
    data,stamp=_regular(Path(report_path),MAX_REPORT_BYTES)
    report=_json(data)
    if not isinstance(report,dict) or report.get("schema")!="camarodiag-offline-connector-checkpoint-v1":
        raise ValueError("Unsupported checkpoint report")
    tx=identity(_get(report,"expected_transaction"),32)
    manifest=identity(_get(report,"expected_manifest"),64)
    raw,snapshot=_acquire(path)
    fresh=_report(path,raw,tx,manifest)
    if identity(_get(report,"snapshot_sha256"),64)!=fresh["snapshot_sha256"]:
        raise ValueError("Checkpoint digest differs from report")
    inspection=report.get("inspection")
    if not isinstance(inspection,dict) or _enum(inspection.get("status"))!=fresh["inspection"]["status"]:
        raise ValueError("Checkpoint disposition differs")
    for scope in (report,inspection):
        for field in _flags():
            if _get(scope,field) is True:
                raise ValueError("Unsupported authorization or provenance claim")
    record=inspection.get("record")
    expected=fresh["inspection"]["record"]
    if expected is None:
        if record is not None:
            raise ValueError("Corrupt checkpoint report contains decoded record")
    elif (not isinstance(record,dict) or _enum(record.get("stage"))!=expected["stage"]
          or type(record.get("sequence")) is not int or record["sequence"]!=expected["sequence"]
          or identity(_get(record,"transaction_hex"),32)!=expected["transaction_hex"]
          or identity(_get(record,"manifest_sha256"),64)!=expected["manifest_sha256"]):
        raise ValueError("Checkpoint decoded record differs")
    again,current=_read(path)
    data_again,stamp_again=_regular(Path(report_path),MAX_REPORT_BYTES)
    if raw!=again or current!=snapshot or data_again!=data or stamp_again!=stamp:
        raise ValueError("Checkpoint or report changed during verification")
    return {**fresh,"report_matches":True,"report_sha256":hashlib.sha256(data).hexdigest()}


def inspect_history(paths,expected_transaction=None,expected_manifest=None):
    """Require a contiguous sequence in the caller's explicit order.

    A suffix starting after Prepared can be inspected but is never labelled
    a complete history. Missing records are not reconstructed or reordered.
    """
    tx=identity(expected_transaction,32)
    manifest=identity(expected_manifest,64)
    paths=[Path(x) for x in paths]
    if not 1<=len(paths)<=MAX_HISTORY:
        raise ValueError("Expected one to eight ordered checkpoint files")
    rows=[]
    records=[]
    acquisitions=[]
    problem=None
    for path in paths:
        try:
            raw,snapshot=_acquire(path)
            acquisitions.append((path,raw,snapshot))
            row=_report(path,raw,tx,manifest)
            rows.append(row)
            record=decode(raw)
            if record is None:
                problem="UNAVAILABLE_OR_CORRUPT"
                break
            if row["inspection"]["status"]=="IDENTITY_MISMATCH":
                problem="IDENTITY_MISMATCH"
                break
            if records and not successor(records[-1],record):
                problem="INVALID_SUCCESSOR"
                break
            records.append(record)
        except (ValueError,OSError) as exc:
            problem="ACQUISITION_FAILED"
            rows.append({"file_name":path.name,"problem":str(exc),"snapshot_sha256":None})
            break
    if problem is None:
        for path,raw,snapshot in acquisitions:
            try:
                current,stamp=_read(path)
                if current!=raw or stamp!=snapshot:
                    raise ValueError("Checkpoint changed")
            except (ValueError,OSError):
                problem="CHANGED_DURING_INSPECTION"
                break
    consistent=problem is None and len(records)==len(paths)
    prepared=bool(records) and records[0].stage=="PREPARED"
    final=records[-1] if records else None
    terminal=consistent and final is not None and final.stage in TERMINAL
    return {"schema":"camarodiag-offline-connector-checkpoint-history-v1",
            "expected_transaction":tx,"expected_manifest":manifest,"ordered_inputs":len(paths),
            "inspected_inputs":len(rows),"records":rows,"consistent":consistent,
            "starts_at_prepared":prepared,"terminal_record_observed":terminal,
            "complete_history":bool(consistent and prepared and terminal),
            "last_consistent_record":asdict(final) if final is not None else None,
            "disposition":classify(final,tx,manifest) if consistent else "UNAVAILABLE_OR_CORRUPT",
            "problem":problem,**_flags()}
