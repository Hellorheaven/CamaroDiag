"""Read explicitly selected SAE J1979 freeze frames on a physical CAN channel."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

from . import __version__
from .j1979 import LinuxIsoTp, physical_pair
from .j1979_cli import _can_id
from .j1979_freeze import FreezePlan, execute_plan, Read


def _byte(value):
    try:
        number = int(value, 16)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Expected a hexadecimal byte") from exc
    if not 0 <= number <= 255:
        raise argparse.ArgumentTypeError("Byte outside 00-FF")
    return number


def _parser():
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="Read-only SAE J1979 Mode 02 freeze-frame campaign")
    parser.add_argument("--interface", required=True)
    parser.add_argument("--tx", type=_can_id, required=True)
    parser.add_argument("--rx", type=_can_id, required=True)
    parser.add_argument("--vin", required=True)
    parser.add_argument("--frame", type=_byte, action="append", required=True,
        help="Explicit hexadecimal freeze-frame number, repeat up to eight times")
    parser.add_argument("--pid", type=_byte, action="append", default=[],
        help="Measurement PID, hexadecimal; omitted means read all advertised values")
    parser.add_argument("--budget-ms", type=int, default=180000)
    parser.add_argument("--confirm-physical-channel", action="store_true", required=True,
        help="Confirm the documented physical ECU address pair")
    parser.add_argument("--output", type=Path, required=True)
    return parser


def run(argv=None):
    parser = _parser()
    args = parser.parse_args(argv)
    plan = FreezePlan(args.vin, tuple(args.frame), tuple(args.pid), args.budget_ms)
    try:
        plan.validate()
        if not physical_pair(args.tx, args.rx):
            raise ValueError("Invalid physical CAN address pair")
    except ValueError as exc:
        parser.error(str(exc))
    # Reserve a private, exclusive report destination before opening the bus.
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        start = datetime.now(timezone.utc).isoformat()
        result = None
        transport_problem = None
        try:
            with LinuxIsoTp(args.interface, args.tx, args.rx) as link:
                result = link.run_freeze(plan)
        except (OSError, ValueError, RuntimeError, KeyboardInterrupt) as exc:
            # Failure before the first exchange is not a claimed bus attempt.
            # Failure during close preserves the acquired result but separately
            # invalidates the overall execution verdict.
            transport_problem = "CANCELLED" if isinstance(exc, KeyboardInterrupt) else "TRANSPORT_ERROR"
        if result is None:
            result = execute_plan(plan, lambda request: Read(
                "CANCELLED" if transport_problem == "CANCELLED" else "CONTEXT_CHANGED",
                None, 0, False))
            result["duration_ms"] = 0
        report = {
            "schema": "camarodiag-functional-j1979-freeze-v1",
            "protocol": "SAE-J1979", "transport": "LINUX_SOCKETCAN_ISOTP",
            "version": __version__, "interface": args.interface,
            "tx_can_id": f"{args.tx:X}", "rx_can_id": f"{args.rx:X}",
            "physical_channel_confirmed_by_operator": True,
            "started_at_utc": start,
            "finished_at_utc": datetime.now(timezone.utc).isoformat(),
            "transport_problem": transport_problem,
            "execution_completed": result["completed"] and transport_problem is None,
            "result": result,
        }
        json.dump(report, output, indent=2, ensure_ascii=True, allow_nan=False)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    return report


def main():
    try:
        report = run()
    except (OSError, ValueError) as exc:
        print(f"J1979 freeze: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
    result = report["result"]
    print(json.dumps({"completed": report["execution_completed"],
        "all_frames_stable": result["all_frames_stable"],
        "frames": len(result["frames"]), "stop_reason": result["stop_reason"],
        "transport_problem": report["transport_problem"]}))
    if not report["execution_completed"] or not result["all_frames_stable"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
