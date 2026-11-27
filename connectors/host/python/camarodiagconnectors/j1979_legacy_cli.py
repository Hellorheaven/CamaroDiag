"""Independent legacy SAE J1979 read-only report over a dedicated ELM port."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

from . import __version__
from .j1979_legacy import LegacyElmSerial, PROTOCOLS, scan
from .j1979_legacy_discovery import discover, valid_source
import re


def run(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="Legacy SAE J1979 read-only ELM serial campaign")
    parser.add_argument("--port", required=True)
    parser.add_argument("--baud", type=int, default=38400)
    parser.add_argument("--protocol", type=int, choices=PROTOCOLS, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget", type=float, default=180.0)
    parser.add_argument("--source", help="Two-digit response source, or discover; omit for anonymous legacy scan")
    parser.add_argument("--vin", help="Expected 17-character VIN; requires a selected response source")
    parser.add_argument("--confirm-dedicated-adapter", action="store_true", required=True,
                        help="Confirm a dedicated adapter and documented vehicle protocol")
    args = parser.parse_args(argv)
    if not 0 < args.budget <= 600:
        parser.error("Invalid campaign budget")
    selected = None
    discovery = args.source is not None and args.source.lower() == "discover"
    if args.source is not None and not discovery:
        if not re.fullmatch(r"[0-9A-Fa-f]{2}", args.source) or not valid_source(int(args.source, 16)):
            parser.error("Expected a two-digit response source or discover")
        selected = int(args.source, 16)
    if args.vin is not None and (selected is None or not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", args.vin)):
        parser.error("Expected a documented VIN and selected response source")
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        started = datetime.now(timezone.utc).isoformat()
        result = None
        try:
            with LegacyElmSerial(args.port, args.baud, args.protocol, headered=args.source is not None) as transport:
                if discovery:
                    result = discover(transport.exchange, transport.observed_protocol,
                                      args.protocol, budget=args.budget)
                else:
                    result = scan(transport.exchange, transport.observed_protocol,
                                  args.protocol, budget=args.budget, source=selected, expected_vin=args.vin)
        except (OSError, ValueError, RuntimeError, ImportError, KeyboardInterrupt) as exc:
            if result is None:
                result = {"expected_protocol": args.protocol, "observed_protocol": None,
                          "observed_vin": None, "steps": [],
                          "not_executed": ["MODE_04_CLEAR", "MODE_08_CONTROL"]}
            result.update(completed=False, transport_error=type(exc).__name__,
                          cancelled=isinstance(exc, KeyboardInterrupt) or result.get("cancelled", False),
                          stop_reason=result.get("stop_reason") or
                              ("CANCELLED" if isinstance(exc, KeyboardInterrupt) else type(exc).__name__))
        report = {"schema": "camarodiag-functional-j1979-legacy-discovery-v1" if discovery else
                  "camarodiag-functional-j1979-legacy-v1",
                  "protocol": "SAE-J1979", "version": __version__,
                  "transport": "ELM_SERIAL_LEGACY_HEADERED" if args.source is not None else "ELM_SERIAL_LEGACY",
                  "selected_response_source": f"{selected:02X}" if selected is not None else None,
                  "declared_vin": args.vin, "port": args.port,
                  "baud": args.baud, "physical_vehicle_verified": False,
                  "started_at_utc": started,
                  "finished_at_utc": datetime.now(timezone.utc).isoformat(),
                  **result}
        json.dump(report, output, indent=2, ensure_ascii=True)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    return report


def main():
    try:
        report = run()
    except (OSError, ValueError) as exc:
        print(f"J1979 legacy: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
    for responder in report.get("responders", []):
        print(f'{responder["source"]} [{responder["status"]}] {responder["detail"] or ""}')
    print(json.dumps({"completed": report["completed"], "stop_reason": report["stop_reason"],
                      "observed_protocol": report["observed_protocol"],
                      "steps": len(report["steps"])}))
    if not report["completed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
