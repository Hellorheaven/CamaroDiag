"""Separate local physical SAE J1979 Mode 04 campaign, not a generic write API."""
from __future__ import annotations

import re

_MODES = (3, 7, 10)


def _codes(mode: int, response: bytes):
    if len(response) == 3 and response[:2] == bytes((0x7F, mode)) and response[2] not in (0, 0x78):
        return None, f"NRC_{response[2]:02X}"
    if len(response) < 2 or response[0] != mode + 0x40:
        raise ValueError("Invalid DTC response")
    count = response[1]
    data = response[2:]
    if len(data) != count * 2:
        raise ValueError("Invalid DTC count")
    codes = []
    for i in range(0, len(data), 2):
        word = int.from_bytes(data[i:i + 2], "big")
        if not word:
            raise ValueError("Zero declared DTC")
        codes.append("PCBU"[word >> 14] + f"{(word >> 12) & 3}{word & 0xFFF:03X}")
    return codes, None


def clear_recipe(read, transmit, *, expected_vin: str, primary: bool,
                 warning_acknowledged: bool, confirmed_yes: bool,
                 require_current=lambda: None) -> dict:
    if not primary or not warning_acknowledged or not confirmed_yes or not re.fullmatch(
            r"[A-HJ-NPR-Z0-9]{17}", expected_vin):
        raise ValueError("Physical primary ECM, VIN and two confirmations required")
    observations = []
    before = {f"{mode:02X}": None for mode in _MODES}
    after = before.copy()
    vin = None
    attempted = acknowledged = False

    def finish(outcome, reason=None):
        return {"outcome": outcome, "reason": reason, "command_attempted": attempted,
                "acknowledged": acknowledged, "observed_vin": vin,
                "before_dtcs": before, "after_dtcs": after, "observations": observations}

    def query(stage, request, decode=lambda _: None):
        require_current()
        row = {"stage": stage, "request_hex": request.hex().upper(),
               "response_hex": None, "interpretation": None, "error": None}
        observations.append(row)
        try:
            reply = read(request, 1.8)
            row["response_hex"] = reply.hex().upper()
            require_current()
            row["interpretation"] = decode(reply)
            return reply
        except (OSError, ValueError, RuntimeError, KeyboardInterrupt) as exc:
            row["error"] = type(exc).__name__
            raise

    def snapshot(stage, target):
        for mode in _MODES:
            key = f"{mode:02X}"

            def decode(reply):
                codes, nrc = _codes(mode, reply)
                target[key] = codes
                return nrc if nrc else codes

            try:
                query(stage, bytes((mode,)), decode)
            except (TimeoutError, ValueError):
                pass  # Missing or malformed evidence remains null.

        def readiness(reply):
            if len(reply) != 6 or reply[:2] != b"\x41\x01":
                raise ValueError("Invalid readiness response")
            return {"mil_on": bool(reply[2] & 0x80), "confirmed_dtc_count": reply[2] & 0x7F,
                    "readiness_bcd": reply[3:].hex().upper()}

        try:
            query(stage, b"\x01\x01", readiness)
        except (TimeoutError, ValueError):
            pass

    try:
        identity = query("PRECONDITION", b"\x09\x02")
        if len(identity) != 20 or identity[:3] != b"\x49\x02\x01":
            return finish("NOT_TESTED", "INVALID_VIN")
        vin = identity[3:].decode("ascii")
        if vin != expected_vin:
            return finish("NOT_TESTED", "VIN_MISMATCH")
        snapshot("BEFORE", before)
        if before["03"] is None:
            return finish("NOT_TESTED", "BASELINE_UNAVAILABLE")
        if (query("PRECONDITION", b"\x01\x0D") != b"\x41\x0D\x00" or
                query("PRECONDITION", b"\x01\x0C") != b"\x41\x0C\x00\x00"):
            return finish("NOT_TESTED", "STATIONARY_EVIDENCE_REQUIRED")
        require_current()
        attempted = True
        row = {"stage": "RESET", "request_hex": "04", "response_hex": None,
               "interpretation": None, "error": None}
        observations.append(row)
        reply = transmit()
        row["response_hex"] = reply.hex().upper()
        if reply != b"\x44":
            if len(reply) == 3 and reply[:2] == b"\x7F\x04" and reply[2] not in (0, 0x78):
                return finish("REFUSED", f"NRC_{reply[2]:02X}")
            return finish("UNCERTAIN", "INVALID_OR_INCOMPLETE_ACK")
        acknowledged = True
        snapshot("AFTER", after)
        if after["03"] is None or after["07"] is None:
            return finish("ACKNOWLEDGED_PARTIAL", "READBACK_UNAVAILABLE")
        return finish("ACKNOWLEDGED_READBACK",
                      "DTCS_STILL_PRESENT" if after["03"] or after["07"] else None)
    except (OSError, ValueError, RuntimeError, KeyboardInterrupt) as exc:
        return finish("ACKNOWLEDGED_PARTIAL" if acknowledged else
                      "UNCERTAIN" if attempted else "NOT_TESTED", type(exc).__name__)
