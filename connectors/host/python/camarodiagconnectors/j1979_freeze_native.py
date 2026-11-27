"""Strict independent decoder for native CF/1 freeze evidence.

CRC and sequence protect framing, not origin. A terminal record is required
for capture integrity, and all conclusions are recomputed from observations.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import os
import struct
import zlib
from pathlib import Path

from .j1979_freeze import FreezePlan, Read, execute_plan, MAX_STEPS, MAX_RESPONSE
from .j1979_usb_auth import validate_profile

HEADER = struct.Struct("<2sBBHH")
PROFILE = struct.Struct("<IIBI")
OBSERVATION = struct.Struct("<BBBBIH")
FINAL = struct.Struct("<BBBBII8B")
SCOPE_SIZE = 91
PLAN_SIZE = PROFILE.size + SCOPE_SIZE
MAX_BODY = OBSERVATION.size + 3 + MAX_RESPONSE
MAX_CAPTURE = (MAX_STEPS + 2) * (HEADER.size + MAX_BODY + 4)
OUTCOMES = {1: "RECEIVED", 2: "TIMEOUT", 3: "TRANSPORT_ERROR", 4: "BUDGET",
            5: "CONTEXT_CHANGED", 6: "LATE_RESPONSE"}
STOPS = {0: None, 1: "BUDGET_EXHAUSTED", 2: "TRANSPORT_ERROR",
         3: "CONTEXT_CHANGED", 4: "VIN_UNVERIFIED", 5: "VIN_MISMATCH",
         6: "FINAL_VIN_UNVERIFIED", 7: "FINAL_VIN_MISMATCH"}
STATES = {0: "UNAVAILABLE", 1: "ABSENT", 2: "COMPLETE", 3: "PARTIAL", 4: "CHANGED"}


@dataclass(frozen=True)
class NativeProfile:
    tx: int
    rx: int
    extended: bool
    plan: FreezePlan

    def encode(self):
        validate_profile(self.tx, self.rx, self.extended, self.plan.budget_ms)
        self.plan.validate()
        return (PROFILE.pack(self.tx, self.rx, int(self.extended), self.plan.budget_ms)
                + self.plan.vin.encode("ascii")
                + bytes((len(self.plan.frames),))
                + bytes(self.plan.frames).ljust(8, b"\0")
                + bytes((len(self.plan.pids),))
                + bytes(self.plan.pids).ljust(64, b"\0"))

    @classmethod
    def decode(cls, data):
        if len(data) != PLAN_SIZE:
            raise ValueError("Invalid CF/1 scope length")
        tx, rx, extended, budget = PROFILE.unpack_from(data)
        if extended not in (0, 1):
            raise ValueError("Invalid CF/1 extended flag")
        scope = data[PROFILE.size:]
        try:
            vin = scope[:17].decode("ascii")
        except UnicodeError as exc:
            raise ValueError("Invalid CF/1 VIN") from exc
        nf, np = scope[17], scope[26]
        if not 1 <= nf <= 8 or np > 64 or any(scope[18+nf:26]) or any(scope[27+np:]):
            raise ValueError("Noncanonical CF/1 scope")
        profile = cls(tx, rx, bool(extended),
                      FreezePlan(vin, tuple(scope[18:18+nf]), tuple(scope[27:27+np]), budget))
        if profile.encode() != data:
            raise ValueError("Noncanonical CF/1 profile")
        return profile

    def report(self):
        return {"request_can_id": f"{self.tx:X}", "response_can_id": f"{self.rx:X}",
                "extended": self.extended, "plan": self.plan.evidence()}


def hello(profile, nonce=None):
    payload = profile.encode()
    nonce = os.urandom(32) if nonce is None else nonce
    if not isinstance(nonce, bytes) or len(nonce) != 32 or not any(nonce):
        raise ValueError("Invalid CU/2 client nonce")
    return b"CU\x02\x01" + payload + nonce


def proof(request, challenge, key):
    if not isinstance(key, bytes) or len(key) != 32:
        raise ValueError("A provisioned 32-byte credential is required")
    if len(request) != 4 + PLAN_SIZE + 32 or request[:4] != b"CU\x02\x01":
        raise ValueError("Invalid CU/2 request")
    NativeProfile.decode(request[4:4+PLAN_SIZE])
    if len(challenge) != 68 or challenge[:4] != b"CU\x02\x02":
        raise ValueError("Invalid CU/2 challenge")
    if not any(request[-32:]) or not any(challenge[4:36]):
        raise ValueError("Invalid CU/2 nonce")
    transcript = request[4:] + challenge[4:36]
    expected = hmac.digest(key, b"F" + transcript, "sha256")
    if not hmac.compare_digest(expected, challenge[36:]):
        raise ValueError("CU/2 server proof rejected")
    return b"CU\x02\x03" + hmac.digest(key, b"f" + transcript, "sha256")


def _read_exact(stream, size):
    result = stream.read(size)
    if not isinstance(result, bytes) or len(result) != size:
        raise ValueError("Truncated CF/1 record")
    return result


def _records(stream):
    for seq in range(MAX_STEPS + 2):
        header = _read_exact(stream, HEADER.size)
        magic, version, kind, actual, length = HEADER.unpack(header)
        if magic != b"CF" or version != 1 or actual != seq or kind not in (1, 2, 3):
            raise ValueError("Invalid CF/1 framing or sequence")
        if not 1 <= length <= MAX_BODY:
            raise ValueError("Invalid CF/1 record size")
        body = _read_exact(stream, length)
        expected, = struct.unpack("<I", _read_exact(stream, 4))
        if zlib.crc32(header + body) != expected:
            raise ValueError("CF/1 CRC mismatch")
        yield kind, body
        if kind == 3:
            if stream.read(1):
                raise ValueError("Trailing bytes after CF/1 terminal record")
            return
    raise ValueError("CF/1 record limit exceeded")


def _observation(body):
    if len(body) < OBSERVATION.size:
        raise ValueError("Invalid CF/1 observation")
    code, attempted, size, reserved, duration, response_size = OBSERVATION.unpack_from(body)
    if (code not in OUTCOMES or attempted not in (0, 1) or reserved or size not in (2, 3)
            or response_size > MAX_RESPONSE or len(body) != OBSERVATION.size + size + response_size):
        raise ValueError("Malformed CF/1 observation")
    req = body[OBSERVATION.size:OBSERVATION.size+size]
    response = body[OBSERVATION.size+size:] or None
    if code in (4, 5) and attempted:
        raise ValueError("CF/1 pre-exchange failure claims transmission")
    return req, Read(OUTCOMES[code], response, duration, bool(attempted)).validate()


def decode(stream, expected=None):
    profile = None
    observations = []
    result = None
    summary = None
    error = None
    try:
        for kind, body in _records(stream):
            if profile is None:
                if kind != 1:
                    raise ValueError("Missing CF/1 plan")
                profile = NativeProfile.decode(body)
                if expected is not None and profile != expected:
                    raise ValueError("CF/1 scope differs from authenticated request")
                continue
            if kind == 1:
                raise ValueError("Duplicate CF/1 plan")
            if kind == 2:
                if len(observations) >= MAX_STEPS:
                    raise ValueError("CF/1 observation limit exceeded")
                observations.append(_observation(body))
                continue
            if len(body) != FINAL.size:
                raise ValueError("Invalid CF/1 terminal length")
            stop, visited, flags, reserved, count, duration, *states = FINAL.unpack(body)
            if (stop not in STOPS or visited > len(profile.plan.frames) or flags & ~7 or reserved
                    or count != len(observations) or not count or duration > 3600000
                    or any(state not in STATES for state in states)
                    or any(states[visited:])):
                raise ValueError("Malformed CF/1 terminal summary")
            if sum(item.duration_ms for _, item in observations) > duration:
                raise ValueError("CF/1 duration shorter than observations")
            summary = {"stop_reason": STOPS[stop], "visited_frames": visited,
                       "completed": bool(flags & 1), "identity_rechecked": bool(flags & 2),
                       "all_frames_stable": bool(flags & 4), "steps": count,
                       "duration_ms": duration, "frame_states": [STATES[s] for s in states[:visited]]}
        if profile is None or summary is None:
            raise ValueError("Incomplete CF/1 capture")
        position = 0

        def observe(request):
            nonlocal position
            if position >= len(observations):
                raise ValueError("Missing CF/1 recipe observation")
            recorded, reply = observations[position]
            position += 1
            if recorded != request:
                raise ValueError("CF/1 request differs from deterministic recipe")
            return reply

        result = execute_plan(profile.plan, observe)
        if position != len(observations):
            raise ValueError("Unexpected CF/1 observations after recipe end")
        computed = {"stop_reason": result["stop_reason"], "visited_frames": len(result["frames"]),
                    "completed": result["completed"], "identity_rechecked": result["identity_rechecked"],
                    "all_frames_stable": result["all_frames_stable"], "steps": len(result["steps"]),
                    "duration_ms": summary["duration_ms"],
                    "frame_states": [frame["status"] for frame in result["frames"]]}
        if computed != summary:
            raise ValueError("CF/1 terminal summary disagrees with independent replay")
        result["duration_ms"] = summary["duration_ms"]
    except (ValueError, OSError, EOFError) as exc:
        error = str(exc)
    return {"schema": "camarodiag-functional-j1979-native-freeze-v1",
            "protocol": "SAE-J1979", "source": "OFFLINE_CF1",
            "profile": profile.report() if profile else None,
            "capture_integrity_verified": error is None,
            "device_credential_authenticated": False,
            "hardware_provenance_verified": False, "evidence_authenticated": False,
            "physical_vehicle_verified": False, "permits_programming": False,
            "observations_received": len(observations), "summary": summary,
            "result": result, "error": error}


def inspect_file(path):
    with Path(path).open("rb") as stream:
        before = os.fstat(stream.fileno())
        if before.st_size > MAX_CAPTURE:
            raise ValueError("CF/1 capture exceeds size limit")
        digest = hashlib.sha256()
        remaining = MAX_CAPTURE
        while True:
            block = stream.read(min(65536, remaining + 1))
            if not block:
                break
            remaining -= len(block)
            if remaining < 0:
                raise ValueError("CF/1 capture exceeds size limit")
            digest.update(block)
        stream.seek(0)
        report = decode(stream)
        after = os.fstat(stream.fileno())
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            raise ValueError("CF/1 capture changed during inspection")
        report["capture_sha256"] = digest.hexdigest()
    return report
