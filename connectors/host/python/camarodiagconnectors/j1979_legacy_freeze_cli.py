"""CLI for legacy Mode 02 on a selected documented response source."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys

from . import __version__
from .j1979_freeze import FreezePlan, execute_plan, Read
from .j1979_legacy import LegacyElmSerial, PROTOCOLS
from .j1979_legacy_discovery import valid_source

SCHEMA = "camarodiag-functional-j1979-legacy-freeze-v1"


def byte(value):
    if not re.fullmatch(r"[0-9A-Fa-f]{2}", value):
        raise argparse.ArgumentTypeError("Expected two-digit hexadecimal identifier")
    return int(value, 16)


def parse(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False, description=__doc__)
    parser.add_argument("--port", required=True)
    parser.add_argument("--baud", type=int, choices=(9600,19200,38400,57600,115200,230400), default=38400)
    parser.add_argument("--protocol", type=int, choices=PROTOCOLS, required=True)
    parser.add_argument("--source", type=byte, required=True)
    parser.add_argument("--vin", required=True)
    parser.add_argument("--frame", type=byte, action="append", required=True)
    parser.add_argument("--pid", type=byte, action="append", default=[])
    parser.add_argument("--budget-ms", type=int, default=180000)
    parser.add_argument("--confirm-dedicated-adapter", action="store_true", required=True)
    parser.add_argument("--confirm-functional-scope", action="store_true", required=True,
                        help="Legacy requests may reach other ECUs; only selected replies are retained")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if not valid_source(args.source) or not args.port.strip() or len(args.port) > 1024:
        parser.error("A documented legacy response source and serial port are required")
    try:
        plan = FreezePlan(args.vin, tuple(args.frame), tuple(args.pid), args.budget_ms).validate()
    except ValueError as exc:
        parser.error(str(exc))
    return args, plan


def run(argv=None):
    args, plan = parse(argv)
    started = datetime.now(timezone.utc).isoformat()
    fd = os.open(args.output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        data = None
        transport_problem = None
        try:
            with LegacyElmSerial(args.port, args.baud, args.protocol, headered=True) as link:
                data = link.run_freeze(args.source, plan)
        except KeyboardInterrupt:
            transport_problem = "CANCELLED"
        except (OSError, ValueError, RuntimeError, ImportError):
            transport_problem = "TRANSPORT_ERROR"
        if data is None:
            result = execute_plan(plan, lambda _: Read(
                "CANCELLED" if transport_problem == "CANCELLED" else "CONTEXT_CHANGED", None, 0, False))
            result["duration_ms"] = 0
            data = {"scope": "FUNCTIONAL_SELECTED_RESPONSE_FREEZE", "physically_addressed": False,
                    "expected_protocol": args.protocol, "observed_protocol": None,
                    "selected_response_source": f"{args.source:02X}", "other_response_sources": [],
                    "response_attribution": "CHECKSUM_VERIFIED_LEGACY_HEADER",
                    "response_hex_representation": "NORMALIZED_APPLICATION_PAYLOAD",
                    "transport_exchanges": [], "result": result}
        result = data["result"]
        report = {"schema": SCHEMA, "protocol": "SAE-J1979",
                  "transport": "ELM_SERIAL_LEGACY_HEADERED", "version": __version__,
                  "port": args.port, "baud": args.baud,
                  "functional_scope_confirmed_by_operator": True,
                  "dedicated_adapter_confirmed_by_operator": True,
                  "started_at_utc": started, "finished_at_utc": datetime.now(timezone.utc).isoformat(),
                  "transport_problem": transport_problem,
                  "execution_completed": result["completed"] and transport_problem is None and
                      data["observed_protocol"] == args.protocol, **data}
        json.dump(report, output, indent=2, ensure_ascii=True, allow_nan=False)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    return report


def main():
    try:
        report = run()
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2) from exc
    print(json.dumps({"execution_completed": report["execution_completed"],
                      "frames": report["result"]["frames"],
                      "stop_reason": report["result"]["stop_reason"]}))
    if not report["execution_completed"] or not report["result"]["all_frames_stable"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
