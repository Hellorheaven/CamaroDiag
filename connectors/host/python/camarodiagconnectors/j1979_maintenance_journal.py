"""Independent, offline CM/1 native maintenance journal inspection.

No hardware access, automatic retry, or assertion of local authorization.
CRC verifies consistency, not origin or the fact a command reached a vehicle.
"""
from __future__ import annotations

from dataclasses import dataclass
import re
import struct
import zlib

HEADER = struct.Struct("<2sBBBBHI")
SCOPE = struct.Struct("<BBIII17s")
ROW = struct.Struct("<BBBBBBHIIH")
SUMMARY_SIZE = 20
MAX_REPLY = 512
MAX_ROWS = 16
MAX_RECORD = HEADER.size + SCOPE.size + SUMMARY_SIZE + MAX_ROWS * (ROW.size + MAX_REPLY) + 4
MAX_JOURNAL = 4 * MAX_RECORD
VIN = re.compile(r"[A-HJ-NPR-Z0-9]{17}", re.ASCII)
KINDS = {1: "OPEN", 2: "BASELINE", 3: "INTENT", 4: "TERMINAL"}
OUTCOMES = ("RUNNING", "NOT_TESTED", "REFUSED", "UNCERTAIN", "ACKNOWLEDGED_PARTIAL",
            "ACKNOWLEDGED_READBACK", "ACKNOWLEDGED_EVAP")
REASONS = ("NONE", "INVALID_SCOPE", "CONTEXT", "DEADLINE", "SEQUENCE", "IDENTITY",
           "STATIONARY", "UNSUPPORTED", "BASELINE", "JOURNAL", "TRANSPORT",
           "NEGATIVE", "READBACK", "DTC_REMAINING")
STAGES = {1: "INITIAL", 2: "BASELINE", 4: "RECHECK", 6: "COMMAND", 7: "READBACK"}
STATUSES = {1: "POSITIVE", 2: "NEGATIVE", 3: "TIMEOUT", 4: "FAULT"}
FLAG_A = ("baseline_persisted", "intent_persisted", "attempted", "acknowledged",
          "initial_vin", "rechecked_vin", "final_vin", "before_confirmed")
FLAG_B = ("after_confirmed", "after_pending", "after_confirmed_empty", "after_pending_empty",
          "physical_stop_declared", "recovery_required")
CLEAR = [(1, "0902"), (2, "03"), (2, "07"), (2, "0A"), (2, "0101"), (2, "010D"), (2, "010C"),
         (4, "0902"), (4, "010D"), (4, "010C"), (6, "04"),
         (7, "03"), (7, "07"), (7, "0A"), (7, "0101"), (7, "0902")]
EVAP = [(1, "0902"), (2, "0800"), (2, "010D"), (2, "010C"),
        (4, "0902"), (4, "0800"), (4, "010D"), (4, "010C"), (6, "0801")]


def require(value, message):
    if not value:
        raise ValueError(message)


@dataclass(frozen=True)
class Scope:
    operation: int
    extended: bool
    tx: int
    rx: int
    budget_ms: int
    vin: str

    @classmethod
    def parse(cls, data):
        require(len(data) == SCOPE.size, "Invalid CM/1 scope size")
        operation, ext, tx, rx, budget, vin = SCOPE.unpack(data)
        require(operation in (1, 2) and ext in (0, 1), "Invalid operation or addressing")
        require((tx, rx) == ((0x18DA10F1, 0x18DAF110) if ext else (0x7E0, 0x7E8)),
                "Primary physical ECM required")
        require(1000 <= budget <= 60000, "Invalid maintenance budget")
        try:
            vin = vin.decode("ascii")
        except UnicodeError as exc:
            raise ValueError("Invalid CM/1 VIN") from exc
        require(VIN.fullmatch(vin), "Invalid CM/1 VIN")
        return cls(operation, bool(ext), tx, rx, budget, vin)

    @property
    def plan(self):
        return CLEAR if self.operation == 1 else EVAP

    @property
    def baseline_boundary(self):
        return 7 if self.operation == 1 else 4

    @property
    def command_boundary(self):
        return 10 if self.operation == 1 else 8

    def report(self):
        return {"operation": "CLEAR" if self.operation == 1 else "EVAP",
                "extended": self.extended, "request_can_id": f"{self.tx:X}",
                "response_can_id": f"{self.rx:X}", "budget_ms": self.budget_ms,
                "expected_vin": self.vin}


@dataclass(frozen=True)
class Event:
    stage: int
    status: int
    request: bytes
    response: bytes
    start: int
    end: int
    timeout: int

    def report(self):
        return {"stage": STAGES[self.stage], "status": STATUSES[self.status],
                "request_hex": self.request.hex().upper(),
                "response_hex": self.response.hex().upper() if self.response else None,
                "started_ms": self.start, "ended_ms": self.end, "timeout_ms": self.timeout}


def parse_summary(data, count):
    require(len(data) == SUMMARY_SIZE and not any(data[6:8]) and not any(data[12:]),
            "Malformed CM/1 summary reserved fields")
    outcome, reason, observations, nrc, a, b = data[:6]
    require(outcome < len(OUTCOMES) and reason < len(REASONS) and observations == count
            and not b & 0xC0, "Invalid CM/1 summary")
    value = {"outcome": OUTCOMES[outcome], "reason": REASONS[reason],
             "observations": observations, "nrc": nrc, "duration_ms": struct.unpack_from("<I", data, 8)[0]}
    value.update((name, bool(a & (1 << i))) for i, name in enumerate(FLAG_A))
    value.update((name, bool(b & (1 << i))) for i, name in enumerate(FLAG_B))
    return value


@dataclass(frozen=True)
class Record:
    kind: int
    scope: Scope
    summary: dict | None
    rows: tuple[Event, ...]


def records(data):
    require(type(data) is bytes and 0 < len(data) <= MAX_JOURNAL, "Invalid CM/1 journal size")
    pos = 0
    prior_kind = 0
    for seq in range(4):
        if pos == len(data):
            return
        require(len(data)-pos >= HEADER.size, "Truncated CM/1 header")
        magic, version, kind, number, count, reserved, length = HEADER.unpack_from(data, pos)
        require(magic == b"CM" and version == 1 and number == seq and not reserved
                and kind in KINDS and count <= MAX_ROWS, "Malformed CM/1 header")
        require((kind == 1 if seq == 0 else kind == prior_kind+1 or kind == 4)
                and prior_kind != 4, "Unexpected CM/1 journal record")
        require(SCOPE.size <= length <= MAX_RECORD-HEADER.size-4, "CM/1 body size limit")
        end = pos+HEADER.size+length
        require(end+4 <= len(data), "Truncated CM/1 record")
        require(zlib.crc32(data[pos:end]) == struct.unpack_from("<I", data, end)[0], "CM/1 CRC mismatch")
        body = data[pos+HEADER.size:end]
        scope = Scope.parse(body[:SCOPE.size])
        cursor = SCOPE.size
        summary = None
        rows = []
        if kind == 1:
            require(count == 0 and cursor == len(body), "Unexpected CM/1 open payload")
        else:
            require(cursor+SUMMARY_SIZE <= len(body), "Truncated CM/1 summary")
            summary = parse_summary(body[cursor:cursor+SUMMARY_SIZE], count)
            cursor += SUMMARY_SIZE
            for _ in range(count):
                require(cursor+ROW.size <= len(body), "Truncated CM/1 observation header")
                stage, status, n, a, b, padding, size, start, finish, timeout = ROW.unpack_from(body, cursor)
                cursor += ROW.size
                require(stage in STAGES and status in STATUSES and n in (1, 2) and not padding
                        and (n == 2 or b == 0) and size <= MAX_REPLY and cursor+size <= len(body)
                        and start <= finish and 1 <= timeout <= 1800, "Malformed CM/1 observation")
                response = body[cursor:cursor+size]
                cursor += size
                rows.append(Event(stage, status, bytes((a, b))[:n], response, start, finish, timeout))
            require(cursor == len(body), "Trailing CM/1 record bytes")
        yield Record(kind, scope, summary, tuple(rows))
        prior_kind = kind
        pos = end+4
    require(pos == len(data), "Trailing CM/1 journal bytes")


def response_shape(scope, event):
    req, reply = event.request, event.response
    if not reply:
        return event.status if event.status in (3, 4) else 0
    if reply[0] == 0x7F:
        return 2 if len(reply) == 3 and reply[1] == req[0] and reply[2] not in (0, 0x78) else 0
    if req == b"\x04":
        return 1 if reply == b"\x44" else 0
    if req == b"\x08\x01":
        return 1 if reply == b"\x48\x01" else 0
    if req[0] in (3, 7, 10):
        return 1 if (len(reply) >= 2 and reply[0] == req[0]+0x40 and len(reply) == 2+2*reply[1]
                     and all(a or b for a, b in zip(reply[2::2], reply[3::2]))) else 0
    if req == b"\x09\x02":
        if len(reply) != 20 or reply[:3] != b"\x49\x02\x01":
            return 0
        try:
            return 1 if VIN.fullmatch(reply[3:].decode("ascii")) else 0
        except UnicodeError:
            return 0
    sizes = {b"\x01\x01": 6, b"\x01\x0D": 3, b"\x01\x0C": 4, b"\x08\x00": 6}
    return 1 if len(reply) == sizes.get(req, 0) and reply[:2] == bytes((req[0]+0x40, req[1])) else 0


def replay(scope, rows, *, baseline, intent):
    state = dict.fromkeys(FLAG_A + FLAG_B, False)
    state.update(outcome="RUNNING", reason="NONE", observations=0, nrc=0, duration_ms=0)
    now = 0
    recheck_times = {}
    def abort(reason):
        state["reason"] = reason
        state["outcome"] = ("ACKNOWLEDGED_PARTIAL" if state["acknowledged"] else
                            "UNCERTAIN" if state["attempted"] or state["intent_persisted"] else "NOT_TESTED")
    require(len(rows) <= len(scope.plan), "Too many maintenance observations")
    for i, event in enumerate(rows):
        stage, cmd = scope.plan[i]
        req = bytes.fromhex(cmd)
        require(state["outcome"] == "RUNNING" and event.stage == stage and event.request == req,
                "Unexpected request or request after terminal")
        require(event.start >= now and event.start < scope.budget_ms
                and event.timeout <= scope.budget_ms-event.start, "Invalid maintenance deadline")
        if i == scope.baseline_boundary:
            require(baseline, "Recheck without persisted baseline")
            state["baseline_persisted"] = True
        if i == scope.command_boundary:
            require(intent and baseline and set(recheck_times) == {"0902", "010D", "010C"}
                    and all(event.start-t+event.timeout <= 5000 for t in recheck_times.values()),
                    "Command without fresh recheck and durable intent")
            state["intent_persisted"] = state["attempted"] = True
        now = event.end
        state["observations"] += 1
        state["duration_ms"] = now
        if now >= scope.budget_ms or now-event.start > event.timeout:
            abort("DEADLINE")
            continue
        if event.status == 4:
            require(not event.response, "Fault row carries data")
            abort("TRANSPORT")
            continue
        shape = response_shape(scope, event)
        if shape != event.status:
            abort("TRANSPORT")
            continue
        positive = event.status == 1
        if stage == 6:
            if event.status == 2:
                state.update(outcome="REFUSED", reason="NEGATIVE", nrc=event.response[2])
            elif not positive:
                abort("TRANSPORT")
            else:
                state["acknowledged"] = True
                if scope.operation == 2:
                    state["outcome"] = "ACKNOWLEDGED_EVAP"
        elif cmd == "0902":
            if not positive or event.response[3:] != scope.vin.encode("ascii"):
                abort("IDENTITY")
            elif stage == 1:
                state["initial_vin"] = True
            elif stage == 4:
                state["rechecked_vin"] = True
                recheck_times[cmd] = now
            else:
                state["final_vin"] = True
        elif cmd in ("010D", "010C"):
            if not positive or any(event.response[2:]):
                abort("STATIONARY")
            elif stage == 4:
                recheck_times[cmd] = now
        elif cmd == "0800":
            if not positive or not event.response[2] & 0x80:
                abort("UNSUPPORTED")
        elif stage == 2 and cmd == "03":
            if not positive:
                abort("BASELINE")
            else:
                state["before_confirmed"] = True
        elif stage == 7 and cmd in ("03", "07"):
            flag = "after_confirmed" if cmd == "03" else "after_pending"
            state[flag] = positive
            state[flag+"_empty"] = positive and event.response[1] == 0
        if i == len(scope.plan)-1 and scope.operation == 1 and state["outcome"] == "RUNNING":
            if state["after_confirmed"] and state["after_pending"] and state["final_vin"]:
                state["outcome"] = "ACKNOWLEDGED_READBACK"
                state["reason"] = ("NONE" if state["after_confirmed_empty"] and state["after_pending_empty"]
                                   else "DTC_REMAINING")
            else:
                abort("READBACK")
    return state


def inspect(data, *, expected_vin):
    require(isinstance(expected_vin, str) and VIN.fullmatch(expected_vin), "Independent VIN required")
    entries = list(records(data))
    require(entries and entries[0].scope.vin == expected_vin, "CM/1 scope differs from independent VIN")
    scope = entries[0].scope
    previous = ()
    baseline = intent = False
    terminal = None
    for record in entries[1:]:
        require(record.scope == scope and record.rows[:len(previous)] == previous,
                "CM/1 scope or prior evidence changed")
        state = replay(scope, record.rows, baseline=baseline, intent=intent)
        claimed = record.summary
        if record.kind == 2:
            require(len(record.rows) == scope.baseline_boundary and state["outcome"] == "RUNNING",
                    "Baseline prerequisites not satisfied")
            require(state == claimed, "Baseline summary differs from replay")
            baseline = True
        elif record.kind == 3:
            require(baseline and len(record.rows) == scope.command_boundary and state["outcome"] == "RUNNING",
                    "Intent prerequisites not satisfied")
            require(state == claimed, "Intent summary differs from replay")
            intent = True
        else:
            require(record.kind == 4 and claimed["physical_stop_declared"], "Terminal missing shutdown declaration")
            if state["outcome"] == "RUNNING":
                # An external context/deadline/journal stop is a declaration,
                # not an independently verified physical event.
                require(claimed["reason"] in ("CONTEXT", "DEADLINE", "SEQUENCE", "STATIONARY", "JOURNAL", "TRANSPORT"),
                        "Unsupported external terminal reason")
                require(claimed["duration_ms"] >= state["duration_ms"], "Invalid terminal duration")
                if claimed["reason"] == "DEADLINE":
                    require(claimed["duration_ms"] >= scope.budget_ms, "Premature deadline declaration")
                if len(record.rows) == scope.baseline_boundary and baseline:
                    state["baseline_persisted"] = claimed["baseline_persisted"]
                if len(record.rows) == scope.command_boundary and intent:
                    state["intent_persisted"] = claimed["intent_persisted"]
                state["outcome"] = ("ACKNOWLEDGED_PARTIAL" if state["acknowledged"] else
                                    "UNCERTAIN" if state["attempted"] or state["intent_persisted"] else "NOT_TESTED")
                state["reason"] = claimed["reason"]
                state["duration_ms"] = claimed["duration_ms"]
            state["physical_stop_declared"] = True
            state["recovery_required"] = claimed["recovery_required"]
            require(state["outcome"] != "UNCERTAIN" or state["recovery_required"], "Uncertain command without recovery")
            require(state == claimed, "Terminal summary differs from replay")
            terminal = state
        previous = record.rows
    # A missing terminal can never resolve an open/intent journal.
    return {"schema": "camarodiag-j1979-native-maintenance-inspection-v1",
            "protocol": "SAE-J1979", "scope": scope.report(),
            "record_consistency_verified": True, "records": [KINDS[r.kind] for r in entries],
            "baseline_record_present": baseline, "intent_record_present": intent,
            "terminal_present": terminal is not None, "terminal": terminal,
            "observations": [event.report() for event in previous],
            "recovery_required": terminal is None or terminal["recovery_required"],
            "physical_vehicle_verified": False, "physical_stop_independently_verified": False,
            "durable_before_tx_independently_verified": False, "operator_consent_verified": False,
            "monitor_completion_verified": False, "evidence_authenticated": False,
            "permits_automatic_retry": False, "permits_programming": False}
