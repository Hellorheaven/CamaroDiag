"""Independent legacy PID00 discovery replay for Android, ELM and J2534.

Never chooses an ECU automatically. Discovery is not a vehicle inventory,
and a driver-provided frame does not attest an omitted link checksum.
"""
from __future__ import annotations

from collections import defaultdict
from .j1979_live_inspection import load, _require, _get, _hex, _dict, _array
from .j1979_legacy_inspection import _source, _driver_frame, _tx_frame, VARIANTS
from .j1979_legacy_frames import response_frames

SCHEMA="camarodiag-functional-j1979-legacy-discovery-v1"


def _fixed_protocol(raw):
    _require(isinstance(raw,str) and len(raw)<=4096,"Invalid adapter protocol probe")
    tokens=[s.strip().upper() for s in raw.replace(">","\n").splitlines()
            if s.strip() and s.strip().upper()!="ATDPN"]
    return int(tokens[0]) if len(tokens)==1 and tokens[0] in ("1","2","3","4","5") else None


def _project(frames,platform):
    groups=defaultdict(list)
    for source,payload,raw in frames:
        groups[source].append((payload,raw))
    result=[]
    for source,rows in sorted(groups.items()):
        payloads=[p for p,_ in rows]
        unique=set(payloads)
        positive=(len(unique)==1 and len(payloads[0])==6 and payloads[0][:2]==b"\x41\x00")
        if platform=="ELM":
            positive=positive and len(rows)==1
            negative=(len(rows)==1 and len(payloads[0])==3 and
                      payloads[0][:2]==b"\x7f\x01" and payloads[0][2] not in (0,0x78))
        else:
            negative=all(len(p)==3 and p[:2]==b"\x7f\x01" and p[2]!=0 for p in payloads)
        bitmap=payloads[0][2:] if positive else None
        result.append({"source":f"{source:02X}","status":"PASSED" if positive else
                       "NOT_TESTED" if negative else "FAILED",
                       "supported_pid_bitmap":bitmap.hex().upper() if bitmap is not None else None,
                       "supported_pids":[i+1 for i in range(31) if bitmap[i//8]&(0x80>>(i%8))]
                       if bitmap is not None else None,
                       "has_next_page":bool(bitmap[-1]&1) if bitmap is not None else None,
                       "raw_frames":[raw for _,raw in rows]})
    return result


def _compare_android(body,expected):
    actual=_array(body.get("sources"),254,"Android discovery sources")
    _require(len(actual)==len(expected),"Missing or additional Android responder")
    for got,want in zip(actual,expected):
        for key in ("source","status","supported_pid_bitmap","raw_frames"):
            _require(_get(got,key)==want[key],"Android discovery differs: "+key)


def _compare_elm(body,expected,frames):
    actual=_array(body.get("responders"),254,"ELM discovery sources")
    _require(len(actual)==len(expected),"Missing or additional ELM responder")
    for got,want in zip(actual,expected):
        for key in ("source","status","supported_pids","has_next_page"):
            _require(got.get(key)==want[key],"ELM discovery differs: "+key)
        rows=[f for f in frames if f.source==int(want["source"],16)]
        _require(got.get("frames")==[f.evidence() for f in rows],"ELM grouped frame evidence differs")
        if want["status"]=="PASSED":
            detail=None
        elif want["status"]=="NOT_TESTED":
            detail=f"NRC_{rows[0].payload[2]:02X}"
        else:
            detail="AMBIGUOUS_OR_INVALID_PID00"
        _require(got.get("detail")==detail,"ELM discovery detail differs")


def inspect(report):
    root=_dict(report,"legacy discovery report")
    for flag in ("physical_vehicle_verified","whole_vehicle_coverage_verified",
                 "evidence_authenticated","permits_programming"):
        _require(_get(root,flag,False) is False,"Unsupported discovery claim")
    frames=[]
    if "legacySourceDiscovery" in root:
        platform="ANDROID"
        body=_dict(root["legacySourceDiscovery"],"Android discovery")
        protocol=_get(body,"selected_protocol")
        _require(type(protocol) is int and 1<=protocol<=5,"Invalid fixed legacy transport")
        observed=_fixed_protocol(_get(body,"raw_protocol_response"))
        _require(_get(body,"observed_protocol")==observed,"Android protocol probe differs")
        raw=_get(body,"raw_response")
        _require(isinstance(raw,str) and len(raw)<=32768,"Invalid Android discovery response")
        error=body.get("error")
        if observed!=protocol:
            _require(error=="PROTOCOL_UNVERIFIED","Protocol mismatch not reflected in report")
        else:
            try:
                parsed=response_frames(protocol,"0100",raw)
            except ValueError:
                _require(error=="MALFORMED_LEGACY_FRAME","Invalid response not reflected in report")
            else:
                _require(error is None,"Unexpected error with valid discovery")
                frames=[(f.source,f.payload,f.raw.hex().upper()) for f in parsed]
        expected=_project(frames,platform)
        _compare_android(body,expected)
        eligible=[r["source"] for r in expected if r["status"]=="PASSED"] if error is None else []
        _require(_get(body,"verified_sources")==eligible,"Android verified sources differ")
        complete=error is None
        probe_verified=observed==protocol
    elif root.get("schema")==SCHEMA and "discovery" in root:
        platform="J2534"
        protocol=VARIANTS.get(root.get("variant"))
        _require(protocol is not None,"Invalid J2534 variant")
        _require(_get(root,"selected_response_source") is None,"Discovery unexpectedly pins a source")
        _require(root.get("baud")== (41600 if protocol==1 else 10400),"Driver baud differs")
        body=_dict(root["discovery"],"J2534 discovery")
        _require(_get(body,"request_hex")=="0100" and _hex(_get(body,"tx_hex"),10)==
                 _tx_frame(b"\x01\x00",protocol),"Discovery did not record PID00")
        sources=_array(body.get("sources"),254,"driver responders")
        for group in sources:
            source=_source(_get(group,"response_source"))
            for frame in _array(group.get("records"),128,"driver frames"):
                payload=_driver_frame(frame,protocol,source)
                frames.append((source,payload,_hex(_get(frame,"raw_hex"),11).hex().upper()))
        expected=_project(frames,platform)
        _require(len(sources)==len(expected),"Duplicate or omitted driver source")
        for got,want in zip(sources,expected):
            _require(_get(got,"response_source")==want["source"] and got.get("status")==want["status"],
                     "Driver source classification differs")
            detail=want["supported_pid_bitmap"] if want["status"]=="PASSED" else (
                "NEGATIVE_RESPONSE" if want["status"]=="NOT_TESTED" else "CONFLICTING_OR_MALFORMED_RESPONSE")
            _require(got.get("detail")==detail,"Driver bitmap or status detail differs")
        eligible=[r["source"] for r in expected if r["status"]=="PASSED"]
        _require(_get(body,"has_verified_source")==bool(eligible),"Driver eligibility differs")
        error=root.get("error")
        complete=error is None
        if error is not None:
            eligible=[]
        probe_verified=False
    else:
        _require(root.get("schema")==SCHEMA,"Unsupported discovery schema")
        platform="ELM"
        body=root
        protocol=_get(root,"expected_protocol")
        observed=_get(root,"observed_protocol")
        _require(type(protocol) is int and 1<=protocol<=5,"Invalid legacy transport")
        _require(observed is None or type(observed) is int and observed==protocol,"Invalid observed protocol")
        steps=_array(root.get("steps"),1,"PID00 observation")
        _require(len(steps)==1 and steps[0].get("request_hex")=="0100","Expected exactly one discovery observation")
        step=steps[0]
        raw=step.get("raw_response")
        error=root.get("stop_reason")
        complete=root.get("completed")
        _require(type(complete) is bool and complete==(error is None),"Inconsistent discovery completion")
        parsed=[]
        if raw is not None:
            _require(isinstance(raw,str) and len(raw)<=256*1024,"Invalid adapter response")
            try:
                parsed=response_frames(protocol,"0100",raw)
            except ValueError:
                _require(error in ("INVALID_RESPONSE_OR_PROTOCOL","BUDGET_EXHAUSTED"),
                         "Malformed discovery reported as successful")
        claimed=step.get("frames")
        if error=="BUDGET_EXHAUSTED" and not claimed:
            parsed=[]
        _require(claimed==[f.evidence() for f in parsed],"Saved discovery frames differ from original bytes")
        if observed==protocol:
            frames=[(f.source,f.payload,f.raw.hex().upper()) for f in parsed]
        expected=_project(frames,platform)
        _compare_elm(root,expected,parsed)
        eligible=[r["source"] for r in expected if r["status"]=="PASSED"]
        _require(not complete or bool(eligible),"Discovery completed without eligible responder")
        if not complete:
            eligible=[]
        probe_verified=False
    _require(_get(body,"whole_vehicle_coverage_verified",False) is False,"Unsupported coverage claim")
    return {"schema":"camarodiag-j1979-legacy-discovery-inspection-v1",
            "consistent":True,"platform":platform,"protocol":protocol,"completed":complete,
            "error":error,"responders":expected,"eligible_sources":eligible,
            "link_checksum_verified":platform!="J2534" and bool(frames),
            "protocol_probe_independently_verified":probe_verified,
            "whole_vehicle_coverage_verified":False,"physical_vehicle_verified":False,
            "evidence_authenticated":False,"automatic_selection_permitted":False}


def inspect_file(path):
    report,digest=load(path)
    return {**inspect(report),"source_sha256":digest}


def bind_scan(discovery,scan):
    """Compare an independently replayed discovery and selected-source scan.

    An older observation is not current authorization. Report changes rather
    than silently widening or replacing the selected source.
    """
    from .j1979_legacy_inspection import inspect as inspect_scan
    d=inspect(discovery)
    s=inspect_scan(scan)
    _require(d["protocol"]==s["protocol"],"Discovery and scan protocols differ")
    source=s["selected_response_source"]
    _require(source in d["eligible_sources"],"Scan source not eligible in discovery")
    row=next(r for r in d["responders"] if r["source"]==source)
    first=s["steps"][0] if s["steps"] else None
    bitmap=first["response_hex"][4:] if first and first["request_hex"]=="0100" and first["response_hex"] else None
    return {"schema":"camarodiag-j1979-legacy-discovery-scan-inspection-v1",
            "protocol":s["protocol"],"selected_response_source":source,
            "discovery_bitmap":row["supported_pid_bitmap"],"scan_bitmap":bitmap,
            "bitmap_unchanged":bitmap is not None and bitmap==row["supported_pid_bitmap"],
            "scan_completed":s["completed"],"observed_vin":s["observed_vin"],
            "physical_vehicle_verified":False,"whole_vehicle_coverage_verified":False,
            "current_authorization_verified":False}


def bind_files(discovery_path,scan_path):
    d,dh=load(discovery_path)
    s,sh=load(scan_path)
    return {**bind_scan(d,s),"discovery_sha256":dh,"scan_sha256":sh}
