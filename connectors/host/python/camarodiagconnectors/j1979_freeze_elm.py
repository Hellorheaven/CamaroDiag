"""Independent replay of Android ELM/STN classical ISO-TP freeze transcripts.

The transcript is unauthenticated. It establishes whether normalized
application bytes match the reported physical responder, not actual bus
provenance or adapter configuration outside the observed fixed protocol.
"""
from __future__ import annotations

import re

MAX_RAW = 256 * 1024
MAX_COMBINED_RAW = 16 * 1024 * 1024
_HEX_BYTE = re.compile(r"[0-9A-Fa-f]{2}")


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def fixed_protocol(raw):
    if raw is None:
        return None
    _require(isinstance(raw, str) and len(raw) <= MAX_RAW, "Invalid ELM protocol transcript")
    lines = [line.strip().upper() for line in raw.replace(">", "\n").splitlines()
             if line.strip() and line.strip().upper() != "ATDPN"]
    return int(lines[0]) if len(lines) == 1 and lines[0] in ("6", "7", "8", "9") else None


def is_no_data(raw):
    return [line.strip().upper() for line in raw.replace(">", "\n").splitlines()
            if line.strip()] == ["NO DATA"]


def decode(raw, responder, extended):
    """Correlate a single complete classical ISO-TP response.

    Follow the Android CAF0/ATH1/ATS1/ATD0 representation. Other responder
    IDs are ignored, never used as substitute data. Duplicate messages,
    truncation, bad PCI, invalid CF sequence and selected-source errors fail.
    """
    _require(isinstance(raw, str) and len(raw) <= MAX_RAW, "Invalid ELM response transcript")
    output = bytearray()
    expected = 0
    seq = 1
    complete = False
    width = 8 if extended else 3
    for line in raw.replace("\r", "\n").replace(">", "\n").split("\n"):
        clean = line.strip()
        if not clean or clean.upper().startswith("FC:"):
            continue
        _require(not any(error in clean.upper() for error in
            ("NO DATA", "CAN ERROR", "BUS ERROR", "BUFFER FULL", "STOPPED",
             "UNABLE TO CONNECT", "?")), "ELM diagnostic exchange failed")
        tokens = clean.split()
        header_count = 4 if extended and len(tokens) >= 4 and all(
            _HEX_BYTE.fullmatch(part) for part in tokens[:4]) else 1
        header = "".join(tokens[:header_count])
        if len(header) != width or re.fullmatch(r"[0-9A-Fa-f]+", header) is None or int(header,16) != responder:
            continue
        data = tokens[header_count:]
        _require(1 <= len(data) <= 8 and all(_HEX_BYTE.fullmatch(value) for value in data),
                 "Malformed selected ELM CAN frame")
        frame = bytes(int(value,16) for value in data)
        _require(not complete, "Multiple ISO-TP responses from selected ECU")
        kind = frame[0] >> 4
        if kind == 0:
            _require(expected == 0, "Unexpected single frame")
            expected = frame[0] & 15
            _require(1 <= expected <= 7 and len(frame) >= expected + 1, "Truncated single frame")
            output.extend(frame[1:1+expected])
        elif kind == 1:
            _require(expected == 0 and len(frame) == 8, "Invalid first frame")
            expected = (frame[0] & 15) << 8 | frame[1]
            _require(8 <= expected <= 4095, "Invalid first-frame length")
            output.extend(frame[2:])
        elif kind == 2:
            _require(expected > len(output) and expected > 7 and frame[0] & 15 == seq,
                     "Unexpected consecutive-frame sequence")
            remaining = expected - len(output)
            _require(len(frame) >= min(remaining,7)+1, "Truncated consecutive frame")
            output.extend(frame[1:1+remaining])
            seq = (seq+1) & 15
        else:
            raise ValueError("Unexpected ISO-TP frame from selected ECU")
        complete = expected > 0 and len(output) == expected
    _require(complete, "No complete ISO-TP response from selected ECU")
    return bytes(output)


def verify(report, result, rx):
    observed = report["observed_protocol"]
    _require(observed is None or type(observed) is int and observed in (6,7,8,9),
             "Invalid declared ELM protocol")
    _require(fixed_protocol(report["raw_protocol_response"]) == observed,
             "ELM protocol evidence disagrees with declaration")
    trace = report["adapter_exchanges"]
    steps = result["steps"]
    _require(isinstance(trace,list) and len(trace) == len(steps),
             "Missing Android adapter exchanges")
    total = 0
    attempted = any(step["transmission_attempted"] for step in steps)
    if attempted:
        _require(observed == report["profile"], "Unverified ELM profile before physical requests")
    for index,(saved,step) in enumerate(zip(trace,steps)):
        _require(isinstance(saved,dict) and set(saved) == {"step","request_hex","raw_response"} and
                 type(saved["step"]) is int and saved["step"] == index and
                 saved["request_hex"] == step["request_hex"],
                 "Unexpected Android adapter exchange order")
        raw = saved["raw_response"]
        if raw is not None:
            _require(isinstance(raw,str) and len(raw) <= MAX_RAW,
                     "Invalid Android adapter response")
            total += len(raw)
            _require(total <= MAX_COMBINED_RAW, "Android adapter evidence exceeds bound")
            _require(step["transmission_attempted"], "Adapter transcript without exchange attempt")
        outcome = step["transport_outcome"]
        if outcome in ("RECEIVED","LATE_RESPONSE"):
            _require(raw is not None, "Missing Android physical response transcript")
            normalized = decode(raw,rx,report["profile"] in (7,9))
            _require(normalized.hex().upper() == step["response_hex"],
                     "Android normalized response differs from selected responder frames")
        elif outcome == "TIMEOUT" and raw is not None:
            _require(is_no_data(raw), "Android timeout contradicts returned adapter transcript")
        # A transport fault may have a malformed or incomplete transcript.
        # It remains negative evidence and is never upgraded to a response.
    return observed == report["profile"]
