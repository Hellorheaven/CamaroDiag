"""Separate, interactive SAE J1979 Mode 08 EVAP functional recipe."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys

from . import __version__
from .j1979 import LinuxIsoTp
from .j1979_cli import _can_id


def run(argv=None, *, prompt=input) -> dict:
    parser = argparse.ArgumentParser(allow_abbrev=False,
        description="Independent local EVAP functional test (SAE J1979 CAN)")
    parser.add_argument("--interface", required=True)
    parser.add_argument("--tx", required=True, type=_can_id)
    parser.add_argument("--rx", required=True, type=_can_id)
    parser.add_argument("--vin", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fr", action="store_true")
    args = parser.parse_args(argv)
    if (args.tx, args.rx) not in ((0x7E0, 0x7E8), (0x18DA10F1, 0x18DAF110)):
        parser.error("Primary physical ECM address pair required")
    if not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", args.vin):
        parser.error("Expected a documented 17-character VIN")
    warning = (
        "Cette demande prépare les conditions EVAP sans réaliser le test de fuite. Respectez la procédure constructeur. Véhicule immobilisé, moteur arrêté, "
        "local ventilé et conditions de sécurité réunies. Une seule demande de test. OK : "
        if args.fr else
        "This request prepares EVAP leak-test conditions; it does not perform the test. Follow the manufacturer's test procedure. Stationary vehicle, engine stopped, "
        "ventilated area and safe conditions. One test request only. Type OK: ")
    confirmation = (f"Confirmer la préparation du test EVAP sur {args.vin}, {args.tx:X}/{args.rx:X} ? OUI/NON : "
                    if args.fr else
                    f"Confirm EVAP test conditions on {args.vin}, {args.tx:X}/{args.rx:X}? YES/NO: ")
    try:
        if prompt(warning).strip().upper() != "OK":
            return {"outcome": "NOT_TESTED", "reason": "CANCELLED"}
        if prompt(confirmation).strip().upper() != ("OUI" if args.fr else "YES"):
            return {"outcome": "NOT_TESTED", "reason": "CANCELLED"}
    except (EOFError, KeyboardInterrupt):
        return {"outcome": "NOT_TESTED", "reason": "CANCELLED"}

    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        started = datetime.now(timezone.utc).isoformat()
        try:
            with LinuxIsoTp(args.interface, args.tx, args.rx) as transport:
                result = transport.run_evap(args.vin, warning_acknowledged=True, confirmed_yes=True)
        except (OSError, ValueError) as exc:
            result = {"outcome": "NOT_TESTED", "reason": type(exc).__name__,
                      "command_attempted": False, "observed_vin": None, "exchanges": []}
        report = {"schema": "camarodiag-functional-j1979-evap-v1",
                  "protocol": "SAE-J1979", "version": __version__,
                  "transport": "LINUX_SOCKETCAN_ISOTP", "interface": args.interface,
                  "expected_vin": args.vin, "tx_can_id": f"{args.tx:X}",
                  "rx_can_id": f"{args.rx:X}", "started_at_utc": started,
                  "finished_at_utc": datetime.now(timezone.utc).isoformat(),
                  "physical_vehicle_verified": False, "monitor_completion_verified": False,
                  **result}
        json.dump(report, output, indent=2, ensure_ascii=True)
        output.write("\n")
    return report


def main():
    # No noninteractive yes flag. A redirected stdin cannot authorize a test.
    if not sys.stdin.isatty():
        print("Interactive operator confirmation required.", file=sys.stderr)
        raise SystemExit(2)
    try:
        report = run()
    except (OSError, ValueError) as exc:
        print(f"EVAP: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
    print(json.dumps({"outcome": report["outcome"], "reason": report.get("reason"),
                      "monitor_completion_verified": False}))
    if report["outcome"] != "ACKNOWLEDGED":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
