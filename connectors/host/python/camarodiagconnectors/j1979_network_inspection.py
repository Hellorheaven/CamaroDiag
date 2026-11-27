"""Independent replay of explicit J1979 CAN network discovery and read scans.

This validates the recorded plan and application evidence, not CAN traffic
provenance. The operator's selected responders are never enlarged by replay.
"""
from __future__ import annotations

from collections import defaultdict
import math
import re

from .j1979_can_network import CanFrame, decode_frame, response_pair, validate_targets
from .j1979 import allowed_read
from .j1979_vehicle_info import can_info
from .j1979_multi_signal import from_steps as multi_records, historical_projection
from .j1979_status import from_steps as status_records
from .j1979_campaign_evidence import decode_observation, summarize_observations
from .j1979_live_inspection import load, _get, _require, _hex, _array, _dict, _id

SCHEMAS = {"camarodiag-functional-j1979-can-discovery-v1",
           "camarodiag-functional-j1979-can-network-v1", "camarodiag-j1979-network-v1"}
MAX_STEPS = 1400


def _address(value):
    if type(value) is int:
        _require(0<=value<=0x1FFFFFFF,"Invalid CAN address")
        return value
    return _id(value)


def _flags(value):
    for name in ("authenticated","physical_vehicle_verified","whole_vehicle_coverage_verified",
                 "permits_programming","permits_automatic_recovery"):
        _require(_get(value,name,False) is False,"Unsupported physical verification or authorization claim")


def _normalize(value):
    if isinstance(value,dict):
        def snake(key):
            return re.sub(r"(?<=[a-z0-9])(?=[A-Z])","_",key).lower()
        return {snake(k):_normalize(v) for k,v in value.items()}
    if isinstance(value,list):
        return [_normalize(x) for x in value]
    return value


def _equal(actual, expected):
    if type(expected) is float:
        return type(actual) in (float,int) and math.isfinite(actual) and math.isclose(actual,expected,rel_tol=1e-9,abs_tol=1e-9)
    if isinstance(expected,list):
        return isinstance(actual,list) and len(actual)==len(expected) and all(_equal(a,b) for a,b in zip(actual,expected))
    if isinstance(expected,dict):
        return isinstance(actual,dict) and set(actual)==set(expected) and all(_equal(actual[k],v) for k,v in expected.items())
    return type(actual) is type(expected) and actual==expected


def _unwrap(root):
    if root.get("schema") in SCHEMAS:
        return root
    nested=root.get("network")
    _require(isinstance(nested,dict) and nested.get("schema") in SCHEMAS,"Unsupported CAN network evidence schema")
    return nested


def _frame(row,extended,android):
    row=_dict(row,"CAN frame")
    address=_address(_get(row,"response_id") if android else _get(row,"can_id"))
    payload=_hex(_get(row,"data_hex"),8)
    _require(_get(row,"extended",extended) is extended and
             row.get("rtr",False) is False and row.get("error",False) is False,
             "Wrong CAN frame flags")
    _require(response_pair(address,extended) is not None,"Unrelated recorded responder")
    return CanFrame(address,extended,payload)


def _frame_verdict(frame,extended,android):
    if android and 4<=len(frame.data)<8:
        # ATH1 can expose a short DLC rather than padding. The Android
        # discovery recipe accepts complete SF payloads, not truncated ones.
        valid_sf=(len(frame.data)>=7 and frame.data[:3]==b"\x06\x41\x00") or (
            frame.data[:3]==b"\x03\x7f\x01")
        if valid_sf:
            return decode_frame(CanFrame(frame.can_id,extended,frame.data.ljust(8,b"\x00")),extended)
    return decode_frame(frame,extended)


def replay_discovery(discovery,extended,android=False):
    d=_dict(discovery,"discovery")
    _flags(d)
    sent=_get(d,"request_sent")
    completed=d.get("completed")
    _require(type(sent) is bool and type(completed) is bool,"Missing discovery state")
    stop=_get(d,"stop_reason")
    _require((stop is None)==completed,"Inconsistent discovery completion")
    if completed:
        _require(sent,"Discovery completed without recorded request")
    if android and completed:
        observed=_get(d,"observed_protocol")
        _require(observed in ((7,9) if extended else (6,8)),"Unverified ELM CAN profile")
        raw=_get(d,"raw_protocol")
        _require(isinstance(raw,str),"Missing fixed protocol response")
        lines=[x.strip().upper() for x in raw.replace(">","\n").splitlines()
               if x.strip() and x.strip().upper()!="ATDPN"]
        _require(lines==[str(observed)],"ELM fixed protocol differs")
    if "request" in d:
        from .j1979_can_network import functional_request
        _require(_frame_request_matches(d["request"],functional_request(extended)),
                 "Unexpected functional discovery request")
    raw_rows=_get(d,"raw_frames",d.get("frames"))
    frames=[_frame(x,extended,android) for x in _array(raw_rows,512,"discovery frames")]
    _require(sent or not frames,"Discovery frames without transmitted request")
    groups=defaultdict(list)
    for frame in frames:
        verdict=_frame_verdict(frame,extended,android)
        _require(verdict is not None,"Discovery frame is not a response-family member")
        groups[frame.can_id].append((verdict,frame))
    calculated={}
    for address,group in sorted(groups.items()):
        terminals=[v for v,f in group if v["detail"]!="PENDING_WITHOUT_FINAL_RESPONSE"]
        first=terminals[0] if terminals else group[0][0]
        verdict=dict(first)
        if any(v!=first for v in terminals):
            verdict.update(status="FAILED",detail="CONFLICTING_RESPONSES",supported_pids=None,
                           next_page=None,negative_response_code=None)
        calculated[address]=(verdict,[f.data for v,f in group])
    claimed=_array(d.get("responders"),512,"responder rows")
    seen=set()
    for row in claimed:
        row=_dict(row,"responder")
        rx=_address(_get(row,"response_id") if android else _get(row,"rx_can_id"))
        tx=_address(_get(row,"request_id") if android else _get(row,"tx_can_id"))
        _require(rx in calculated and rx not in seen and response_pair(rx,extended)==(tx,rx),
                 "Responder identity does not match raw frames")
        seen.add(rx)
        verdict,raw=calculated[rx]
        for name in ("status","detail","supported_pids","next_page","negative_response_code"):
            actual=_get(row,name)
            # Older Windows exports included the page continuation marker
            # in the PID list. It is a page flag, not a supported measurement.
            if name=="supported_pids" and not android and isinstance(actual,list) and actual and actual[-1]==32:
                _require(verdict["next_page"] is True,"Unsupported continuation marker")
                actual=actual[:-1]
            _require(_equal(actual,verdict[name]),"Responder classification differs: "+name)
        evidence=_array(row.get("frames"),512,"responder evidence")
        if android:
            evidence=[_hex(x,8) for x in evidence]
        else:
            evidence=[_frame(x,extended,False) for x in evidence]
            _require(all(f.can_id==rx for f in evidence),"Responder evidence mixes CAN IDs")
            evidence=[f.data for f in evidence]
        _require(evidence==raw,"Responder raw frame list differs")
    _require(seen==set(calculated),"Missing responder classification")
    eligible=sorted(k for k,(v,_) in calculated.items() if v["status"]=="PASSED") if completed else []
    if completed:
        _require(bool(eligible),"No qualifying responder in completed discovery")
    claimed_eligible=_get(d,"eligible_response_ids")
    if claimed_eligible is not None:
        _require([_address(x) for x in claimed_eligible]==eligible,"Eligible responder list differs")
    return {"completed":completed,"stop_reason":stop,"request_sent":sent,
            "eligible_response_ids":eligible,
            "responders":[{"response_id":k,**v,"frame_count":len(f)} for k,(v,f) in calculated.items()]}


def _frame_request_matches(row,expected):
    return isinstance(row,dict) and _address(_get(row,"can_id"))==expected.can_id and (
        _hex(_get(row,"data_hex"),8)==expected.data) and row.get("extended") is expected.extended and (
        row.get("rtr",False) is False and row.get("error",False) is False)


def _normalize_steps(steps):
    result=[]
    for original in _array(steps,MAX_STEPS,"physical campaign steps"):
        step=_dict(original,"physical campaign step")
        request=_hex(_get(step,"request_hex"),3)
        _require(allowed_read(request),"Non-read request in network campaign")
        raw=_get(step,"response_hex")
        response=None if raw is None else _hex(raw)
        status=step.get("status")
        _require(status in ("PASSED","RAW","NEGATIVE","TIMEOUT","FAILED","NOT_TESTED"),"Unknown physical step status")
        duration=_get(step,"duration_ms")
        _require(type(duration) is int and 0<=duration<=600000,"Invalid physical step duration")
        evidence=decode_observation(request,response) if response is not None else None
        verified=None
        if status in ("PASSED","RAW","NEGATIVE"):
            _require(evidence is not None and evidence["shape"]==status,"Physical step response does not match status")
            verified=evidence
        elif status=="NOT_TESTED" and evidence and evidence["shape"]=="NEGATIVE":
            if step.get("detail")==f'NRC_{evidence["negative_response_code"]:02X}':
                verified=evidence
        if "application_evidence" in step or "applicationEvidence" in step:
            claimed=_get(step,"application_evidence")
            # Windows includes null fields on negative/RAW records; Python
            # omits them. All material non-null data must still match.
            def nonnull(value):
                if isinstance(value,dict):
                    return {k:nonnull(v) for k,v in value.items() if v is not None}
                if isinstance(value,list):
                    return [nonnull(v) for v in value]
                return value
            _require(_equal(nonnull(_normalize(claimed)),nonnull(verified)),
                     "Application evidence differs from response")
        for name in ("measurement","dtc_codes"):
            if name in step or re.sub(r"_([a-z])",lambda m:m[1].upper(),name) in step:
                _require(_equal(_normalize(_get(step,name)),(verified or {}).get(name)),
                         "Exported physical measurement or DTC differs")
        result.append({"request_hex":request.hex().upper(),"response_hex":raw,"status":status,
                       "detail":step.get("detail"),"duration_ms":duration,"application_evidence":verified})
    return result


def _replay_plan(steps,android,vin,completed,stop):
    index=0
    halted=False
    first_vin=None
    last_vin=None

    def take(request):
        nonlocal index,halted
        if halted:
            return None
        if index>=len(steps):
            halted=True
            return None
        row=steps[index]
        _require(row["request_hex"]==request.hex().upper(),"Unexpected read request or inventory order")
        index+=1
        if row["status"]=="FAILED" and (android or str(row["detail"]).startswith("TRANSPORT_ERROR") or
                row["detail"] in ("OSError","ConnectionError","BrokenPipeError","ConnectionResetError")):
            halted=True
        if row["detail"] in ("LATE_RESPONSE","CANCELLED","BUDGET_EXHAUSTED"):
            halted=True
        return row["application_evidence"] if row["status"]=="PASSED" else None

    def inventory(mode,cached=None,members=True):
        found=[]
        suffix=bytes(1) if mode==2 else b""
        for base in range(0,0xE1,32):
            if halted:
                break
            ev=cached if base==0 and cached is not None else take(bytes((mode,base))+suffix)
            page=ev.get("supported_page") if ev else None
            if page is None:
                break
            ids=page["supported_ids"]
            found.extend(ids)
            if android and members:
                for pid in ids:
                    if mode in (2,9) and pid==2:
                        continue
                    take(bytes((mode,pid))+suffix)
            if not page["has_next_page"]:
                break
        if not android and members:
            for pid in found:
                if mode in (2,9) and pid==2:
                    continue
                take(bytes((mode,pid))+suffix)

    initial=None
    if android:
        initial=take(b"\x01\x00")
        if initial is None:
            halted=True
    first=take(b"\x09\x02")
    first_vin=first.get("observed_vin") if first else None
    if vin is not None and first_vin!=vin:
        halted=True
    for mode in (3,7,10):
        take(bytes((mode,)))
    inventory(1,initial)
    trigger=take(b"\x02\x02\x00")
    if trigger and trigger.get("freeze_trigger") and trigger["freeze_trigger"]["present"]:
        inventory(2)
    inventory(6)
    inventory(8,members=False)
    inventory(9)
    if android:
        final=take(b"\x09\x02")
        last_vin=final.get("observed_vin") if final else None
        if vin is not None and last_vin!=vin:
            halted=True
    _require(index==len(steps),"Read operations recorded after campaign halted")
    if completed:
        _require(not halted and stop is None,"Incomplete read plan claimed complete")
    else:
        _require(isinstance(stop,str) and stop,"Incomplete read plan lacks reason")
    return first_vin,last_vin


def replay_ecu(row,android,vin):
    body=row if android else row.get("report")
    if body is None:
        _require(row["status"] in ("FAILED","NOT_TESTED"),"Successful ECU without observations")
        return {"completed":False,"observed_vin":None,"final_vin":None,"steps":[],
                "inventory":[],"dtc_modes":[],"measurements":[]}
    body=_dict(body,"physical ECU result")
    _flags(body)
    completed=body.get("completed")
    stop=_get(body,"stop_reason")
    _require(type(completed) is bool and (completed==(stop is None)),"Inconsistent ECU completion")
    if row["status"]=="PASSED":
        _require(completed,"Successful ECU without completed read plan")
    steps=_normalize_steps(body.get("steps"))
    if not android:
        _require(_get(body,"declared_vin")==vin,"Physical ECU VIN constraint differs")
    first,last=_replay_plan(steps,android,vin,completed,stop)
    _require(_get(body,"observed_vin")==first,"Reported ECU VIN differs from response")
    # summarize_observations uses a request map; Android final VIN repeats
    # 0902 but only inventories, DTC modes and measurements are summarized.
    summary=summarize_observations(steps)
    if not android:
        for name in ("inventory","dtc_modes","measurements"):
            declared=_get(body,name)
            if declared is not None:
                _require(_equal(_normalize(declared),summary[name]),"Physical summary differs: "+name)
    extended=[]
    for step in steps:
        req=step["request_hex"]
        if req not in ("0908","090B","090D","090F") or step["status"]!="RAW":
            continue
        try:
            extended.append(can_info(int(req[2:],16),bytes.fromhex(step["response_hex"])))
        except ValueError:
            # A historical RAW row may be malformed under the newer decoder.
            # It remains raw, but no decoded projection is accepted.
            pass
    declared=_get(body,"extended_vehicle_info")
    if declared is not None:
        def canonical(record):
            record=_normalize(_dict(record,"extended Mode 09 record"))
            allowed={"info_type","values","general_denominator","ignition_cycles",
                     "counter_count","monitors","regulatory_verdict"}
            _require(set(record)<=allowed and record.get("regulatory_verdict") is None,
                     "Unsupported extended Mode 09 claim")
            kind=record.get("info_type")
            if kind in (8,11):
                _require(record.get("values",[])==[],"Counter record has unexpected identity values")
                monitors=[]
                for raw in _array(record.get("monitors"),9,"IPT monitor records"):
                    monitor=_dict(raw,"IPT monitor")
                    _require(set(monitor)<= {"monitor","name","numerator","denominator","ratio"} and
                             not ("name" in monitor and "monitor" in monitor),
                             "Unsupported IPT monitor fields")
                    monitors.append({"monitor":monitor.get("monitor",monitor.get("name")),
                                     "numerator":monitor.get("numerator"),
                                     "denominator":monitor.get("denominator"),"ratio":monitor.get("ratio")})
                return {"info_type":kind,"general_denominator":record.get("general_denominator"),
                        "ignition_cycles":record.get("ignition_cycles"),"counter_count":record.get("counter_count"),
                        "monitors":monitors,"regulatory_verdict":None}
            _require(kind in (13,15) and record.get("general_denominator") is None and
                     record.get("ignition_cycles") is None and record.get("counter_count") is None and
                     record.get("monitors") in (None,[]),"Unexpected identification fields")
            return {"info_type":kind,"values":record.get("values")}
        _require(_equal([canonical(x) for x in _array(declared,4,"extended Mode 09 records")],extended),
                 "Extended Mode 09 data differs from physical response")
    multi=multi_records(steps)
    claimed=_get(body,"multi_signal_records")
    catalog="NOT_REPORTED"
    if claimed is not None:
        normalized=_normalize(claimed)
        if _equal(normalized,multi):
            catalog="CURRENT"
        else:
            _require(_equal(normalized,historical_projection(multi)),
                     "Multi-signal records differ from physical responses")
            catalog="HISTORICAL_49"
    states=status_records(steps)
    claimed_states=_get(body,"status_records")
    if claimed_states is not None:
        _require(_equal(_normalize(claimed_states),states),"Categorical states differ from physical responses")
    return {"completed":completed,"observed_vin":first,"final_vin":last,
            "steps":steps,"extended_vehicle_info":extended,"multi_signal_records":multi,
            "multi_signal_report_catalog":catalog,"status_records":states,**summary}


def inspect_network(root,expected_vin=None):
    root=_unwrap(_dict(root,"network report"))
    _flags(root)
    android=root["schema"]=="camarodiag-j1979-network-v1"
    windows=not android and "profile" in root
    if android:
        protocol=root.get("protocol")
        _require(type(protocol) is int and protocol in range(6,10),"Invalid Android CAN profile")
        extended=protocol in (7,9)
        body=root
        discovery=root.get("discovery")
        if discovery and discovery.get("completed"):
            _require(_get(discovery,"observed_protocol")==protocol,"Android protocol differs")
    elif windows:
        profile=root.get("profile")
        _require(profile in (33,34,35,36),"Invalid Windows CAN profile")
        extended=profile in (34,36)
        discovery=root.get("discovery")
        body=root.get("network") or {}
        if body:
            _require(body.get("discovery")==discovery,"Duplicated discovery differs")
    else:
        addressing=root.get("addressing")
        _require(addressing in ("11BIT","29BIT"),"Invalid Linux CAN addressing")
        extended=addressing=="29BIT"
        discovery=root.get("discovery",root)
        body=root
    _require(discovery is not None,"No discovery evidence captured")
    if "extended" in discovery:
        _require(discovery["extended"] is extended,"Discovery addressing differs")
    d=replay_discovery(discovery,extended,android)
    selected=validate_targets([_address(x) for x in _array(_get(body,"selected_response_ids",[]),32,
                                                            "selected response IDs")],extended)
    vin=_get(body,"declared_vin")
    _require(vin is None or isinstance(vin,str) and re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}",vin),"Invalid declared VIN")
    if expected_vin is not None:
        _require(vin==expected_vin,"Declared VIN differs from expected VIN")
    if android:
        _require(vin is not None,"Android network VIN required")
    rows=_array(body.get("ecus") if android else _get(body,"ecu_reports",[]),32,"physical ECU reports")
    completed=root.get("completed")
    _require(type(completed) is bool,"Missing network completion")
    if completed:
        _require(d["completed"],"Network completed without verified discovery")
        _require(len(rows)==len(selected),"Missing selected ECU report")
        _require(set(selected)<=set(d["eligible_response_ids"]),"Selected ECU was not eligible")
    results=[]
    terminal=False
    for i,row in enumerate(rows):
        _require(i<len(selected),"Unselected physical ECU")
        row=_dict(row,"physical ECU")
        rx=_address(_get(row,"response_id") if android else _get(row,"rx_can_id"))
        tx=_address(_get(row,"request_id") if android else _get(row,"tx_can_id"))
        _require(rx==selected[i] and response_pair(rx,extended)==(tx,rx),"Physical ECU address differs from selection")
        _require(row.get("status") in ("PASSED","FAILED","NOT_TESTED"),"Invalid physical ECU status")
        evidence=replay_ecu(row,android,vin)
        if evidence["steps"]:
            _require(not terminal and set(selected)<=set(d["eligible_response_ids"]),
                     "Read attempted after failed selection or terminal ECU result")
        if not evidence["completed"]:
            reason=_get(row,"stop_reason",row.get("error"))
            if android or windows or reason in ("CANCELLED","BUDGET_EXHAUSTED","VIN_MISMATCH","VIN_UNVERIFIED","TRANSPORT_ERROR"):
                terminal=True
        results.append({"request_id":tx,"response_id":rx,"status":row["status"],**evidence})
    if completed:
        _require(all(r["completed"] for r in results),"Incomplete ECU in completed network")
    return {"schema":"camarodiag-j1979-network-inspection-v1","verified":True,"completed":completed,
            "addressing":"29BIT" if extended else "11BIT","declared_vin":vin,"discovery":d,
            "selected_response_ids":list(selected),"ecu_reports":results,"authenticated":False,
            "physical_vehicle_verified":False,"whole_vehicle_coverage_verified":False,
            "not_executed":["MODE_04_CLEAR","MODE_08_CONTROL"]}


def inspect_network_file(path,expected_vin=None):
    root,digest=load(path)
    return {**inspect_network(root,expected_vin),"source_sha256":digest}
