"""Independent inspection of an interrupted SAE J1979 Mode 02 CF/1 stream.

This is *not* an alternate completion path. A verified prefix establishes only
that complete, CRC-checked records fit the explicitly selected recipe. An
uncommitted record is not replayed. Its bytes are preserved in the original
file and reported solely by offset and length.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import struct
import zlib

from .j1979_freeze import FreezePlan, Read, execute_plan, MAX_STEPS
from .j1979_freeze_native import (
    HEADER, PROFILE, FINAL, OBSERVATION, MAX_BODY, MAX_CAPTURE, PLAN_SIZE,
    NativeProfile, _observation,
)
from .recovery_bundle import _regular


SCHEMA = "camarodiag-j1979-native-freeze-prefix-v1"


def _check(ok, problem):
    if not ok:
        raise ValueError(problem)


def inspect_prefix(raw: bytes, *, expected_vin: str) -> dict:
    """Replay only whole CF/1 records, never claiming a finished campaign.

    Complete malformed records, a wrong expected VIN, an impossible request
    sequence and complete terminal records are hard failures. A truncated
    record has no trusted observations. A partial header is admitted only if
    every available byte matches the expected fixed framing/sequence.
    """
    FreezePlan(expected_vin).validate()
    _check(type(raw) is bytes and 0 < len(raw) <= MAX_CAPTURE, "Invalid CF/1 input size")
    position = sequence = 0
    profile = None
    observations = []
    interruption = "MISSING_TERMINAL"
    while position < len(raw):
        _check(sequence < MAX_STEPS + 2, "CF/1 prefix record limit exceeded")
        left = len(raw) - position
        head = raw[position:position + HEADER.size]
        magic = b"CF\x01"
        _check(head[:min(3, len(head))] == magic[:min(3, len(head))],
               "Invalid partial CF/1 header")
        if len(head) >= 4:
            kind = head[3]
            _check(kind == 1 if sequence == 0 else kind in (2, 3),
                   "Invalid CF/1 prefix record kind")
        if len(head) >= 5:
            _check(head[4] == sequence & 255, "Invalid partial CF/1 sequence")
        if len(head) >= 6:
            _check(head[5] == sequence >> 8, "Invalid partial CF/1 sequence")
        if left < HEADER.size:
            interruption = "TRUNCATED_HEADER"
            break
        _, version, kind, recorded, length = HEADER.unpack(head)
        _check(version == 1 and recorded == sequence and 1 <= length <= MAX_BODY,
               "Invalid CF/1 prefix framing")
        _check((kind != 1 or length == PLAN_SIZE) and
               (kind != 2 or length >= OBSERVATION.size + 2) and
               (kind != 3 or length == FINAL.size), "Invalid CF/1 prefix record length")
        size = HEADER.size + length + 4
        if left < size:
            interruption = "TRUNCATED_RECORD"
            break
        body = raw[position + HEADER.size:position + HEADER.size + length]
        stored_crc, = struct.unpack_from("<I", raw, position + HEADER.size + length)
        _check(zlib.crc32(head + body) == stored_crc, "CF/1 complete record CRC mismatch")
        if kind == 1:
            profile = NativeProfile.decode(body)
            _check(profile.plan.vin == expected_vin, "CF/1 expected VIN differs")
        elif kind == 2:
            _check(profile is not None and len(observations) < MAX_STEPS,
                   "CF/1 observation without valid plan or beyond limit")
            observations.append(_observation(body))
        else:
            _check(profile is not None, "CF/1 terminal without plan")
            # A complete terminal, even one declaring an unsuccessful
            # campaign, must follow the normal complete-capture inspector.
            _check(position + size == len(raw), "Trailing bytes after CF/1 terminal")
            raise ValueError("CF/1 has a complete terminal; use normal inspect")
        position += size
        sequence += 1
    _check(profile is not None, "No complete CF/1 plan in prefix")

    cursor = 0
    synthesized = False

    def observe(request):
        nonlocal cursor, synthesized
        if cursor == len(observations):
            synthesized = True
            return Read("CONTEXT_CHANGED", None, 0, False)
        saved_request, reply = observations[cursor]
        cursor += 1
        _check(saved_request == request, "CF/1 prefix differs from Mode 02 recipe")
        return reply

    partial_result = execute_plan(profile.plan, observe)
    _check(cursor == len(observations), "Unexpected CF/1 observations after recipe stop")
    # Include only observations actually carried by the file. A synthetic
    # terminal context change is used strictly to stop independent replay.
    steps = partial_result["steps"][:len(observations)]
    _check(len(steps) == len(observations), "CF/1 prefix step mismatch")
    # A completed frame recipe can appear within an interrupted stream.
    # Its local status is useful for forensic reconstruction but MUST NOT be
    # interpreted as a completed capture or a physical snapshot.
    frame_progress = [
        {
            "frame": row["frame"],
            "provisional_status": row["status"],
            "status_finalized": False,
            "inventory_complete_in_prefix": row["inventory_complete"],
            "trigger_dtc_in_prefix": row["trigger_dtc"],
            "trigger_recheck_dtc_in_prefix": row["final_trigger_dtc"],
            "trigger_stable_in_prefix": row["trigger_stable"],
            "advertised_measurement_pids": sorted(
                pid for pid in row["advertised_pids"] if pid != 2 and pid % 32 != 0),
            "read_measurement_pids": sorted(row["read_pids"]),
            "measurement_observation_count": len(row["measurement_steps"]),
        }
        for row in partial_result["frames"]
    ]
    return {
        "schema": SCHEMA,
        "protocol": "SAE-J1979",
        "source": "OFFLINE_CF1_PREFIX",
        "profile": profile.report(),
        "expected_vin_independently_supplied": True,
        "prefix_integrity_verified": True,
        "capture_integrity_verified": False,
        "execution_completed": False,
        "terminal_present": False,
        "physical_vehicle_verified": False,
        "hardware_provenance_verified": False,
        "evidence_authenticated": False,
        "permits_programming": False,
        "complete_records": sequence,
        "complete_observations": len(observations),
        "last_complete_offset": position,
        "discarded_tail_bytes": len(raw) - position,
        "interruption": interruption,
        "replay_terminated_by_missing_observation": synthesized,
        "last_observed_request_hex": steps[-1]["request_hex"] if steps else None,
        "next_unobserved_request_hex": (partial_result["steps"][len(observations)]["request_hex"]
                                         if synthesized else None),
        "unobserved_request_transmitted": False,
        "unvisited_frame_numbers_in_prefix": partial_result["unvisited_frames"],
        "observed_initial_vin_in_prefix": partial_result["initial_vin"],
        "observed_final_vin_in_prefix": partial_result["final_vin"],
        "independently_replayed_steps": steps,
        "visited_frame_numbers_in_prefix": [f["frame"] for f in partial_result["frames"]],
        "observed_frame_progress": frame_progress,
        "frame_states_are_provisional": True,
        "timing_independently_verified": False,
        "recorded_observation_duration_ms": sum(r.duration_ms for _, r in observations),
        "authenticated": False,
        "diagnostic_verdict": None,
    }


def inspect_prefix_file(source, *, expected_vin):
    source = Path(source)
    raw, stamp = _regular(source, MAX_CAPTURE)
    report = inspect_prefix(raw, expected_vin=expected_vin)
    again, final = _regular(source, MAX_CAPTURE)
    _check(raw == again and stamp == final, "CF/1 changed during prefix inspection")
    report["capture_sha256"] = stamp.digest
    return report
