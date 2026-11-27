"""Explicit Linux SocketCAN discovery and selected physical diagnostic reports."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
import time

from . import __version__
from .j1979 import LinuxIsoTp
from .j1979_can_network import LinuxFunctionalCan, discover, scan_network, validate_targets


def parse(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="Discover classic J1979 CAN responders, optionally scan explicitly selected physical ECUs")
    parser.add_argument("--interface", required=True, help="Already configured Linux SocketCAN interface")
    parser.add_argument("--addressing", required=True, choices=("11bit", "29bit"))
    parser.add_argument("--discover-only", action="store_true", help="Send only one functional PID00 request")
    parser.add_argument("--source", action="append", default=[], help="Documented response CAN ID in hex, repeat to select several")
    parser.add_argument("--vin", help="Expected uppercase VIN, required on every selected responder when supplied")
    parser.add_argument("--discovery-timeout", type=float, default=1.5)
    parser.add_argument("--budget", type=float, default=180.0, help="Total campaign seconds")
    parser.add_argument("--confirm-functional-pid00", action="store_true", required=True,
                        help="Confirm configured network and functional PID00 broadcast")
    parser.add_argument("--confirm-physical-channels", action="store_true",
                        help="Confirm documented physical target IDs before individual scans")
    parser.add_argument("--output", required=True, type=Path, help="New private JSON file")
    args = parser.parse_args(argv)
    if (not re.fullmatch(r"[A-Za-z0-9_.-]{1,15}", args.interface) or
        not 0 < args.discovery_timeout <= 30 or not args.discovery_timeout <= args.budget <= 600):
        parser.error("Invalid interface or time limits")
    if args.discover_only == bool(args.source):
        parser.error("Choose --discover-only or one or more --source")
    if args.source and not args.confirm_physical_channels:
        parser.error("Selected physical channels need confirmation")
    if args.vin is not None and (args.discover_only or not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}",args.vin)):
        parser.error("VIN comparison requires a selected physical campaign and documented VIN")
    try:
        if any(not re.fullmatch(r"[0-9A-Fa-f]{1,8}",x) for x in args.source):
            raise ValueError("Expected hexadecimal CAN response IDs")
        args.source = validate_targets([int(x,16) for x in args.source], args.addressing=="29bit")
    except ValueError as exc:
        parser.error(str(exc))
    return args


def run(argv=None):
    args = parse(argv)
    extended = args.addressing == "29bit"
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd,"w",encoding="utf-8") as output:
        started = datetime.now(timezone.utc).isoformat()
        start = time.monotonic()
        result = None
        discovery = None
        try:
            with LinuxFunctionalCan(args.interface, extended) as link:
                discovery = discover(link, extended, timeout=args.discovery_timeout)
            result = discovery
            if args.source:
                left = args.budget - (time.monotonic()-start)
                if left <= 0:
                    result = {"completed":False,"stop_reason":"BUDGET_EXHAUSTED","discovery":discovery,
                              "selected_response_ids":[f"{x:X}" for x in args.source],"ecu_reports":[]}
                else:
                    result = scan_network(discovery,args.source,
                        lambda tx,rx:LinuxIsoTp(args.interface,tx,rx),expected_vin=args.vin,budget=left)
        except (KeyboardInterrupt, OSError, ValueError, RuntimeError) as exc:
            if result is None:
                result = {"discovery":discovery,"ecu_reports":[]}
            result.update(completed=False,stop_reason="CANCELLED" if isinstance(exc,KeyboardInterrupt) else type(exc).__name__)
        report = {"schema":"camarodiag-functional-j1979-can-discovery-v1" if args.discover_only else
                  "camarodiag-functional-j1979-can-network-v1",
                  "protocol":"SAE-J1979","transport":"LINUX_SOCKETCAN_CLASSIC",
                  "version":__version__,"interface":args.interface,"addressing":args.addressing,
                  "started_at_utc":started,"finished_at_utc":datetime.now(timezone.utc).isoformat(),
                  "declared_vin":args.vin,"physical_vehicle_verified":False,
                  "whole_vehicle_coverage_verified":False,"not_executed":["MODE_04_CLEAR","MODE_08_CONTROL"],
                  **result}
        json.dump(report,output,indent=2,ensure_ascii=True)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    return report


def main():
    try:
        report=run()
    except (OSError,ValueError) as exc:
        print(f"J1979 network: {exc}",file=sys.stderr)
        raise SystemExit(2) from exc
    discovered=report.get("discovery") or report
    for row in discovered.get("responders",[]):
        print(f'{row["rx_can_id"]} [{row["status"]}] {row["detail"] or ""}')
    for row in report.get("ecu_reports",[]):
        print(f'{row["rx_can_id"]} [{row["status"]}] {row["error"] or ""}')
    if not report["completed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
