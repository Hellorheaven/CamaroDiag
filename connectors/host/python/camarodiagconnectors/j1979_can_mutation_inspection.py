"""Independent replay of the dedicated physical CAN Mode 04/08 recipes.

No transport access, no replay of commands to hardware. A positive ACK does
not prove monitor completion, emissions compliance, or permission to retry.
Historical reports without a durable baseline remain distinguishable.
"""
from __future__ import annotations

import re
from .j1979_live_inspection import load, _get, _dict, _array, _hex, _id, _require

CLEAR="camarodiag-functional-j1979-clear-v1"
EVAP="camarodiag-functional-j1979-evap-v1"
BASELINE="camarodiag-j1979-can-clear-baseline-v1"
PAIRS={(0x7E0,0x7E8),(0x18DA10F1,0x18DAF110)}
MODES=("03","07","0A")
VIN=re.compile(r"[A-HJ-NPR-Z0-9]{17}")


def _scope(report,baseline=False):
    report=_dict(report,"CAN operation report")
    schema=report.get("schema")
    _require(schema==BASELINE if baseline else schema in (CLEAR,EVAP),"Unsupported CAN operation schema")
    operation="evap" if schema==EVAP else "clear"
    nested=report.get(operation)
    body=_dict(report.get("result",nested if isinstance(nested,dict) else report),"operation result")
    vin=_get(report,"expected_vin",_get(report,"selected_vin"))
    _require(isinstance(vin,str) and VIN.fullmatch(vin),"Invalid expected VIN")
    tx=_id(_get(body,"request_can_id",_get(report,"request_can_id",_get(report,"tx_can_id"))))
    rx=_id(_get(body,"response_can_id",_get(report,"response_can_id",_get(report,"rx_can_id"))))
    _require((tx,rx) in PAIRS,"Not a primary physical ECM pair")
    protocol=_get(body,"elm_protocol")
    if protocol is not None:
        _require(type(protocol) is int and protocol in (6,7,8,9) and
                 (protocol in (7,9))==(tx>0x7FF),"ELM protocol/address mismatch")
    for obj in (report,body):
        for flag in ("physical_vehicle_verified","monitor_completion_verified",
                     "whole_vehicle_coverage_verified","evidence_authenticated",
                     "permits_automatic_retry"):
            _require(_get(obj,flag,False) is False,"Unsupported operation claim: "+flag)
        for name,expected in (("request_can_id",tx),("response_can_id",rx)):
            if _get(obj,name) is not None:
                _require(_id(_get(obj,name))==expected,"Conflicting CAN address")
    platform="J2534" if "result" in report else "ANDROID" if isinstance(nested,dict) or protocol is not None else "SOCKETCAN"
    return body,(operation,vin,tx,rx),platform


def _row(raw,evap=False):
    raw=_dict(raw,"exchange")
    request=_get(raw,"request_hex")
    req=_hex(request,2)
    _require(len(req)>0 and request==req.hex().upper(),"Invalid request encoding")
    response=_get(raw,"response_hex")
    reply=None if response is None else _hex(response)
    error=raw.get("error")
    _require(error is None or isinstance(error,str) and 0<len(error)<=256,"Invalid exchange error")
    return {"stage":None if evap else raw.get("stage"),"request_hex":request,
            "response_hex":reply.hex().upper() if reply is not None else None,
            "error":error},reply


def _vin(reply):
    if reply is None or len(reply)!=20 or reply[:3]!=b"\x49\x02\x01":
        return None
    try:
        return reply[3:].decode("ascii")
    except UnicodeError:
        return None


def _codes(mode,reply):
    if reply is None or len(reply)<2 or reply[0]!=int(mode,16)+0x40 or len(reply)!=2+2*reply[1]:
        return None
    data=reply[2:]
    if any(not(a or b) for a,b in zip(data[::2],data[1::2])):
        return None
    return [f"{'PCBU'[a>>6]}{(a>>4)&3:X}{a&15:X}{b:02X}" for a,b in zip(data[::2],data[1::2])]


def _nrc(reply,mode):
    return f"NRC_{reply[2]:02X}" if reply is not None and len(reply)==3 and reply[:2]==bytes((0x7F,mode)) and reply[2] not in (0,0x78) else None


def _plan(recheck=True):
    rows=[("PRECONDITION","0902")]
    rows.extend(("BEFORE",m) for m in (*MODES,"0101"))
    rows.extend((("PRECONDITION","010D"),("PRECONDITION","010C")))
    boundary=len(rows)
    if recheck:
        rows.extend(("RECHECK",m) for m in ("0902","010D","010C"))
    rows.append(("RESET","04"))
    rows.extend(("AFTER",m) for m in (*MODES,"0101"))
    return rows,boundary


def _clear(body,scope,baseline=False):
    _,vin,_,_=scope
    observations=[_row(row) for row in _array(body.get("observations"),32,"clear observations")]
    _,boundary=_plan()
    recheck=len(observations)>boundary and observations[boundary][0]["stage"]=="RECHECK"
    plan,boundary=_plan(recheck)
    if baseline:
        plan=plan[:boundary]
    _require(len(observations)<=len(plan),"Extra exchange or reset retry")
    before={m:None for m in MODES}
    after=before.copy()
    readiness={"BEFORE":None,"AFTER":None}
    observed=None
    attempted=acknowledged=blocked=False
    reset_nrc=None
    for i,(row,reply) in enumerate(observations):
        stage,command=plan[i]
        _require((row["stage"],row["request_hex"])==(stage,command),"Unexpected clear sequence")
        _require(not blocked,"Continued after invalid prerequisite or reset acknowledgment")
        if stage in ("BEFORE","AFTER"):
            if not row["error"]:
                if command in MODES:
                    (before if stage=="BEFORE" else after)[command]=_codes(command,reply)
                elif reply is not None and len(reply)==6 and reply[:2]==b"\x41\x01":
                    readiness[stage]={"mil_on":bool(reply[2]&128),"confirmed_dtc_count":reply[2]&127,
                                      "readiness_hex":reply[3:].hex().upper()}
            # Some optional read failures continue; confirmed baseline is mandatory.
            if stage=="BEFORE" and command=="0101":
                blocked=before["03"] is None
        elif stage=="RESET":
            attempted=True
            acknowledged=reply==b"\x44" and row["error"] is None
            reset_nrc=_nrc(reply,4)
            blocked=not acknowledged
        elif row["error"]:
            blocked=True
        elif command=="0902":
            received=_vin(reply)
            if stage=="PRECONDITION":
                observed=received
            blocked=received!=vin
        elif command=="010D":
            blocked=reply!=b"\x41\x0D\x00"
        elif command=="010C":
            blocked=reply!=b"\x41\x0C\x00\x00"
    _require(type(_get(body,"command_attempted")) is bool and _get(body,"command_attempted")==attempted,
             "Attempted flag differs from reset boundary")
    _require(type(body.get("acknowledged")) is bool and body["acknowledged"]==acknowledged,"ACK flag differs from reply")
    claimed_vin=_get(body,"observed_vin",observed)
    _require(claimed_vin==observed,"Observed VIN differs")
    for name,want in (("before_dtcs",before),("after_dtcs",after)):
        _require(_get(body,name)==want,"Claimed DTCs differ: "+name)
    outcome=body.get("outcome")
    reason=body.get("reason")
    full=len(observations)==len(plan)
    if baseline:
        _require(full and not blocked and not attempted and outcome=="BASELINE_SAVED" and reason is None,
                 "Invalid baseline prefix")
    elif not attempted:
        _require(outcome=="NOT_TESTED","Unattempted reset claims success")
    elif not acknowledged:
        _require(outcome==("REFUSED" if reset_nrc else "UNCERTAIN"),"Outcome contradicts acknowledgment")
        if reset_nrc:
            _require(reason==reset_nrc,"Refusal code differs")
    elif outcome=="ACKNOWLEDGED_READBACK":
        _require(full and after["03"] is not None and after["07"] is not None and
                 reason==("DTCS_STILL_PRESENT" if after["03"] or after["07"] else None),
                 "Readback claim exceeds evidence")
    else:
        _require(outcome=="ACKNOWLEDGED_PARTIAL","Invalid acknowledged reset outcome")
    return {"observations":[r for r,_ in observations],"boundary":boundary,
            "command_attempted":attempted,"acknowledged":acknowledged,"observed_vin":observed,
            "before_dtcs":before,"after_dtcs":after,"readiness":readiness,
            "fresh_identity_and_stationary_recorded":recheck and len(observations)>=boundary+3 and
            all(not r["error"] for r,_ in observations[boundary:boundary+3]) and
            observations[boundary][1] is not None and _vin(observations[boundary][1])==vin and
            observations[boundary+1][1]==b"\x41\x0D\x00" and observations[boundary+2][1]==b"\x41\x0C\x00\x00",
            "outcome":outcome,"reason":reason}


def _evap(body,scope):
    _,vin,_,_=scope
    rows=[_row(row,True) for row in _array(body.get("exchanges"),5,"EVAP exchanges")]
    plan=("0902","0800","010D","010C","0801")
    observed=None
    blocked=attempted=ack=False
    nrc=None
    for i,(row,reply) in enumerate(rows):
        command=plan[i]
        _require(row["request_hex"]==command and not blocked,"Unexpected EVAP exchange")
        if command=="0902":
            observed=_vin(reply)
            blocked=observed!=vin
        elif command=="0800":
            blocked=reply is None or len(reply)!=6 or reply[:2]!=b"\x48\x00" or not(reply[2]&128)
        elif command=="010D":
            blocked=reply!=b"\x41\x0D\x00"
        elif command=="010C":
            blocked=reply!=b"\x41\x0C\x00\x00"
        else:
            attempted=True
            ack=reply==b"\x48\x01"
            nrc=_nrc(reply,8)
    _require(type(_get(body,"command_attempted")) is bool and _get(body,"command_attempted")==attempted,
             "Attempted flag differs from EVAP boundary")
    _require(_get(body,"observed_vin",observed)==observed,"Observed VIN differs")
    outcome=body.get("outcome")
    reason=body.get("reason")
    expected="NOT_TESTED" if not attempted else "ACKNOWLEDGED" if ack else "REFUSED" if nrc else "UNCERTAIN"
    _require(outcome==expected,"EVAP outcome contradicts captured evidence")
    if ack:
        _require(reason in (None,"LEAK_TEST_NOT_PERFORMED"),"EVAP preparation is not a leak test")
    if nrc:
        _require(reason==nrc,"Refusal code differs")
    return {"observations":[r for r,_ in rows],"command_attempted":attempted,
            "acknowledged":ack,"observed_vin":observed,"outcome":outcome,"reason":reason}


def inspect(report,baseline=None,*,expected_vin=None):
    body,scope,platform=_scope(report)
    operation,vin,tx,rx=scope
    if expected_vin is not None:
        _require(expected_vin==vin,"Inspection VIN differs from documented target")
    replay=_clear(body,scope) if operation=="clear" else _evap(body,scope)
    origin=None
    if baseline is not None:
        _require(operation=="clear","EVAP reports do not use reset baseline")
        base,base_scope,base_platform=_scope(baseline,True)
        _require(base_scope==scope and base_platform==platform,"Baseline target differs")
        origin="SEPARATE_FILE"
    else:
        base=report.get("clearBaseline",report.get("baseline"))
        if base is not None:
            _require(operation=="clear","Unexpected EVAP baseline")
            base=_dict(base,"embedded baseline")
            origin="EMBEDDED_COPY"
    if base is not None:
        initial=_clear(base,scope,True)
        _require(initial["observations"]==replay["observations"][:initial["boundary"]],
                 "Baseline prefix differs from final report")
    return {"schema":"camarodiag-j1979-can-mutation-inspection-v1","consistent":True,
            "operation":operation,"platform":platform,"expected_vin":vin,
            "request_can_id":f"{tx:X}","response_can_id":f"{rx:X}",
            "baseline_consistent":base is not None,"baseline_origin":origin,
            "baseline_required_missing":operation=="clear" and replay["command_attempted"] and base is None,
            "physical_vehicle_verified":False,"monitor_completion_verified":False,
            "operator_consent_verified":False,"durable_before_tx_verified":False,
            "evidence_authenticated":False,"permits_automatic_retry":False,**replay}


def inspect_files(report_path,baseline_path=None,*,expected_vin=None):
    report,digest=load(report_path)
    baseline,base_digest=load(baseline_path) if baseline_path is not None else (None,None)
    return {**inspect(report,baseline,expected_vin=expected_vin),"report_sha256":digest,
            "baseline_sha256":base_digest}
