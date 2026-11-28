"""Read-only responder discovery on an explicitly configured legacy ELM link.

A header and checksum correlate observations, but do not establish physical
identity, authenticate an ECU, or imply that silent ECUs are absent.
"""
from __future__ import annotations

import time

from .j1979_legacy import PROTOCOLS
from .j1979_legacy_frames import response_frames


def valid_source(source):
    return isinstance(source, int) and not isinstance(source, bool) and 1 <= source <= 254 and source != 0xF1


def classify_pid00(frames):
    """Exactly one positive bitmap per source. Retain conflicting evidence."""
    groups = {}
    for frame in frames:
        groups.setdefault(frame.source, []).append(frame)
    results = []
    for source, rows in sorted(groups.items()):
        payloads = [frame.payload for frame in rows]
        entry = {"source": f"{source:02X}", "status": "FAILED",
                 "detail": "AMBIGUOUS_OR_INVALID_PID00", "supported_pids": None,
                 "has_next_page": None, "frames": [frame.evidence() for frame in rows]}
        if len(payloads) == 1:
            data = payloads[0]
            if len(data) == 6 and data[:2] == b"\x41\x00":
                entry.update(status="PASSED", detail=None,
                             supported_pids=[bit + 1 for bit in range(31)
                                             if data[2 + bit // 8] & (0x80 >> (bit % 8))],
                             has_next_page=bool(data[-1] & 1))
            elif len(data) == 3 and data[:2] == b"\x7F\x01" and data[2] not in (0, 0x78):
                entry.update(status="NOT_TESTED", detail=f"NRC_{data[2]:02X}")
        results.append(entry)
    return results


def discover(exchange, protocol_check, protocol, *, budget=15.0, clock=time.monotonic):
    """One PID00 request only; never select an ECU by arrival order."""
    if protocol not in PROTOCOLS or not 0 < budget <= 600:
        raise ValueError("Invalid legacy discovery scope")
    start = clock()
    raw, frames, actual, stop = None, [], None, None
    responders = []
    try:
        raw = exchange("0100", min(budget, 4.0))
        if clock() - start >= budget:
            stop = "BUDGET_EXHAUSTED"
        else:
            frames = response_frames(protocol, "0100", raw)
            if not frames:
                stop = "NO_RESPONSE"
            else:
                candidate = protocol_check()
                if clock() - start >= budget:
                    stop = "BUDGET_EXHAUSTED"
                elif candidate != protocol:
                    stop = "PROTOCOL_MISMATCH"
                else:
                    actual = candidate
                    responders = classify_pid00(frames)
                    if not any(row["status"] == "PASSED" for row in responders):
                        stop = "NO_VERIFIED_SOURCE"
    except KeyboardInterrupt:
        stop = "CANCELLED"
    except (OSError, RuntimeError):
        stop = "TRANSPORT_ERROR"
    except ValueError:
        stop = "INVALID_RESPONSE_OR_PROTOCOL"
    return {"expected_protocol": protocol, "observed_protocol": actual,
            "protocol_name": PROTOCOLS[protocol], "scope": "FUNCTIONAL_DISCOVERY",
            "completed": stop is None, "stop_reason": stop, "cancelled": stop == "CANCELLED",
            "responders": responders, "whole_vehicle_coverage_verified": False,
            "observed_vin": None, "steps": [{"request_hex": "0100", "raw_response": raw,
                "frames": [frame.evidence() for frame in frames],
                "status": "PASSED" if stop is None else "NOT_TESTED" if stop in
                    ("NO_RESPONSE", "NO_VERIFIED_SOURCE", "CANCELLED", "BUDGET_EXHAUSTED") else "FAILED",
                "detail": stop, "duration_ms": round((clock()-start)*1000)}],
            "not_executed": ["MODE_04_CLEAR", "MODE_08_CONTROL"],
            "duration_ms": round((clock()-start)*1000)}
