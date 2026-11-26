"""Independent J1979 CAN functional recipe on an explicitly selected Linux host."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

from . import __version__
from .j1979 import LinuxIsoTp, physical_pair, scan


def _can_id(value: str) -> int:
    try:
        number = int(value, 16)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Expected a hexadecimal CAN ID") from exc
    if not 0 <= number <= 0x1FFFFFFF:
        raise argparse.ArgumentTypeError("CAN ID out of range")
    return number


def run(argv=None) -> dict:
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="SAE J1979 read-only physical ISO-TP CAN campaign")
    parser.add_argument("--interface", required=True)
    parser.add_argument("--tx", type=_can_id, required=True)
    parser.add_argument("--rx", type=_can_id, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--confirm-physical-channel", action="store_true", required=True,
                        help="Confirm the documented physical ECU and interface configuration")
    parser.add_argument("--budget", type=float, default=180.0)
    args = parser.parse_args(argv)
    if not physical_pair(args.tx, args.rx) or not 0 < args.budget <= 600:
        parser.error("Invalid physical address pair or budget")

    # Reserve the destination before opening a live channel, never overwrite.
    # A private file may contain VINs and diagnostic records.
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        start = datetime.now(timezone.utc).isoformat()
        try:
            with LinuxIsoTp(args.interface, args.tx, args.rx) as transport:
                result = scan(transport.exchange, budget=args.budget)
        except (OSError, ValueError) as exc:
            result = {"completed": False, "stop_reason": type(exc).__name__,
                      "observed_vin": None, "steps": [],
                      "not_executed": ["MODE_04_CLEAR", "MODE_05_LEGACY", "MODE_08_CONTROL"]}
        report = {"schema": "camarodiag-functional-j1979-can-v1",
                  "protocol": "SAE-J1979", "transport": "LINUX_SOCKETCAN_ISOTP",
                  "version": __version__, "interface": args.interface,
                  "tx_can_id": f"{args.tx:X}", "rx_can_id": f"{args.rx:X}",
                  "physical_channel_confirmed_by_operator": True,
                  "physical_vehicle_verified": False,
                  "started_at_utc": start,
                  "finished_at_utc": datetime.now(timezone.utc).isoformat(),
                  **result}
        json.dump(report, output, indent=2, ensure_ascii=True)
        output.write("\n")
    return report


def main() -> None:
    try:
        report = run()
    except (OSError, ValueError) as exc:
        print(f"J1979: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
    print(json.dumps({"completed": report["completed"],
                      "stop_reason": report.get("stop_reason"),
                      "steps": len(report["steps"])}))
    if not report["completed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
