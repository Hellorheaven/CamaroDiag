"""Read-only SAE J1979 classic CAN responder discovery and scoped multi-ECU scan.

A functional PID00 request is the sole broadcast. Subsequent diagnostics
are separate physical ISO-TP channels explicitly selected by the operator.
No CAN FD, address guessing, protocol fallback, DTC clear or actuation.
"""
from __future__ import annotations

from dataclasses import dataclass
import re
import socket
import struct
import time

from .j1979 import LinuxIsoTp, physical_pair, scan

_EFF = 0x80000000
_RTR = 0x40000000
_ERR = 0x20000000
_SFF_MASK = 0x7FF
_EFF_MASK = 0x1FFFFFFF
_FRAME = struct.Struct("=IB3x8s")
_FILTER = struct.Struct("=II")
_MAX_FRAMES = 512
_MAX_TARGETS = 32


@dataclass(frozen=True)
class CanFrame:
    can_id: int
    extended: bool
    data: bytes
    rtr: bool = False
    error: bool = False

    def evidence(self):
        return {"can_id": f"{self.can_id:X}", "extended": self.extended,
                "data_hex": self.data.hex().upper(), "rtr": self.rtr, "error": self.error}


def functional_request(extended: bool) -> CanFrame:
    return CanFrame(0x18DB33F1 if extended else 0x7DF, extended,
                    bytes.fromhex("020100CCCCCCCCCC"))


def response_pair(can_id: int, extended: bool) -> tuple[int, int] | None:
    if not extended:
        if 0x7E8 <= can_id <= 0x7EF:
            return can_id - 8, can_id
        return None
    if can_id & 0x1FFFFF00 != 0x18DAF100 or not 0 <= can_id <= _EFF_MASK:
        return None
    address = can_id & 0xFF
    if address >= 0xF0:
        return None
    return 0x18DA00F1 | address << 8, can_id


def decode_frame(frame: CanFrame, extended: bool) -> dict | None:
    if frame.extended != extended or frame.rtr or frame.error:
        return None
    pair = response_pair(frame.can_id, extended)
    if pair is None:
        return None
    value = {"tx_can_id": f"{pair[0]:X}", "rx_can_id": f"{pair[1]:X}",
             "status": "FAILED", "detail": "INVALID_PID00_FRAME",
             "supported_pids": None, "next_page": None, "negative_response_code": None}
    data = frame.data
    # ISO 15765-4 classic diagnostic frames use DLC 8. Only the declared
    # single-frame payload is interpreted; padding bytes are never PIDs.
    if len(data) != 8:
        return value
    if data[:3] == b"\x06\x41\x00":
        bitmap = data[3:7]
        value.update(status="PASSED", detail=None,
                     supported_pids=[i+1 for i in range(31) if bitmap[i//8] & (0x80 >> (i%8))],
                     next_page=bool(bitmap[-1] & 1))
    elif data[:3] == b"\x03\x7F\x01" and data[3] not in (0, 0x78):
        value.update(status="NOT_TESTED", detail=f"NRC_{data[3]:02X}", negative_response_code=data[3])
    elif data[:4] == b"\x03\x7F\x01\x78":
        value.update(status="NOT_TESTED", detail="PENDING_WITHOUT_FINAL_RESPONSE")
    return value


class LinuxFunctionalCan:
    """Own a classic CAN_RAW socket; exact functional discovery only.

    Kernel filters remove unrelated standard/extended IDs. No configuration
    of the interface bit rate, link state or adapter firmware is attempted.
    """
    def __init__(self, interface: str, extended: bool):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,15}", interface):
            raise ValueError("Invalid SocketCAN interface")
        self.extended = extended
        self._used = False
        self._socket = socket.socket(socket.AF_CAN, socket.SOCK_RAW, socket.CAN_RAW)
        try:
            # Match response family and the extended flag, exclude RTR/error.
            match = _EFF | 0x18DAF100 if extended else 0x7E8
            mask = (_EFF | _RTR | _ERR | (0x1FFFFF00 if extended else 0x7F8))
            self._socket.setsockopt(socket.SOL_CAN_RAW, socket.CAN_RAW_FILTER, _FILTER.pack(match, mask))
            self._socket.bind((interface,))
        except BaseException:
            self._socket.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self._socket.close()

    def send_discovery(self, timeout: float):
        if self._used or not 0 < timeout <= 30:
            raise ValueError("Functional discovery may be sent only once")
        deadline = time.monotonic() + timeout
        self._socket.setblocking(False)
        for _ in range(64):
            try:
                self._socket.recv(_FRAME.size+1)
            except BlockingIOError:
                break
            if time.monotonic() >= deadline:
                raise OSError("Stale CAN queue did not drain")
        else:
            raise OSError("Stale CAN queue exceeded bound")
        left = deadline - time.monotonic()
        if left <= 0:
            raise TimeoutError("No time remains for discovery")
        self._socket.settimeout(left)
        request = functional_request(self.extended)
        wire = _FRAME.pack(request.can_id | (_EFF if self.extended else 0), 8, request.data)
        self._used = True
        if self._socket.send(wire) != len(wire):
            raise OSError("Incomplete CAN discovery send")

    def receive(self, timeout: float) -> CanFrame:
        if not self._used or not 0 < timeout <= 30:
            raise ValueError("No active discovery")
        self._socket.settimeout(timeout)
        wire = self._socket.recv(_FRAME.size+1)
        if len(wire) != _FRAME.size:
            raise OSError("Expected one classic CAN frame")
        raw_id, length, data = _FRAME.unpack(wire)
        if length > 8:
            raise OSError("Invalid classic CAN length")
        extended = bool(raw_id & _EFF)
        return CanFrame(raw_id & (_EFF_MASK if extended else _SFF_MASK), extended,
                        data[:length], bool(raw_id & _RTR), bool(raw_id & _ERR))


def discover(link, extended: bool, *, timeout: float = 1.5, clock=time.monotonic) -> dict:
    """Classify all replies during a bounded observation window.

    An identical duplicate is retained as evidence; conflicting positives
    or positive/terminal-negative mixtures cannot qualify a responder.
    Pending replies are not terminal results.
    """
    if not 0 < timeout <= 30:
        raise ValueError("Invalid discovery time window")
    start = clock()
    groups = {}
    frames = []
    ignored = 0
    stop = None
    sent = False
    try:
        link.send_discovery(timeout)
        sent = True
        for _ in range(_MAX_FRAMES):
            left = timeout - (clock()-start)
            if left <= 0:
                break
            try:
                frame = link.receive(left)
            except TimeoutError:
                break
            if clock()-start >= timeout:
                ignored += 1
                break
            decoded = decode_frame(frame, extended)
            if decoded is None:
                ignored += 1
                continue
            evidence = frame.evidence()
            frames.append(evidence)
            key = int(decoded["rx_can_id"], 16)
            groups.setdefault(key, []).append((decoded, evidence))
        else:
            stop = "FRAME_LIMIT"
    except KeyboardInterrupt:
        stop = "CANCELLED"
    except (OSError, ValueError, RuntimeError) as exc:
        stop = type(exc).__name__
    responders = []
    for key, observations in sorted(groups.items()):
        results = [item[0] for item in observations]
        positives = [r for r in results if r["status"] == "PASSED"]
        pending = [r for r in results if r["detail"] == "PENDING_WITHOUT_FINAL_RESPONSE"]
        terminals = [r for r in results if r["detail"] != "PENDING_WITHOUT_FINAL_RESPONSE"]
        first = positives[0] if positives else terminals[0] if terminals else pending[0]
        verdict = dict(first)
        if any(r != first for r in terminals):
            verdict.update(status="FAILED", detail="CONFLICTING_RESPONSES",
                           supported_pids=None, next_page=None, negative_response_code=None)
        verdict["frames"] = [item[1] for item in observations]
        responders.append(verdict)
    eligible = [r["rx_can_id"] for r in responders if r["status"] == "PASSED"] if stop is None else []
    if stop is None and not eligible:
        stop = "NO_VERIFIED_RESPONDER"
    return {"scope": "FUNCTIONAL_PID00_DISCOVERY", "addressing": "29BIT" if extended else "11BIT",
            "request": functional_request(extended).evidence(), "request_sent": sent,
            "responders": responders, "eligible_response_ids": eligible,
            "raw_frames": frames, "ignored_frame_count": ignored, "completed": stop is None,
            "stop_reason": stop, "cancelled": stop == "CANCELLED",
            "duration_ms": round((clock()-start)*1000),
            "physical_vehicle_verified": False, "whole_vehicle_coverage_verified": False,
            "not_executed": ["MODE_04_CLEAR", "MODE_08_CONTROL"]}


def validate_targets(response_ids, extended: bool) -> tuple[int, ...]:
    result = tuple(response_ids)
    if len(result) > _MAX_TARGETS or len(set(result)) != len(result):
        raise ValueError("Too many or duplicate selected responder IDs")
    if any(not isinstance(i, int) or isinstance(i, bool) or response_pair(i, extended) is None for i in result):
        raise ValueError("Selected response ID is outside the documented address family")
    return result


def scan_network(discovery: dict, selected, open_physical, *, expected_vin: str | None,
                 budget: float = 180.0, clock=time.monotonic) -> dict:
    """Run separate physical scans for explicitly chosen responders.

    Discovery is never a license to scan every discovered module. If the
    requested set cannot be verified in this discovery, do not open any ECU.
    A shared deadline and no fallback avoid unbounded network scanning.
    """
    if not 0 < budget <= 600 or (expected_vin is not None and
            not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", expected_vin)):
        raise ValueError("Invalid physical campaign budget or VIN")
    extended = discovery.get("addressing") == "29BIT"
    targets = validate_targets(selected, extended)
    if not targets:
        raise ValueError("Explicit selected responders required")
    start = clock()
    selected_hex = [f"{i:X}" for i in targets]
    # Never trust an eligible list alone: independently derive the selected
    # address pair and insist on a single positive classification row.
    by_id = {}
    for row in discovery.get("responders", []):
        by_id.setdefault(row.get("rx_can_id"), []).append(row)
    def eligible(rx):
        key = f"{rx:X}"
        rows = by_id.get(key, [])
        pair = response_pair(rx, extended)
        return (discovery.get("completed") is True and key in discovery.get("eligible_response_ids", [])
                and len(rows) == 1 and rows[0].get("status") == "PASSED"
                and rows[0].get("tx_can_id") == f"{pair[0]:X}")
    reports = []
    stop = None
    if not all(eligible(rx) for rx in targets):
        stop = "SELECTED_RESPONDER_UNVERIFIED"
    for rx in targets:
        tx, _ = response_pair(rx, extended)
        entry = {"tx_can_id": f"{tx:X}", "rx_can_id": f"{rx:X}",
                 "status": "NOT_TESTED", "report": None, "error": None}
        reports.append(entry)
        if stop is not None:
            entry["error"] = stop
            continue
        left = budget - (clock()-start)
        if left <= 0:
            stop = entry["error"] = "BUDGET_EXHAUSTED"
            continue
        try:
            with open_physical(tx, rx) as transport:
                left = budget - (clock()-start)
                if left <= 0:
                    stop = entry["error"] = "BUDGET_EXHAUSTED"
                    continue
                entry["report"] = scan(transport.exchange, budget=left, clock=clock,
                                       expected_vin=expected_vin)
            result = entry["report"]
            entry["status"] = "PASSED" if result["completed"] else "NOT_TESTED"
            if not result["completed"]:
                entry["error"] = result["stop_reason"]
                # VIN mismatch/context and terminal link errors stop all later
                # operations. No automatic fallback to a different ECU.
                if result["stop_reason"] in ("CANCELLED", "BUDGET_EXHAUSTED", "VIN_MISMATCH",
                                              "VIN_UNVERIFIED", "TRANSPORT_ERROR"):
                    stop = result["stop_reason"]
        except (KeyboardInterrupt, OSError, ValueError, RuntimeError) as exc:
            entry["status"] = "FAILED"
            stop = entry["error"] = "CANCELLED" if isinstance(exc, KeyboardInterrupt) else type(exc).__name__
            if entry["report"] is not None:
                entry["report"].update(completed=False, stop_reason=entry["report"].get("stop_reason") or stop)
    return {"scope": "EXPLICIT_PHYSICAL_RESPONDER_SET", "discovery": discovery,
            "selected_response_ids": selected_hex, "declared_vin": expected_vin,
            "ecu_reports": reports, "completed": stop is None and all(r["status"] == "PASSED" for r in reports),
            "stop_reason": stop, "cancelled": stop == "CANCELLED",
            "physical_vehicle_verified": False, "whole_vehicle_coverage_verified": False,
            "duration_ms": round((clock()-start)*1000),
            "not_executed": ["MODE_04_CLEAR", "MODE_08_CONTROL"]}
