"""Explicit, read-only SAE J1979 Mode 02 freeze-frame campaign.

ISO 15031-5:2006 sections 6.2 and 7.2 describe frame 00 and optional
manufacturer-defined additional frame numbers. Never enumerate frame
numbers without an explicit plan. This module does not alter the existing
frame-00 scan, CJ/1 evidence format or firmware transport permissions.
"""
from __future__ import annotations

from dataclasses import dataclass
import math
import re
import time
from typing import Callable

from .j1979_campaign_evidence import decode_observation
from .j1979_drive_cycle import TRIMS, trim_width

MAX_FRAMES = 8
MAX_PIDS = 64
MAX_STEPS = MAX_FRAMES * (8 + 2 + 247) + 2
MAX_RESPONSE = 4095
_VIN = re.compile(r"[A-HJ-NPR-Z0-9]{17}")
_OUTCOMES = frozenset(("RECEIVED", "TIMEOUT", "TRANSPORT_ERROR", "BUDGET",
                       "CANCELLED", "CONTEXT_CHANGED", "LATE_RESPONSE"))


@dataclass(frozen=True)
class FreezePlan:
    vin: str
    frames: tuple[int, ...] = (0,)
    pids: tuple[int, ...] = ()
    budget_ms: int = 180000

    def validate(self):
        if not isinstance(self.vin, str) or not _VIN.fullmatch(self.vin):
            raise ValueError("A documented 17-character expected VIN is required")
        if (not isinstance(self.frames, tuple) or not 1 <= len(self.frames) <= MAX_FRAMES
                or any(type(value) is not int or not 0 <= value <= 255 for value in self.frames)
                or len(set(self.frames)) != len(self.frames)):
            raise ValueError("Select one to eight distinct explicit frame numbers")
        if (not isinstance(self.pids, tuple) or len(self.pids) > MAX_PIDS
                or any(type(value) is not int or not 1 <= value <= 255
                       or value % 32 == 0 or value == 2 for value in self.pids)
                or len(set(self.pids)) != len(self.pids)):
            raise ValueError("Select up to 64 distinct measurement PIDs; trigger and bitmaps are automatic")
        if type(self.budget_ms) is not int or not 1000 <= self.budget_ms <= 600000:
            raise ValueError("Invalid freeze-frame campaign time budget")
        return self

    def evidence(self):
        return {"expected_vin": self.vin, "frames": list(self.frames),
                "selected_pids": list(self.pids), "budget_ms": self.budget_ms}


@dataclass(frozen=True)
class Read:
    """Transport observation, not a verdict about the application response."""
    outcome: str
    response: bytes | None
    duration_ms: int
    transmission_attempted: bool

    def validate(self):
        if type(self.outcome) is not str or self.outcome not in _OUTCOMES or type(self.transmission_attempted) is not bool:
            raise ValueError("Invalid read transport outcome")
        if type(self.duration_ms) is not int or not 0 <= self.duration_ms <= 3600000:
            raise ValueError("Invalid read duration")
        if self.response is not None and (not isinstance(self.response, bytes)
                                           or not self.response or len(self.response) > MAX_RESPONSE):
            raise ValueError("Response exceeds application evidence bound")
        if self.outcome in ("RECEIVED", "LATE_RESPONSE"):
            if not self.transmission_attempted or self.response is None:
                raise ValueError("Received observation lacks an attempted exchange")
        elif self.response is not None:
            raise ValueError("Unexpected response for non-received observation")
        if self.outcome in ("TIMEOUT", "TRANSPORT_ERROR") and not self.transmission_attempted:
            raise ValueError("Transport failure without exchange attempt")
        if self.outcome == "BUDGET" and self.transmission_attempted:
            raise ValueError("Budget exhausted before transmission")
        return self


def allowed_request(request: bytes, frames: tuple[int, ...]) -> bool:
    """Read-only recipe boundary; no Mode 04, Mode 08 or UDS request."""
    return isinstance(request, bytes) and (
        request == b"\x09\x02" or
        len(request) == 3 and request[0] == 2 and request[2] in frames
    )


def _vin(response):
    if response is None or len(response) != 20 or response[:3] != b"\x49\x02\x01":
        return None
    try:
        value = response[3:].decode("ascii")
    except UnicodeError:
        return None
    return value if _VIN.fullmatch(value) else None


def _dtc(data):
    a, b = data
    return None if not (a or b) else f"{'PCBU'[a >> 6]}{(a >> 4) & 3:X}{a & 15:X}{b:02X}"


def _bitmap(base, data):
    if len(data) != 4 or (base == 0xe0 and data[-1] & 1):
        return None
    return {"base": base, "bitmap_hex": data.hex().upper(),
            "advertised_pids": [base + bit + 1 for bit in range(31)
                                if data[bit // 8] & (0x80 >> (bit % 8))],
            "has_next_page": bool(data[-1] & 1)}


# These scalar interpretations do not depend on ECU-specific Mode 01 scale
# settings. Keep other stored values raw, rather than applying a current
# configuration or a newer Mode 01 catalogue to an older frozen record.
_CONTEXT_FREE_SCALARS = frozenset((
    0x04, 0x05, 0x0a, 0x0c, 0x0d, 0x0e, 0x0f, 0x11, 0x1f, 0x21, 0x22,
    0x23, 0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32, 0x33, 0x3c, 0x3d,
    0x3e, 0x3f, 0x42, 0x43, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b,
    0x4c, 0x4d, 0x4e, 0x52, 0x53, 0x54, 0x59, 0x5a, 0x5b, 0x5c,
    0x5d, 0x5e, 0x61, 0x62, 0x63, 0xa6
))


def classify(request, read):
    """Preserve the exact frame echo and classify only the matching payload."""
    read.validate()
    row = {"request_hex": request.hex().upper(),
           "response_hex": None if read.response is None else read.response.hex().upper(),
           "transport_outcome": read.outcome,
           "duration_ms": read.duration_ms,
           "transmission_attempted": read.transmission_attempted,
           "status": "NOT_TESTED", "detail": None, "evidence": None}
    if read.outcome != "RECEIVED":
        row["detail"] = read.outcome
        if read.outcome == "TRANSPORT_ERROR":
            row["status"] = "FAILED"
        return row
    response = read.response
    if len(response) == 3 and response[:2] == bytes((0x7f, request[0])) and response[2] not in (0, 0x78):
        row["detail"] = f"NRC_{response[2]:02X}"
        return row
    if request == b"\x09\x02":
        vin = _vin(response)
        if vin is None:
            row.update(status="FAILED", detail="INVALID_VIN")
        else:
            row.update(status="PASSED", evidence={"vin": vin})
        return row
    if len(request) != 3 or request[0] != 2 or response[:3] != bytes((0x42, request[1], request[2])) or len(response) <= 3:
        row.update(status="FAILED", detail="WRONG_SERVICE_PID_OR_FRAME")
        return row
    pid, frame = request[1:]
    data = response[3:]
    evidence = {"frame": frame, "pid": pid, "data_hex": data.hex().upper()}
    if pid % 32 == 0:
        page = _bitmap(pid, data)
        if page is None:
            row.update(status="FAILED", detail="INVALID_SUPPORT_BITMAP")
            return row
        evidence.update(page=page)
        row.update(status="PASSED", evidence=evidence)
    elif pid == 2:
        if len(data) != 2:
            row.update(status="FAILED", detail="INVALID_TRIGGER_DTC")
            return row
        evidence.update(trigger_present=bool(data[0] or data[1]), trigger_dtc=_dtc(data))
        row.update(status="PASSED", evidence=evidence)
    else:
        # The established CJ/1 decoder is restricted to frame zero. Normalize
        # only for shared classical width/measurement checks after correlating
        # the actual frame, and never export a substituted request or response.
        if frame != 0:
            # Additional frame semantics can be manufacturer-defined. Do not
            # assume the frame-zero measurement catalogue applies to them.
            row.update(status="RAW", evidence=evidence, detail="STORED_RAW_VALUE")
            return row
        normalized = decode_observation(bytes((2, pid, 0)), bytes((0x42, pid, 0)) + data)
        if normalized is None or pid in TRIMS and not trim_width(pid, len(data)):
            row.update(status="FAILED", detail="INVALID_PID_DATA_WIDTH")
            return row
        if pid in _CONTEXT_FREE_SCALARS and normalized.get("measurement") is not None:
            evidence["measurement"] = {**normalized["measurement"], "frame": frame}
            row.update(status="PASSED", evidence=evidence)
        else:
            row.update(status="RAW", evidence=evidence, detail="STORED_RAW_VALUE")
    return row


def execute_plan(plan: FreezePlan, observe: Callable[[bytes], Read]):
    """Deterministic recipe used both by the transport and independent replay.

    Read each frame's own support bitmap, then the advertised trigger DTC.
    If the DTC is zero, its stored measurements are not valid. Do not use
    another frame's inventory, and never read an unadvertised data PID.
    Re-read the trigger after acquisition; a changed or unavailable trigger
    prevents a stable frame claim. A final VIN check binds the acquisition
    to the same declared target, but cannot authenticate the physical ECU.
    """
    plan.validate()
    steps = []
    results = []
    stop = None
    first_vin = final_vin = None

    def read(request):
        nonlocal stop
        if stop is not None:
            return None
        if not allowed_request(request, plan.frames) or len(steps) >= MAX_STEPS:
            raise ValueError("Unexpected or excessive freeze-frame request")
        row = classify(request, observe(request))
        steps.append(row)
        if row["transport_outcome"] in ("TRANSPORT_ERROR", "BUDGET", "CANCELLED", "CONTEXT_CHANGED", "LATE_RESPONSE"):
            stop = {"BUDGET": "BUDGET_EXHAUSTED", "LATE_RESPONSE": "BUDGET_EXHAUSTED"}.get(
                row["transport_outcome"], row["transport_outcome"])
        return row

    def evidence(row):
        return row["evidence"] if row is not None and row["status"] == "PASSED" else None

    def acquire_frame(frame):
        result = {"frame": frame, "required_frame": frame == 0, "status": "UNAVAILABLE",
                  "trigger_dtc": None, "final_trigger_dtc": None,
                  "trigger_stable": False, "inventory_complete": False,
                  "verified_pages": [], "advertised_pids": [], "read_pids": [],
                  "unadvertised_selected_pids": [], "not_read_selected_pids": [],
                  "measurement_steps": [], "detail": None}
        ids = set()
        pages = result["verified_pages"]
        selected = set(plan.pids)

        def page(base):
            found = evidence(read(bytes((2, base, frame))))
            if found is None:
                return None
            value = found["page"]
            pages.append(value)
            ids.update(value["advertised_pids"])
            return value

        initial = page(0)
        if initial is None:
            result["detail"] = "PID00_UNVERIFIED"
            return result
        if 2 not in ids:
            result["detail"] = "TRIGGER_NOT_ADVERTISED"
            result["advertised_pids"] = sorted(ids)
            return result
        trigger = evidence(read(bytes((2, 2, frame))))
        if trigger is None:
            result["detail"] = "TRIGGER_UNVERIFIED"
            result["advertised_pids"] = sorted(ids)
            return result
        result["trigger_dtc"] = trigger["trigger_dtc"]
        if not trigger["trigger_present"]:
            result.update(status="ABSENT", detail="ZERO_TRIGGER_DTC",
                          advertised_pids=sorted(ids))
            return result
        continuation = initial["has_next_page"]
        complete = not continuation
        for base in range(0x20, 0x100, 0x20):
            if not continuation or stop is not None:
                break
            subsequent = page(base)
            if subsequent is None:
                break
            continuation = subsequent["has_next_page"]
            complete = not continuation
        result["inventory_complete"] = complete
        result["advertised_pids"] = sorted(ids)
        candidates = sorted((ids - {2}) if not selected else ids & selected)
        result["unadvertised_selected_pids"] = sorted(selected - ids) if complete else []
        result["not_read_selected_pids"] = sorted(selected - ids) if not complete else []
        failures = 0
        for pid in candidates:
            row = read(bytes((2, pid, frame)))
            if row is None:
                break
            if row["transmission_attempted"]:
                result["read_pids"].append(pid)
            result["measurement_steps"].append(len(steps) - 1)
            failures += row["status"] not in ("PASSED", "RAW")
        result["not_read_selected_pids"] = sorted(set(result["not_read_selected_pids"]) | (selected & ids) - set(result["read_pids"]))
        if stop is not None:
            result.update(status="PARTIAL", detail=stop)
            return result
        final = evidence(read(bytes((2, 2, frame))))
        if final is not None:
            result["final_trigger_dtc"] = final["trigger_dtc"]
        stable = final is not None and final["trigger_dtc"] == trigger["trigger_dtc"]
        result["trigger_stable"] = stable
        if not stable:
            result.update(status="CHANGED" if final is not None else "PARTIAL",
                          detail="TRIGGER_CHANGED" if final is not None else "FINAL_TRIGGER_UNVERIFIED")
        elif not complete or failures or len(result["read_pids"]) != len(candidates):
            result.update(status="PARTIAL", detail="INCOMPLETE_INVENTORY_OR_VALUES")
        else:
            result.update(status="COMPLETE")
        return result

    initial = evidence(read(b"\x09\x02"))
    first_vin = None if initial is None else initial["vin"]
    if stop is None and first_vin != plan.vin:
        stop = "VIN_UNVERIFIED" if first_vin is None else "VIN_MISMATCH"
    for frame in plan.frames:
        if stop is not None:
            break
        results.append(acquire_frame(frame))
    if stop is None:
        final = evidence(read(b"\x09\x02"))
        final_vin = None if final is None else final["vin"]
        if stop is None and final_vin != first_vin:
            stop = "FINAL_VIN_UNVERIFIED" if final_vin is None else "FINAL_VIN_MISMATCH"
    completed = stop is None and len(results) == len(plan.frames)
    return {
        "plan": plan.evidence(), "initial_vin": first_vin, "final_vin": final_vin,
        "completed": completed, "stop_reason": stop,
        "identity_rechecked": completed and first_vin == final_vin == plan.vin,
        "frames": results, "steps": steps,
        "unvisited_frames": list(plan.frames[len(results):]),
        "all_frames_stable": completed and all(row["status"] in ("COMPLETE", "ABSENT") for row in results),
        "physical_vehicle_verified": False, "authenticated": False,
        "whole_vehicle_coverage_verified": False, "permits_programming": False,
    }


def scan_freeze(exchange, plan: FreezePlan, *, clock=time.monotonic, require_current=lambda: None,
                request_timeout=lambda request: 2.5):
    """Acquire bounded actual replies; never synthesize a vehicle response."""
    plan.validate()
    start = clock()

    def current():
        require_current()

    def observed(request):
        begin = clock()
        attempted = False
        def result(outcome, response=None):
            elapsed = clock() - begin
            return Read(outcome, response, max(0, round(elapsed * 1000)), attempted)
        try:
            current()
            left = plan.budget_ms / 1000 - (begin - start)
            if not math.isfinite(left) or left <= 0:
                return result("BUDGET")
            attempted = True
            maximum = request_timeout(request)
            if not isinstance(maximum, (int, float)) or not math.isfinite(maximum) or not 0 < maximum <= 20:
                return result("TRANSPORT_ERROR")
            response = exchange(request, min(maximum, left))
            if not isinstance(response, bytes) or not response or len(response) > MAX_RESPONSE:
                return result("TRANSPORT_ERROR")
            current()
            if clock() - start >= plan.budget_ms / 1000:
                return result("LATE_RESPONSE", response)
            return result("RECEIVED", response)
        except KeyboardInterrupt:
            return result("CANCELLED")
        except TimeoutError:
            return result("TIMEOUT") if attempted else result("CONTEXT_CHANGED")
        except (OSError, ValueError, RuntimeError):
            return result("TRANSPORT_ERROR") if attempted else result("CONTEXT_CHANGED")

    result = execute_plan(plan, observed)
    result["duration_ms"] = max(0, round((clock() - start) * 1000))
    return result
