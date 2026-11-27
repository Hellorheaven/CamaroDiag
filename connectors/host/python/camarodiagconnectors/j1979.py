"""Read-only classic SAE J1979 on a selected Linux ISO-TP CAN channel.

This host transport is independent of the discovery-only USB firmware.
A positive diagnostic response is not proof of monitor success or a write.
"""
from __future__ import annotations

import re
import socket
import time
from collections.abc import Callable
from .j1979_campaign_evidence import decode_observation, summarize_observations

_MAX_PAYLOAD = 4095
Exchange = Callable[[bytes, float], bytes]


def physical_pair(tx: int, rx: int) -> bool:
    if 0x7E0 <= tx <= 0x7E7:
        return rx == tx + 8
    if not 0 <= tx <= 0x1FFFFFFF or (tx & ~0xFF00) != 0x18DA00F1:
        return False
    address = (tx >> 8) & 0xFF
    return address not in (0xF1, 0xFE, 0xFF) and rx == (0x18DAF100 | address)


def allowed_read(request: bytes) -> bool:
    return bool(request) and (
        len(request) == 2 and request[0] in (1, 6, 9)
        or len(request) == 3 and request[0] == 2 and request[2] == 0
        or len(request) == 2 and request[0] == 8 and request[1] % 32 == 0
        or len(request) == 1 and request[0] in (3, 7, 10)
    )


class LinuxIsoTp:
    """One explicit physical ECU address pair; no functional broadcast."""

    def __init__(self, interface: str, tx: int, rx: int):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,15}", interface) or not physical_pair(tx, rx):
            raise ValueError("Invalid physical ISO 15765-4 CAN channel")
        if not hasattr(socket, "CAN_ISOTP") or not hasattr(socket, "AF_CAN"):
            raise OSError("Linux SocketCAN ISO-TP is unavailable")
        self._primary = (tx, rx) in ((0x7E0, 0x7E8), (0x18DA10F1, 0x18DAF110))
        self._socket = socket.socket(socket.AF_CAN, socket.SOCK_DGRAM, socket.CAN_ISOTP)
        try:
            flag = 0x80000000 if tx > 0x7FF else 0
            self._socket.bind((interface, rx | flag, tx | flag))
        except BaseException:
            self._socket.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self._socket.close()

    def exchange(self, request: bytes, timeout: float) -> bytes:
        if not allowed_read(request):
            raise ValueError("Unsupported read request")
        return self._exchange(request, timeout)

    def _exchange(self, request: bytes, timeout: float, before_send=lambda: None) -> bytes:
        if not 0 < timeout <= 2.5:
            raise ValueError("Unsupported timeout")
        deadline = time.monotonic() + timeout
        # Discard previously queued completed ISO-TP messages before TX.
        # A continuously busy queue cannot establish a fresh exchange.
        self._socket.setblocking(False)
        for _ in range(64):
            try:
                self._socket.recv(_MAX_PAYLOAD + 1)
            except BlockingIOError:
                break
            if time.monotonic() >= deadline:
                raise OSError("Stale ISO-TP receive queue did not drain")
        else:
            raise OSError("Stale ISO-TP receive queue exceeded bound")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise OSError("No time remains for a fresh ISO-TP exchange")
        self._socket.settimeout(remaining)
        before_send()
        if self._socket.send(request) != len(request):
            raise OSError("Incomplete ISO-TP request")
        pending = 0
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                if pending:
                    raise OSError("ECU response pending without final response")
                raise TimeoutError("ECU timeout")
            self._socket.settimeout(remaining)
            try:
                response = self._socket.recv(_MAX_PAYLOAD + 1)
            except TimeoutError:
                if pending:
                    raise OSError("ECU response pending without final response") from None
                raise
            if response == bytes((0x7F, request[0], 0x78)):
                pending += 1
                if pending > 8:
                    raise OSError("Too many pending responses")
                continue
            return response


    def run_clear(self, expected_vin: str, *, warning_acknowledged: bool,
                  confirmed_yes: bool, require_current=lambda: None) -> dict:
        from .j1979_clear import clear_recipe
        return clear_recipe(self.exchange,
                            lambda: self._exchange(b"\x04", 2.5, require_current),
                            expected_vin=expected_vin, primary=self._primary,
                            warning_acknowledged=warning_acknowledged,
                            confirmed_yes=confirmed_yes, require_current=require_current)

    def run_evap(self, expected_vin: str, *, warning_acknowledged: bool,
                 confirmed_yes: bool, require_current=lambda: None) -> dict:
        # The generic exchange method remains read-only. Only this narrowly
        # scoped recipe can dispatch the standardized EVAP request.
        return evap_recipe(self.exchange,
                           lambda: self._exchange(b"\x08\x01", 2.5, require_current),
                           expected_vin=expected_vin, primary=self._primary,
                           warning_acknowledged=warning_acknowledged,
                           confirmed_yes=confirmed_yes, require_current=require_current)


def evap_recipe(read, transmit, *, expected_vin: str, primary: bool,
                warning_acknowledged: bool, confirmed_yes: bool,
                require_current=lambda: None) -> dict:
    """One explicitly approved EVAP request; acknowledgment is not completion."""
    if not primary or not warning_acknowledged or not confirmed_yes or not re.fullmatch(
            r"[A-HJ-NPR-Z0-9]{17}", expected_vin):
        raise ValueError("Physical primary ECU, valid VIN and two confirmations required")
    exchanges = []
    observed = None
    attempted = False

    def result(outcome, reason=None):
        return {"outcome": outcome, "reason": reason, "command_attempted": attempted,
                "observed_vin": observed, "monitor_completion_verified": False,
                "exchanges": exchanges}

    def query(request):
        require_current()
        reply = read(request, 1.8)
        exchanges.append({"request_hex": request.hex().upper(),
                          "response_hex": reply.hex().upper()})
        require_current()
        return reply

    try:
        vin = query(b"\x09\x02")
        if len(vin) != 20 or vin[:3] != b"\x49\x02\x01":
            return result("NOT_TESTED", "INVALID_VIN")
        observed = vin[3:].decode("ascii")
        if observed != expected_vin:
            return result("NOT_TESTED", "VIN_MISMATCH")
        inventory = query(b"\x08\x00")
        if len(inventory) != 6 or inventory[:2] != b"\x48\x00" or not inventory[2] & 0x80:
            return result("NOT_TESTED", "TID_01_NOT_ADVERTISED")
        if query(b"\x01\x0D") != b"\x41\x0D\x00" or query(b"\x01\x0C") != b"\x41\x0C\x00\x00":
            return result("NOT_TESTED", "STATIONARY_EVIDENCE_REQUIRED")
        require_current()
        attempted = True
        exchanges.append({"request_hex": "0801", "response_hex": None})
        response = transmit()
        exchanges[-1]["response_hex"] = response.hex().upper()
        if response == b"\x48\x01":
            return result("ACKNOWLEDGED")
        if len(response) == 3 and response[:2] == b"\x7F\x08" and response[2] not in (0, 0x78):
            return result("REFUSED", f"NRC_{response[2]:02X}")
        return result("UNCERTAIN", "INVALID_OR_INCOMPLETE_RESPONSE")
    except (OSError, ValueError, RuntimeError, KeyboardInterrupt) as exc:
        return result("UNCERTAIN" if attempted else "NOT_TESTED", type(exc).__name__)


def _validate(request: bytes, response: bytes) -> str | None:
    if not response or len(response) > _MAX_PAYLOAD:
        raise ValueError("Invalid diagnostic response length")
    if response[0] == 0x7F:
        if len(response) != 3 or response[1] != request[0] or response[2] in (0, 0x78):
            raise ValueError("Unrelated or unfinished negative response")
        return f"NRC_{response[2]:02X}"
    if response[0] != request[0] + 0x40:
        raise ValueError("Wrong service")
    if len(request) > 1 and response[1:len(request)] != request[1:]:
        raise ValueError("Wrong echoed identifier or frame")
    if request[0] in (3, 7, 10):
        if len(response) < 2 or len(response) != 2 + 2 * response[1]:
            raise ValueError("Invalid CAN DTC count")
        if any(response[i:i+2] == b"\x00\x00" for i in range(2, len(response), 2)):
            raise ValueError("Zero DTC within declared records")
    elif request[0] in (1, 2, 6, 8, 9) and len(response) <= len(request):
        raise ValueError("No diagnostic data")
    if request[0] in (1, 2):
        pid = request[1]
        # Known common widths; unlisted PID data stays raw. Mode 02 PID 02
        # is the two-byte triggering DTC, not a Mode 01 fuel-system value.
        lengths = {
            **dict.fromkeys((0x01, 0x24, 0x25, 0x28, 0x29, 0x34, 0x35,
                             0x38, 0x39, 0xA6), 4),
            **dict.fromkeys((0x0C, 0x10, 0x14, 0x15, 0x16, 0x17, 0x1F,
                             0x42, 0x43, 0x44), 2),
            **dict.fromkeys((0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
                             0x0B, 0x0D, 0x0E, 0x0F, 0x11, 0x13,
                             0x1D, 0x45, 0x46, 0x47, 0x48, 0x49,
                             0x4A, 0x4B, 0x4C, 0x52, 0x5B), 1),
        }
        width = (4 if pid % 32 == 0 else
                 2 if request[0] == 2 and pid == 2 else lengths.get(pid))
        if width is not None and len(response) != len(request) + width:
            raise ValueError("Wrong PID data width")
    if request[0] == 9 and request[1] in (4, 6, 10):
        width = {4: 16, 6: 4, 10: 20}[request[1]]
        if len(response) < 3 or response[2] == 0 or len(response) != 3 + response[2] * width:
            raise ValueError("Invalid Mode 09 record count")
    return None


def scan(exchange: Exchange, *, budget: float = 180.0,
         clock: Callable[[], float] = time.monotonic, expected_vin: str | None = None) -> dict:
    """Per-request evidence; optional unsupported services remain NOT_TESTED."""
    if not 0 < budget <= 600:
        raise ValueError("Invalid campaign budget")
    if expected_vin is not None and not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", expected_vin):
        raise ValueError("Invalid expected VIN")
    started = clock()
    steps = []
    stop = None
    vin = None

    def read(request: bytes, decode=None):
        nonlocal stop
        if stop is not None:
            return None
        if not allowed_read(request):
            raise ValueError("Non-read request in campaign")
        left = budget - (clock() - started)
        if left <= 0:
            stop = "BUDGET_EXHAUSTED"
            return None
        begin = clock()
        response = None
        status, detail, result = "NOT_TESTED", None, None
        application = None
        try:
            response = exchange(request, min(2.5, left))
            if clock() - started >= budget:
                stop = "BUDGET_EXHAUSTED"
                detail = "LATE_RESPONSE"
                steps.append({"request_hex": request.hex().upper(),
                              "response_hex": response.hex().upper(),
                              "status": "NOT_TESTED", "detail": detail, "decoded": None,
                              "application_evidence": None,
                              "duration_ms": round((clock() - begin) * 1000)})
                return None
            detail = _validate(request, response)
            application = decode_observation(request, response)
            if application is None:
                raise ValueError("Invalid application record")
            if application["shape"] == "RAW":
                status = "RAW"
            elif detail is None:
                result = decode(response) if decode else response.hex().upper()
                status = "PASSED"
        except KeyboardInterrupt:
            status, detail, stop = "NOT_TESTED", "CANCELLED", "CANCELLED"
        except TimeoutError:
            detail = "TIMEOUT"
        except ValueError as exc:
            status, detail = "FAILED", str(exc)
        except OSError as exc:
            status, detail, stop = "FAILED", type(exc).__name__, "TRANSPORT_ERROR"
        steps.append({"request_hex": request.hex().upper(),
                      "response_hex": response.hex().upper() if response is not None else None,
                      "status": status, "detail": detail, "decoded": result,
                      "application_evidence": application if status != "FAILED" else None,
                      "duration_ms": round((clock() - begin) * 1000)})
        return result

    def inventory(mode: int, frame: bool = False) -> list[int]:
        found = set()
        for base in range(0, 0x100, 32):
            request = bytes((mode, base, 0)) if frame else bytes((mode, base))

            def bitmap(response: bytes):
                offset = len(request)
                if len(response) != offset + 4 or (base == 0xE0 and response[-1] & 1):
                    raise ValueError("Invalid support bitmap")
                ids = [base + i + 1 for i in range(31)
                       if response[offset + i // 8] & (0x80 >> (i % 8))]
                return {"ids": ids, "next": bool(response[-1] & 1)}

            page = read(request, bitmap)
            if page is None:
                break
            found.update(page["ids"])
            if not page["next"]:
                break
        return sorted(found)

    def decode_vin(response: bytes) -> str:
        if len(response) != 20 or response[:3] != b"\x49\x02\x01":
            raise ValueError("Invalid VIN record")
        value = response[3:].decode("ascii")
        if not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", value):
            raise ValueError("Invalid VIN characters")
        return value

    vin = read(b"\x09\x02", decode_vin)
    match = "NOT_REQUESTED" if expected_vin is None else (
        "UNVERIFIED" if vin is None else "MATCH" if vin == expected_vin else "MISMATCH")
    if stop is None and match in ("UNVERIFIED", "MISMATCH"):
        stop = "VIN_" + match
    for mode in (3, 7, 10):
        read(bytes((mode,)))
    def odometer(response: bytes):
        return {"name": "odometer", "number": int.from_bytes(response[2:], "big") / 10.0, "unit": "km"}
    for pid in inventory(1):
        read(bytes((1, pid)), odometer if pid == 0xA6 else None)

    def freeze_trigger(response: bytes) -> int:
        if len(response) != 5:
            raise ValueError("Invalid freeze frame DTC")
        return int.from_bytes(response[3:], "big")

    if read(b"\x02\x02\x00", freeze_trigger):
        for pid in inventory(2, True):
            if pid != 2:
                read(bytes((2, pid, 0)))

    def monitor(response: bytes):
        if len(response) < 10 or (len(response) - 1) % 9:
            raise ValueError("Invalid Mode 06 record length")
        records = []
        for index in range(1, len(response), 9):
            part = response[index:index+9]
            if part[0] != response[1]:
                raise ValueError("Mixed monitor identifiers")
            records.append({"mid": part[0], "tid": part[1], "uasid": part[2],
                            "raw": int.from_bytes(part[3:5], "big"),
                            "min_raw": int.from_bytes(part[5:7], "big"),
                            "max_raw": int.from_bytes(part[7:9], "big")})
        return records

    for mid in inventory(6):
        read(bytes((6, mid)), monitor)
    controls = inventory(8)
    for info in inventory(9):
        if info != 2:
            read(bytes((9, info)))
    return {"observed_vin": vin, "steps": steps, "advertised_control_tests": controls,
            "cancelled": stop == "CANCELLED", "declared_vin": expected_vin, "vin_match": match,
            **summarize_observations(steps),
            "completed": stop is None, "stop_reason": stop,
            "duration_ms": round((clock() - started) * 1000),
            "not_executed": ["MODE_04_CLEAR", "MODE_05_LEGACY", "MODE_08_CONTROL"]}
