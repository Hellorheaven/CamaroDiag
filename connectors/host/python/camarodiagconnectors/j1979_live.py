"""Bounded, physical-source SAE J1979 live measurement recording.

Only standardized Mode 01 numeric PIDs announced by the selected ECU are
sampled. Every observation preserves its own timestamp and response bytes.
A timeout never copies a preceding value; no monitor or OEM verdict is inferred.
"""
from __future__ import annotations

from dataclasses import dataclass
import math
import re
import time

from .j1979_campaign_evidence import decode_observation
from .j1979_multi_signal import width as multi_width, decode_response as multi_decode
from .j1979_status import width as status_width, decode_response as status_decode
from .j1979_scaling_context import dependency, context_from_steps, config_data

MAX_PIDS = 32
MAX_CYCLES = 1000
MAX_OBSERVATIONS = 10000
MAX_BUDGET = 600.0

# Reuse the canonical application decoder. Widths are not guessed from an
# observed response and a bitmap identifier is never a numeric live value.
def numeric_pid(pid):
    if not isinstance(pid, int) or isinstance(pid, bool) or not 1 <= pid <= 255 or pid % 32 == 0:
        return False
    if multi_width(pid) is not None:
        return True
    for width in (1, 2, 4):
        evidence = decode_observation(bytes((1,pid)), bytes((0x41,pid))+bytes(width))
        if evidence and evidence.get("measurement") is not None:
            return True
    return False


def legacy_numeric_pid(pid):
    return numeric_pid(pid) and (multi_width(pid) or 0) <= 5


@dataclass(frozen=True)
class Plan:
    pids: tuple[int,...]
    expected_vin: str
    cycles: int = 30
    interval: float = 1.0
    budget: float = 180.0

    def validate(self):
        if (not self.pids or len(self.pids)>MAX_PIDS or len(set(self.pids))!=len(self.pids)
                or not all(numeric_pid(pid) for pid in self.pids)):
            raise ValueError("Select unique supported numeric PID definitions")
        if not isinstance(self.expected_vin,str) or not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}",self.expected_vin):
            raise ValueError("Documented physical ECU VIN required")
        if (not isinstance(self.cycles,int) or isinstance(self.cycles,bool) or not 1<=self.cycles<=MAX_CYCLES
                or self.cycles*len(self.pids)>MAX_OBSERVATIONS):
            raise ValueError("Invalid cycle or observation limit")
        if (not math.isfinite(self.interval) or not 0.1<=self.interval<=60 or
                not math.isfinite(self.budget) or not 0<self.budget<=MAX_BUDGET):
            raise ValueError("Invalid recording interval or budget")
        return self


class _Accumulator:
    """Online count and finite numeric summary of accepted observations only."""
    def __init__(self,pid):
        self.pid=pid
        self.name=None
        self.unit=None
        self.counts={}
        self.minimum=None
        self.maximum=None
        self.mean=None
        self.last=None
        self.last_elapsed_ms=None

    def append(self,row):
        status=row["status"]
        self.counts[status]=self.counts.get(status,0)+1
        value=row["measurement"]
        if status!="PASSED" or value is None:
            return
        number=value["number"]
        if not math.isfinite(number):
            raise ValueError("Nonfinite measurement")
        count=self.counts["PASSED"]
        self.name=value["name"]
        self.unit=value["unit"]
        self.minimum=number if self.minimum is None else min(self.minimum,number)
        self.maximum=number if self.maximum is None else max(self.maximum,number)
        self.mean=number if self.mean is None else self.mean+(number-self.mean)/count
        self.last=number
        self.last_elapsed_ms=row["elapsed_ms"]

    def report(self):
        return {"pid":self.pid,"name":self.name,"unit":self.unit,
                "attempted":sum(self.counts.values()),"status_counts":dict(self.counts),
                "minimum":self.minimum,"maximum":self.maximum,"mean":self.mean,
                "last_verified_value":self.last,"last_verified_elapsed_ms":self.last_elapsed_ms}


class _ChannelAccumulator:
    """Keep separate online statistics by PID and stable signal identity."""
    def __init__(self, signal):
        self.pid = signal["pid"]
        self.name = signal["name"]
        self.unit = signal["unit"]
        self.count = 0
        self.minimum = self.maximum = self.mean = self.last = self.last_time = None

    def add(self, signal, elapsed):
        value = signal["number"]
        if not math.isfinite(value) or signal["unit"] != self.unit:
            raise ValueError("Invalid multi-channel sample")
        self.count += 1
        self.minimum = value if self.minimum is None else min(self.minimum, value)
        self.maximum = value if self.maximum is None else max(self.maximum, value)
        self.mean = value if self.mean is None else self.mean + (value-self.mean)/self.count
        self.last, self.last_time = value, elapsed

    def report(self, attempts):
        return {"pid":self.pid,"name":self.name,"unit":self.unit,"count":self.count,
                "attempted":attempts,"missing":attempts-self.count,
                "minimum":self.minimum,"maximum":self.maximum,"mean":self.mean,
                "last_verified_value":self.last,"last_verified_elapsed_ms":self.last_time}


def record(exchange, plan: Plan, *, clock=time.monotonic, sleep=time.sleep,
           require_current=lambda:None, on_observation=lambda _:None,
           request_timeout=lambda _:2.5):
    """Capture a private prefix even on cancellation or transport failure.

    A cycle is a sequence of individual requests, not simultaneous samples.
    Scheduling is start-to-start; if an ECU is slow the next cycle is delayed,
    never burst-replayed to catch up. The whole session has one deadline.
    """
    plan.validate()
    started=clock()
    admission=[]
    rows=[]
    stats={pid:_Accumulator(pid) for pid in plan.pids}
    channels={}
    pages=[]
    advertised=set()
    observed=None
    last_identity=None
    stop=None
    completed_cycles=0
    inventory_complete=False
    required_configs=sorted({dependency(pid) for pid in plan.pids if dependency(pid) is not None})
    scaling=None

    def elapsed():
        return max(0,round((clock()-started)*1000))

    def remaining():
        return plan.budget-(clock()-started)

    def active():
        nonlocal stop
        if stop is not None:
            return False
        require_current()
        if remaining()<=0:
            stop="BUDGET_EXHAUSTED"
            return False
        return True

    def query(request,phase,cycle=None,pid=None):
        nonlocal stop
        if not active():
            return None
        began=clock()
        row={"phase":phase,"cycle":cycle,"pid":pid,"request_hex":request.hex().upper(),
             "response_hex":None,"started_elapsed_ms":max(0,round((began-started)*1000)),
             "elapsed_ms":None,"duration_ms":None,"status":"NOT_TESTED","detail":None,
             "measurement":None,"application_evidence":None,"multi_signals":None}
        try:
            allowed_timeout=request_timeout(request)
            if not math.isfinite(allowed_timeout) or not 0<allowed_timeout<=30:
                raise ValueError("Invalid per-request time limit")
            raw=exchange(request,min(allowed_timeout,remaining()))
            if not isinstance(raw,bytes) or len(raw)>4095:
                raise ValueError("Invalid response type or length")
            row["response_hex"]=raw.hex().upper()
            require_current()
            if remaining()<=0:
                stop="BUDGET_EXHAUSTED"
                row["detail"]="LATE_RESPONSE"
            else:
                evidence=decode_observation(request,raw)
                if evidence is None:
                    row.update(status="FAILED",detail="INVALID_APPLICATION_RESPONSE")
                    # A malformed or unrelated response calls the correlation
                    # into question; stop instead of using another ECU/value.
                    stop="INVALID_APPLICATION_RESPONSE"
                else:
                    row["application_evidence"]=evidence
                    row["status"]=evidence["shape"]
                    if evidence["shape"]=="NEGATIVE":
                        row["detail"]=f'NRC_{evidence["negative_response_code"]:02X}'
                    if phase=="SCALING" and evidence["shape"] in ("RAW","PASSED") and config_data(request[1],raw[2:]) is None:
                        stop="INVALID_APPLICATION_RESPONSE"
                        row.update(status="FAILED",detail=stop,application_evidence=None)
                    elif phase=="SAMPLE" and evidence["shape"] in ("RAW","PASSED") and multi_width(pid) is not None:
                        signals=multi_decode(request,raw)
                        if signals is None or (status_width(pid) is not None and status_decode(request,raw) is None):
                            stop="INVALID_MEASUREMENT"
                            row.update(status="FAILED",detail=stop,application_evidence=None)
                        else:
                            row["multi_signals"]=signals
                            if dependency(pid) is not None and scaling is not None:
                                contextual=scaling.decode(request,raw)
                                if contextual is None or any(s["number"] is None for s in contextual["signals"]):
                                    stop="SCALING_UNVERIFIED"
                                    row.update(status="FAILED",detail=stop,application_evidence=None,multi_signals=None)
                                else:
                                    row["multi_signals"]=[{**s,"number":c["number"]} for s,c in zip(signals,contextual["signals"])]
                    elif phase=="SAMPLE" and evidence["shape"]=="PASSED":
                        value=evidence.get("measurement")
                        if value is None or value["pid"]!=pid or value["mode"]!=1:
                            stop="INVALID_MEASUREMENT"
                            row.update(status="FAILED",detail=stop,application_evidence=None)
                        else:
                            row["measurement"]=value
                            if dependency(pid) is not None and scaling is not None:
                                contextual=scaling.decode(request,raw)
                                if contextual is None or contextual["signals"][0]["number"] is None:
                                    stop="SCALING_UNVERIFIED"
                                    row.update(status="FAILED",detail=stop,application_evidence=None,measurement=None)
                                else:
                                    row["measurement"]={**value,"number":contextual["signals"][0]["number"]}
        except KeyboardInterrupt:
            stop="CANCELLED"
            row["detail"]=stop
        except TimeoutError:
            row["status"]="TIMEOUT"
            row["detail"]="TIMEOUT"
            if remaining()<=0:
                stop="BUDGET_EXHAUSTED"
        except (OSError,ValueError,RuntimeError) as error:
            stop="TRANSPORT_OR_CONTEXT_ERROR"
            row.update(status="FAILED",detail=type(error).__name__)
        row["elapsed_ms"]=elapsed()
        row["duration_ms"]=max(0,round((clock()-began)*1000))
        (rows if phase=="SAMPLE" else admission).append(row)
        if phase=="SAMPLE":
            stats[pid].append(row)
            for signal in row["multi_signals"] or ():
                key=(pid,signal["name"])
                if key not in channels:
                    channels[key]=_ChannelAccumulator(signal)
                channels[key].add(signal,row["elapsed_ms"])
            on_observation(dict(row))
        return row

    def identity(phase):
        nonlocal observed,last_identity,stop
        row=query(b"\x09\x02",phase)
        if row is None or stop is not None:
            return False
        found=(row.get("application_evidence") or {}).get("observed_vin")
        if phase=="INITIAL_VIN":
            observed=found
        if found!=plan.expected_vin:
            stop="VIN_UNVERIFIED" if found is None else "VIN_MISMATCH"
            return False
        last_identity=clock()
        return True

    def inventory():
        nonlocal inventory_complete,stop
        for base in range(0,256,32):
            row=query(bytes((1,base)),"INVENTORY")
            if row is None or stop is not None:
                return
            page=(row.get("application_evidence") or {}).get("supported_page")
            if row["status"]!="PASSED" or page is None or page["base"]!=base:
                break
            pages.append(page)
            advertised.update(page["supported_ids"])
            if not page["has_next_page"]:
                inventory_complete=True
                break
            # No need to probe additional optional pages once all selected
            # PIDs are proven, but report that remaining inventory is partial.
            if all(pid in advertised for pid in plan.pids) and (not required_configs or base>=0x40):
                break
        if any(pid not in advertised for pid in plan.pids):
            stop="SELECTED_PID_NOT_VERIFIED"

    def pause_until(target):
        nonlocal stop
        while active():
            left=target-clock()
            if left<=0:
                return True
            sleep(min(left,remaining(),0.2))
        return False

    try:
        if identity("INITIAL_VIN"):
            inventory()
        if stop is None and required_configs:
            for config in required_configs:
                if config in advertised:
                    query(bytes((1,config)),"SCALING")
                    if stop is not None:
                        break
            scaling=context_from_steps(admission)
            if stop is None and any(scaling.get(config).state=="UNVERIFIED" for config in required_configs):
                stop="SCALING_UNVERIFIED"
        if stop is None:
            next_start=clock()
            for cycle in range(1,plan.cycles+1):
                if not pause_until(next_start):
                    break
                if last_identity is not None and clock()-last_identity>=30:
                    if not identity("PERIODIC_VIN"):
                        break
                actual_start=clock()
                done=True
                for pid in plan.pids:
                    if not active():
                        done=False
                        break
                    row=query(bytes((1,pid)),"SAMPLE",cycle,pid)
                    if row is None or stop is not None:
                        done=False
                        break
                if not done:
                    break
                completed_cycles+=1
                next_start=max(actual_start+plan.interval,clock())
        if stop is None and completed_cycles==plan.cycles:
            identity("FINAL_VIN")
        if stop is None and not any(row["measurement"] is not None or row["multi_signals"] for row in rows):
            stop="NO_VERIFIED_MEASUREMENT"
    except KeyboardInterrupt:
        stop="CANCELLED"
    except (OSError,ValueError,RuntimeError) as error:
        stop="CONTEXT_OR_SINK_ERROR"
    return {"scope":"PHYSICAL_SELECTED_ECU_LIVE","declared_vin":plan.expected_vin,
            "observed_vin":observed,"vin_match":"UNVERIFIED" if observed is None else
                "MATCH" if observed==plan.expected_vin else "MISMATCH",
            "selected_pids":list(plan.pids),"advertised_pids":sorted(advertised),
            "inventory_pages":pages,"inventory_complete":inventory_complete,
            "unverified_selected_pids":[pid for pid in plan.pids if pid not in advertised],
            "requested_cycles":plan.cycles,"completed_cycles":completed_cycles,
            "interval_ms":round(plan.interval*1000),"budget_ms":round(plan.budget*1000),
            "duration_ms":elapsed(),"admission":admission,"observations":rows,
            "scaling_configuration":(scaling or context_from_steps(admission)).report() if required_configs else None,
            "summary":[stats[pid].report() for pid in plan.pids],
            "multi_signal_summary":[channels[k].report(sum(stats[k[0]].counts.values())) for k in sorted(channels)],
            "completed":stop is None and completed_cycles==plan.cycles,
            "stop_reason":stop,"cancelled":stop=="CANCELLED",
            "physical_vehicle_verified":False,"whole_vehicle_coverage_verified":False,
            "not_executed":["MODE_04_CLEAR","MODE_08_CONTROL"]}


def csv_rows(report):
    """Stable long-form CSV; empty fields represent missing values, never zero."""
    multi=any(multi_width(pid) is not None for pid in report.get("selected_pids",()))
    header=("cycle","pid_hex","started_elapsed_ms","elapsed_ms","duration_ms","status",
            "value","unit","request_hex","response_hex","detail")
    yield header+("signal_name","raw_encoded") if multi else header
    for row in report["observations"]:
        values=row.get("multi_signals") or [row.get("measurement") or {}]
        for value in values:
            fields=(row["cycle"],f'{row["pid"]:02X}',row["started_elapsed_ms"],row["elapsed_ms"],
                row["duration_ms"],row["status"],value.get("number"),value.get("unit"),
                row["request_hex"],row["response_hex"],row["detail"])
            yield fields+(value.get("name"),value.get("raw_encoded")) if multi else fields
