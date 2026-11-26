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


# Mirror the firmware's known application record widths. Unknown PID
# definitions are retained as RAW only; a capture cannot upgrade them.
_ONE = {0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0D,0x0E,0x0F,0x11,
        0x13,0x1D,0x2C,0x2D,0x2E,0x2F,0x30,0x33,0x45,0x46,0x47,0x48,
        0x49,0x4A,0x4B,0x4C,0x52,0x5A,0x5B,0x5C,0x61,0x62}
_TWO = {0x0C,0x10,0x1F,0x21,0x22,0x23,0x31,0x32,0x3C,0x3D,0x3E,0x3F,
        0x42,0x43,0x44,0x4D,0x4E,0x53,0x54,0x59,0x5D,0x5E,0x63}


def _positive_state(req, res):
    mode = req[0]
    echo = req[1:] if mode == 2 else req[1:2]
    if not res.startswith(bytes((mode + 0x40,)) + echo):
        return None
    if mode in (3, 7, 10):
        return 1 if len(res) >= 2 and len(res) == 2 + 2 * res[1] and all(
            res[i:i+2] != bytes(2) for i in range(2, len(res), 2)) else None
    pid = req[1]
    offset = 3 if mode == 2 else 2
    if pid % 32 == 0:
        return 1 if len(res) == offset + 4 and not (pid == 0xE0 and res[-1] & 1) else None
    if mode in (1, 2):
        width = 2 if mode == 2 and pid == 2 else 4 if pid == 1 else 1 if pid in _ONE else 2 if pid in _TWO else 0
        return (1 if len(res) == offset + width else None) if width else (2 if len(res) > offset else None)
    if mode == 6:
        return 1 if len(res) >= 10 and (len(res)-1) % 9 == 0 and all(
            res[i] == pid for i in range(1, len(res), 9)) else None
    if mode == 9:
        if pid == 2:
            return 1 if re.fullmatch(rb"\x49\x02\x01[A-HJ-NPR-Z0-9]{17}", res) else None
        width = {4: 16, 6: 4, 10: 20}.get(pid)
        if width:
            return 1 if len(res) >= 3 and res[2] and len(res) == 3 + res[2]*width else None
        return 2 if len(res) > 2 else None
    return None


def _exact(stream, size):
    chunks = bytearray()
    while len(chunks) < size:
        part = stream.read(size - len(chunks))
        if not part:
            raise EOFError("Truncated evidence")
        chunks.extend(part)
    return bytes(chunks)


def _verify_campaign(steps, end, flags):
    """Replay the exact read-only campaign plan, including advertised members.

    Integrity means consistent recorded evidence, not successful measurements,
    hardware provenance, or an authenticated capture.
    """
    index = 0
    computed = 0
    stopped = None

    def read(req):
        nonlocal index, stopped
        if stopped:
            return None
        if index == len(steps):
            stopped = "MISSING"
            return None
        row = steps[index]
        if row["request_hex"] != req.hex().upper():
            raise ValueError("Unexpected campaign request or order")
        index += 1
        if row["status"] == "FAILED":
            stopped = "LINK_FAULT"
        return bytes.fromhex(row["response_hex"]) if row["status"] == "PASSED" else None

    initial = read(b"\x01\x00")
    if initial is not None:
        computed |= 1
        read(b"\x09\x02")
        for mode in (3, 7, 10):
            read(bytes((mode,)))

        def inventory(mode, cached=False, members=True):
            offset = 3 if mode == 2 else 2
            for base in range(0, 0xE1, 32):
                if stopped:
                    return False
                suffix = b"\x00" if mode == 2 else b""
                bitmap = initial[2:] if cached and base == 0 else None
                if bitmap is None:
                    response = read(bytes((mode, base)) + suffix)
                    if response is None:
                        return False
                    bitmap = response[offset:]
                if members:
                    for bit in range(31):
                        if stopped:
                            return False
                        if not bitmap[bit // 8] & (0x80 >> (bit % 8)):
                            continue
                        pid = base + bit + 1
                        if mode in (2, 9) and pid == 2:
                            continue
                        read(bytes((mode, pid)) + suffix)
                if not bitmap[3] & 1:
                    return not stopped
            return False

        if inventory(1, cached=True):
            computed |= 2
        trigger = read(b"\x02\x02\x00")
        if trigger is not None:
            computed |= 4
            if any(trigger[3:5]):
                computed |= 8
                if inventory(2):
                    computed |= 16
        if inventory(6):
            computed |= 32
        if inventory(8, members=False):
            computed |= 64
        if inventory(9):
            computed |= 128

    if index != len(steps):
        raise ValueError("Unexpected records after campaign stopped")
    if stopped == "LINK_FAULT":
        expected_end = 4
    elif stopped == "MISSING":
        # Zero observations can also indicate the link disappeared before
        # the campaign's first exchange, after the start record was emitted.
        expected_end = 4 if not steps and end == 4 else 3
    elif initial is None:
        expected_end = 2
    else:
        expected_end = 1
    if end != expected_end or flags != computed:
        raise ValueError("Campaign summary inconsistent with recorded steps")


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
                if state in (1, 2) and _positive_state(req, res) != state:
                    raise ValueError("Response shape or claimed evidence status is inconsistent")
                if state == 3 and (len(res) != 3 or res[:2] != bytes((0x7F, req[0]))
                                   or res[2] in (0, 0x78)):
                    raise ValueError("Invalid terminal negative record")
                status = _STATES[state]
                if req == b"\x09\x02" and state == 1:
                    if not re.fullmatch(rb"\x49\x02\x01[A-HJ-NPR-Z0-9]{17}", res):
                        raise ValueError("Malformed VIN record")
                    report["observed_vin"] = res[3:].decode("ascii")
                counts[status] += 1
                dtc_codes = None
                if state == 1 and req[0] in (3, 7, 10):
                    dtc_codes = [f"{'PCBU'[a >> 6]}{(a >> 4) & 3:X}{a & 15:X}{b:02X}"
                                 for a, b in zip(res[2::2], res[3::2])]
                report["steps"].append({"sequence": seq, "request_hex": req.hex().upper(),
                    "response_hex": res.hex().upper() if res else None, "status": status,
                    "duration_ms": elapsed, "dtc_codes": dtc_codes,
                    "negative_response_code": res[2] if state == 3 else None})
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
            _verify_campaign(report["steps"], end, flags)
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
