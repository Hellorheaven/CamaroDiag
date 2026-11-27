"""Offline verification of recorded legacy clear/EVAP operations.

This module does not import or call any hardware mutation recipe. A matched
baseline is evidence consistency, not proof of durable storage before TX,
operator consent, ECU authenticity, emissions compliance or safe retry.
"""
from __future__ import annotations

from collections import defaultdict
import re

from .j1979_live_inspection import load, _get, _require, _hex, _array, _dict
from .j1979_legacy_inspection import _source, _vin, _driver_frame, _tx_frame, VARIANTS
from .j1979_legacy_frames import response_frames

FINAL = "camaradiag-functional-j1979-legacy-mutation-v1"
BASELINE = "camaradiag-j1979-legacy-baseline-v1"
MODES = ("03", "07", "0A")
OPERATIONS = {"clear":"clear","Clear":"clear","CLEAR":"clear",
              "evap":"evap","EvapPreparation":"evap","EVAP_PREPARATION":"evap"}
OUTCOMES = {"NOT_TESTED","UNCERTAIN","ACKNOWLEDGED_PARTIAL",
            "ACKNOWLEDGED_READBACK","ACKNOWLEDGED","BASELINE_SAVED"}


def _false(row, name):
    _require(_get(row,name,False) is False,"Unsupported claim: "+name)


def _scope(root, baseline=False):
    root=_dict(root,"operation report")
    _require(root.get("schema")== (BASELINE if baseline else FINAL),"Unsupported mutation schema")
    android="legacyMutation" in root or "elmProtocol" in root
    driver="result" in root
    if android and not baseline:
        body=_dict(root.get("legacyMutation"),"Android mutation result")
    elif driver:
        body=_dict(root.get("result"),"J2534 mutation result")
    else:
        body=root
    if android:
        variant=_get(body,"elm_protocol")
        vin=_get(root,"expected_vin",_get(root,"selected_vin"))
    else:
        variant=root.get("variant")
        vin=_get(root,"expected_vin")
    if driver:
        variant=VARIANTS.get(variant)
    _require(type(variant) is int and 1<=variant<=5,"Invalid legacy transport")
    vin=_vin(vin)
    _require(vin is not None,"Missing documented VIN")
    meta=body if android else root
    operation=OPERATIONS.get(meta.get("operation"))
    _require(operation is not None,"Unknown operation")
    engine=_source(_get(meta,"engine_source"))
    sources=[_source(v) for v in _array(_get(meta,"documented_sources"),32,"documented responders")]
    _require(len(sources)==len(set(sources)) and engine in sources,"Invalid documented responder set")
    claimed=_get(body,"expected_sources")
    if claimed is not None:
        _require(sorted(_source(s) for s in claimed)==sorted(sources),"Conflicting expected responders")
    if android and not baseline:
        for name,expected in (("legacy_protocol",variant),("legacy_operation",meta["operation"]),
                              ("legacy_engine_source",f"{engine:02X}")):
            _require(_get(root,name)==expected,"Outer Android scope differs: "+name)
        _require(sorted(_source(v) for v in _array(_get(root,"legacy_documented_sources"),32,"outer responders"))
                 ==sorted(sources),"Outer Android responder set differs")
    for obj in (root,body):
        for flag in ("whole_vehicle_coverage_verified","physical_vehicle_verified","leak_test_performed",
                     "evidence_authenticated","permits_automatic_retry"):
            _false(obj,flag)
    _require(_get(meta,"scope")=="FUNCTIONAL_VEHICLE_WIDE" or
             driver and root.get("scope")=="FUNCTIONAL_VEHICLE_WIDE","Operation scope not functional")
    return body,(variant,vin,operation,engine,tuple(sorted(sources))),("J2534" if driver else "ANDROID" if android else "ELM")


def _plan(operation, android, fresh_scope=False):
    rows=[("SCOPE","0100"),("IDENTITY","0901"),("IDENTITY","0902")]
    if operation=="clear":
        rows.extend(("BEFORE",c) for c in (*MODES,"0101"))
    else:
        rows.append(("SUPPORT","08000000000000"))
    rows.extend((("SCOPE","0100"),("STATIONARY","010D"),("STATIONARY","010C")))
    boundary=len(rows)
    if android or fresh_scope:
        rows.append(("SCOPE","0100"))
    rows.extend((("STATIONARY","010D"),("STATIONARY","010C"),
                 ("MUTATION","04" if operation=="clear" else "08010000000000")))
    if operation=="clear":
        rows.extend(("AFTER",c) for c in (*MODES,"0101"))
    return rows,boundary


def _parse_observation(row, variant, platform):
    row=_dict(row,"observation")
    stage=row.get("stage")
    req=_get(row,"request_hex")
    command=_hex(req,7)
    _require(command and req==command.hex().upper(),"Invalid operation request encoding")
    error=row.get("error")
    _require(error is None or isinstance(error,str) and 0<len(error)<=256,"Invalid observation error")
    frames=[]
    raw=_get(row,"raw_response")
    tx=_get(row,"tx_hex")
    if platform=="J2534":
        replies=_array(row.get("replies"),128,"driver replies")
        if tx is not None:
            _require(_hex(tx,10)==_tx_frame(command,variant),"Recorded TX differs from request")
        _require(tx is not None or not replies,"Replies without recorded TX")
        for reply in replies:
            source=_source(reply.get("source"))
            payload=_driver_frame(reply,variant,source)
            frames.append((source,payload,_hex(_get(reply,"raw_hex"),11).hex().upper()))
    else:
        parsed=[]
        if raw is not None:
            _require(isinstance(raw,str) and len(raw)<=256*1024,"Invalid adapter response")
            try:
                parsed=response_frames(variant,req,raw)
            except ValueError:
                # Transport/parser failures cannot support positive claims.
                _require(error is not None or stage=="MUTATION","Unrecorded invalid response")
                error=error or "INVALID_MUTATION_RESPONSE"
        frames=[(f.source,f.payload,f.raw.hex().upper()) for f in parsed]
        if platform=="ANDROID":
            claimed=_array(row.get("sources"),128,"Android observed sources")
            _require([_source(s) for s in claimed]==list(dict.fromkeys(f[0] for f in frames)),
                     "Android responder list differs from raw frames")
        else:
            claimed=_array(row.get("frames"),128,"ELM observed frames")
            _require(claimed==[f.evidence() for f in parsed],"ELM frame list differs from raw response")
    grouped=defaultdict(list)
    for source,payload,_ in frames:
        grouped[source].append(payload)
    return {"stage":stage,"request_hex":req,"raw_response":raw,"tx_hex":tx,
            "error":error,"frames":[{"source":f"{s:02X}","payload_hex":p.hex().upper(),
                                     "raw_hex":rawhex} for s,p,rawhex in frames]},dict(grouped)


def _dtcs(mode, rows):
    if not rows or any(len(p)!=7 or p[0]!=int(mode,16)+0x40 for p in rows):
        return None
    result=[]
    for row in rows:
        for a,b in zip(row[1::2],row[2::2]):
            if a or b:
                code=f"{'PCBU'[a>>6]}{(a>>4)&3:X}{a&15:X}{b:02X}"
                if code not in result:
                    result.append(code)
    return result


def _empty(sources):
    return {f"{s:02X}":{m:None for m in MODES} for s in sources}


def _readback(out, grouped, command, sources):
    if command in MODES:
        for s in sources:
            out[f"{s:02X}"][command]=_dtcs(command,grouped.get(s,[]))


def _identity(counts,parts,vin,engine,sources):
    engine_vin=None
    for source in sources:
        c=counts.get(source,[])
        rows=parts.get(source,[])
        if source!=engine and not c and not rows:
            continue
        if c!=[b"\x49\x01\x05"] or len(rows)!=5 or any(
                len(row)!=7 or row[:3]!=bytes((0x49,2,i+1)) for i,row in enumerate(rows)):
            return engine_vin,False
        if b"".join(row[3:] for row in rows)!=bytes(3)+vin.encode("ascii"):
            return engine_vin,False
        if source==engine:
            engine_vin=vin
    return engine_vin,engine_vin is not None


def _stationary(grouped,command,engine):
    positive=bytes.fromhex("410D00" if command=="010D" else "410C0000")
    return grouped.get(engine)==[positive] and all(
        p==positive or s!=engine and len(p)==3 and p[:2]==b"\x7f\x01" and p[2] in (0x11,0x12,0x31)
        for s,rows in grouped.items() for p in rows)


def _replay(body,scope,platform,baseline=False):
    variant,vin,operation,engine,sources=scope
    raw_rows=_array(body.get("observations"),40,"mutation observations")
    observations=[_parse_observation(row,variant,platform) for row in raw_rows]
    # Older ELM/J2534 producers did not record the extra scope observation
    # after saving. Accept their historical layout, but identify the gap.
    base_plan,boundary=_plan(operation,platform=="ANDROID")
    fresh_scope=(len(observations)>boundary and
                 observations[boundary][0]["stage"]=="SCOPE")
    plan,boundary=_plan(operation,platform=="ANDROID",fresh_scope)
    if baseline:
        plan=plan[:boundary]
    _require(len(observations)<=len(plan),"Unexpected additional observation or retry")
    before,after=_empty(sources),_empty(sources)
    acks={f"{s:02X}":"NOT_TESTED" for s in sources}
    ack_sources=set(sources)
    attempted=False
    complete_ack=False
    observed=None
    counts={}
    prerequisite_failed=False
    for i, (row,grouped) in enumerate(observations):
        stage,command=plan[i]
        _require((row["stage"],row["request_hex"])==(stage,command),"Unexpected operation sequence")
        _require(not prerequisite_failed,"Continued after failed prerequisite")
        if stage!="MUTATION" and (row["error"] or set(grouped)-set(sources)):
            prerequisite_failed=True
            continue
        if stage=="SCOPE":
            prerequisite_failed=set(grouped)!=set(sources) or any(
                len(v)!=1 or len(v[0])!=6 or v[0][:2]!=b"\x41\x00" for v in grouped.values())
        elif stage=="IDENTITY":
            if command=="0901":
                counts=grouped
            else:
                observed,ok=_identity(counts,grouped,vin,engine,sources)
                prerequisite_failed=not ok
        elif stage=="BEFORE":
            _readback(before,grouped,command,sources)
            if command=="0101":
                prerequisite_failed=any(m["03"] is None for m in before.values())
        elif stage=="SUPPORT":
            ack_sources=set()
            for source,rows in grouped.items():
                if len(rows)!=1 or len(rows[0])!=7 or rows[0][:3]!=b"\x48\x00\x00":
                    prerequisite_failed=True
                elif rows[0][3]&0x80:
                    ack_sources.add(source)
            prerequisite_failed|=ack_sources!={engine}
        elif stage=="STATIONARY":
            prerequisite_failed=not _stationary(grouped,command,engine)
        elif stage=="MUTATION":
            attempted=True
            positive=b"\x44" if operation=="clear" else b"\x48\x01"+bytes(5)
            mode=4 if operation=="clear" else 8
            for s,rows in grouped.items():
                acks[f"{s:02X}"]=("ACKNOWLEDGED" if rows==[positive] else
                    f"REFUSED_{rows[0][2]:02X}" if len(rows)==1 and len(rows[0])==3 and
                    rows[0][:2]==bytes((0x7F,mode)) and rows[0][2] not in (0,0x78) else "UNCERTAIN")
            complete_ack=not row["error"] and set(grouped)<=ack_sources and all(
                acks[f"{s:02X}"]=="ACKNOWLEDGED" for s in ack_sources)
        elif stage=="AFTER":
            _readback(after,grouped,command,sources)
    outcome=body.get("outcome")
    reason=body.get("reason")
    _require(outcome in OUTCOMES and (reason is None or isinstance(reason,str)),"Invalid operation outcome")
    claimed=_get(body,"command_attempted",body.get("attempted"))
    _require(type(claimed) is bool and claimed==attempted,"Attempted flag differs from recorded boundary")
    _require(_get(body,"observed_vin")==observed,"Observed VIN differs from identity records")
    _require(body.get("acknowledgments")==acks,"Acknowledgments differ from recorded replies")
    for name,wanted in (("before_dtcs",before),("after_dtcs",after)):
        actual=_dict(_get(body,name),name)
        _require(actual==wanted,"DTC readback differs: "+name)
    if baseline:
        _require(outcome=="BASELINE_SAVED" and reason is None and not attempted and
                 len(observations)==boundary and not prerequisite_failed and observed==vin,
                 "Invalid baseline boundary")
    elif not attempted:
        _require(outcome=="NOT_TESTED","Unattempted operation cannot claim acknowledgment")
    elif not complete_ack:
        _require(outcome=="UNCERTAIN","Incomplete acknowledgments cannot establish success")
    elif operation=="evap":
        _require(outcome in ("ACKNOWLEDGED","ACKNOWLEDGED_PARTIAL"),"Unsupported EVAP outcome")
        if outcome=="ACKNOWLEDGED":
            _require(reason=="LEAK_TEST_NOT_PERFORMED","EVAP preparation is not a leak test")
    else:
        full=len(observations)==len(plan) and not prerequisite_failed and all(m["03"] is not None for m in after.values())
        residual=any(m["03"] or m["07"] for m in after.values())
        if outcome=="ACKNOWLEDGED_READBACK":
            _require(full and (reason=="DTCS_STILL_PRESENT" if residual else
                     reason is None and all(m["07"] is not None for m in after.values())),
                     "Readback outcome exceeds available DTC evidence")
        else:
            _require(outcome=="ACKNOWLEDGED_PARTIAL","Invalid acknowledged outcome")
    return {"observations":[row for row,_ in observations],"boundary":boundary,
            "command_attempted":attempted,"complete_acknowledgment":complete_ack,
            "observed_vin":observed,"before_dtcs":before,"after_dtcs":after,"acknowledgments":acks,
            "outcome":outcome,"reason":reason,"fresh_scope_recorded":fresh_scope}


def inspect(report, baseline=None, *, expected_vin=None):
    body,scope,platform=_scope(report)
    variant,vin,operation,engine,sources=scope
    if expected_vin is not None:
        _require(_vin(expected_vin)==vin,"Inspection VIN differs from documented target")
    replay=_replay(body,scope,platform)
    baseline_verified=False
    baseline_origin=None
    if baseline is not None:
        base,base_scope,base_platform=_scope(baseline,baseline=True)
        _require(base_scope==scope and base_platform==platform,"Baseline belongs to another operation")
        baseline_origin="SEPARATE_FILE"
    elif platform=="ANDROID" and isinstance(report.get("legacyBaseline"),dict):
        base=_dict(report["legacyBaseline"],"embedded baseline")
        _,base_scope,base_platform=_scope(dict(base,schema=BASELINE,expectedVin=vin),baseline=True)
        _require(base_scope==scope and base_platform==platform,"Embedded baseline scope differs")
        baseline_origin="EMBEDDED_COPY"
    else:
        base=None
    if base is not None:
        initial=_replay(base,scope,platform,baseline=True)
        _require(initial["observations"]==replay["observations"][:initial["boundary"]],
                 "Baseline observations differ from operation prefix")
        baseline_verified=True
    return {"schema":"camarodiag-j1979-legacy-mutation-inspection-v1",
            "consistent":True,"platform":platform,"variant":variant,"operation":operation,
            "documented_vin":vin,"engine_source":f"{engine:02X}",
            "documented_sources":[f"{s:02X}" for s in sources],
            "baseline_consistent":baseline_verified,"baseline_origin":baseline_origin,
            "baseline_required_missing":replay["command_attempted"] and not baseline_verified,
            "physical_vehicle_verified":False,"whole_vehicle_coverage_verified":False,
            "operator_consent_verified":False,"durable_before_tx_verified":False,
            "evidence_authenticated":False,"protocol_probe_independently_verified":False,
            "leak_test_performed":False,"permits_automatic_retry":False,**replay}


def inspect_files(report_path, baseline_path=None, *, expected_vin=None):
    report,digest=load(report_path)
    baseline,base_digest=load(baseline_path) if baseline_path is not None else (None,None)
    return {**inspect(report,baseline,expected_vin=expected_vin),
            "report_sha256":digest,"baseline_sha256":base_digest}
