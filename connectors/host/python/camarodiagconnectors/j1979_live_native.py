"""CU/3 authentication and independent CL/1 raw Mode 01 evidence replay.

Only application evidence is verified. A saved stream cannot authenticate the
device credential, the vehicle or the physical transport. No I/O to an ECU.
"""
from __future__ import annotations

from dataclasses import dataclass
import hmac
import io
import secrets
import struct
import re
import zlib
from pathlib import Path

from .j1979_usb_auth import validate_profile
from .recovery_bundle import _regular

HEADER = struct.Struct("<2sBBHH")
CHANNEL = struct.Struct("<IIB")
AUTH_PREFIX = struct.Struct("<IIBI")
SCOPE = struct.Struct("<17sB32sHHI")
OBSERVATION = struct.Struct("<BBBBHBBIIHHI2s")
FINAL = struct.Struct("<BBH7I32s32H")
MAX_SAMPLES = 10_000
MAX_STEPS = MAX_SAMPLES + 8 + 1002
MAX_RAW_BYTES = 16 * 1024 * 1024
MAX_BODY = OBSERVATION.size + 4095
MAX_CAPTURE = MAX_RAW_BYTES + (MAX_STEPS + 2) * (HEADER.size + OBSERVATION.size + 4) + 1024
VIN_PATTERN = re.compile(r"[A-HJ-NPR-Z0-9]{17}", re.ASCII)
PHASES = {1: "INITIAL_VIN", 2: "INVENTORY", 3: "PERIODIC_VIN", 4: "SAMPLE", 5: "FINAL_VIN"}
STATES = {1: "PASSED", 2: "RAW", 3: "NEGATIVE", 4: "TIMEOUT", 5: "FAILED"}
STOPS = {0: None, 1: "INVALID_SCOPE", 2: "BUDGET_EXHAUSTED",
         3: "CONTEXT_CHANGED", 4: "TRANSPORT_ERROR", 5: "VIN_UNVERIFIED",
         6: "VIN_MISMATCH", 7: "SELECTED_PID_NOT_VERIFIED",
         8: "SELECTED_RAW_PID_NO_RESPONSE", 9: "SINK_STOPPED",
         10: "INVALID_SEQUENCE", 11: "EVIDENCE_LIMIT"}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def valid_vin(vin):
    return isinstance(vin, str) and VIN_PATTERN.fullmatch(vin) is not None


@dataclass(frozen=True)
class NativeLiveProfile:
    tx: int
    rx: int
    extended: bool
    vin: str
    pids: tuple[int, ...]
    cycles: int = 30
    interval_ms: int = 1000
    budget_ms: int = 180000

    def validate(self):
        validate_profile(self.tx, self.rx, self.extended, self.budget_ms)
        require(valid_vin(self.vin), "Invalid independently approved VIN")
        require(isinstance(self.pids, tuple) and 1 <= len(self.pids) <= 32 and
                all(type(p) is int and 1 <= p <= 255 and p % 32 for p in self.pids) and
                len(set(self.pids)) == len(self.pids), "Invalid selected raw PIDs")
        require(type(self.cycles) is int and 1 <= self.cycles <= 1000 and
                self.cycles * len(self.pids) <= MAX_SAMPLES, "Invalid sample count")
        require(type(self.interval_ms) is int and 100 <= self.interval_ms <= 60000 and
                1000 <= self.budget_ms <= 600000, "Invalid live timing")
        return self

    def scope(self):
        self.validate()
        return SCOPE.pack(self.vin.encode("ascii"), len(self.pids),
                          bytes(self.pids).ljust(32, b"\0"), self.cycles,
                          self.interval_ms, self.budget_ms)

    def capture_plan(self):
        return CHANNEL.pack(self.tx, self.rx, int(self.extended)) + self.scope()

    def auth_profile(self):
        return AUTH_PREFIX.pack(self.tx, self.rx, int(self.extended), self.budget_ms) + self.scope()

    @classmethod
    def from_capture_plan(cls, data):
        require(len(data) == CHANNEL.size + SCOPE.size, "Invalid CL/1 plan length")
        tx, rx, ext = CHANNEL.unpack_from(data)
        vin, count, pids, cycles, interval, budget = SCOPE.unpack_from(data, CHANNEL.size)
        require(ext in (0, 1) and 1 <= count <= 32 and not any(pids[count:]),
                "Noncanonical CL/1 scope")
        try:
            vin = vin.decode("ascii")
        except UnicodeError as exc:
            raise ValueError("Invalid CL/1 VIN") from exc
        profile = cls(tx, rx, bool(ext), vin, tuple(pids[:count]), cycles, interval, budget)
        require(profile.capture_plan() == data, "Noncanonical CL/1 plan")
        return profile

    @classmethod
    def from_auth_profile(cls, data):
        require(len(data) == AUTH_PREFIX.size + SCOPE.size, "Invalid CU/3 scope length")
        tx, rx, ext, budget = AUTH_PREFIX.unpack_from(data)
        profile = cls.from_capture_plan(CHANNEL.pack(tx, rx, ext) + data[AUTH_PREFIX.size:])
        require(profile.budget_ms == budget and profile.auth_profile() == data,
                "Contradictory CU/3 budget")
        return profile

    def report(self):
        return {"request_can_id": f"{self.tx:X}", "response_can_id": f"{self.rx:X}",
                "extended": self.extended, "expected_vin": self.vin, "selected_pids": list(self.pids),
                "requested_cycles": self.cycles, "interval_ms": self.interval_ms,
                "budget_ms": self.budget_ms, "raw_only": True}


def hello(profile, nonce=None):
    nonce = secrets.token_bytes(32) if nonce is None else nonce
    require(isinstance(nonce, bytes) and len(nonce) == 32 and any(nonce),
            "Invalid CU/3 client nonce")
    return b"CU\x03\x01" + profile.auth_profile() + nonce


def proof(request, challenge, key):
    require(isinstance(key, bytes) and len(key) == 32, "A provisioned 32-byte credential is required")
    require(isinstance(request, bytes) and len(request) == 4 + AUTH_PREFIX.size + SCOPE.size + 32
            and request[:4] == b"CU\x03\x01", "Invalid CU/3 hello")
    NativeLiveProfile.from_auth_profile(request[4:-32])
    require(isinstance(challenge, bytes) and len(challenge) == 68 and challenge[:4] == b"CU\x03\x02"
            and any(request[-32:]) and any(challenge[4:36]), "Invalid CU/3 challenge")
    transcript = request[4:] + challenge[4:36]
    require(hmac.compare_digest(challenge[36:], hmac.digest(key, b"L" + transcript, "sha256")),
            "CU/3 server credential proof rejected")
    return b"CU\x03\x03" + hmac.digest(key, b"l" + transcript, "sha256")


def read_exact(stream, size):
    result = stream.read(size)
    require(isinstance(result, bytes) and len(result) == size, "Truncated CL/1 record")
    return result


def records(stream):
    total = 0
    for seq in range(MAX_STEPS + 2):
        header = read_exact(stream, HEADER.size)
        magic, version, kind, actual, length = HEADER.unpack(header)
        require(magic == b"CL" and version == 1 and actual == seq and kind in (1, 2, 3)
                and 1 <= length <= MAX_BODY, "Invalid CL/1 framing or sequence")
        total += HEADER.size + length + 4
        require(total <= MAX_CAPTURE, "CL/1 capture exceeds byte limit")
        body = read_exact(stream, length)
        checksum, = struct.unpack("<I", read_exact(stream, 4))
        require(zlib.crc32(header + body) == checksum, "CL/1 CRC mismatch")
        yield kind, body
        if kind == 3:
            require(stream.read(1) == b"", "Trailing CL/1 bytes")
            return
    raise ValueError("CL/1 record count exceeded")


@dataclass(frozen=True)
class Event:
    phase: int
    state: int
    accepted: bool
    cycle: int
    pid: int
    start: int
    end: int
    timeout: int
    exchange_ms: int
    request: bytes
    response: bytes

    @classmethod
    def decode(cls, body):
        require(len(body) >= OBSERVATION.size, "Truncated CL/1 observation")
        phase, state, accepted, reserved, cycle, pid, padding, start, end, timeout, size, elapsed, request = (
            OBSERVATION.unpack_from(body))
        require(phase in PHASES and state in STATES and accepted in (0, 1) and not reserved and not padding
                and start <= end <= 604000 and 1 <= timeout <= 4000 and size <= 4095
                and len(body) == OBSERVATION.size + size and (state <= 3) == bool(size)
                and (bool(accepted) or state == 5), "Malformed CL/1 observation")
        return cls(phase, state, bool(accepted), cycle, pid, start, end, timeout, elapsed,
                   request, body[OBSERVATION.size:])

    def report(self):
        return {"phase": PHASES[self.phase], "status": STATES[self.state],
                "accepted": self.accepted, "cycle": self.cycle if self.phase == 4 else None,
                "pid": self.pid if self.phase == 4 else None,
                "started_elapsed_ms": self.start, "elapsed_ms": self.end,
                "duration_ms": self.end - self.start, "exchange_duration_ms": self.exchange_ms,
                "timeout_ms": self.timeout, "request_hex": self.request.hex().upper(),
                "response_hex": self.response.hex().upper() if self.response else None}


def response_shape(event):
    req, response = event.request, event.response
    if not response:
        return event.state if event.state in (4, 5) else 0
    if response[0] == 0x7F:
        return 3 if len(response) == 3 and response[1] == req[0] and response[2] not in (0, 0x78) else 0
    if req == b"\x09\x02":
        if len(response) != 20 or response[:3] != b"\x49\x02\x01":
            return 0
        try:
            observed = response[3:].decode("ascii")
        except UnicodeError:
            return 0
        return 1 if valid_vin(observed) else 0
    if req[0] != 1 or len(response) < 3 or response[:2] != bytes((0x41, req[1])):
        return 0
    if req[1] % 32 == 0:
        return 1 if len(response) == 6 and not (req[1] == 224 and response[-1] & 1) else 0
    return 2


class Replay:
    """Reconstruct all allowed transitions and the terminal counters.

    The recorder's absolute monotonic clock is relative to session start.
    Rejected events cannot yield measurements. A context-loss declaration
    between exchanges is not independently proved by the application bytes.
    """
    def __init__(self, profile):
        self.profile = profile
        self.stage = 1
        self.stop = 0
        self.steps = self.samples = self.raw = self.negative = self.timeout = 0
        self.duration = self.raw_bytes = self.completed_cycles = 0
        self.initial = self.final = self.inventory_complete = self.completed = False
        self.advertised = set()
        self.raw_per_pid = [0] * 32
        self.base = self.index = 0
        self.last_identity = 0
        self.cycle_start = None
        self.early_identity = False
        self.rows = []
        self.sample_rows = []

    def abort(self, stop, now):
        self.stop, self.stage, self.duration = stop, 7, now

    def expected_phase(self, now):
        if self.stage == 7:
            return 7
        if now >= self.profile.budget_ms:
            return 7
        if self.stage == 4 and self.index == 0:
            if self.cycle_start is not None and now - self.cycle_start < self.profile.interval_ms:
                return 6
            if now - self.last_identity >= 30000:
                return 3
        return self.stage

    def next_request(self, phase):
        if phase in (1, 3, 5):
            return b"\x09\x02"
        if phase == 2:
            return bytes((1, self.base))
        if phase == 4:
            return bytes((1, self.profile.pids[self.index]))
        raise ValueError("CL/1 request after completion or during wait")

    def observe(self, event):
        require(event.start >= self.duration and self.stage != 7 and self.steps < MAX_STEPS,
                "Overlapping or post-terminal CL/1 observation")
        phase = self.expected_phase(event.start)
        require(event.phase == phase and phase in PHASES, "Unexpected CL/1 phase")
        req = self.next_request(phase)
        require(req == event.request and event.timeout <= self.profile.budget_ms - event.start,
                "CL/1 request or deadline outside approved scope")
        require(event.cycle == (self.completed_cycles + 1 if phase == 4 else 0)
                and event.pid == (req[1] if phase == 4 else 0), "Incorrect CL/1 sample index")
        if phase == 4 and self.index == 0:
            self.cycle_start = event.start
        self.steps += 1
        self.duration = event.end
        self.rows.append(event.report())
        if event.end >= self.profile.budget_ms:
            require(not event.accepted, "Late CL/1 sample accepted")
            self.abort(2, event.end)
            return
        if event.end - event.start > event.timeout or event.exchange_ms > event.timeout:
            require(not event.accepted, "CL/1 exchange exceeded deadline")
            self.abort(4, event.end)
            return
        if not event.accepted:
            require(event.state == 5 and not event.response, "Rejected CL/1 observation carries evidence")
            self.abort(4, event.end)
            return
        require(event.state in (1, 2, 3, 4) and response_shape(event) == event.state,
                "CL/1 application response not correlated")
        self.raw_bytes += len(event.response)
        require(self.raw_bytes <= MAX_RAW_BYTES, "CL/1 raw evidence limit")
        if phase in (1, 3, 5):
            if event.state != 1:
                self.abort(5, event.end)
                return
            if event.response[3:] != self.profile.vin.encode("ascii"):
                self.abort(6, event.end)
                return
            self.last_identity = event.end
            if phase == 1:
                self.initial, self.stage = True, 2
            elif phase == 3:
                self.early_identity, self.stage = True, 4
            else:
                self.final, self.stage = True, 7
                if not all(self.raw_per_pid[:len(self.profile.pids)]):
                    self.stop = 8
                else:
                    self.completed = True
            return
        if phase == 2:
            if event.state != 1:
                self.abort(7, event.end)
                return
            bitmap = event.response[2:]
            self.advertised.update(self.base + bit + 1 for bit in range(31)
                                   if bitmap[bit // 8] & (0x80 >> (bit % 8)))
            more = bool(bitmap[3] & 1)
            self.inventory_complete = not more
            if set(self.profile.pids) <= self.advertised:
                self.stage = 4
            elif not more or self.base == 224:
                self.abort(7, event.end)
            else:
                self.base += 32
            return
        require(phase == 4 and event.state in (2, 3, 4), "Invalid CL/1 measurement status")
        self.samples += 1
        if event.state == 2:
            self.raw += 1
            self.raw_per_pid[self.index] += 1
        elif event.state == 3:
            self.negative += 1
        else:
            self.timeout += 1
        self.sample_rows.append({"cycle": event.cycle, "pid": event.pid, "elapsed_ms": event.end,
                                 "status": STATES[event.state],
                                 "response_hex": event.response.hex().upper() if event.response else None})
        self.index += 1
        if self.index == len(self.profile.pids):
            self.index = 0
            self.early_identity = False
            self.completed_cycles += 1
            if self.completed_cycles == self.profile.cycles:
                self.stage = 5

    def summary(self):
        bitmap = bytearray(32)
        for pid in self.advertised:
            bitmap[pid // 8] |= 1 << (pid % 8)
        flags = int(self.initial) | int(self.final) << 1 | int(self.inventory_complete) << 2 | int(self.completed) << 3
        return FINAL.pack(self.stop, flags, self.completed_cycles, self.steps, self.samples, self.raw,
                          self.negative, self.timeout, self.duration, self.raw_bytes, bytes(bitmap), *self.raw_per_pid)

    def finish(self, body):
        require(len(body) == FINAL.size, "Invalid CL/1 final length")
        stop, flags, completed, steps, samples, raw, neg, timeout, duration, byte_count, bitmap, *per_pid = FINAL.unpack(body)
        require(stop in STOPS and stop not in (1, 9, 10, 11) and not flags & ~15
                and self.duration <= duration <= 604000, "Invalid CL/1 terminal state")
        if self.stage != 7:
            require(stop in (2, 3), "CL/1 incomplete sequence without external stop")
            require(stop != 2 or self.profile.budget_ms - duration <= 1, "Premature CL/1 budget stop")
            self.abort(stop, duration)
        require(self.summary() == body, "CL/1 terminal counters differ from independent replay")
        return {"stop_reason": STOPS[self.stop], "completed": self.completed,
                "initial_identity_verified": self.initial, "final_identity_verified": self.final,
                "inventory_complete": self.inventory_complete, "completed_cycles": self.completed_cycles,
                "steps": self.steps, "samples": self.samples, "raw": self.raw,
                "negative": self.negative, "timeout": self.timeout, "duration_ms": self.duration,
                "raw_bytes": self.raw_bytes, "advertised_pids": sorted(self.advertised),
                "raw_per_pid": [{"pid": pid, "count": self.raw_per_pid[i]}
                                for i, pid in enumerate(self.profile.pids)]}


def decode(stream, expected=None):
    profile = replay = summary = None
    error = None
    received = 0
    try:
        for kind, body in records(stream):
            if profile is None:
                require(kind == 1, "Missing CL/1 plan")
                profile = NativeLiveProfile.from_capture_plan(body)
                require(expected is None or profile == expected, "CL/1 scope differs from authenticated profile")
                replay = Replay(profile)
            elif kind == 2:
                received += 1
                replay.observe(Event.decode(body))
            elif kind == 3:
                summary = replay.finish(body)
            else:
                raise ValueError("Duplicate CL/1 plan")
        require(profile is not None and summary is not None, "Incomplete CL/1 capture")
    except (ValueError, OSError, EOFError, struct.error) as exc:
        error = str(exc)
    result = None
    if error is None:
        result = {"schema": "camarodiag-j1979-live-inspection-v1", "verified": True,
                  "raw_only": True, "declared_vin": profile.vin,
                  "channel": {"kind": "can", "tx": profile.tx, "rx": profile.rx},
                  "completed": replay.completed, "final_identity_verified": replay.final,
                  "samples": replay.sample_rows, "observations": replay.rows,
                  "physical_vehicle_verified": False, "authenticated": False}
    return {"schema": "camarodiag-functional-j1979-native-live-v1", "protocol": "SAE-J1979",
            "source": "OFFLINE_CL1", "profile": profile.report() if profile else None,
            "capture_integrity_verified": error is None, "device_credential_authenticated": False,
            "hardware_provenance_verified": False, "evidence_authenticated": False,
            "physical_vehicle_verified": False, "permits_programming": False,
            "observations_received": received, "summary": summary if error is None else None,
            "result": result, "error": error}


def inspect_file(path, expected=None):
    source = Path(path)
    raw, stamp = _regular(source, MAX_CAPTURE)
    report = decode(io.BytesIO(raw), expected=expected)
    again, final = _regular(source, MAX_CAPTURE)
    require(raw == again and stamp == final, "CL/1 changed during inspection")
    report["capture_sha256"] = stamp.digest
    return report
