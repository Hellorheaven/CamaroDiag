"""Live DoIP network diagnostics on a PC host; independent of USB firmware."""
from __future__ import annotations

import argparse
from dataclasses import asdict
import json
import ssl
import sys
from pathlib import Path
from datetime import datetime, timezone
from typing import Sequence

from .doip import (Session, discover, discover_unique_vin, discover_ipv4, discover_ipv6, gateway_key, status,
                   DoIpError, valid_vin, parse_commands, confirm_commands, parse_did_layout)
from .doip_archive import load_scope
from .doip_capture import capture_region, save_capture


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
    parser.add_argument("--host", help=tr("Numeric gateway IP; omit for VIN discovery", "IP numérique ; laisser vide pour découverte VIN"))
    parser.add_argument("--vin", help=tr("Expected vehicle VIN", "VIN attendu"))
    parser.add_argument("--eid", type=lambda x: hex_value(x, 12).to_bytes(6, "big"),
                        help=tr("Gateway EID, 12 hex digits (discovery only)", "EID passerelle, 12 chiffres hex (découverte uniquement)"))
    discovery_mode = parser.add_mutually_exclusive_group()
    discovery_mode.add_argument("--broadcast-ip", help=tr("Explicit IPv4 discovery destination", "Destination de découverte IPv4 explicite"))
    discovery_mode.add_argument("--listen", action="store_true", help=tr("Listen passively for IPv4 announcements", "Écouter passivement les annonces IPv4"))
    discovery_mode.add_argument("--multicast-ipv6-ifindex", type=int,
                                help=tr("Active link-local IPv6 discovery on an interface index",
                                        "Découverte IPv6 link-local active sur un index d'interface"))
    discovery_mode.add_argument("--listen-ipv6-ifindex", type=int,
                                help=tr("Passive IPv6 announcements on an interface index",
                                        "Écoute passive IPv6 sur un index d'interface"))
    parser.add_argument("--listen-host", default="0.0.0.0", help=tr("Local IPv4 bind address for discovery", "Adresse IPv4 locale pour découverte"))
    parser.add_argument("--tester", type=lambda x: hex_value(x, 4))
    parser.add_argument("--ecu", type=lambda x: hex_value(x, 4))
    parser.add_argument("--tcp", action="store_true", help=tr("Explicit unencrypted TCP", "TCP non chiffré explicite"))
    parser.add_argument("--ca-file", help=tr("Trusted CA file for TLS", "Fichier AC de confiance TLS"))
    parser.add_argument("--client-cert", help=tr("TLS client certificate file", "Certificat client TLS"))
    parser.add_argument("--client-key", help=tr("TLS client private key file", "Clé privée client TLS"))
    parser.add_argument("--timeout", type=float, default=3,
                        help=tr("Overall timeout in seconds (0.1..30)", "Délai global en secondes (0,1..30)"))
    parser.add_argument("--p2-ms", type=int,
                        help=tr("Initial UDS response deadline in milliseconds", "Délai de première réponse UDS en millisecondes"))
    parser.add_argument("--p2-star-ms", type=int,
                        help=tr("UDS response deadline after NRC 78 in milliseconds", "Délai de réponse UDS après NRC 78 en millisecondes"))
    parser.add_argument("--routing-activation-type", type=lambda x: hex_value(x, 2),
                        help=tr("Two hex digits supplied by the gateway manufacturer", "Deux chiffres hexadécimaux fournis par le constructeur"))
    parser.add_argument("--routing-oem-data", type=lambda x: hex_value(x, 8).to_bytes(4, "big"),
                        help=tr("Optional four OEM bytes, eight hex digits", "Quatre octets OEM facultatifs, huit chiffres hexadécimaux"))
    parser.add_argument("--trace", action="store_true", help=tr("Write observed PDUs to stderr", "Journal PDU vers stderr"))
    sub = parser.add_subparsers(dest="action", required=True)
    for action in ("discover", "status", "power", "vin", "obd-identify"):
        sub.add_parser(action)
    did = sub.add_parser("did")
    did.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
    obd_did = sub.add_parser("obd-did", help=tr("Read legislated DID after ECU identification", "Lire un DID réglementaire après identification ECU"))
    obd_did.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
    for action in ("dids", "obd-dids"):
        multi = sub.add_parser(action, help=tr("Read several DIDs in one verified request", "Lire plusieurs DID dans une seule requête vérifiée"))
        multi.add_argument("--layout", required=True,
                           help=tr("DID:length in bytes, e.g. F190:17,F187:4; use actual ECU definitions",
                                   "DID:longueur en octets, ex. F190:17,F187:4 ; utiliser les définitions ECU réelles"))
    for action in ("monitor", "obd-monitor"):
        monitor = sub.add_parser(action, help=tr("Monitor a DID on one verified ECU", "Surveiller un DID sur un ECU vérifié"))
        monitor.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
        monitor.add_argument("--samples", type=int, default=10)
        monitor.add_argument("--interval", type=float, default=1)
    for action in ("dtcs", "count"):
        p = sub.add_parser(action)
        p.add_argument("--mask", type=lambda x: hex_value(x, 2), default=255)
    wwh = sub.add_parser("obd-wwh-dtcs", help=tr("Verified WWH emissions DTCs", "DTC émissions WWH vérifiés"))
    wwh.add_argument("--mask", type=lambda x: hex_value(x, 2), default=255)
    wwh.add_argument("--severity-mask", type=lambda x: hex_value(x, 2), default=255)
    sub.add_parser("obd-wwh-permanent", help=tr("Verified permanent emissions DTCs", "DTC émissions permanents vérifiés"))
    readiness = sub.add_parser("obd-wwh-readiness", help=tr("Verified readiness group DTCs", "DTC du groupe de readiness vérifié"))
    readiness.add_argument("--group", required=True, type=lambda x: hex_value(x, 2))
    for action in ("snapshot", "extended"):
        p = sub.add_parser(action)
        p.add_argument("--dtc", required=True, type=lambda x: hex_value(x, 6))
        p.add_argument("--record", required=True, type=lambda x: hex_value(x, 2))
    backup = sub.add_parser("backup", help=tr("Read a documented ECU region twice", "Lire deux fois une zone ECU documentée"))
    backup.add_argument("--profile", required=True, type=Path)
    backup.add_argument("--output", required=True, type=Path)
    sequence = sub.add_parser("sequence", help=tr("Send confirmed ordered UDS commands", "Envoyer une séquence UDS confirmée"))
    sequence.add_argument("--commands", required=True,
                          help=tr("Hexadecimal commands separated by semicolons", "Commandes hexadécimales séparées par des points-virgules"))
    o = parser.parse_args(argv)
    commands = parse_commands(o.commands) if o.action == "sequence" else None
    layout = parse_did_layout(o.layout) if o.action in ("dids", "obd-dids") else None
    if o.action == "obd-dids" and any(not 0xF400 <= did <= 0xF8FF for did, _ in layout):
        parser.error(tr("Legislated DIDs F400..F8FF required", "DID réglementaires F400..F8FF requis"))
    scope = None
    if o.action == "backup":
        scope = load_scope(o.profile)
        for supplied, expected in ((o.vin, scope["Vin"]), (o.tester, scope["Tester"]), (o.ecu, scope["Ecu"])):
            if supplied is not None and supplied != expected:
                parser.error(tr("Target differs from profile", "La cible diffère du profil"))
        o.vin, o.tester, o.ecu = scope["Vin"], scope["Tester"], scope["Ecu"]
        if o.output.exists() or not o.output.parent.is_dir():
            parser.error(tr("Choose a new output in an existing directory", "Choisir un nouveau fichier dans un dossier existant"))
        if (scope["Length"] + scope["BlockSize"] - 1) // scope["BlockSize"] > 8192:
            parser.error(tr("Profile exceeds 8192 blocks per pass", "Le profil dépasse 8192 blocs par passe"))
    trace = _trace if o.trace else None
    network_only = o.action in ("discover", "status", "power")
    p2 = o.timeout if o.p2_ms is None else o.p2_ms / 1000
    p2_star = o.timeout if o.p2_star_ms is None else o.p2_star_ms / 1000
    if not 0.1 <= o.timeout <= 30 or not 0.001 <= p2 <= p2_star <= o.timeout:
        parser.error(tr("Expected 1 ms <= P2 <= P2* <= overall timeout (0.1..30 s)",
                        "Attendu : 1 ms <= P2 <= P2* <= délai global (0,1..30 s)"))
    if network_only and any(value is not None for value in
                            (o.p2_ms, o.p2_star_ms, o.routing_activation_type, o.routing_oem_data)):
        parser.error(tr("Routing and UDS options require a diagnostic action",
                        "Les options de routage et UDS nécessitent une action de diagnostic"))
    if not network_only and (o.vin is None or not valid_vin(o.vin) or o.tester is None or o.ecu is None or o.tester == o.ecu):
        parser.error(tr("Expected VIN and distinct tester/ECU addresses required", "VIN attendu et adresses testeur/ECU distinctes requis"))
    if o.action in ("obd-did", "obd-monitor") and not 0xF400 <= o.did <= 0xF8FF:
        parser.error(tr("Legislated DID F400..F8FF required", "DID réglementaire F400..F8FF requis"))
    if o.action in ("monitor", "obd-monitor") and not (1 <= o.samples <= 3600 and 0.25 <= o.interval <= 60):
        parser.error(tr("Invalid sample count or interval", "Nombre de mesures ou intervalle invalide"))
    if o.action in ("snapshot", "extended") and o.record == 0:
        parser.error(tr("Record must be nonzero", "Enregistrement non nul requis"))
    if o.tcp and (o.ca_file or o.client_cert or o.client_key):
        parser.error(tr("Certificate files require TLS", "Les certificats nécessitent TLS"))
    if o.client_key and not o.client_cert:
        parser.error(tr("Client key requires a certificate", "La clé client nécessite un certificat"))
    if o.vin is not None and not valid_vin(o.vin):
        parser.error(tr("Invalid VIN", "VIN invalide"))
    if o.eid is not None and o.vin is not None:
        parser.error(tr("Choose VIN or EID, not both", "Choisir VIN ou EID, pas les deux"))
    ipv6_index = o.multicast_ipv6_ifindex if o.multicast_ipv6_ifindex is not None else o.listen_ipv6_ifindex
    discovery_options = (o.eid is not None or o.broadcast_ip is not None or o.listen or
                         o.listen_host != "0.0.0.0" or ipv6_index is not None)
    if discovery_options and o.action != "discover":
        parser.error(tr("Discovery options require discover", "Options réservées à discover"))
    if o.host and (o.broadcast_ip is not None or o.listen or ipv6_index is not None):
        parser.error(tr("Choose unicast, broadcast or passive discovery", "Choisir unicast, diffusion ou écoute passive"))
    if o.listen_host != "0.0.0.0" and not (o.broadcast_ip is not None or o.listen):
        parser.error(tr("Local bind address requires broadcast or passive discovery", "Adresse locale réservée à la diffusion ou écoute passive"))
    if ipv6_index is not None:
        found = discover_ipv6(ipv6_index, o.timeout, trace, passive=o.listen_ipv6_ifindex is not None,
                              vin=o.vin, eid=o.eid)
        return {"gateways": [asdict(item) for item in found], "ecu_authenticated": False,
                "physical_qualification": False}
    if o.broadcast_ip is not None or o.listen:
        found = discover_ipv4(o.timeout, trace, destination=o.broadcast_ip, vin=o.vin, eid=o.eid,
                              listen_host=o.listen_host)
        return {"gateways": [asdict(item) for item in found], "ecu_authenticated": False,
                "physical_qualification": False}
    if not o.host and (o.vin is None or not valid_vin(o.vin)):
        parser.error(tr("Host or valid expected VIN required", "IP ou VIN attendu valide requis"))
    if commands is not None and not sys.stdin.isatty():
        parser.error(tr("Interactive terminal required for confirmation", "Terminal interactif requis pour la confirmation"))
    if o.host:
        gateway = (discover(o.host, o.vin, o.timeout, trace, eid=o.eid) if o.eid is not None
                   else discover(o.host, o.vin, o.timeout, trace))
    else:
        selected = discover_unique_vin(o.vin, o.timeout, trace)
        gateway = discover(selected.host, o.vin, o.timeout, trace)
        if gateway_key(selected) != gateway_key(gateway):
            raise DoIpError(tr("Gateway changed after discovery", "La passerelle a changé après découverte"))
    result = {"gateway": asdict(gateway), "ecu_authenticated": False,
              "physical_qualification": False}
    if o.action == "discover":
        return result
    if o.action in ("status", "power"):
        return result | status(gateway, o.action == "power", o.timeout, trace)
    if scope is not None and gateway.logical_address != scope["Gateway"]:
        raise DoIpError(tr("Observed gateway differs from profile", "La passerelle observée diffère du profil"))
    context = ssl.create_default_context(cafile=o.ca_file) if (o.ca_file or o.client_cert) else None
    if o.client_cert:
        context.load_cert_chain(o.client_cert, o.client_key)
    if commands is not None and not confirm_commands(commands, gateway, o.tester, o.ecu,
            not o.tcp, input, lambda line: print(line, file=sys.stderr), fr):
        return result | {"cancelled": True, "commands_sent": 0}
    if commands is not None:
        current = discover(gateway.host, o.vin, o.timeout, trace)
        if gateway_key(current) != gateway_key(gateway):
            raise DoIpError(tr("Gateway changed after confirmation", "La passerelle a changé après confirmation"))
    with Session(gateway, o.tester, o.ecu, tls=not o.tcp, timeout=o.timeout,
                 trace=trace, context=context, p2=p2, p2_star=p2_star,
                 routing_type=o.routing_activation_type or 0,
                 routing_oem_data=o.routing_oem_data) as session:
        if layout is not None:
            return result | {"ecu": o.ecu, "tester": o.tester, "tls": not o.tcp} | session.read_dids(
                o.vin, layout, obd=o.action == "obd-dids")
        if o.action in ("obd-identify", "obd-did"):
            return result | {"ecu": o.ecu, "tester": o.tester, "tls": not o.tcp} | session.read_obd(
                o.vin, o.did if o.action == "obd-did" else None)
        if o.action in ("obd-wwh-dtcs", "obd-wwh-permanent", "obd-wwh-readiness"):
            subfunction = {"obd-wwh-dtcs": 0x42, "obd-wwh-permanent": 0x55,
                           "obd-wwh-readiness": 0x56}[o.action]
            return result | {"ecu": o.ecu, "tester": o.tester, "tls": not o.tcp} | session.wwh_dtcs(
                o.vin, subfunction, status_mask=getattr(o, "mask", 255),
                severity_mask=getattr(o, "severity_mask", 255), readiness_group=getattr(o, "group", None))
        if o.action in ("monitor", "obd-monitor"):
            def on_sample(row):
                print(json.dumps({"partial_sample": row}), file=sys.stderr)
            return result | {"ecu": o.ecu, "tester": o.tester, "tls": not o.tcp} | session.monitor(
                o.vin, o.did, o.samples, o.interval, obd=o.action == "obd-monitor", on_sample=on_sample)
        vin = session.verify_vin(o.vin)
        result |= {"vin": vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp}
        if commands is not None:
            # Preserve each completed response even if a later command fails.
            def on_reply(index, reply):
                print(json.dumps({"index": index, "response": reply.hex().upper()}), file=sys.stderr)
            replies = session.exchange_sequence(commands, on_reply)
            return result | {"responses": [reply.hex().upper() for reply in replies],
                             "commands_completed": len(replies)}
        if scope is not None:
            def progress(index, offset, length):
                print(tr("Pass", "Passe") + f" {index}/2: {offset}/{length}", file=sys.stderr)
            first, second = capture_region(session, scope, progress)
            return result | save_capture(o.output, o.profile, scope, first, second)
        if o.action == "vin":
            return result
        if o.action == "did":
            data = session.read_did(o.did)
            session.verify_vin(o.vin)
            return result | {"did": o.did, "data": data.hex().upper()}
        if o.action == "dtcs":
            return result | session.dtcs(o.mask)
        if o.action == "count":
            return result | session.dtc_count(o.mask)
        if o.action in ("snapshot", "extended"):
            return result | session.dtc_detail(o.dtc, o.record, o.action == "extended")
        raise DoIpError("Unsupported diagnostic action")


def main():
    try:
        print(json.dumps(run(), indent=2))
    except (OSError, EOFError, ValueError) as exc:
        print(f"DoIP: {exc}", file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
