"""Explicit vehicle-wide legacy J1979 operations on an external ELM adapter.

The source set is a checked operator declaration, not exhaustive network
coverage. A silent ECU may still be affected. Never retry a mutation.
"""
from __future__ import annotations

import copy
import re
import time

from .j1979_legacy import allowed_read
from .j1979_legacy_frames import response_frames


def _dtcs(mode, frames):
    if not frames or any(len(r) != 7 or r[0] != mode + 0x40 for r in frames):
        return None
    codes = []
    for row in frames:
        for offset in (1, 3, 5):
            word = int.from_bytes(row[offset:offset+2], "big")
            if word:
                code = "PCBU"[word >> 14] + f"{(word >> 12) & 3}{word & 0xFFF:03X}"
                if code not in codes:
                    codes.append(code)
    return codes


def mutation_recipe(protocol, read, protocol_check, transmit, *, operation, expected_vin,
                    engine_source, documented_sources, warning_acknowledged, confirmed_yes,
                    save_baseline, require_current=lambda: None):
    sources = tuple(sorted(documented_sources))
    if (protocol not in range(1, 6) or operation not in ("clear", "evap") or
            not warning_acknowledged or not confirmed_yes or
            not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", expected_vin) or
            not 1 <= len(sources) <= 32 or len(set(sources)) != len(sources) or
            any(s in (0, 0xF1, 0xFF) or not 0 <= s <= 255 for s in sources) or engine_source not in sources):
        raise ValueError("Explicit legacy functional scope, VIN and two confirmations required")
    expected = set(sources)
    key = lambda s: f"{s:02X}"
    before = {key(s): {m: None for m in ("03", "07", "0A")} for s in sources}
    after = copy.deepcopy(before)
    acks = {key(s): "NOT_TESTED" for s in sources}
    observations = []
    started = time.monotonic()
    vin = None
    attempted = complete_ack = False

    def finish(outcome, reason=None):
        return {"outcome": outcome, "reason": reason, "observed_vin": vin,
                "command_attempted": attempted, "scope": "FUNCTIONAL_VEHICLE_WIDE",
                "expected_sources": [key(s) for s in sources],
                "acknowledgments": copy.deepcopy(acks),
                "before_dtcs": copy.deepcopy(before), "after_dtcs": copy.deepcopy(after),
                "whole_vehicle_coverage_verified": False, "leak_test_performed": False,
                "observations": copy.deepcopy(observations),
                "duration_ms": round((time.monotonic()-started)*1000)}

    def request(stage, command):
        require_current()
        if not allowed_read(command):
            raise ValueError("Non-read prerequisite")
        row = {"stage": stage, "request_hex": command, "raw_response": None, "frames": [],
               "error": None}
        observations.append(row)
        try:
            raw = read(command, 4.0)
            row["raw_response"] = raw
            frames = response_frames(protocol, command, raw)
            row["frames"] = [frame.evidence() for frame in frames]
            require_current()
            if any(frame.source not in expected for frame in frames):
                raise ValueError("Unexpected response source")
            return frames
        except (OSError, ValueError, RuntimeError, KeyboardInterrupt) as error:
            row["error"] = type(error).__name__
            raise

    def for_source(frames, source):
        return [frame.payload for frame in frames if frame.source == source]

    def scope():
        frames = request("SCOPE", "0100")
        return all(len(rows := for_source(frames, s)) == 1 and
                   len(rows[0]) == 6 and rows[0][:2] == b"\x41\x00" for s in sources)

    def stationary(command, positive):
        frames = request("STATIONARY", command)
        return for_source(frames, engine_source) == [positive] and all(
            frame.payload == positive or (frame.source != engine_source and
                len(frame.payload) == 3 and frame.payload[:2] == b"\x7f\x01" and
                frame.payload[2] in (0x11, 0x12, 0x31)) for frame in frames)

    def snapshot(stage, target):
        for mode in (3, 7, 10):
            command = f"{mode:02X}"
            frames = request(stage, command)
            for s in sources:
                target[key(s)][command] = _dtcs(mode, for_source(frames, s))
        request(stage, "0101")

    try:
        if not scope() or protocol_check() != protocol:
            return finish("NOT_TESTED", "RESPONDER_OR_PROTOCOL_UNVERIFIED")
        counts, fragments = request("IDENTITY", "0901"), request("IDENTITY", "0902")
        for source in sources:
            count, rows = for_source(counts, source), for_source(fragments, source)
            if not count and not rows and source != engine_source:
                continue
            if count != [b"\x49\x01\x05"] or len(rows) != 5 or any(
                    len(row) != 7 or row[:3] != bytes((0x49, 2, i+1)) for i,row in enumerate(rows)):
                return finish("NOT_TESTED", "VIN_SEQUENCE_UNVERIFIED")
            data = b"".join(row[3:] for row in rows)
            if data != bytes(3) + expected_vin.encode("ascii"):
                return finish("NOT_TESTED", "VIN_MISMATCH")
            if source == engine_source:
                vin = expected_vin
        if vin is None:
            return finish("NOT_TESTED", "ENGINE_VIN_UNVERIFIED")
        ack_sources = expected.copy()
        if operation == "clear":
            snapshot("BEFORE", before)
            if any(modes["03"] is None for modes in before.values()):
                return finish("NOT_TESTED", "BASELINE_UNVERIFIED")
        else:
            frames = request("SUPPORT", "08000000000000")
            ack_sources = set()
            for source in sources:
                rows = for_source(frames, source)
                if not rows:
                    continue
                if len(rows) != 1 or len(rows[0]) != 7 or rows[0][:3] != b"\x48\x00\x00":
                    return finish("NOT_TESTED", "CONTROL_SUPPORT_UNVERIFIED")
                if rows[0][3] & 0x80:
                    ack_sources.add(source)
            if ack_sources != {engine_source}:
                return finish("NOT_TESTED", "EVAP_TARGET_UNVERIFIED")
        if not scope() or not stationary("010D", b"\x41\x0d\x00") or not stationary("010C", b"\x41\x0c\x00\x00"):
            return finish("NOT_TESTED", "STATIONARY_OR_SCOPE_UNVERIFIED")
        save_baseline(finish("BASELINE_SAVED"))
        if protocol_check() != protocol or not stationary("010D", b"\x41\x0d\x00") or not stationary("010C", b"\x41\x0c\x00\x00"):
            return finish("NOT_TESTED", "STATIONARY_OR_PROTOCOL_CHANGED")
        require_current()
        command = "04" if operation == "clear" else "08010000000000"
        attempted = True
        row = {"stage": "MUTATION", "request_hex": command, "raw_response": None, "frames": [], "error": None}
        observations.append(row)
        raw = transmit()
        row["raw_response"] = raw
        frames = response_frames(protocol, command, raw)
        row["frames"] = [frame.evidence() for frame in frames]
        ack = b"\x44" if operation == "clear" else b"\x48\x01" + bytes(5)
        for source in {frame.source for frame in frames}:
            rows = for_source(frames, source)
            if rows == [ack]:
                acks[key(source)] = "ACKNOWLEDGED"
            elif len(rows) == 1 and len(rows[0]) == 3 and rows[0][:2] == bytes((0x7F, int(command[:2],16))) and rows[0][2] not in (0,0x78):
                acks[key(source)] = f"REFUSED_{rows[0][2]:02X}"
            else:
                acks[key(source)] = "UNCERTAIN"
        complete_ack = all(frame.source in ack_sources for frame in frames) and all(
            acks[key(source)] == "ACKNOWLEDGED" for source in ack_sources)
        if operation == "clear":
            snapshot("AFTER", after)
            # Missing optional legacy Mode 07/0A remains explicitly null.
            if complete_ack and all(modes["03"] is not None for modes in after.values()):
                residual = any(modes["03"] or modes["07"] for modes in after.values())
                return finish("ACKNOWLEDGED_READBACK", "DTCS_STILL_PRESENT" if residual else
                              "PENDING_READBACK_UNAVAILABLE" if any(modes["07"] is None for modes in after.values()) else None)
        elif complete_ack:
            return finish("ACKNOWLEDGED", "LEAK_TEST_NOT_PERFORMED")
        return finish("ACKNOWLEDGED_PARTIAL" if complete_ack else "UNCERTAIN", "MISSING_OR_CONFLICTING_EVIDENCE")
    except (OSError, ValueError, RuntimeError, KeyboardInterrupt) as error:
        return finish("ACKNOWLEDGED_PARTIAL" if complete_ack else "UNCERTAIN" if attempted else "NOT_TESTED",
                      type(error).__name__)
