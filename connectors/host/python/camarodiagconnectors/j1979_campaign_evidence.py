"""Decode CJ/1 evidence emitted by the embedded J1979 read campaign.

This module only consumes bytes. CRC and sequence are corruption checks,
not authentication, hardware qualification or proof of vehicle identity.
"""
from __future__ import annotations

import re
import struct
import zlib

_HEADER = struct.Struct("<2sBBHH")
_STATES = {1: "PASSED", 2: "RAW", 3: "NEGATIVE", 4: "TIMEOUT", 5: "FAILED"}
_END = {1: "COMPLETE", 2: "INITIAL_UNVERIFIED", 3: "BUDGET", 4: "LINK_FAULT", 5: "SINK_STOPPED"}
_FLAGS = ("transport_observed", "pid_inventory_complete", "freeze_trigger_observed",
          "freeze_frame_present", "freeze_inventory_complete", "monitor_inventory_complete",
          "control_inventory_complete", "identity_inventory_complete")


def _physical_pair(tx, rx, ext):
    if ext == 0:
        return 0x7E0 <= tx <= 0x7E7 and rx == tx + 8
    if ext != 1 or tx > 0x1FFFFFFF or tx & 0x1FFF00FF != 0x18DA00F1:
        return False
    source = tx >> 8 & 0xFF
    return source < 0xF0 and rx == 0x18DAF100 | source


def _read_only(req):
    if not req:
        return False
    return (len(req) == 1 and req[0] in (3, 7, 10) or
            len(req) == 2 and (req[0] in (1, 6, 9) or req[0] == 8 and req[1] % 32 == 0) or
            len(req) == 3 and req[0] == 2 and req[2] == 0)


def _exact(stream, size):
    chunks = bytearray()
    while len(chunks) < size:
        part = stream.read(size - len(chunks))
        if not part:
            raise EOFError("Truncated evidence")
        chunks.extend(part)
    return bytes(chunks)


def decode(stream):
    """Consume one capture, preserving verified prefix on corruption/truncation."""
    report = {"schema": "camarodiag-functional-j1979-embedded-evidence-v1",
              "protocol": "SAE-J1979", "capture_integrity_verified": False,
              "physical_vehicle_verified": False, "observed_vin": None,
              "steps": [], "summary": None, "error": None}
    counts = {status: 0 for status in _STATES.values()}
    try:
        for seq in range(2048):
            header = _exact(stream, _HEADER.size)
            magic, version, kind, actual, length = _HEADER.unpack(header)
            if magic != b"CJ" or version != 1 or actual != seq or length > 4110:
                raise ValueError("Invalid evidence header or sequence")
            body = _exact(stream, length)
            crc, = struct.unpack("<I", _exact(stream, 4))
            if zlib.crc32(header + body) != crc:
                raise ValueError("Evidence CRC mismatch")
            if seq == 0:
                if kind != 1 or length != 13:
                    raise ValueError("Missing campaign start")
                tx, rx, ext, budget = struct.unpack("<IIBI", body)
                if not _physical_pair(tx, rx, ext) or not 1 <= budget <= 600000:
                    raise ValueError("Invalid campaign profile")
                report.update(request_can_id=f"{tx:X}", response_can_id=f"{rx:X}",
                              extended=bool(ext), budget_ms=budget)
                continue
            if kind == 2:
                if length < 9:
                    raise ValueError("Truncated observation")
                state, req_len, elapsed, res_len = struct.unpack_from("<BBIH", body)
                if (state not in _STATES or not 1 <= req_len <= 7 or res_len > 4095 or
                        length != 8 + req_len + res_len or (state <= 3) != bool(res_len)):
                    raise ValueError("Invalid observation")
                req, res = body[8:8+req_len], body[8+req_len:]
                if not _read_only(req):
                    raise ValueError("Unexpected mutating request in evidence")
                if state in (1, 2):
                    echo = req[1:] if req[0] == 2 else req[1:2]
                    if not res.startswith(bytes((req[0] + 0x40,)) + echo):
                        raise ValueError("Positive record does not match request")
                if state == 3 and (len(res) != 3 or res[:2] != bytes((0x7F, req[0]))
                                   or res[2] in (0, 0x78)):
                    raise ValueError("Invalid terminal negative record")
                status = _STATES[state]
                if req == b"\x09\x02" and state == 1:
                    if not re.fullmatch(rb"\x49\x02\x01[A-HJ-NPR-Z0-9]{17}", res):
                        raise ValueError("Malformed VIN record")
                    report["observed_vin"] = res[3:].decode("ascii")
                counts[status] += 1
                report["steps"].append({"sequence": seq, "request_hex": req.hex().upper(),
                    "response_hex": res.hex().upper() if res else None, "status": status,
                    "duration_ms": elapsed})
                continue
            if kind != 3 or length != 22:
                raise ValueError("Unexpected evidence record")
            end, flags, steps, positive, raw, negative, timeout = struct.unpack("<BBIIIII", body)
            if (end not in _END or steps != len(report["steps"]) or
                    (positive, raw, negative, timeout) != tuple(counts[k] for k in
                        ("PASSED", "RAW", "NEGATIVE", "TIMEOUT"))):
                raise ValueError("Summary does not match observations")
            observed = bool(flags & 1)
            first = report["steps"][0] if steps else None
            verified = bool(first and first["request_hex"] == "0100" and
                            first["status"] == "PASSED" and first["response_hex"] and
                            len(first["response_hex"]) == 12)
            if observed != verified or end == 1 and not observed:
                raise ValueError("Transport observation inconsistent")
            if (flags & 8 and not flags & 4) or (flags & 16 and not flags & 8):
                raise ValueError("Freeze-frame flags inconsistent")
            if stream.read(1):
                raise ValueError("Trailing evidence after summary")
            report["summary"] = {"end": _END[end], "steps": steps, "counts": counts,
                **{name: bool(flags & (1 << i)) for i, name in enumerate(_FLAGS)}}
            report["capture_integrity_verified"] = True
            return report
        raise ValueError("Evidence record limit exceeded")
    except (EOFError, ValueError, OSError) as error:
        report["error"] = str(error)
        return report
