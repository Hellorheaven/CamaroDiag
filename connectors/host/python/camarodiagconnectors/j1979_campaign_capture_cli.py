"""Convert an already captured CJ/1 read campaign to independent JSON."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
import re
from pathlib import Path
import sys

from . import __version__
from .j1979_campaign_evidence import decode, vin_match


def run(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="Inspect an existing embedded J1979 evidence capture (no vehicle I/O)")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--vehicle", required=True, help="Operator-declared vehicle")
    parser.add_argument("--vin", help="Expected VIN for comparison against recorded bytes")
    parser.add_argument("--ecu", required=True, help="Operator-declared ECU")
    parser.add_argument("--connector", required=True, help="Operator-declared connector/profile")
    args = parser.parse_args(argv)
    expected_vin = args.vin.strip().upper() if args.vin is not None else None
    if expected_vin is not None and not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", expected_vin):
        parser.error("Expected VIN must contain 17 valid characters")
    for value in (args.vehicle, args.ecu, args.connector):
        if not value.strip() or len(value) > 256 or any(ord(c) < 32 for c in value):
            parser.error("Nonempty bounded declared labels required")
    if args.input.resolve() == args.output.resolve():
        parser.error("Input and output must differ")
    with args.input.open("rb") as capture:
        fd = os.open(args.output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            report = decode(capture)
            report.update(declared_vin=expected_vin, vin_match=vin_match(report, expected_vin),
                host_version=__version__, declared_vehicle=args.vehicle,
                declared_ecu=args.ecu, declared_connector=args.connector,
                inspected_at_utc=datetime.now(timezone.utc).isoformat(),
                source="OFFLINE_CAPTURE", hardware_provenance_verified=False)
            json.dump(report, output, indent=2, ensure_ascii=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
    return report


def main():
    try:
        report = run()
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps({"capture_integrity_verified": report["capture_integrity_verified"],
                      "steps": len(report["steps"]), "error": report["error"]}))
    if (not report["capture_integrity_verified"] or report["summary"]["end"] != "COMPLETE"
            or report["vin_match"] not in ("NOT_REQUESTED", "MATCH")):
        raise SystemExit(2)


if __name__ == "__main__":
    main()
