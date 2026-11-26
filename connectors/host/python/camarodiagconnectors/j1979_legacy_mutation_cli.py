"""Separate interactive ELM legacy vehicle-wide operations and durable JSON."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys

from . import __version__
from .j1979_legacy import LegacyElmSerial, PROTOCOLS


def source(value):
    if not re.fullmatch(r"[0-9A-Fa-f]{2}", value):
        raise argparse.ArgumentTypeError("Expected two-digit ECU source")
    number = int(value, 16)
    if number in (0, 0xF1, 0xFF):
        raise argparse.ArgumentTypeError("Invalid ECU source")
    return number


def sources(value):
    values = [source(x.strip()) for x in value.split(",")]
    if not 1 <= len(values) <= 32 or len(set(values)) != len(values):
        raise argparse.ArgumentTypeError("Expected unique documented sources")
    return values


def _write(handle, report):
    json.dump(report, handle, indent=2, ensure_ascii=True)
    handle.write("\n")
    handle.flush()
    os.fsync(handle.fileno())


def run(argv=None, *, prompt=input):
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="Independent functional vehicle-wide J1979 legacy operation")
    parser.add_argument("--port", required=True)
    parser.add_argument("--baud", type=int, default=38400)
    parser.add_argument("--protocol", type=int, choices=PROTOCOLS, required=True)
    parser.add_argument("--operation", choices=("clear", "evap"), required=True)
    parser.add_argument("--vin", required=True)
    parser.add_argument("--engine-source", type=source, required=True)
    parser.add_argument("--sources", type=sources, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--confirm-dedicated-adapter", action="store_true", required=True)
    parser.add_argument("--fr", action="store_true")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", args.vin) or args.engine_source not in args.sources:
        parser.error("Documented VIN and engine response source required")
    if args.fr:
        warning = ("Requête fonctionnelle véhicule : même des calculateurs silencieux peuvent être affectés. "
                   "Respectez la procédure constructeur, moteur arrêté, véhicule immobilisé. "
                   + ("Exportez d’abord le diagnostic complet : les défauts, trames figées et états de préparation peuvent être effacés. "
                      if args.operation == "clear" else
                      "La préparation des conditions EVAP ne réalise pas le test de fuite. ")
                   + "Aucune répétition automatique. Tapez OK : ")
        confirmation = f"Confirmer UNE demande fonctionnelle véhicule {args.vin} ({PROTOCOLS[args.protocol]}) ? OUI/NON : "
    else:
        warning = ("Functional vehicle-wide request: silent ECUs may also be affected. Follow manufacturer procedure, "
                   "engine stopped and vehicle stationary. " +
                   ("Export original full scan first; emissions DTCs, freeze frames and readiness may be erased. "
                    if args.operation == "clear" else
                    "Preparing EVAP leak-test conditions does not perform a leak test. ") +
                   "No automatic retry. Type OK: ")
        confirmation = f"Confirm ONE vehicle-wide request on {args.vin} ({PROTOCOLS[args.protocol]})? YES/NO: "
    try:
        if prompt(warning).strip().upper() != "OK":
            return {"outcome": "NOT_TESTED", "reason": "CANCELLED"}
        if prompt(confirmation).strip().upper() != ("OUI" if args.fr else "YES"):
            return {"outcome": "NOT_TESTED", "reason": "CANCELLED"}
    except (EOFError, KeyboardInterrupt):
        return {"outcome": "NOT_TESTED", "reason": "CANCELLED"}
    baseline_path = Path(str(args.output) + ".baseline.json")
    if baseline_path.exists():
        raise FileExistsError(str(baseline_path))
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        started = datetime.now(timezone.utc).isoformat()
        def envelope(schema, result):
            return {"schema": schema, "protocol": "SAE-J1979", "version": __version__,
                    "transport": "ELM_SERIAL_LEGACY_HEADERED", "port": args.port, "baud": args.baud,
                    "variant": args.protocol, "variant_name": PROTOCOLS[args.protocol],
                    "operation": args.operation, "scope": "FUNCTIONAL_VEHICLE_WIDE",
                    "expected_vin": args.vin, "engine_source": f"{args.engine_source:02X}",
                    "documented_sources": [f"{s:02X}" for s in args.sources],
                    "physical_vehicle_verified": False, "started_at_utc": started,
                    "finished_at_utc": datetime.now(timezone.utc).isoformat(), **result}
        def save_baseline(result):
            bfd = os.open(baseline_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(bfd, "w", encoding="utf-8") as handle:
                _write(handle, envelope("camarodiag-j1979-legacy-baseline-v1", result))
        try:
            with LegacyElmSerial(args.port, args.baud, args.protocol, headered=True) as session:
                result = session.run_vehicle_mutation(args.operation, expected_vin=args.vin,
                    engine_source=args.engine_source, documented_sources=args.sources,
                    warning_acknowledged=True, confirmed_yes=True, save_baseline=save_baseline)
        except (OSError, ValueError, RuntimeError, ImportError, KeyboardInterrupt) as error:
            # If the baseline exists, an operation may have been attempted.
            result = {"outcome": "UNCERTAIN" if baseline_path.exists() else "NOT_TESTED",
                      "reason": type(error).__name__, "command_attempted": None,
                      "observed_vin": None, "whole_vehicle_coverage_verified": False}
        report = envelope("camarodiag-functional-j1979-legacy-mutation-v1", result)
        _write(output, report)
    return report


def main():
    if not sys.stdin.isatty():
        print("Interactive operator confirmation required.", file=sys.stderr)
        raise SystemExit(2)
    try:
        report = run()
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps({"outcome": report["outcome"], "reason": report.get("reason")}))
    if not (report["outcome"] == "ACKNOWLEDGED" or
            report["outcome"] == "ACKNOWLEDGED_READBACK" and report.get("reason") is None):
        raise SystemExit(2)


if __name__ == "__main__":
    main()
