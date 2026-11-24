"""Live DoIP network diagnostics on a PC host; independent of USB firmware."""
from __future__ import annotations

import argparse
from dataclasses import asdict
import json
import ssl
import sys
from datetime import datetime, timezone
from typing import Sequence

from .doip import Session, discover, status, DoIpError, valid_vin


def hex_value(value: str, digits: int) -> int:
    if len(value) != digits or any(c not in "0123456789abcdefABCDEF" for c in value):
        raise argparse.ArgumentTypeError(f"Expected {digits} hexadecimal digits")
    return int(value, 16)


def _trace(direction, transport, host, pdu):
    print(json.dumps({"direction": direction, "transport": transport, "host": host,
                      "utc": datetime.now(timezone.utc).isoformat(), "pdu": pdu}), file=sys.stderr)


def run(argv: Sequence[str] | None = None) -> dict:
    argv = list(sys.argv[1:] if argv is None else argv)
    fr = "--fr" in argv
    def tr(en, french):
        return french if fr else en
    parser = argparse.ArgumentParser(description=tr("Live DoIP host diagnostics", "Diagnostic DoIP réseau du PC"))
    parser.add_argument("--fr", action="store_true", help="Français")
    parser.add_argument("--host", required=True, help=tr("Numeric gateway IP", "IP numérique de la passerelle"))
    parser.add_argument("--vin", help=tr("Expected vehicle VIN", "VIN attendu"))
    parser.add_argument("--tester", type=lambda x: hex_value(x, 4))
    parser.add_argument("--ecu", type=lambda x: hex_value(x, 4))
    parser.add_argument("--tcp", action="store_true", help=tr("Explicit unencrypted TCP", "TCP non chiffré explicite"))
    parser.add_argument("--ca-file", help=tr("Trusted CA file for TLS", "Fichier AC de confiance TLS"))
    parser.add_argument("--timeout", type=float, default=3)
    parser.add_argument("--trace", action="store_true", help=tr("Write observed PDUs to stderr", "Journal PDU vers stderr"))
    sub = parser.add_subparsers(dest="action", required=True)
    for action in ("discover", "status", "power", "vin"):
        sub.add_parser(action)
    did = sub.add_parser("did")
    did.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
    monitor = sub.add_parser("monitor")
    monitor.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
    monitor.add_argument("--samples", type=int, default=10)
    monitor.add_argument("--interval", type=float, default=1)
    for action in ("dtcs", "count"):
        p = sub.add_parser(action)
        p.add_argument("--mask", type=lambda x: hex_value(x, 2), default=255)
    for action in ("snapshot", "extended"):
        p = sub.add_parser(action)
        p.add_argument("--dtc", required=True, type=lambda x: hex_value(x, 6))
        p.add_argument("--record", required=True, type=lambda x: hex_value(x, 2))
    o = parser.parse_args(argv)
    trace = _trace if o.trace else None
    network_only = o.action in ("discover", "status", "power")
    if not network_only and (o.vin is None or not valid_vin(o.vin) or o.tester is None or o.ecu is None or o.tester == o.ecu):
        parser.error(tr("Expected VIN and distinct tester/ECU addresses required", "VIN attendu et adresses testeur/ECU distinctes requis"))
    if o.action == "monitor" and not (1 <= o.samples <= 3600 and 0.25 <= o.interval <= 60):
        parser.error(tr("Invalid sample count or interval", "Nombre de mesures ou intervalle invalide"))
    if o.action in ("snapshot", "extended") and o.record == 0:
        parser.error(tr("Record must be nonzero", "Enregistrement non nul requis"))
    if o.tcp and o.ca_file:
        parser.error(tr("CA file requires TLS", "Le fichier AC nécessite TLS"))
    gateway = discover(o.host, o.vin, o.timeout, trace)
    result = {"gateway": asdict(gateway), "ecu_authenticated": False,
              "physical_qualification": False}
    if o.action == "discover":
        return result
    if o.action in ("status", "power"):
        return result | status(gateway, o.action == "power", o.timeout, trace)
    context = ssl.create_default_context(cafile=o.ca_file) if o.ca_file else None
    with Session(gateway, o.tester, o.ecu, tls=not o.tcp, timeout=o.timeout,
                 trace=trace, context=context) as session:
        vin = session.verify_vin(o.vin)
        result |= {"vin": vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp}
        if o.action == "vin":
            return result
        if o.action == "did":
            return result | {"did": o.did, "data": session.read_did(o.did).hex().upper()}
        if o.action == "dtcs":
            return result | session.dtcs(o.mask)
        if o.action == "count":
            return result | session.dtc_count(o.mask)
        if o.action in ("snapshot", "extended"):
            return result | session.dtc_detail(o.dtc, o.record, o.action == "extended")
        samples = []
        for index in range(o.samples):
            samples.append({"index": index + 1, "utc": datetime.now(timezone.utc).isoformat(),
                            "data": session.read_did(o.did).hex().upper()})
            if index + 1 < o.samples:
                session.wait(o.interval)
        session.verify_vin(vin)
        return result | {"did": o.did, "samples": samples}


def main():
    try:
        print(json.dumps(run(), indent=2))
    except (OSError, EOFError, ValueError) as exc:
        print(f"DoIP: {exc}", file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
