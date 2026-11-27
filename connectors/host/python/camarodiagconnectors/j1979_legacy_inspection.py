"""Offline consistency inspection of selected-source legacy J1979 campaigns.

This parser never opens a transport. The report cannot establish provenance,
ECU authentication, physical test results, or whole-vehicle coverage. J2534
driver data deliberately does not claim verification of the omitted checksum.
Anonymous ELM reports are not eligible for selected-source verification.
"""
from __future__ import annotations

from collections import defaultdict
import math
import re

from .j1979_legacy import allowed_read, PROTOCOLS
from .j1979_legacy_frames import response_frames
from .j1979_legacy_discovery import valid_source
from .j1979_campaign_evidence import decode_observation
from .j1979_multi_signal import decode_response as multi_decode, width as multi_width
from .j1979_status import decode_response as status_decode, width as status_width
from .j1979_scaling_context import project as contextual_project, config_data, CONFIG_PIDS
from .j1979_vehicle_info import legacy_info
from .j1979_live_inspection import load, _get, _require, _hex, _array, _dict, _id

MAX_STEPS = 4096
MAX_FRAMES = 128
COUNT_TYPES = {1,3,5,7,9,12,14}
VIN = re.compile(r"[A-HJ-NPR-Z0-9]{17}")
VARIANTS = {"Pwm":1,"Vpw":2,"Iso9141":3,"KwpSlow":4,"KwpFast":5}
SCHEMAS = {"camarodiag-functional-j1979-legacy-v1",
           "camarodiag-functional-j1979-legacy-source-v1"}


def _flag_false(obj, name):
    _require(_get(obj,name,False) is False, "Unsupported assertion: "+name)


def _source(value):
    _require(isinstance(value,str) and re.fullmatch(r"[0-9A-Fa-f]{2}",value),
             "Missing or invalid selected response source")
    result = int(value,16)
    _require(valid_source(result),"Reserved selected response source")
    return result


def _vin(value):
    _require(value is None or isinstance(value,str) and VIN.fullmatch(value),
             "Invalid declared VIN")
    return value


def _driver_frame(frame,protocol,source):
    frame=_dict(frame,"driver response")
    raw=_hex(_get(frame,"raw_hex"),11)
    payload=_hex(_get(frame,"payload_hex"),7)
    _require(len(raw)>=4 and len(payload) in range(1,8) and
             raw[2]==source and _source(frame.get("source"))==source,
             "Driver response source differs from selected ECU")
    if protocol in (4,5):
        _require(raw[0]&0xC0==0x80 and raw[1]==0xF1,"Invalid KWP response addressing")
        width=raw[0]&0x3F
        offset=3
        if width==0:
            _require(len(raw)>=5,"Missing explicit KWP length")
            width=raw[3]
            offset=4
        _require(width==len(payload) and raw[offset:]==payload,
                 "KWP driver payload or length differs from raw frame")
    else:
        _require(raw[:2]==bytes((0x41 if protocol==1 else 0x48,0x6B)) and raw[3:]==payload,
                 "Legacy driver payload differs from response frame")
    timestamp=_get(frame,"timestamp")
    _require(type(timestamp) is int and 0<=timestamp<=0xFFFFFFFF,"Invalid driver timestamp")
    if _get(frame,"in_frame_response_hex") is not None:
        _hex(_get(frame,"in_frame_response_hex"),12)
    return payload


def _tx_frame(req, protocol):
    prefix=bytes((0xC0|len(req),0x33,0xF1)) if protocol in (4,5) else \
        bytes((0x61 if protocol==1 else 0x68,0x6A,0xF1))
    return prefix+req


def _shape(req,payload):
    mode=req[0]
    if len(payload)>7 or not payload or payload[0]!=mode+0x40:
        return False
    if mode in (3,7,10):
        return len(payload)==7
    pid=req[1]
    if len(payload)<2 or payload[1]!=pid:
        return False
    if pid%32==0:
        offset=2 if mode==1 else 3
        filler={2:0,5:req[2] if mode==5 else None,6:0xFF,8:0,9:1}.get(mode)
        return len(payload)==offset+4 and (filler is None or payload[2]==filler) and \
            not (pid==0xE0 and payload[-1]&1)
    if mode in (1,2):
        evidence=decode_observation(req,payload)
        if evidence is None or evidence["shape"] not in ("PASSED","RAW"):
            return False
        if mode==1:
            if multi_width(pid) is not None and multi_decode(req,payload) is None:
                return False
            if status_width(pid) is not None and status_decode(req,payload) is None:
                return False
            if pid in CONFIG_PIDS and config_data(pid,payload[2:]) is None:
                return False
        return True
    if mode==5:
        return len(payload)==(4 if 1<=pid<=4 else 6) and payload[2]==req[2]
    if mode==6:
        return len(payload)==7
    if mode==9:
        return len(payload)==(3 if pid in COUNT_TYPES else 7) and payload[2]>0
    return False


def _single(req):
    mode=req[0]
    return mode in (1,2) or mode not in (3,7,10) and req[1]%32==0 or \
        mode==9 and req[1] in COUNT_TYPES


def _dtc(a,b):
    return f"{'PCBU'[a>>6]}{(a>>4)&3:X}{a&15:X}{b:02X}"


def _identity(kind,rows,counts):
    count=counts.get(7 if kind==11 else kind-1)
    _require(count is not None and len(rows)==count and
             [r[2] for r in rows]==list(range(1,count+1)), "Incomplete legacy identity sequence")
    data=b"".join(row[3:] for row in rows)
    if kind==2:
        _require(count==5 and data[:3]==bytes(3),"Invalid VIN fragment padding or count")
        try:
            value=data[3:].decode("ascii")
        except UnicodeError as exc:
            raise ValueError("Invalid VIN encoding") from exc
        _require(VIN.fullmatch(value),"Invalid VIN encoding")
        return {"info_type":kind,"values":[value]}
    if kind==4:
        _require(count%4==0,"Invalid CALID fragment count")
        fields=[data[i:i+16].rstrip(b" \x00") for i in range(0,len(data),16)]
        _require(all(f and all(32<=c<=126 for c in f) for f in fields),"Invalid CALID data")
        return {"info_type":kind,"values":[f.decode("ascii") for f in fields]}
    if kind==6:
        return {"info_type":kind,"values":[r[3:].hex().upper() for r in rows]}
    if kind in (8,10,11,13,15):
        return legacy_info(kind,count,rows)
    return None


def _unwrap(root):
    _require(root.get("schema") in SCHEMAS,"Unsupported selected-source legacy schema")
    driver="result" in root
    body=_dict(root.get("result") if driver else root,"legacy report")
    if driver:
        protocol=VARIANTS.get(root.get("variant"))
        _require(protocol is not None,"Unknown J2534 legacy variant")
        source=_source(_get(root,"selected_response_source"))
        _require(_source(_get(body,"selected_response_source"))==source,
                 "Conflicting selected response sources")
        baud=_get(root,"baud")
        _require(baud==(41600 if protocol==1 else 10400),"Legacy J2534 baud differs from variant")
        # Driver's protocol and initialization are recorded, not authenticated.
        observed=protocol if _get(body,"transport_observed") is True else None
        initialization=_get(root,"initialization_evidence")
        if observed is not None and protocol in (3,4):
            _require(len(_hex(initialization,2))==2,"Missing driver initialization keywords")
        if observed is not None and protocol==5:
            raw=_hex(initialization,10)
            _require(len(raw)==6 and raw[:3]==bytes((0x83,0xF1,source)) and raw[3]==0xC1,
                     "Invalid fast initialization evidence")
    else:
        protocol=_get(body,"expected_protocol")
        _require(type(protocol) is int and protocol in PROTOCOLS,"Invalid ELM protocol")
        source=_source(_get(body,"selected_response_source"))
        observed=_get(body,"observed_protocol")
        _require(observed is None or type(observed) is int and observed==protocol,
                 "Reported protocol differs from selected legacy profile")
        if _get(body,"raw_protocol_response") is not None and observed is not None:
            raw=_get(body,"raw_protocol_response")
            _require(isinstance(raw,str) and len(raw)<=4096,"Invalid protocol probe")
            lines=[s.strip().upper() for s in raw.replace(">","\n").splitlines()
                   if s.strip() and s.strip().upper()!="ATDPN"]
            _require(lines==[str(protocol)],"Fixed protocol probe differs")
    _flag_false(root,"physical_vehicle_verified")
    _flag_false(body,"whole_vehicle_coverage_verified")
    return body,protocol,source,driver,observed


def _records(step,req,protocol,source,driver):
    status=step.get("status")
    _require(status in ("PASSED","RAW","NOT_TESTED","FAILED","TIMEOUT","NEGATIVE"),
             "Unknown legacy status")
    command=req.hex().upper()
    if driver:
        tx=_get(step,"tx_hex")
        if tx is not None:
            _require(_hex(tx,10)==_tx_frame(req,protocol),"Transmitted legacy frame differs from request")
        frames=_array(step.get("responses"),MAX_FRAMES,"driver responses")
        rows=[_driver_frame(f,protocol,source) for f in frames]
        _require(not rows or tx is not None,"Driver response without request evidence")
        return rows,rows if status in ("PASSED","RAW") else [],set()
    raw=_get(step,"raw_response")
    frames=_array(step.get("frames",[]),MAX_FRAMES,"legacy ELM frames")
    claimed=_get(step,"accepted_records",_get(step,"records_hex",[]))
    accepted=[_hex(f,7) for f in _array(claimed,MAX_FRAMES,"accepted records")]
    parsed=[]
    if raw is not None:
        _require(isinstance(raw,str) and len(raw)<=256*1024,"Invalid raw adapter response")
        try:
            parsed=response_frames(protocol,command,raw)
        except ValueError:
            _require(status not in ("PASSED","RAW") and not accepted and not frames,
                     "Malformed original adapter response accepted")
            return [],[],set()
    # A late response may have been preserved without processing its frames.
    late=step.get("detail")=="LATE_RESPONSE" and status=="NOT_TESTED" and not frames
    if not late:
        _require(len(parsed)==len(frames),"Raw adapter response differs from saved frame count")
        for actual,claimed_frame in zip(parsed,frames):
            _require(_source(claimed_frame.get("source"))==actual.source and
                     _hex(_get(claimed_frame,"raw_hex"),11)==actual.raw and
                     _hex(_get(claimed_frame,"payload_hex"),7)==actual.payload,
                     "Original frame differs from saved frame evidence")
    rows=[f.payload for f in parsed if f.source==source]
    if status in ("PASSED","RAW"):
        _require(rows==accepted,"Accepted records differ from selected response source")
    else:
        # Some historical failed identity steps retain their parsed prefix.
        _require(not accepted or accepted==rows,"Failed step claims an unrelated payload")
        accepted=[]
    return rows,accepted,{f.source for f in parsed if f.source!=source}


def inspect(root,*,expected_vin=None):
    body,protocol,source,driver,observed=_unwrap(_dict(root,"report"))
    declared=_vin(_get(body,"declared_vin"))
    expected_vin=_vin(expected_vin)
    if expected_vin is not None:
        _require(declared is None or expected_vin==declared,"Requested VIN differs from report")
    scope=declared or expected_vin
    source_hex=f"{source:02X}"
    rows=_array(body.get("steps"),MAX_STEPS,"legacy steps")
    completed=body.get("completed")
    _require(type(completed) is bool,"Missing completion state")
    stop=_get(body,"stop_reason")
    _require((completed and stop is None) or (not completed and isinstance(stop,str) and stop),
             "Completion and stop reason disagree")
    duration=_get(body,"duration_ms")
    _require(type(duration) is int and 0<=duration<=600000+10000,"Invalid campaign duration")
    chains={}
    seen=set()
    other=set()
    normalized=[]
    identities=[]
    counts={}
    observed_vin=None
    dtcs={}
    measurements=[]
    multis=[]
    states=[]
    monitors=[]
    sensor_masks=set()
    freeze=None
    elapsed=0

    for index,step in enumerate(rows):
        step=_dict(step,"legacy step")
        req=_hex(_get(step,"request_hex"),7)
        command=req.hex().upper()
        _require(allowed_read(command) and req not in seen,"Unexpected, duplicate or mutating request")
        seen.add(req)
        mode=req[0]
        pid=req[1] if len(req)>1 else None
        _require(index!=0 or req==b"\x01\x00","Missing initial PID00")
        if index>0:
            _require(normalized[0]["accepted_records"] and observed==protocol,
                     "Requests after unverified initial transport")
        if scope is not None and mode!=9 and index>0:
            _require(observed_vin==scope,"Requests before selected ECU VIN verification")
        elapsed_step=_get(step,"duration_ms")
        _require(type(elapsed_step) is int and 0<=elapsed_step<=duration+2,"Invalid step duration")
        elapsed+=elapsed_step
        raw,accepted,others=_records(step,req,protocol,source,driver)
        other.update(others)
        status=step["status"]
        if status in ("PASSED","RAW"):
            _require(accepted and all(_shape(req,r) for r in accepted),
                     "Invalid positive legacy response shape")
            _require(not _single(req) or len(accepted)==1,"Ambiguous selected-source response")
        sensor=req[2] if mode==5 else None
        key=(mode,sensor)
        if mode==5:
            _require(sensor in sensor_masks,"Oxygen monitor requested without sensor support")
        if mode==2 and pid!=2:
            _require(freeze is not None and freeze["present"],"Freeze data without trigger")
        if pid is not None and pid%32==0:
            chain=chains.setdefault(key,{"mode":mode,"sensor_mask":sensor,
                "ids":set(),"next":0,"pages":0,"complete":False})
            _require(chain["next"]==pid and not chain["complete"],
                     "Unadvertised continuation page")
            chain["next"]=None
            if accepted:
                data=accepted[0][-4:]
                chain["ids"].update(pid+bit+1 for bit in range(31)
                    if data[bit//8]&(0x80>>(bit%8)))
                chain["pages"]+=1
                chain["complete"]=not bool(data[-1]&1)
                if not chain["complete"]:
                    chain["next"]=pid+32
        elif pid is not None and not (mode==2 and pid==2):
            _require(pid in chains.get(key,{}).get("ids",set()),
                     "Request absent from verified support inventory")
        if accepted:
            if mode==9 and pid%32:
                if pid in COUNT_TYPES:
                    counts[pid]=accepted[0][2]
                else:
                    identity=_identity(pid,accepted,counts)
                    if identity is not None:
                        identities.append(identity)
                        if pid==2:
                            observed_vin=identity["values"][0]
            elif mode in (3,7,10):
                dtcs[mode]=sorted({_dtc(a,b) for row in accepted
                                    for a,b in zip(row[1::2],row[2::2]) if a or b})
            elif mode in (1,2) and pid%32:
                payload=accepted[0]
                ev=decode_observation(req,payload)
                if ev and ev.get("measurement") is not None:
                    measurements.append(ev["measurement"])
                values=multi_decode(req,payload)
                if values is not None:
                    multis.append({"request_hex":command,"signals":values})
                categorical=status_decode(req,payload)
                if categorical is not None:
                    states.append({"request_hex":command,"states":categorical})
                if mode==1 and pid in (0x13,0x1D):
                    sensor_masks.update(1<<bit for bit in range(8) if payload[2]&(1<<bit))
                if mode==2 and pid==2:
                    freeze={"present":bool(payload[3] or payload[4]),
                            "dtc":_dtc(payload[3],payload[4]) if payload[3] or payload[4] else None}
            elif mode in (5,6) and pid%32:
                for n,payload in enumerate(accepted):
                    record={"mode":mode,"test_id":pid,"sensor_mask":sensor,
                            "response_index":n,"request_hex":command,
                            "encoded_data_hex":payload[3 if mode==5 else 2:].hex().upper()}
                    if mode==6:
                        record.update(component_and_limit_encoded=payload[2],
                                      value_encoded=int.from_bytes(payload[3:5],"big"),
                                      limit_encoded=int.from_bytes(payload[5:7],"big"))
                    monitors.append(record)
        normalized.append({"request_hex":command,"status":status,
                           "accepted_records":[r.hex().upper() for r in accepted],
                           "response_hex":accepted[0].hex().upper() if len(accepted)==1 else None,
                           "duration_ms":elapsed_step})
    _require(elapsed<=duration+2*len(rows),"Step durations exceed campaign duration")
    _require(_get(body,"observed_vin")==observed_vin,"Reported VIN differs from reassembled response")
    match="NOT_REQUESTED" if declared is None else "UNVERIFIED" if observed_vin is None else \
        "MATCH" if observed_vin==declared else "MISMATCH"
    _require(_get(body,"vin_match",match)==match,"Reported VIN match differs from evidence")
    if completed:
        _require(observed==protocol and normalized and normalized[0]["accepted_records"] and
                 (scope is None or scope==observed_vin),"Completion without verified admission")
        for mode in (1,6,8,9):
            _require((mode,None) in chains,"Completed campaign omitted inventory")
        _require(all(bytes((m,)) in seen for m in (3,7,10)) and b"\x02\x02\x00" in seen,
                 "Completed campaign omitted DTC or freeze trigger")
        for (mode,mask),chain in chains.items():
            _require(chain["next"] is None or chain["complete"],
                     "Completed campaign omitted advertised continuation page")
            if mode==8:
                continue
            for pid in chain["ids"]:
                if mode==2 and pid==2:
                    continue
                command=bytes((mode,pid,0)) if mode==2 else bytes((5,pid,mask)) if mode==5 else bytes((mode,pid))
                _require(command in seen,"Completed campaign omitted advertised identifier")
        if freeze and freeze["present"]:
            _require((2,None) in chains,"Completed campaign omitted freeze inventory")
        _require(all((5,mask) in chains for mask in sensor_masks),"Completed campaign omitted oxygen inventory")
    inventories=[{"mode":mode,"sensor_mask":sensor,"state":"COMPLETE" if c["complete"]
                  else "PARTIAL" if c["pages"] else "UNAVAILABLE",
                  "verified_pages":c["pages"],"advertised_ids":sorted(c["ids"])}
                 for (mode,sensor),c in chains.items()]
    if not driver:
        claimed_other=_get(body,"other_response_sources",[])
        _require(sorted(_source(v) for v in _array(claimed_other,254,"other sources"))==sorted(other),
                 "Other responders differ from observed frames")
    return {"schema":"camarodiag-j1979-legacy-inspection-v1","verified":True,
            "completed":completed,"stop_reason":stop,"protocol":protocol,
            "protocol_name":PROTOCOLS[protocol],"selected_response_source":source_hex,
            "link_checksum_verified":not driver,"physically_addressed":False,
            "evidence_authenticated":False,"physical_vehicle_verified":False,
            "whole_vehicle_coverage_verified":False,"observed_vin":observed_vin,
            "declared_vin":declared,"vin_match":match,"steps":normalized,
            "other_response_sources":[f"{s:02X}" for s in sorted(other)],
            "inventory":inventories,"identification":identities,
            "dtc_modes":[{"mode":m,"codes":dtcs.get(m)} for m in (3,7,10)],
            "measurements":measurements,"multi_signal_records":multis,"status_records":states,
            "contextual_measurements":contextual_project(normalized),
            "freeze_trigger":freeze,"monitor_records":monitors}


def inspect_file(path,*,expected_vin=None):
    root,digest=load(path)
    return {**inspect(root,expected_vin=expected_vin),"source_sha256":digest}
