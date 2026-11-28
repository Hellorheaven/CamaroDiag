"""Run one independent, read-only CU/1 functional test and preserve CJ/1 + JSON."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
import re
from pathlib import Path
import sys

from . import __version__
from .j1979_usb_live import run_campaign
from .j1979_usb_auth import hello
from .j1979_campaign_evidence import service_observations, vin_match


def run(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="Qualified native USB J1979 read campaign (not discovery firmware)")
    parser.add_argument("--port", required=True)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--tx", type=lambda x: int(x, 0), required=True)
    parser.add_argument("--rx", type=lambda x: int(x, 0), required=True)
    parser.add_argument("--extended", action="store_true")
    parser.add_argument("--budget-ms", type=int, default=60000)
    parser.add_argument("--vehicle", required=True)
    parser.add_argument("--vin", help="Expected VIN; mismatched or unverified VIN fails the requested comparison")
    parser.add_argument("--ecu", required=True)
    parser.add_argument("--connector", required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    expected_vin = args.vin.strip().upper() if args.vin is not None else None
    if expected_vin is not None and not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", expected_vin):
        parser.error("Expected VIN must contain 17 valid characters")
    for value in (args.vehicle, args.ecu, args.connector):
        if not value.strip() or len(value) > 256 or any(ord(c) < 32 for c in value):
            parser.error("Nonempty bounded declared labels required")
    if len({p.resolve() for p in (args.key_file, args.capture, args.output)}) != 3:
        parser.error("Key, raw capture and report must use different files")
    hello(args.tx, args.rx, args.extended, args.budget_ms)
    with args.key_file.open("rb") as key_file:
        key = key_file.read(33)
    if len(key) != 32:
        parser.error("Key file must contain exactly 32 provisioned bytes")
    try:
        import serial
    except ImportError as exc:
        raise SystemExit("Install pyserial: python -m pip install pyserial") from exc
    if input("Qualified hardware, documented ECU profile, stationary vehicle; start read-only test? [yes/no] ") != "yes":
        raise SystemExit("Cancelled; no USB command sent")
    # Reserve outputs before communicating, never overwrite evidence.
    out_fd = os.open(args.output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(out_fd, "w", encoding="utf-8") as output:
        report = None
        try:
            raw_fd = os.open(args.capture, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(raw_fd, "wb") as raw:
                with serial.Serial(args.port, baudrate=115200, timeout=0.1,
                                   write_timeout=1) as port:
                    report = run_campaign(port, key=key, tx=args.tx, rx=args.rx,
                        extended=args.extended, budget_ms=args.budget_ms, capture=raw)
                raw.flush()
                os.fsync(raw.fileno())
        except (OSError, ValueError, KeyboardInterrupt) as exc:
            if report is None:
                report = {"schema": "camarodiag-functional-j1979-embedded-evidence-v1",
                          "protocol": "SAE-J1979", "source": "LIVE_USB_CU1",
                          "device_credential_authenticated": False, "evidence_authenticated": False,
                          "physical_vehicle_verified": False, "observed_vin": None,
                          "steps": [], "summary": None}
            report.update(capture_integrity_verified=False,
                          cancelled=bool(report.get("cancelled")) or isinstance(exc, KeyboardInterrupt),
                          error="USB campaign cancelled by operator" if isinstance(exc, KeyboardInterrupt) else str(exc))
        report.setdefault("cancelled", False)
        report.setdefault("service_observations", service_observations(report["steps"]))
        report.update(declared_vin=expected_vin,
                      vin_match=vin_match(report, expected_vin, require_device_auth=True))
        report.update(host_version=__version__, declared_vehicle=args.vehicle,
            declared_ecu=args.ecu, declared_connector=args.connector,
            inspected_at_utc=datetime.now(timezone.utc).isoformat(),
            raw_capture_name=args.capture.name)
        json.dump(report, output, indent=2, ensure_ascii=True)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    return report


def main():
    try:
        report = run()
    except (OSError, ValueError, EOFError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2) from exc
    print(json.dumps({"capture_integrity_verified": report["capture_integrity_verified"],
                      "steps": len(report["steps"]), "error": report["error"]}))
    if (report.get("cancelled") or not report["capture_integrity_verified"] or report["summary"]["end"] != "COMPLETE"
            or report["vin_match"] not in ("NOT_REQUESTED", "MATCH")):
        raise SystemExit(2)


if __name__ == "__main__":
    main()
