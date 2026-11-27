"""Explicit Mode 02 on classic legacy emissions networks.

Requests remain functional broadcasts; only checksum-verified replies from
the selected documented source enter the recipe. Additional frame numbers
are operator-supplied and their values are retained raw. No CAN addressing,
manufacturer frame enumeration, ECU writes or protocol fallback.
"""
from __future__ import annotations

import re
import time

from .j1979_freeze import FreezePlan, allowed_request, scan_freeze
from .j1979_legacy import PROTOCOLS
from .j1979_legacy_live import LegacyLiveLink

MAX_RAW = 32768
MAX_TRACE_BYTES = 32 * 1024 * 1024


class LegacyFreezeLink(LegacyLiveLink):
    def __init__(self, exchange, protocol_probe, protocol, source, plan: FreezePlan,
                 *, clock=time.monotonic, require_current=lambda: None):
        self.plan = plan.validate()
        self.protocol_probe = protocol_probe
        self.raw_bytes = 0
        # The base recipe supplies the numbered VIN assembly and checksum
        # verification; this wrapper limits the raw transcripts and scope.
        def bounded(command, timeout):
            raw = exchange(command, timeout)
            self._bound(raw)
            return raw
        super().__init__(bounded, lambda _: None, protocol, source,
                         clock=clock, require_current=require_current)

    def _bound(self, raw):
        if not isinstance(raw, str) or len(raw) > MAX_RAW:
            raise ValueError("Invalid legacy freeze adapter transcript")
        self.raw_bytes += len(raw)
        if self.raw_bytes > MAX_TRACE_BYTES:
            raise ValueError("Legacy freeze transcript budget exceeded")

    def _allows_command(self, command):
        return (command in ("0100", "0900", "0901", "0902") or
                re.fullmatch(r"02[0-9A-F]{4}", command) is not None and
                allowed_request(bytes.fromhex(command), self.plan.frames) and
                (int(command[2:4], 16) in (0, 2) or int(command[2:4], 16) % 32 == 0 or
                 not self.plan.pids or int(command[2:4], 16) in self.plan.pids))

    def _probe(self, deadline, logical):
        row = {"logical_query": logical, "request_hex": "ATDPN", "raw_response": None,
               "frames": [], "selected_records_hex": [], "status": "NOT_TESTED",
               "detail": None, "observed_protocol": None}
        self.exchanges.append(row)
        try:
            raw = self.protocol_probe(self._remaining(deadline, 2.0))
            self._bound(raw)
            row["raw_response"] = raw
            self.require_current()
            if self.clock() >= deadline:
                row["detail"] = "LATE_PROTOCOL"
                raise TimeoutError("Late legacy protocol probe")
            lines = [s.strip().upper() for s in raw.replace(">", "\n").splitlines()
                     if s.strip() and s.strip().upper() != "ATDPN"]
            if lines != [str(self.protocol)]:
                row["detail"] = "PROTOCOL_MISMATCH"
                raise ValueError("Legacy protocol not fixed or differs")
            self.observed_protocol = self.protocol
            row.update(status="PASSED", observed_protocol=self.protocol)
        except KeyboardInterrupt:
            row["detail"] = "CANCELLED"
            raise
        except (OSError, ValueError, RuntimeError) as exc:
            row.update(status="FAILED", detail=row["detail"] or type(exc).__name__)
            raise

    def query(self, request, timeout):
        if not isinstance(request, bytes) or not 0 < timeout <= 20:
            raise ValueError("Invalid legacy logical freeze query")
        logical = self.logical_count
        self.logical_count += 1
        deadline = self.clock() + timeout
        if request == b"\x09\x02":
            return self._vin(deadline, logical)
        command = request.hex().upper()
        if len(request) != 3 or request[0] != 2 or not self._allows_command(command):
            raise ValueError("Request outside explicit legacy freeze scope")
        if self.observed_protocol != self.protocol:
            raise ValueError("Fixed legacy protocol is unverified")
        return self._single(command, deadline, logical)


def acquire(exchange, protocol_probe, protocol, source, plan, *,
            clock=time.monotonic, require_current=lambda: None):
    link = LegacyFreezeLink(exchange, protocol_probe, protocol, source, plan,
                            clock=clock, require_current=require_current)
    result = scan_freeze(link.query, plan, clock=clock, require_current=require_current,
                         request_timeout=lambda req: 20 if req == b"\x09\x02" else 4)
    return {"scope": "FUNCTIONAL_SELECTED_RESPONSE_FREEZE",
            "physically_addressed": False, "expected_protocol": protocol,
            "observed_protocol": link.observed_protocol,
            "selected_response_source": f"{source:02X}",
            "other_response_sources": [f"{x:02X}" for x in sorted(link.other_sources)],
            "response_attribution": "CHECKSUM_VERIFIED_LEGACY_HEADER",
            "response_hex_representation": "NORMALIZED_APPLICATION_PAYLOAD",
            "transport_exchanges": link.exchanges, "result": result}
