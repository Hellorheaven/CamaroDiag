"""Offline replay of numeric J1979 live exports from the three hosts.

Verification is internal consistency, not an authenticated vehicle capture.
Never open a serial port, socket, adapter or ECU from this module.
"""
from __future__ import annotations

import base64
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

from .j1979_campaign_evidence import decode_observation
from .j1979_live import Plan, numeric_pid, legacy_numeric_pid, raw_sample
from .j1979_multi_signal import width as multi_width, decode_response as multi_decode
from .j1979_scaling_context import dependency, context_from_steps, config_data
from .j1979_drive_cycle import TRIMS, trim_layout, live_signals as trim_signals
from .j1979 import physical_pair
from .j1979_legacy_discovery import valid_source
from .j1979_legacy_frames import checksum

MAX_REPORT_BYTES = 64 * 1024 * 1024
MAX_ROWS = 10000
SCHEMAS = {"camarodiag-j1979-live-v1", "camarodiag-j1979-legacy-live-v1"}


def _get(row, name, default=None):
    camel = re.sub(r"_([a-z])", lambda m: m[1].upper(), name)
    return row.get(name, row.get(camel, default))


def _require(condition, detail):
    if not condition:
        raise ValueError(detail)


def _integer(value, low, high, name):
    _require(type(value) is int and low <= value <= high, "Invalid " + name)
    return value


def _hex(value, maximum=4095):
    _require(isinstance(value, str) and len(value) <= 2*maximum and
             re.fullmatch(r"(?:[0-9A-Fa-f]{2})*", value) is not None, "Invalid hexadecimal evidence")
    return bytes.fromhex(value)


def _id(value):
    _require(isinstance(value, str) and re.fullmatch(r"[0-9A-Fa-f]{1,8}", value), "Invalid channel ID")
    return int(value, 16)


def _array(value, bound, name):
    _require(isinstance(value, list) and len(value) <= bound, "Invalid " + name)
    return value


def _dict(value, name):
    _require(isinstance(value, dict), "Invalid " + name)
    return value


def _same_number(a, b):
    return type(a) in (int, float) and math.isfinite(a) and math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-9)


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def load(path):
    with Path(path).open("rb") as stream:
        raw = stream.read(MAX_REPORT_BYTES + 1)
    _require(len(raw) <= MAX_REPORT_BYTES, "Live report exceeds size bound")
    try:
        obj = json.loads(raw, object_pairs_hook=_pairs,
                         parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON number")))
    except (UnicodeError, RecursionError) as exc:
        raise ValueError("Invalid JSON report") from exc
    return _dict(obj, "report"), hashlib.sha256(raw).hexdigest()


def _unwrap(root):
    # Windows wraps Result; Android and Python export a direct result.
    if root.get("schema") in SCHEMAS:
        return root, root.get("result", root)
    for key in ("j1979Live", "j1979_live", "liveReport", "live"):
        candidate = root.get(key)
        if isinstance(candidate, dict) and candidate.get("schema") in SCHEMAS:
            return candidate, candidate.get("result", candidate)
    raise ValueError("Unsupported live evidence schema")


def _pids(value):
    # System.Text.Json serializes byte[] as base64, not as JSON numbers.
    if isinstance(value, str):
        try:
            value = list(base64.b64decode(value, validate=True))
        except ValueError as exc:
            raise ValueError("Invalid Windows selected PIDs") from exc
    return _array(value, 32, "selected PIDs")


def _frame_payload(frame, protocol, source, driver):
    frame = _dict(frame, "legacy frame")
    raw = _hex(_get(frame, "raw_hex"), 11)
    payload = _hex(_get(frame, "payload_hex"), 7)
    _require(1 <= len(payload) <= 7 and len(raw) == 3+len(payload)+(not driver),
             "Legacy frame length differs from payload")
    address = raw[2]
    _require(valid_source(address) and _id(frame.get("source")) == address, "Legacy source differs from header")
    if protocol in (4, 5):
        _require(raw[0]&0xC0 == 0x80 and raw[1] == 0xF1 and raw[0]&0x3F == len(payload),
                 "Invalid KWP response header")
    else:
        _require(raw[:2] == bytes((0x41 if protocol == 1 else 0x48, 0x6B)), "Invalid legacy response header")
    if driver:
        _require(raw[3:] == payload and address == source, "J2534 source/payload mismatch")
    else:
        _require(raw[3:-1] == payload and checksum(protocol, raw[:-1]) == raw[-1],
                 "Legacy frame checksum or payload mismatch")
    return address, payload


def _legacy_groups(root, protocol, source, driver):
    groups = defaultdict(list)
    previous = -1
    traces = _array(_get(root, "transport_exchanges"), MAX_ROWS+256, "legacy transport exchanges")
    for trace in traces:
        trace = _dict(trace, "legacy exchange")
        index = _integer(_get(trace, "logical_query"), 0, MAX_ROWS+256, "logical query")
        _require(index >= previous and index <= previous+1, "Missing or reordered legacy exchange")
        previous = index
        request = _get(trace, "request_hex")
        _require(request == "ATDPN" or isinstance(request,str) and
                 re.fullmatch(r"01[0-9A-F]{2}|090[012]",request), "Unexpected legacy command")
        frames = _array(trace.get("frames"), 128, "legacy frames")
        selected = []
        for frame in frames:
            address,payload = _frame_payload(frame,protocol,source,driver)
            if address == source:
                selected.append(payload)
        declared = _get(trace,"selected_records_hex")
        if declared is not None:
            _require([_hex(x,7) for x in _array(declared,128,"selected records")] == selected,
                     "Selected records disagree with frames")
        if request == "ATDPN":
            _require(not frames, "Protocol probe cannot contain application frames")
        groups[index].append((request,selected,trace.get("status"),trace))
    return groups


def _match_legacy(group, request, response, status, protocol, driver):
    _require(group, "Missing legacy transport evidence")
    commands = [item[0] for item in group]
    if request != b"\x09\x02":
        _require(commands == [request.hex().upper()], "Unexpected legacy sample exchange sequence")
        if status in ("PASSED","RAW","NEGATIVE"):
            _require(group[0][1] == [response] and group[0][2] in ("CORRELATED","PASSED","NEGATIVE"),
                     "Normalized sample does not match selected source")
        return
    expected = ["0100"] + ([] if driver else ["ATDPN"]) + ["0900","0901","0902"]
    _require(commands == expected[:len(commands)], "Unexpected legacy VIN exchange sequence")
    if status != "PASSED":
        if status == "NEGATIVE":
            _require(group[-1][1] == [response], "Normalized negative response differs")
        return
    _require(commands == expected, "Incomplete legacy VIN evidence")
    initial = group[0][1]
    _require(len(initial)==1 and decode_observation(b"\x01\x00",initial[0]) is not None and
             initial[0][:2]==b"\x41\x00", "Missing verified legacy initial PID00")
    if not driver:
        probe = group[1][3]
        observed = _get(probe,"observed_protocol")
        if observed is None:
            raw = _get(probe,"raw_response")
            _require(isinstance(raw,str), "Missing legacy fixed protocol evidence")
            lines = [x.strip().upper() for x in raw.replace(">","\n").splitlines()
                     if x.strip() and x.strip().upper()!="ATDPN"]
            _require(lines==[str(protocol)], "Legacy protocol mismatch")
        else:
            _require(observed==protocol,"Legacy protocol mismatch")
        _require(group[1][2]=="PASSED","Legacy protocol probe did not pass")
    page,count,fragments = [item[1] for item in group[-3:]]
    _require(len(page)==1 and len(page[0])==7 and page[0][:3]==b"\x49\x00\x01"
             and page[0][3]&0xC0==0xC0 and count==[b"\x49\x01\x05"], "Missing legacy VIN inventory or count")
    _require(len(fragments)==5 and all(len(x)==7 and x[:3]==bytes((0x49,2,i))
             for i,x in enumerate(fragments,1)), "Missing or reordered VIN fragments")
    data=b"".join(x[3:] for x in fragments)
    _require(data[:3]==bytes(3) and response==b"\x49\x02\x01"+data[3:],
             "Normalized VIN differs from original fragments")


def inspect(root, *, expected_vin=None):
    """Replay application data, admission, chronology and summary.

    Complete means all requested cycles and final identity are documented.
    Partial but internally consistent evidence stays distinct from completion.
    """
    root, result = _unwrap(_dict(root, "report"))
    _require(isinstance(result,dict), "No captured observations (transport setup failed)")
    schema=root["schema"]
    legacy=schema.endswith("legacy-live-v1")
    driver= "legacyVariant" in root and root.get("legacyVariant") is not None
    vin=_get(result,"declared_vin")
    pids=_pids(_get(result,"selected_pids"))
    cycles=_integer(_get(result,"requested_cycles"),1,1000,"requested cycles")
    interval=_integer(_get(result,"interval_ms"),100,60000,"interval")
    budget=_integer(_get(result,"budget_ms"),1,600000,"budget")
    raw_only=_get(result,"raw_only",False)
    _require(type(raw_only) is bool,"Invalid raw-only selection")
    Plan(tuple(pids),vin,cycles,interval/1000,budget/1000,raw_only).validate()
    if expected_vin is not None:
        _require(vin==expected_vin,"Declared VIN differs from expected VIN")
    for obj in (root,result):
        for key in ("physical_vehicle_verified","whole_vehicle_coverage_verified"):
            _require(_get(obj,key,False) is False,"Unsupported physical verification claim")
    groups={}
    protocol=None
    if legacy:
        _require(raw_only or all(legacy_numeric_pid(pid) for pid in pids),"PID exceeds legacy application width")
        _require(_get(root,"physically_addressed",False) is False,"Legacy cannot claim physical addressing")
        source=_id(_get(root,"selected_response_source"))
        _require(valid_source(source),"Invalid selected legacy source")
        protocol=_get(root,"expected_protocol",root.get("profile"))
        _integer(protocol,1,5,"legacy protocol")
        groups=_legacy_groups(root,protocol,source,driver)
        channel={"kind":"legacy","protocol":protocol,"source":source,
                 "link_checksum_verified":not driver}
    else:
        tx=_id(_get(root,"tx_can_id"))
        rx=_id(_get(root,"rx_can_id"))
        _require(physical_pair(tx,rx),"Invalid physical CAN channel pair")
        channel={"kind":"can","tx":tx,"rx":rx}
    rows=_array(result.get("observations"),MAX_ROWS,"observations")
    admissions=_array(result.get("admission"),256,"admission")
    required_configs=[] if raw_only else sorted({dependency(pid) for pid in pids if dependency(pid) is not None})
    claimed_scaling=_get(result,"scaling_configuration")
    scoped_scaling=claimed_scaling is not None
    _require(not scoped_scaling or bool(required_configs),"Unexpected scaling configuration")
    claimed_layout=_get(result,"trim_layout")
    contextual_trims=claimed_layout is not None
    _require(not contextual_trims or not raw_only and any(pid in TRIMS for pid in pids),"Unexpected trim layout")
    _require(raw_only or contextual_trims or not any(0x55<=pid<=0x58 for pid in pids),"Missing secondary trim layout")
    layout_rows=[]
    layout_attempted=False
    scale_rows=[]
    config_requests=[]
    combined=[]
    for group,phase_name in ((admissions,"admission"),(rows,"sample")):
        previous=-1
        for index,row in enumerate(group):
            row=_dict(row,"observation")
            start=_integer(_get(row,"started_elapsed_ms"),0,2**31-1,"start time")
            end=_integer(_get(row,"elapsed_ms"),start,2**31-1,"end time")
            duration=_integer(_get(row,"duration_ms"),0,2**31-1,"duration")
            _require(abs(duration-(end-start))<=2,"Inconsistent observation duration")
            _require(start>=previous,"Reordered observations")
            previous=end
            combined.append((start,end,phase_name,index,row))
    # Timestamp ties at millisecond resolution are legal. Admission ordering
    # disambiguates initial/inventory versus first samples and final versus last.
    def order(item):
        phase=item[4].get("phase")
        priority=0 if phase in ("INITIAL_VIN","INVENTORY","PERIODIC_VIN","SCALING","TRIM_LAYOUT") else 2 if phase=="FINAL_VIN" else 1
        return item[0],priority,item[1],item[3]
    combined.sort(key=order)
    advertised=set()
    next_page=0
    pages=[]
    inventory_complete=False
    identity=None
    initial=None
    final=False
    identity_count=0
    counts={pid:defaultdict(int) for pid in pids}
    samples={pid:[] for pid in pids}
    multi_samples=defaultdict(list)
    clean=[]
    sample_index=0
    last_end=0
    last_start=None
    last_identity=None
    terminal=False
    raw_bytes=0
    for index,(start,end,group,_,row) in enumerate(combined):
        _require(start>=last_end,"Overlapping sequential exchanges")
        last_end=end
        phase=row.get("phase")
        status=row.get("status")
        _require(status in ("PASSED","RAW","NEGATIVE","TIMEOUT","FAILED","NOT_TESTED"),"Unknown live status")
        req=_hex(_get(row,"request_hex"),2)
        raw=_get(row,"response_hex")
        res=None if raw is None else _hex(raw)
        raw_bytes+=len(res) if res is not None else 0
        if raw_only:_require(raw_bytes<=16*1024*1024,"Raw live payload evidence exceeds bound")
        ev=(raw_sample(req,res,legacy=legacy) if raw_only and group=="sample" else decode_observation(req,res)) if res is not None else None
        if status in ("PASSED","RAW","NEGATIVE"):
            _require(ev is not None and ev["shape"]==status,"Response and status do not correlate")
            _require(end<=budget+1,"Successful response beyond budget")
        value=row.get("measurement")
        declared_multi=_get(row,"multi_signals")
        if group!="sample":
            _require((status!="RAW" or phase=="SCALING" and scoped_scaling) and declared_multi in (None,[]),"Admission cannot carry multi-channel samples")
        if legacy:
            _match_legacy(groups.get(index),req,res,status,protocol,driver)
        _require(not terminal,"Observation after terminal condition")
        if group=="sample":
            _require(phase=="SAMPLE" and identity==vin and set(pids)<=advertised and not final,
                     "Sampling without verified VIN and supported PIDs")
            cycle=sample_index//len(pids)+1
            pid=pids[sample_index%len(pids)]
            _require(cycle<=cycles and row.get("cycle")==cycle and row.get("pid")==pid and req==bytes((1,pid)),
                     "Unexpected sample order or PID")
            if sample_index%len(pids)==0:
                if last_start is not None:
                    _require(start+2>=last_start+interval,"Cycle starts faster than configured interval")
                _require(last_identity is not None and start-last_identity<=30000+2,
                         "Missing periodic identity check")
                last_start=start
            context=context_from_steps(scale_rows) if scoped_scaling else None
            if contextual_trims:
                _require(0x1D not in advertised or layout_attempted,"Sampling before trim-layout admission")
            if scoped_scaling:
                _require(all(context.get(c).state!="UNVERIFIED" for c in required_configs),
                         "Sampling before ECU scaling admission")
            counts[pid][status]+=1
            measured=ev.get("measurement") if status=="PASSED" else None
            signals=None
            if raw_only:
                _require(value is None and declared_multi in (None,[]),
                         "Raw-only sample cannot carry interpreted values")
                _require(status!="PASSED", "Raw-only response cannot claim decoded measurement")
                if status=="RAW":
                    _require(row.get("detail")=="RAW_UNINTERPRETED","Missing raw interpretation boundary")
                measured=None
            elif (multi_width(pid) is not None or contextual_trims and pid in TRIMS) and status in ("RAW","PASSED"):
                signals=trim_signals(req,res,trim_layout(scale_rows+layout_rows)) if contextual_trims and pid in TRIMS else multi_decode(req,res)
                _require(signals is not None and value is None,"Invalid multi-signal response")
                if context is not None and dependency(pid) is not None:
                    scaled=context.decode(req,res)
                    _require(scaled is not None and len(scaled["signals"])==len(signals), "Invalid contextual signal response")
                    signals=[{**s,"number":c["number"]} for s,c in zip(signals,scaled["signals"])]
                claimed=_array(declared_multi,10,"multi-signal values")
                _require(len(claimed)==len(signals),"Missing or extra signal values")
                for raw,signal in zip(claimed,signals):
                    raw=_dict(raw,"multi-signal value")
                    _require(raw.get("pid")==pid and raw.get("name")==signal["name"] and
                             raw.get("unit")==signal["unit"] and
                             type(_get(raw,"raw_encoded")) is int and
                             _get(raw,"raw_encoded")==signal["raw_encoded"] and
                             _same_number(raw.get("number"),signal["number"]),"Multi-signal value differs from raw bytes")
                    multi_samples[(pid,signal["name"])].append((signal["number"],end,signal["unit"]))
                measured=None
            elif status=="PASSED":
                _require(declared_multi in (None,[]),"Single measurement carries unexpected signal vector")
                _require(measured is not None and isinstance(value,dict),"Missing numeric measurement")
                if context is not None and dependency(pid) is not None:
                    scaled=context.decode(req,res)
                    _require(scaled is not None,"Invalid contextual scalar response")
                    measured={**measured,"number":scaled["signals"][0]["number"]}
                _require(value.get("name")==measured["name"] and value.get("unit")==measured["unit"]
                         and _same_number(value.get("number"),measured["number"]),"Measurement differs from raw response")
                samples[pid].append((measured["number"],end,measured["name"],measured["unit"]))
            else:
                _require(status!="RAW" and declared_multi in (None,[]) and value is None,
                         "Failed or missing response cannot carry a measurement")
            clean.append({"cycle":cycle,"pid":pid,"elapsed_ms":end,"status":status,
                          "number":measured["number"] if measured else None,
                          "unit":measured["unit"] if measured else None,"multi_signals":signals,
                          **({"response_hex":res.hex().upper() if res is not None else None} if raw_only else {})})
            sample_index+=1
        elif phase in ("INITIAL_VIN","PERIODIC_VIN","FINAL_VIN"):
            _require(req==b"\x09\x02" and value is None,"Unexpected identity request")
            if phase=="INITIAL_VIN":
                _require(index==0 and identity_count==0,"Duplicate or late initial VIN")
            else:
                _require(identity==vin and set(pids)<=advertised and sample_index%len(pids)==0 and not final,
                         "Misplaced identity recheck")
                if phase=="FINAL_VIN":
                    _require(sample_index==cycles*len(pids),"Final VIN before requested cycles")
                    final=True
            identity=ev.get("observed_vin") if status=="PASSED" else None
            if phase=="INITIAL_VIN":
                initial=identity
            identity_count+=1
            if identity!=vin:
                terminal=True
            else:
                last_identity=end
        elif phase=="INVENTORY":
            _require(identity==vin and not sample_index and not final and not inventory_complete and value is None
                     and req==bytes((1,next_page)),"Unexpected inventory page")
            _require(not config_requests and not layout_attempted,"Inventory after contextual admission")
            scale_rows.append({"request_hex":req.hex(),"response_hex":res.hex() if res is not None else None,"status":status})
            if status=="PASSED":
                page=ev.get("supported_page")
                _require(page is not None,"Missing support bitmap")
                pages.append(page)
                advertised.update(page["supported_ids"])
                inventory_complete=not page["has_next_page"]
                next_page+=32
            else:
                terminal=True
        elif phase=="TRIM_LAYOUT":
            _require(contextual_trims and not layout_attempted and identity==vin and not sample_index and not final and
                     set(pids)<=advertised and 0x1D in advertised and req==bytes((1,0x1D)) and value is None,
                     "Unexpected trim-layout admission")
            layout_attempted=True
            layout_rows.append({"request_hex":"011D","response_hex":res.hex() if res is not None else None,"status":status})
        elif phase=="SCALING":
            _require(scoped_scaling and not layout_attempted and identity==vin and not sample_index and not final and
                     set(pids)<=advertised and value is None and len(req)==2 and req[0]==1 and
                     req[1] in required_configs and req[1] in advertised and req[1] not in config_requests,
                     "Unexpected ECU scaling request")
            _require(not config_requests or config_requests[-1]<req[1],"Reordered scaling configuration")
            config_requests.append(req[1])
            if status in ("RAW","PASSED"):
                _require(config_data(req[1],res[2:]) is not None,"Malformed ECU scaling record")
            scale_rows.append({"request_hex":req.hex(),"response_hex":res.hex() if res is not None else None,"status":status})
        else:
            raise ValueError("Unknown admission phase")
        if status in ("FAILED","NOT_TESTED"):
            terminal=True
    derived_layout=trim_layout(scale_rows+layout_rows) if contextual_trims else None
    if contextual_trims:
        declared=_dict(claimed_layout,"trim layout")
        _require(declared.get("state")==derived_layout["state"] and
                 _get(declared,"raw_encoded")==derived_layout["raw_encoded"] and
                 (derived_layout["raw_encoded"] is None or type(_get(declared,"raw_encoded")) is int),
                 "Trim layout differs from admission bytes")
    derived_scaling=context_from_steps(scale_rows).report() if scoped_scaling else None
    if scoped_scaling:
        claimed=_array(claimed_scaling,2,"scaling configuration")
        _require(len(claimed)==2,"Missing scaling configuration")
        for actual,wanted in zip(claimed,derived_scaling):
            _require(isinstance(actual,dict) and actual.get("pid")==wanted["pid"] and
                     actual.get("state")==wanted["state"] and _get(actual,"data_hex")==wanted["data_hex"],
                     "Scaling configuration differs from raw admission")
    _require(not legacy or len(groups)==len(combined),"Extra legacy logical exchanges")
    _require(_get(result,"observed_vin")==initial,"Reported initial VIN differs from evidence")
    _require(sorted(_get(result,"advertised_pids",[]))==sorted(advertised),"Advertised PIDs differ from bitmaps")
    _require(_get(result,"inventory_complete") is inventory_complete,"Inventory completion differs")
    duration=_integer(_get(result,"duration_ms"),0,2**31-1,"report duration")
    _require(duration+2>=last_end,"Report ends before its observations")
    completed=_get(result,"completed")
    _require(type(completed) is bool,"Missing completion status")
    recorded_cycles=_integer(_get(result,"completed_cycles"),0,cycles,"completed cycles")
    possible_cycles=sample_index//len(pids)
    _require(recorded_cycles==possible_cycles or not completed and terminal and
             sample_index%len(pids)==0 and recorded_cycles==possible_cycles-1,"Completed cycles differ")
    summaries=[]
    declared_summaries=_array(result.get("summary"),32,"summary")
    _require([row.get("pid") for row in declared_summaries]==pids,"Missing or reordered PID summary")
    for pid,declared in zip(pids,declared_summaries):
        seq=samples[pid]
        values=[x[0] for x in seq]
        summary={"pid":pid,"attempted":sum(counts[pid].values()),"status_counts":dict(counts[pid]),
                 "name":seq[-1][2] if seq else None,"unit":seq[-1][3] if seq else None,
                 "minimum":min(values) if values else None,"maximum":max(values) if values else None,
                 "mean":statistics.fmean(values) if values else None,
                 "last_verified_value":seq[-1][0] if seq else None,
                 "last_verified_elapsed_ms":seq[-1][1] if seq else None}
        for name,val in summary.items():
            actual=_get(declared,name)
            _require(_same_number(actual,val) if name in ("minimum","maximum","mean","last_verified_value")
                     and val is not None else actual==val,"PID summary differs: "+name)
        summaries.append(summary)
    signal_summaries=[]
    for (pid,name),seq in sorted(multi_samples.items()):
        numbers=[x[0] for x in seq]
        attempts=sum(counts[pid].values())
        signal_summaries.append({"pid":pid,"name":name,"unit":seq[-1][2],"count":len(seq),
            "attempted":attempts,"missing":attempts-len(seq),"minimum":min(numbers),
            "maximum":max(numbers),"mean":statistics.fmean(numbers),
            "last_verified_value":seq[-1][0],"last_verified_elapsed_ms":seq[-1][1]})
    declared_signals=_get(result,"multi_signal_summary")
    if declared_signals is not None or contextual_trims or not raw_only and any(multi_width(pid) is not None for pid in pids):
        declared_signals=_array(declared_signals,320,"multi-signal summary")
        _require(len(declared_signals)==len(signal_summaries),"Missing or excess channel summaries")
        for raw,wanted in zip(declared_signals,signal_summaries):
            raw=_dict(raw,"channel summary")
            for key,val in wanted.items():
                actual=_get(raw,key)
                _require(_same_number(actual,val) if key in ("minimum","maximum","mean","last_verified_value")
                         else type(actual) is type(val) and actual==val,"Multi-signal summary differs: "+key)
    if completed:
        _require(final and identity==vin and not terminal and recorded_cycles==cycles and
                 (all(counts[pid].get("RAW",0)>0 for pid in pids) if raw_only else
                  any(samples.values()) or bool(multi_samples)) and _get(result,"stop_reason") is None,
                 "Completion not established by observations")
    else:
        _require(isinstance(_get(result,"stop_reason"),str) and _get(result,"stop_reason"),
                 "Incomplete report requires stop reason")
    return {"schema":"camarodiag-j1979-live-inspection-v1","verified":True,
            "completed":completed,"declared_vin":vin,"initial_vin":initial,"final_identity_verified":final and identity==vin,
            "channel":channel,"requested_cycles":cycles,"completed_cycles":recorded_cycles,
            "raw_only":raw_only,"selected_pids":pids,"advertised_pids":sorted(advertised),"inventory_complete":inventory_complete,
            "summary":summaries,"multi_signal_summary":signal_summaries,"samples":clean,
            "trim_layout":derived_layout,"scaling_configuration":derived_scaling,"scaling_context_verified":not required_configs or (scoped_scaling and all(context_from_steps(scale_rows).get(c).state!="UNVERIFIED" for c in required_configs)),"physical_vehicle_verified":False,
            "authenticated":False,"whole_vehicle_coverage_verified":False,
            "stop_reason":_get(result,"stop_reason")}


def inspect_file(path, *, expected_vin=None):
    root,digest=load(path)
    result=inspect(root,expected_vin=expected_vin)
    result["source_sha256"]=digest
    return result


def compare_files(paths, *, expected_vin=None):
    """Compare independently replayed recordings, never align them in time."""
    _require(2<=len(paths)<=16,"Select two to sixteen recordings")
    results=[inspect_file(path,expected_vin=expected_vin) for path in paths]
    first=results[0]
    _require(all(x["declared_vin"]==first["declared_vin"] and
                 {k:v for k,v in x["channel"].items() if k!="link_checksum_verified"}==
                 {k:v for k,v in first["channel"].items() if k!="link_checksum_verified"}
                 for x in results),"Different VIN or ECU channel")
    _require(len({x["source_sha256"] for x in results})==len(results),"Duplicate recording")
    shared=sorted(set.intersection(*(set(x["selected_pids"]) for x in results)))
    comparisons=[]
    for pid in shared:
        reports=[next(x for x in report["summary"] if x["pid"]==pid) for report in results]
        means=[row["mean"] for row in reports if row["mean"] is not None]
        comparisons.append({"pid":pid,"unit":next((row["unit"] for row in reports if row["unit"]),None),
                            "recordings":reports,
                            "mean_range":max(means)-min(means) if len(means)>1 else None})
    # Never collapse multiple signals of a PID into a single average. A
    # channel must occur in all recordings with the same name and unit before
    # its means are comparable. An absent channel is not a numeric zero.
    channel_maps=[{(row["pid"],row["name"],row["unit"]):row
                   for row in recording["multi_signal_summary"]}
                  for recording in results]
    channel_keys=sorted(set.union(*(set(m) for m in channel_maps)))
    channel_comparisons=[]
    for pid,name,unit in channel_keys:
        values=[m.get((pid,name,unit)) for m in channel_maps]
        means=[row["mean"] for row in values if row is not None]
        present=sum(row is not None for row in values)
        # Preserve absence and distinguish complete coverage of the inputs
        # from a recording that contains only a subset of the samples.
        channel_comparisons.append({
            "pid":pid,"name":name,"unit":unit,
            "recordings":values,"recordings_with_channel":present,
            "all_recordings_have_channel":present==len(results),
            "all_recordings_complete":all(x["completed"] for x in results),
            "mean_range":max(means)-min(means) if present==len(results) else None,
        })
    return {"schema":"camaradiag-j1979-live-comparison-v1","verified":True,
            "declared_vin":first["declared_vin"],"channel":first["channel"],"shared_pids":shared,
            "recordings":[{"sha256":x["source_sha256"],"completed":x["completed"],
                           "completed_cycles":x["completed_cycles"]} for x in results],
            "comparison":comparisons,"channel_comparison":channel_comparisons,"time_aligned":False,"diagnostic_verdict":None,
            "physical_vehicle_verified":False,"authenticated":False}
