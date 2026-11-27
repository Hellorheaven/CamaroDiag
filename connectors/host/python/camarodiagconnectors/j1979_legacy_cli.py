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


def run(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="Legacy SAE J1979 read-only ELM serial campaign")
    parser.add_argument("--port", required=True)
    parser.add_argument("--baud", type=int, default=38400)
    parser.add_argument("--protocol", type=int, choices=PROTOCOLS, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget", type=float, default=180.0)
    parser.add_argument("--confirm-dedicated-adapter", action="store_true", required=True,
                        help="Confirm a dedicated adapter and documented vehicle protocol")
    args = parser.parse_args(argv)
    if not 0 < args.budget <= 600:
        parser.error("Invalid campaign budget")
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        started = datetime.now(timezone.utc).isoformat()
        try:
            with LegacyElmSerial(args.port, args.baud, args.protocol) as transport:
                result = scan(transport.exchange, transport.observed_protocol,
                              args.protocol, budget=args.budget)
        except (OSError, ValueError, ImportError, KeyboardInterrupt) as exc:
            result = {"expected_protocol": args.protocol, "observed_protocol": None,
                      "observed_vin": None, "completed": False,
                      "stop_reason": "CANCELLED" if isinstance(exc, KeyboardInterrupt) else type(exc).__name__,
                      "cancelled": isinstance(exc, KeyboardInterrupt), "steps": [],
                      "not_executed": ["MODE_04_CLEAR", "MODE_08_CONTROL"]}
        report = {"schema": "camarodiag-functional-j1979-legacy-v1",
                  "protocol": "SAE-J1979", "version": __version__,
                  "transport": "ELM_SERIAL_LEGACY", "port": args.port,
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
    print(json.dumps({"completed": report["completed"], "stop_reason": report["stop_reason"],
                      "observed_protocol": report["observed_protocol"],
                      "steps": len(report["steps"])}))
    if not report["completed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
