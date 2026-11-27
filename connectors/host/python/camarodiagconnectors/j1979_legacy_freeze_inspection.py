"""Independent selected-source legacy Mode 02 report inspection.

The normalized VIN is rebuilt from numbered legacy fragments. ELM records
retain and verify CRC/checksum; J2534 records retain only driver-managed
framing. Neither representation proves physical provenance.
"""
from __future__ import annotations

from pathlib import Path
import re

from .j1979_freeze_inspection import (
    replay_result, _require, _object, _int, _hex, _timestamp, MAX_REPORT,
)
from .j1979_legacy_discovery import valid_source
from .j1979_legacy_frames import response_frames
from .j1979_legacy_inspection import _driver_frame, _tx_frame
from .recovery_bundle import _regular, _json
from .recovery_history import _compare_history_value
from .j1979_freeze import MAX_STEPS
from .j1979_legacy_freeze import MAX_RAW, MAX_TRACE_BYTES

SCHEMA = "camarodiag-functional-j1979-legacy-freeze-v1"
_ELM = ("ELM_SERIAL_LEGACY_HEADERED", "ANDROID_ELM_LEGACY_HEADERED")
_DRIVER = "WINDOWS_J2534_LEGACY"
_STATUSES = ("NOT_TESTED", "TIMEOUT", "FAILED", "CORRELATED", "PASSED", "NEGATIVE")


def source(value):
    _require(isinstance(value,str) and re.fullmatch(r"[0-9A-F]{2}",value) is not None,
             "Invalid selected legacy source")
    number=int(value,16)
    _require(valid_source(number),"Reserved legacy source")
    return number


def _negative(rows,mode):
    return len(rows)==1 and len(rows[0])==3 and rows[0][:2]==bytes((0x7f,mode)) and rows[0][2] not in (0,0x78)


def _vin_rows(records, elm):
    # A complete normalized identity requires every independent admission row.
    expected=["0100","ATDPN","0900","0901","0902"] if elm else ["0100","0900","0901","0902"]
    _require(len(records)<=len(expected) and [row["request_hex"] for row,_ in records]==expected[:len(records)],
             "Unexpected legacy VIN query order")
    if not records:
        return None
    rows=dict((row["request_hex"],(row,payloads)) for row,payloads in records)
    initial=rows.get("0100")
    if not initial or initial[0]["status"]!="PASSED" or len(initial[1])!=1 or (
            len(initial[1][0])!=6 or initial[1][0][:2]!=b"\x41\x00"):
        _require(len(records)==1,"Legacy VIN continued after unverified PID00")
        return None
    if elm:
        probe=rows.get("ATDPN")
        if not probe or probe[0]["status"]!="PASSED":
            _require(len(records)<=2,"Legacy VIN continued after unverified fixed protocol")
            return None
    for command in ("0900","0901","0902"):
        pair=rows.get(command)
        if pair is None:
            return None
        row,payloads=pair
        index=expected.index(command)
        if _negative(payloads,9) and row["status"]=="NEGATIVE":
            _require(len(records)==index+1,"VIN continued after terminal negative response")
            return payloads[0]
        if row["status"]!="PASSED":
            _require(len(records)==index+1,"VIN continued after failed identity response")
            return None
        if command=="0900":
            _require(len(payloads)==1 and len(payloads[0])==7 and payloads[0][:3]==b"\x49\x00\x01" and
                     payloads[0][3]&0xc0==0xc0,"Unverified legacy VIN support types")
        elif command=="0901":
            _require(payloads==[b"\x49\x01\x05"],"Invalid legacy VIN record count")
        else:
            _require(len(payloads)==5 and all(len(payload)==7 and payload[:3]==bytes((0x49,2,i))
                     for i,payload in enumerate(payloads,1)),"Invalid numbered legacy VIN fragments")
            data=b"".join(payload[3:] for payload in payloads)
            _require(data[:3]==bytes(3) and re.fullmatch(rb"[A-HJ-NPR-Z0-9]{17}",data[3:]) is not None,
                     "Invalid legacy VIN ASCII or padding")
            return b"\x49\x02\x01"+data[3:]
    return None


def _elm_record(row,protocol,selected,other):
    raw=row["raw_response"]
    _require(raw is None or isinstance(raw,str) and len(raw)<=MAX_RAW,"Invalid ELM legacy raw response")
    _require(isinstance(row["frames"],list) and len(row["frames"])<=128 and
             isinstance(row["selected_records_hex"],list),"Invalid legacy frame collection")
    if row["request_hex"]=="ATDPN":
        _require(not row["frames"] and not row["selected_records_hex"],"Unexpected protocol probe frames")
        lines=[] if raw is None else [s.strip().upper() for s in raw.replace(">","\n").splitlines()
                                      if s.strip() and s.strip().upper()!="ATDPN"]
        actual=int(lines[0]) if len(lines)==1 and lines[0] in ("1","2","3","4","5") else None
        if row["status"]=="PASSED":
            _require(actual==protocol,"Fixed legacy protocol not independently verified")
        if "observed_protocol" in row:
            _require(row["observed_protocol"]==(protocol if row["status"]=="PASSED" else None),
                     "Declared protocol probe differs")
        return [],actual if row["status"]=="PASSED" else None
    if raw is None:
        _require(not row["frames"] and not row["selected_records_hex"] and
                 row["status"] in ("NOT_TESTED","TIMEOUT","FAILED"),"Unsubstantiated selected legacy response")
        return [],None
    try:
        frames=response_frames(protocol,row["request_hex"],raw)
    except ValueError:
        _require(row["status"] in ("FAILED","NOT_TESTED") and not row["frames"] and
                 not row["selected_records_hex"],"Malformed legacy bytes presented as verified")
        return [],None
    # A response that arrived after the logical deadline may be retained
    # without decoding. It is never promoted to a successful observation.
    if row["detail"]=="LATE_RESPONSE" and not row["frames"]:
        _require(row["status"] in ("TIMEOUT","NOT_TESTED") and not row["selected_records_hex"],
                 "Late legacy response presented as selected evidence")
        return [],None
    _compare_history_value(row["frames"],[frame.evidence() for frame in frames],"legacy.frames")
    payloads=[frame.payload for frame in frames if frame.source==selected]
    other.update(frame.source for frame in frames if frame.source!=selected)
    _compare_history_value(row["selected_records_hex"],[payload.hex().upper() for payload in payloads],
                           "legacy.selected_records")
    return payloads,None


def _driver_record(row,protocol,selected):
    tx=row["tx_hex"]
    _require(isinstance(row["frames"],list) and len(row["frames"])<=128,"Invalid J2534 frame collection")
    if tx is None:
        _require(not row["frames"] and row["status"] in ("NOT_TESTED","TIMEOUT","FAILED"),
                 "Driver replies without recorded transmission")
        return []
    request=_hex(row["request_hex"],3)
    _require(_hex(tx,8)==_tx_frame(request,protocol),"J2534 functional request header differs")
    return [_driver_frame(frame,protocol,selected) for frame in row["frames"]]


def inspect(report, *, expected_vin=None):
    _require(isinstance(report,dict) and report.get("schema")==SCHEMA and report.get("protocol")=="SAE-J1979",
             "Unsupported legacy freeze schema")
    transport=report.get("transport")
    elm=transport in _ELM
    _require(elm or transport==_DRIVER,"Unsupported legacy freeze transport")
    common=("schema","protocol","transport","version","scope","physically_addressed",
            "functional_scope_confirmed_by_operator","expected_protocol","selected_response_source",
            "response_attribution","response_hex_representation","started_at_utc","finished_at_utc",
            "transport_problem","execution_completed","transport_exchanges","result")
    if transport==_ELM[0]:
        extra=("port","baud","dedicated_adapter_confirmed_by_operator","observed_protocol","other_response_sources")
        _require(report.get("dedicated_adapter_confirmed_by_operator") is True,"Dedicated adapter not confirmed")
        _require(report.get("baud") in (9600,19200,38400,57600,115200,230400),"Invalid serial baud")
        identity=report.get("port")
    elif transport==_ELM[1]:
        extra=("adapter","observed_protocol","other_response_sources")
        identity=report.get("adapter")
    else:
        extra=("adapter","j2534_protocol_id","baud","initialization_evidence","physical_vehicle_verified",
               "whole_vehicle_coverage_verified")
        identity=report.get("adapter")
        _require(report.get("physical_vehicle_verified") is False and report.get("whole_vehicle_coverage_verified") is False,
                 "Unsupported physical provenance assertion")
    _object(report,common+extra,"legacy freeze report")
    _require(isinstance(identity,str) and 0<len(identity)<=1024 and isinstance(report["version"],str) and
             0<len(report["version"])<=128,"Invalid transport metadata")
    _require(report["scope"]=="FUNCTIONAL_SELECTED_RESPONSE_FREEZE" and
             report["physically_addressed"] is False and report["functional_scope_confirmed_by_operator"] is True and
             report["response_hex_representation"]=="NORMALIZED_APPLICATION_PAYLOAD",
             "Legacy functional scope not established")
    _require(report["response_attribution"]==("CHECKSUM_VERIFIED_LEGACY_HEADER" if elm else "J2534_DRIVER_MANAGED_SELECTED_HEADER"),
             "Incorrect legacy response attribution")
    _timestamp(report["started_at_utc"]);_timestamp(report["finished_at_utc"])
    _require(report["transport_problem"] in (None,"TRANSPORT_ERROR","CANCELLED"),"Unknown transport problem")
    protocol=_int(report["expected_protocol"],1,5,"legacy variant")
    selected=source(report["selected_response_source"])
    if not elm:
        _require(report["j2534_protocol_id"]=={1:2,2:1,3:3,4:4,5:4}[protocol] and
                 report["baud"]==(41600 if protocol==1 else 10400),"J2534 profile differs from legacy variant")
        initialization=report["initialization_evidence"]
        if initialization is not None:
            raw=_hex(initialization,11)
            if protocol in (3,4):
                _require(len(raw)==2,"Invalid slow initialization keywords")
            elif protocol==5:
                _require(len(raw)==6 and raw[:3]==bytes((0x83,0xf1,selected)) and raw[3]==0xc1,
                         "Invalid KWP fast initialization response")
            else:
                raise ValueError("Unexpected legacy initialization evidence")
        elif protocol>=3 and report["transport_problem"] is None:
            raise ValueError("Missing required legacy initialization")
    result=replay_result(report["result"],expected_vin=expected_vin)
    trace=report["transport_exchanges"]
    steps=result["steps"]
    _require(isinstance(trace,list) and len(trace)<=MAX_STEPS+10,"Excessive legacy transport exchanges")
    groups=[[] for _ in steps]
    previous=-1;total=0;other=set();verified_protocol=None
    for row in trace:
        base=("logical_query","request_hex","frames","status","detail")
        if elm:
            keys=base+("raw_response","selected_records_hex")
            if transport==_ELM[0] and isinstance(row,dict) and row.get("request_hex")=="ATDPN":
                keys+=("observed_protocol",)
        else:
            keys=base+("tx_hex",)
        _object(row,keys,"legacy transport exchange")
        index=_int(row["logical_query"],0,len(steps)-1,"logical query")
        _require(index>=previous,"Unordered legacy transport trace")
        previous=index
        _require(row["status"] in _STATUSES and (row["detail"] is None or isinstance(row["detail"],str)
                 and len(row["detail"])<=1024),"Invalid legacy exchange status")
        command=row["request_hex"]
        _require(command=="ATDPN" and elm or isinstance(command,str) and
                 re.fullmatch(r"(?:[0-9A-F]{2}){2,3}",command) is not None,
                 "Invalid legacy transport request")
        if elm:
            payloads,actual=_elm_record(row,protocol,selected,other)
            raw=row["raw_response"]
            total+=len(raw) if isinstance(raw,str) else 0
            if actual is not None:verified_protocol=actual
        else:
            payloads=_driver_record(row,protocol,selected)
            total+=sum(len(frame.get("raw_hex","")) for frame in row["frames"])
        _require(total<=MAX_TRACE_BYTES,"Legacy trace exceeds byte bound")
        groups[index].append((row,payloads))
        _require(len(groups[index])<=5,"Excessive physical reads per logical query")
    for index,(step,records) in enumerate(zip(steps,groups)):
        attempted=step["transmission_attempted"]
        if not attempted:
            _require(not records,"Transport trace without exchange attempt")
            continue
        command=step["request_hex"];outcome=step["transport_outcome"]
        _require(bool(records) or outcome in ("CANCELLED","CONTEXT_CHANGED","TRANSPORT_ERROR","TIMEOUT"),
                 "Missing legacy transport evidence")
        if command=="0902":
            payload=_vin_rows(records,elm)
        elif records:
            _require(len(records)==1 and records[0][0]["request_hex"]==command,
                     "Legacy freeze measurement request differs")
            row,rows=records[0]
            payload=rows[0] if len(rows)==1 and row["status"]=="CORRELATED" else None
        else:
            payload=None
        if outcome in ("RECEIVED","LATE_RESPONSE"):
            _require(payload is not None and payload.hex().upper()==step["response_hex"],
                     "Normalized legacy payload differs from selected response")
        elif outcome=="TIMEOUT":
            _require(payload is None,"Legacy timeout contradicts completed response")
        # A cancellation or transport fault can occur after a response was
        # read; retaining the raw bytes is not a successful application step.
    if elm:
        observed=report["observed_protocol"]
        _require(observed is None or type(observed) is int and observed==protocol,
                 "Invalid observed legacy protocol")
        _require(observed is None or verified_protocol==observed,"Declared fixed protocol has no matching probe")
        _require(report["other_response_sources"]==[f"{number:02X}" for number in sorted(other)],
                 "Other legacy source inventory differs")
        verified=observed==protocol
    else:
        verified=bool(trace) and (protocol<3 or report["initialization_evidence"] is not None)
    complete=result["completed"] and report["transport_problem"] is None and verified
    _require(type(report["execution_completed"]) is bool and report["execution_completed"]==complete,
             "Legacy freeze completion differs")
    return {"schema":"camarodiag-j1979-legacy-freeze-inspection-v1","consistent":True,
            "source_transport":transport,"execution_completed":complete,
            "all_frames_stable":complete and result["all_frames_stable"],
            "selected_response_source":report["selected_response_source"],"physically_addressed":False,
            "legacy_checksums_independently_verified":elm,
            "driver_checksums_independently_verified":False,"physical_vehicle_verified":False,
            "authenticated":False,"whole_vehicle_coverage_verified":False,
            "permits_programming":False,"replayed":result}


def inspect_file(path, *, expected_vin=None):
    file=Path(path)
    raw,stamp=_regular(file,MAX_REPORT)
    result=inspect(_json(raw),expected_vin=expected_vin)
    again,final=_regular(file,MAX_REPORT)
    _require(raw==again and stamp==final,"Legacy freeze report changed during replay")
    return {**result,"source_sha256":stamp.digest}
