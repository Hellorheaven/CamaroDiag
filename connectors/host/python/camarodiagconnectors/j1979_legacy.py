"""Classic SAE J1979 on an external ELM-compatible serial adapter.

ISO 15031-5:2006 clause 6 application records; not ISO-TP CAN.
Headerless records are anonymous. No mutation, no fallback, no claim
that a particular ECU sent a record.
"""
from __future__ import annotations

import re
import time

from .j1979_campaign_evidence import decode_observation

PROTOCOLS = {1: "SAE J1850 PWM", 2: "SAE J1850 VPW", 3: "ISO 9141-2",
             4: "ISO 14230-4 slow init", 5: "ISO 14230-4 fast init"}


def allowed_read(command: str) -> bool:
    if not re.fullmatch(r"(?:[0-9A-F]{2}){1,7}", command):
        return False
    data = bytes.fromhex(command)
    mode = data[0]
    return (len(data) == 2 and mode in (1, 6, 9)
            or len(data) == 3 and mode == 2 and data[2] == 0
            or len(data) == 3 and mode == 5 and data[2] in (1, 2, 4, 8, 16, 32, 64, 128)
            or len(data) == 7 and mode == 8 and data[1] % 32 == 0 and data[2:] == bytes(5)
            or len(data) == 1 and mode in (3, 7, 10))


def records(command: str, raw: str) -> list[bytes]:
    """Remove only exact command echo/known adapter status. Reject mixed errors."""
    lines = [line.strip().upper() for line in raw.replace(">", "\n").splitlines()]
    result = []
    for line in lines:
        compact = "".join(line.split())
        if not compact or compact == command or compact == "SEARCHING...":
            continue
        if not re.fullmatch(r"(?:[0-9A-F]{2}){1,7}", compact):
            raise ValueError("Malformed or unavailable legacy response")
        result.append(bytes.fromhex(compact))
    if not result:
        raise ValueError("No legacy response")
    return result


class LegacyElmSerial:
    """Own one serial port for the campaign. Explicit protocol only."""
    def __init__(self, port: str, baud: int, protocol: int, *, headered: bool = False):
        if protocol not in PROTOCOLS or baud not in (9600, 19200, 38400, 57600, 115200, 230400):
            raise ValueError("Unsupported legacy protocol or serial baud")
        import serial
        self._serial = serial.Serial(port, baudrate=baud, timeout=0.1, write_timeout=1.0)
        self.protocol = protocol
        self.headered = headered
        self._ready = False

    def __enter__(self):
        try:
            self._serial.reset_input_buffer()
            # Reset the adapter, not the ECU. Never send CAN headers or use
            # automatic protocol detection after the explicit selection.
            banner = self._command("ATZ", 4.0)
            if not re.search(r"ELM|STN|OBDLINK", banner, re.I):
                raise ValueError("Unrecognized ELM-compatible adapter reset")
            header = "616AF1" if self.protocol == 1 else "C033F1" if self.protocol in (4, 5) else "686AF1"
            config = ["ATE0", "ATL0", "ATS1", "ATH1" if self.headered else "ATH0", f"ATSP{self.protocol}"]
            if self.headered:
                config += ["ATSH" + header]
            for cmd in config:
                reply = self._command(cmd, 2.0)
                if [s.strip().upper() for s in reply.splitlines() if s.strip() and
                        s.strip().upper() != cmd] != ["OK"]:
                    raise ValueError("Adapter rejected configuration: " + cmd)
            self._ready = True
            return self
        except BaseException:
            self._serial.close()
            raise

    def __exit__(self, *_):
        # This is a dedicated standalone adapter session; do not reconfigure
        # another process's transport or claim prior settings were restored.
        self._ready = False
        self._serial.close()

    def _command(self, command: str, timeout: float) -> str:
        deadline = time.monotonic() + timeout
        wire = (command + "\r").encode("ascii")
        if self._serial.write(wire) != len(wire):
            self._ready = False
            raise OSError("Incomplete ELM write")
        received = bytearray()
        while time.monotonic() < deadline:
            chunk = self._serial.read(1)
            if chunk == b">":
                return received.decode("ascii", errors="strict")
            received.extend(chunk)
            if len(received) > 32768:
                self._ready = False
                raise OSError("ELM response exceeds bound")
        # No prompt means the serial session is no longer synchronized.
        # Never continue with another request or retry automatically.
        self._ready = False
        raise OSError("ELM prompt timeout")

    def exchange(self, command: str, timeout: float) -> str:
        if not self._ready or not allowed_read(command) or not 0 < timeout <= 4:
            raise ValueError("Unsupported legacy read")
        return self._command(command, timeout)

    def run_vehicle_mutation(self, operation, *, expected_vin, engine_source, documented_sources,
                             warning_acknowledged, confirmed_yes, save_baseline,
                             require_current=lambda: None):
        if not self._ready or not self.headered:
            raise ValueError("Dedicated headered legacy session required")
        from .j1979_legacy_mutation import mutation_recipe
        attempted = False

        def transmit():
            nonlocal attempted
            if attempted or not self._ready:
                raise RuntimeError("One authorized legacy request only")
            command = {"clear": "04", "evap": "08010000000000"}[operation]
            require_current()
            attempted = True
            return self._command(command, 4.0)

        return mutation_recipe(self.protocol, self.exchange, self.observed_protocol, transmit,
                               operation=operation, expected_vin=expected_vin,
                               engine_source=engine_source, documented_sources=documented_sources,
                               warning_acknowledged=warning_acknowledged, confirmed_yes=confirmed_yes,
                               save_baseline=save_baseline, require_current=require_current)

    def observed_protocol(self, timeout: float = 2.0) -> int:
        if not self._ready:
            raise RuntimeError("No synchronized ELM session")
        if not 0 < timeout <= 2.0:
            raise ValueError("Invalid protocol probe timeout")
        raw = self._command("ATDPN", timeout)
        values = [s.strip().upper() for s in raw.splitlines()
                  if s.strip() and s.strip().upper() != "ATDPN"]
        if values != [str(self.protocol)]:
            raise ValueError("Adapter did not confirm the selected fixed protocol")
        return self.protocol


def scan(exchange, protocol_check, expected_protocol: int, *, budget=180.0,
         clock=time.monotonic, source: int | None = None, expected_vin: str | None = None) -> dict:
    if expected_protocol not in PROTOCOLS or not 0 < budget <= 600:
        raise ValueError("Invalid legacy campaign")
    from .j1979_legacy_discovery import valid_source
    from .j1979_legacy_frames import response_frames
    if source is not None and not valid_source(source):
        raise ValueError("Invalid selected response source")
    if expected_vin is not None and (source is None or
            not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", expected_vin)):
        raise ValueError("A documented VIN requires a selected legacy response source")
    attribution = "ANONYMOUS" if source is None else "SELECTED_RESPONSE_HEADER"
    source_hex = None if source is None else f"{source:02X}"
    start = clock()
    steps = []
    inventories = []
    other_sources = set()
    stop = None
    observed_protocol = None
    vin_candidate = None

    def read(command, validate, *, single=False):
        nonlocal stop
        if stop is not None:
            return []
        if not allowed_read(command):
            raise ValueError("Non-read legacy request")
        left = budget - (clock() - start)
        if left <= 0:
            stop = "BUDGET_EXHAUSTED"
            return []
        begun = clock()
        raw = None
        frames = []
        valid = []
        status, detail = "NOT_TESTED", None
        try:
            raw = exchange(command, min(left, 4.0))
            if clock() - start >= budget:
                stop = "BUDGET_EXHAUSTED"
                detail = "LATE_RESPONSE"
                steps.append({"request_hex": command, "raw_response": raw, "status": status,
                              "detail": detail, "records_hex": [],
                              "duration_ms": round((clock() - begun) * 1000)})
                return []
            if source is None:
                parsed = records(command, raw)
            else:
                frames = response_frames(expected_protocol, command, raw)
                other_sources.update(frame.source for frame in frames if frame.source != source)
                parsed = [frame.payload for frame in frames if frame.source == source]
            mode = int(command[:2], 16)
            if not parsed:
                detail = "NO_SELECTED_RESPONSE"
            elif source is not None and len(parsed) == 1 and len(parsed[0]) == 3 and (
                    parsed[0][:2] == bytes((0x7F, mode)) and parsed[0][2] not in (0, 0x78)):
                detail = f"NRC_{parsed[0][2]:02X}"
            else:
                valid = [row for row in parsed if validate(row)]
                if len(valid) != len(parsed) or not valid or (
                        source is not None and single and len(valid) != 1):
                    detail = "MIXED_OR_UNMATCHED_RECORDS"
                    status = "FAILED" if source is not None or valid else "NOT_TESTED"
                    valid = []
                else:
                    status = "PASSED"
        except KeyboardInterrupt:
            stop = detail = "CANCELLED"
        except ValueError as exc:
            detail = str(exc)
            if source is not None:
                status = "FAILED"
        except OSError as exc:
            status, detail, stop = "FAILED", type(exc).__name__, "TRANSPORT_ERROR"
        steps.append({"request_hex": command, "raw_response": raw, "status": status,
                      "detail": detail, "records_hex": [r.hex().upper() for r in valid],
                      "frames": [frame.evidence() for frame in frames],
                      "selected_response_source": source_hex,
                      "duration_ms": round((clock() - begun) * 1000)})
        return valid

    def inventory(mode, prefix, offset, *, suffix="", first=None):
        found = set()
        pages = 0
        complete = False
        for base in range(0, 0x100, 32):
            def valid(row):
                return (len(row) == offset + 4 and row[:2] == bytes((mode + 0x40, base))
                        and prefix(row) and not (base == 0xE0 and row[-1] & 1))
            page = first if base == 0 and first is not None else read(
                f"{mode:02X}{base:02X}" + suffix, valid, single=True)
            if not page:
                break
            pages += 1
            for row in page:
                for bit in range(31):
                    if row[offset + bit // 8] & (0x80 >> (bit % 8)):
                        found.add(base + bit + 1)
            if not any(row[-1] & 1 for row in page):
                complete = True
                break
        inventories.append({"mode": mode, "sensor_mask": int(suffix, 16) if mode == 5 else None,
                            "state": "COMPLETE" if complete else "PARTIAL" if pages else "UNAVAILABLE",
                            "verified_pages": pages, "advertised_ids": sorted(found),
                            "responder_attribution": attribution, "source": source_hex})
        return sorted(found)

    # First verify actual OBD support and the negotiated legacy transport.
    first = read("0100", lambda r: len(r) == 6 and r[:2] == b"\x41\x00", single=True)
    if first:
        try:
            actual = protocol_check()
            if clock() - start >= budget:
                stop = "BUDGET_EXHAUSTED"
            elif actual != expected_protocol:
                stop = "PROTOCOL_MISMATCH"
            else:
                observed_protocol = actual
        except KeyboardInterrupt:
            stop = "CANCELLED"
        except (OSError, ValueError, RuntimeError):
            stop = "PROTOCOL_UNVERIFIED"
    elif stop is None:
        stop = "NO_POSITIVE_PID00"
    identification = []
    def read_identity():
        nonlocal vin_candidate
        info = inventory(9, lambda r: r[2] == 1, 3)
        counts = {}
        for kind in info:
            if kind % 2:
                rows = read(f"09{kind:02X}", lambda r: len(r) == 3 and
                            r[:2] == bytes((0x49, kind)) and r[2] > 0, single=True)
                if len(rows) == 1:
                    counts[kind] = rows[0][2]
                continue
            rows = read(f"09{kind:02X}", lambda r: len(r) == 7 and
                        r[:2] == bytes((0x49, kind)) and r[2] > 0)
            count = counts.get(kind - 1)
            if not rows:
                continue
            ordered = count is not None and len(rows) == count and [r[2] for r in rows] == list(range(1, count + 1))
            if not ordered:
                steps[-1].update(status="FAILED" if source is not None else "NOT_TESTED",
                                 detail="INCOMPLETE_OR_AMBIGUOUS_SEQUENCE")
                continue
            data = b"".join(row[3:] for row in rows)
            if kind == 2:
                if count == 5 and data[:3] == bytes(3) and re.fullmatch(rb"[A-HJ-NPR-Z0-9]{17}", data[3:]):
                    vin_candidate = data[3:].decode("ascii")
                    if source is not None:
                        identification.append({"info_type": 2, "values": [vin_candidate], "source": source_hex})
                else:
                    steps[-1].update(status="FAILED", detail="INVALID_VIN_RECORD")
            elif source is not None and kind == 4:
                fields = [data[i:i+16].rstrip(b" \x00") for i in range(0, len(data), 16)]
                if count % 4 == 0 and all(field and all(32 <= ch <= 126 for ch in field) for field in fields):
                    identification.append({"info_type": 4, "values": [x.decode("ascii") for x in fields],
                                           "source": source_hex})
                else:
                    steps[-1].update(status="FAILED", detail="INVALID_CALID_RECORD")
            elif source is not None and kind == 6:
                identification.append({"info_type": 6, "values": [row[3:].hex().upper() for row in rows],
                                       "source": source_hex})
            elif source is not None:
                steps[-1].update(status="RAW", detail="UNINTERPRETED_INFOTYPE")
    if source is not None and stop is None:
        read_identity()
    vin_match = "NOT_REQUESTED" if expected_vin is None else (
        "UNVERIFIED" if vin_candidate is None else "MATCH" if vin_candidate == expected_vin else "MISMATCH")
    if stop is None and vin_match in ("UNVERIFIED", "MISMATCH"):
        stop = "VIN_" + vin_match
    pids = inventory(1, lambda _: True, 2, first=first) if stop is None else []

    dtc_modes = []
    def dtc(a, b):
        return f"{'PCBU'[a >> 6]}{(a >> 4) & 3:X}{a & 15:X}{b:02X}"
    for mode in (3, 7, 10):
        # Legacy fixed three DTC pairs; zero pair is padding, no CAN count.
        command = f"{mode:02X}"
        rows = read(command, lambda r: len(r) == 7 and r[0] == mode + 0x40)
        codes = [[dtc(a,b) for a,b in zip(row[1::2],row[2::2]) if a or b]
                 for row in rows] if rows else None
        dtc_modes.append({"mode": mode,
            "anonymous_records": codes if source is None else None,
            "codes": sorted({code for group in codes for code in group}) if source is not None and codes is not None else None,
            "responder_attribution": attribution, "source": source_hex})

    measurements = []
    readiness = None
    def read_pid(mode, pid):
        nonlocal readiness
        request = bytes((mode, pid)) + (b"\x00" if mode == 2 else b"")
        def positive(row):
            evidence = decode_observation(request, row)
            return evidence is not None and evidence["shape"] in ("PASSED", "RAW")
        rows = read(request.hex().upper(), positive, single=True)
        for index, row in enumerate(rows):
            evidence = decode_observation(request, row)
            if evidence.get("measurement") is not None:
                measurements.append({**evidence["measurement"], "response_index": index,
                                     "responder_attribution": attribution, "source": source_hex})
            if source is not None and mode == 1 and pid == 1:
                readiness = evidence.get("readiness")
            if source is not None and evidence["shape"] == "RAW":
                steps[-1].update(status="RAW", detail="UNINTERPRETED_PID")
        return rows

    sensors = set()
    for pid in pids:
        rows = read_pid(1, pid)
        if pid in (0x13, 0x1D):
            for row in rows:
                sensors.update(1 << bit for bit in range(8) if row[2] & (1 << bit))

    trigger = read("020200", lambda r: len(r) == 5 and r[:3] == b"\x42\x02\x00", single=True)
    if any(row[3:] != b"\x00\x00" for row in trigger):
        for pid in inventory(2, lambda r: r[2] == 0, 3, suffix="00"):
            if pid != 2:
                read_pid(2, pid)

    # Keep records separate: a headerless response cannot identify an ECU.
    # Unknown scaling, limit direction and monitor verdict are not inferred.
    monitor_records = []
    for sensor in sorted(sensors):
        for tid in inventory(5, lambda r: r[2] == sensor, 3, suffix=f"{sensor:02X}"):
            rows = read(f"05{tid:02X}{sensor:02X}", lambda r:
                 len(r) == (4 if tid in range(1, 5) else 6) and
                 r[:3] == bytes((0x45, tid, sensor)))
            for index, row in enumerate(rows):
                monitor_records.append({"mode": 5, "test_id": tid, "sensor_mask": sensor,
                    "encoded_data_hex": row[3:].hex().upper(), "response_index": index,
                    "responder_attribution": attribution, "source": source_hex})
    for tid in inventory(6, lambda r: r[2] == 0xFF, 3):
        rows = read(f"06{tid:02X}", lambda r: len(r) == 7 and r[:2] == bytes((0x46, tid)))
        for index, row in enumerate(rows):
            monitor_records.append({"mode": 6, "test_id": tid,
                "component_and_limit_encoded": row[2],
                "value_encoded": int.from_bytes(row[3:5], "big"),
                "limit_encoded": int.from_bytes(row[5:7], "big"),
                "response_index": index, "responder_attribution": attribution, "source": source_hex})
    freeze_triggers = [{"response_index": i, "present": bool(row[3] or row[4]),
                        "dtc": dtc(row[3], row[4]) if row[3] or row[4] else None,
                        "responder_attribution": attribution, "source": source_hex}
                       for i, row in enumerate(trigger)] if trigger else None

    controls = inventory(8, lambda r: r[2] == 0, 3, suffix="0000000000")
    if source is None:
        read_identity()

    return {"expected_protocol": expected_protocol, "observed_protocol": observed_protocol,
            "protocol_name": PROTOCOLS[expected_protocol], "responder_attribution": attribution,
            "scope": "FUNCTIONAL_SELECTED_RESPONSE" if source is not None else "FUNCTIONAL_ANONYMOUS",
            "selected_response_source": source_hex, "other_response_sources": [f"{x:02X}" for x in sorted(other_sources)],
            "whole_vehicle_coverage_verified": False, "declared_vin": expected_vin, "vin_match": vin_match,
            "observed_vin": vin_candidate if source is not None else None,
            "anonymous_vin_candidate": vin_candidate if source is None else None,
            "identification": identification, "readiness": readiness, "inventory": inventories,
            "steps": steps, "measurements": measurements, "dtc_modes": dtc_modes,
            "monitor_records": monitor_records, "freeze_triggers": freeze_triggers,
            "advertised_control_tests": controls, "completed": stop is None,
            "stop_reason": stop, "cancelled": stop == "CANCELLED", "duration_ms": round((clock() - start) * 1000),
            "not_executed": ["MODE_04_CLEAR", "MODE_08_CONTROL"]}
