"""Live DoIP network diagnostics on a PC host; independent of USB firmware."""
from __future__ import annotations

import argparse
from contextlib import ExitStack
import os
from dataclasses import asdict
import json
import ssl
import sys
from pathlib import Path
from datetime import datetime, timezone
from typing import Sequence

from .doip import (Session, discover, discover_unique_vin, discover_ipv4, discover_ipv6, gateway_key, status,
                   DoIpError, InventoryTraceError, valid_vin, parse_commands, confirm_commands, parse_did_layout, parse_stored_did_lengths, validate_monitor_layout, parse_ecu_inventory, inventory_ecus)
from .doip_archive import load_scope
from .doip_capture import capture_region, save_capture


class ExactArgumentParser(argparse.ArgumentParser):
    def __init__(self, *args, **kwargs):
        kwargs["allow_abbrev"] = False
        super().__init__(*args, **kwargs)


def hex_value(value: str, digits: int) -> int:
    if len(value) != digits or any(c not in "0123456789abcdefABCDEF" for c in value):
        raise argparse.ArgumentTypeError(f"Expected {digits} hexadecimal digits")
    return int(value, 16)


class TraceWriteError(RuntimeError):
    """A failed evidence sink must abort the operation, not silently lose PDUs."""


def _trace_record(direction, transport, host, pdu, *, ecu=None):
    return json.dumps({"direction": direction, "transport": transport, "host": host,
                       "utc": datetime.now(timezone.utc).isoformat(), "pdu": pdu,
                       **({"inventory_target": f"{ecu:04X}"} if ecu is not None else {})})


def _trace_sinks(stderr, file):
    def emit(direction, transport, host, pdu, *, ecu=None):
        line = _trace_record(direction, transport, host, pdu, ecu=ecu)
        try:
            if file is not None:
                file.write(line + "\n")
                file.flush()
            if stderr:
                print(line, file=sys.stderr, flush=True)
        except (OSError, ValueError) as error:
            raise TraceWriteError("Cannot write DoIP trace") from error

    if not stderr and file is None:
        return None, None
    return emit, lambda ecu, *event: emit(*event, ecu=ecu)


def run(argv: Sequence[str] | None = None) -> dict:
    with ExitStack() as resources:
        return _run(argv, resources)


def _run(argv: Sequence[str] | None, resources: ExitStack) -> dict:
    argv = list(sys.argv[1:] if argv is None else argv)
    fr = "--fr" in argv
    def tr(en, french):
        return french if fr else en
    parser = ExactArgumentParser(description=tr("Live DoIP host diagnostics", "Diagnostic DoIP réseau du PC"))
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
    parser.add_argument("--trace-file", type=Path,
                        help=tr("New JSONL file of observed PDUs; never overwritten",
                                "Nouveau journal JSONL des PDU observés ; jamais écrasé"))
    sub = parser.add_subparsers(dest="action", required=True)
    for action in ("discover", "status", "power", "vin", "obd-identify"):
        sub.add_parser(action)
    for action, description in (
            ("ecu-inventory", tr("Verify explicit ECU logical addresses", "Vérifier les adresses logiques ECU explicites")),
            ("obd-ecu-inventory", tr("Identify OBDonUDS at explicit ECU addresses", "Identifier OBDonUDS aux adresses ECU explicites"))):
        inventory = sub.add_parser(action, help=description)
        inventory.add_argument("--ecus", required=True,
                               help=tr("1..16 explicit addresses, e.g. 1234,1235", "1 à 16 adresses explicites, ex. 1234,1235"))
    did = sub.add_parser("did")
    did.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
    scaling = sub.add_parser("scaling-did", help=tr("Read raw UDS scaling metadata", "Lire les données brutes de mise à l’échelle UDS"))
    scaling.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
    obd_did = sub.add_parser("obd-did", help=tr("Read legislated DID after ECU identification", "Lire un DID réglementaire après identification ECU"))
    obd_did.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
    for action in ("dids", "obd-dids"):
        multi = sub.add_parser(action, help=tr("Read several DIDs in one verified request", "Lire plusieurs DID dans une seule requête vérifiée"))
        multi.add_argument("--layout", required=True,
                           help=tr("DID:length in bytes, e.g. F190:17,F187:4; use actual ECU definitions",
                                   "DID:longueur en octets, ex. F190:17,F187:4 ; utiliser les définitions ECU réelles"))
    for action in ("monitor", "obd-monitor", "monitor-dids", "obd-monitor-dids"):
        monitor = sub.add_parser(action, help=tr("Monitor DIDs on one verified ECU", "Surveiller les DID sur un ECU vérifié"))
        if action.endswith("-dids"):
            monitor.add_argument("--layout", required=True, help=tr("DID:length, e.g. F40C:2,F40D:1",
                                                                   "DID:longueur, ex. F40C:2,F40D:1"))
        else:
            monitor.add_argument("--did", required=True, type=lambda x: hex_value(x, 4))
        monitor.add_argument("--samples", type=int, default=10)
        monitor.add_argument("--interval", type=float, default=1)
    sub.add_parser("supported-dtcs", help=tr("List supported DTCs", "Lister les DTC pris en charge"))
    stored = sub.add_parser("stored-record", help=tr("Read stored DTC data by record number", "Lire les données DTC mémorisées par numéro"))
    stored.add_argument("--record", required=True, type=lambda x: hex_value(x, 2))
    all_stored = sub.add_parser("stored-records", help=tr("Read all stored DTC records", "Lire tous les enregistrements DTC mémorisés"))
    all_stored.add_argument("--did-lengths", required=True, help=tr(
        "Documented DID:length catalog, e.g. F190:17,F40D:1",
        "Catalogue DID:longueur documenté, ex. F190:17,F40D:1"))
    sub.add_parser("snapshot-index", help=tr("List snapshot references", "Lister les références des snapshots"))
    sub.add_parser("permanent-dtcs", help=tr("Read permanent UDS DTCs", "Lire les DTC permanents UDS"))
    for action in ("severity-count", "severity-dtcs"):
        severity = sub.add_parser(action, help=tr("Filter DTCs by severity and status", "Filtrer les DTC par sévérité et statut"))
        severity.add_argument("--severity-mask", required=True, type=lambda x: hex_value(x, 2))
        severity.add_argument("--mask", required=True, type=lambda x: hex_value(x, 2))
    severity_detail = sub.add_parser("severity-info", help=tr("Read severity for one DTC", "Lire la sévérité d'un DTC"))
    severity_detail.add_argument("--dtc", required=True, type=lambda x: hex_value(x, 6))
    sub.add_parser("fault-counters", help=tr("Read DTC fault detection counters", "Lire les compteurs de détection DTC"))
    historical_actions = {"first-failed-dtc": 0x0B, "first-confirmed-dtc": 0x0C,
                          "recent-failed-dtc": 0x0D, "recent-confirmed-dtc": 0x0E}
    for action in historical_actions:
        sub.add_parser(action, help=tr("Read a historical DTC", "Lire un DTC historique"))
    for action in ("dtcs", "count", "obd-dtcs", "obd-count"):
        p = sub.add_parser(action, help=tr("Verified DTC read or count", "Lecture ou comptage DTC vérifié"))
        p.add_argument("--mask", type=lambda x: hex_value(x, 2), default=255)
    wwh = sub.add_parser("obd-wwh-dtcs", help=tr("Verified WWH emissions DTCs", "DTC émissions WWH vérifiés"))
    wwh.add_argument("--mask", type=lambda x: hex_value(x, 2), default=255)
    wwh.add_argument("--severity-mask", type=lambda x: hex_value(x, 2), default=255)
    sub.add_parser("obd-wwh-permanent", help=tr("Verified permanent emissions DTCs", "DTC émissions permanents vérifiés"))
    readiness = sub.add_parser("obd-wwh-readiness", help=tr("Verified readiness group DTCs", "DTC du groupe de readiness vérifié"))
    readiness.add_argument("--group", required=True, type=lambda x: hex_value(x, 2))
    for action in ("snapshot", "extended", "obd-snapshot", "obd-extended"):
        p = sub.add_parser(action, help=tr("Verified DTC record read", "Lecture d'enregistrement DTC vérifiée"))
        p.add_argument("--dtc", required=True, type=lambda x: hex_value(x, 6))
        p.add_argument("--record", required=True, type=lambda x: hex_value(x, 2))
    for action, description in (
            ("memory-dtcs", tr("Read DTCs in a selected memory", "Lire les DTC d'une mémoire sélectionnée")),
            ("memory-snapshot", tr("Read a snapshot in a selected memory", "Lire un snapshot d'une mémoire sélectionnée")),
            ("memory-extended", tr("Read extended data in a selected memory", "Lire les données étendues d'une mémoire sélectionnée"))):
        memory = sub.add_parser(action, help=description)
        memory.add_argument("--memory", required=True, type=lambda x: hex_value(x, 2))
        if action == "memory-dtcs":
            memory.add_argument("--mask", required=True, type=lambda x: hex_value(x, 2))
        else:
            memory.add_argument("--dtc", required=True, type=lambda x: hex_value(x, 6))
            memory.add_argument("--record", required=True, type=lambda x: hex_value(x, 2))
    legacy_actions = {"mirror-dtcs": 0x0F, "mirror-extended": 0x10, "mirror-count": 0x11,
                      "legacy-emissions-count": 0x12, "legacy-emissions-dtcs": 0x13}
    for action, subfunction in legacy_actions.items():
        legacy = sub.add_parser(action, help=tr("Explicit legacy UDS read (before ISO 14229-1:2020)",
                                               "Lecture UDS historique explicite (avant ISO 14229-1:2020)"))
        if subfunction == 0x10:
            legacy.add_argument("--dtc", required=True, type=lambda x: hex_value(x, 6))
            legacy.add_argument("--record", required=True, type=lambda x: hex_value(x, 2))
        else:
            legacy.add_argument("--mask", required=True, type=lambda x: hex_value(x, 2))
    for action, description in (
            ("extended-record-support", tr("DTCs supporting an extended record", "DTC prenant en charge un enregistrement étendu")),
            ("extended-record-data", tr("Read extended records with known size", "Lire les enregistrements étendus de taille connue"))):
        extended_record = sub.add_parser(action, help=description)
        extended_record.add_argument("--record", required=True, type=lambda x: hex_value(x, 2))
        if action == "extended-record-data":
            extended_record.add_argument("--size", required=True, type=int,
                help=tr("Uniform documented byte length per DTC", "Longueur uniforme documentée en octets par DTC"))
    backup = sub.add_parser("backup", help=tr("Read a documented ECU region twice", "Lire deux fois une zone ECU documentée"))
    backup.add_argument("--profile", required=True, type=Path)
    backup.add_argument("--output", required=True, type=Path)
    sequence = sub.add_parser("sequence", help=tr("Send confirmed ordered UDS commands", "Envoyer une séquence UDS confirmée"))
    sequence.add_argument("--commands", required=True,
                          help=tr("Hexadecimal commands separated by semicolons", "Commandes hexadécimales séparées par des points-virgules"))
    seen_options = set()
    for token in argv:
        if token.startswith("--") and token != "--":
            option = token.split("=", 1)[0]
            if option in seen_options:
                parser.error(tr("Duplicate option: ", "Option répétée : ") + option)
            seen_options.add(option)
    o = parser.parse_args(argv)
    targets = parse_ecu_inventory(o.ecus) if o.action in ("ecu-inventory", "obd-ecu-inventory") else None
    commands = parse_commands(o.commands) if o.action == "sequence" else None
    multi_monitor = o.action in ("monitor-dids", "obd-monitor-dids")
    layout = parse_did_layout(o.layout) if multi_monitor or o.action in ("dids", "obd-dids") else None
    if multi_monitor:
        layout = validate_monitor_layout(layout)
    stored_lengths = parse_stored_did_lengths(o.did_lengths) if o.action == "stored-records" else None
    if o.action in ("obd-dids", "obd-monitor-dids") and any(not 0xF400 <= did <= 0xF8FF for did, _ in layout):
        parser.error(tr("Legislated DIDs F400..F8FF required", "DID réglementaires F400..F8FF requis"))
    if o.action == "mirror-extended" and (o.record == 0 or 0xF0 <= o.record <= 0xFD):
        parser.error(tr("Invalid mirror extended record", "Enregistrement étendu miroir invalide"))
    if o.action in ("extended-record-support", "extended-record-data"):
        if not 1 <= o.record <= 0xEF or (o.action == "extended-record-data" and not 1 <= o.size <= 4090):
            parser.error(tr("Expected record 01..EF and documented size 1..4090",
                            "Enregistrement 01..EF et taille documentée 1..4090 attendus"))
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
    if targets is not None and (o.ecu is not None or o.tester in targets):
        parser.error(tr("Inventory uses --ecus instead of --ecu; tester must differ",
                        "Inventaire : --ecus au lieu de --ecu ; testeur distinct"))
    if not network_only and (o.vin is None or not valid_vin(o.vin) or o.tester is None or
                             (targets is None and (o.ecu is None or o.tester == o.ecu))):
        parser.error(tr("Expected VIN and distinct tester/ECU addresses required", "VIN attendu et adresses testeur/ECU distinctes requis"))
    if o.action in ("obd-did", "obd-monitor") and not 0xF400 <= o.did <= 0xF8FF:
        parser.error(tr("Legislated DID F400..F8FF required", "DID réglementaire F400..F8FF requis"))
    if (multi_monitor or o.action in ("monitor", "obd-monitor")) and not (1 <= o.samples <= 3600 and 0.25 <= o.interval <= 60):
        parser.error(tr("Invalid sample count or interval", "Nombre de mesures ou intervalle invalide"))
    if o.action in ("snapshot", "extended", "obd-snapshot", "obd-extended", "memory-snapshot", "memory-extended") and o.record == 0:
        parser.error(tr("Record must be nonzero", "Enregistrement non nul requis"))
    if o.action == "stored-record" and o.record == 255:
        parser.error(tr("Choose an individual record 00..FE", "Choisir un enregistrement individuel 00..FE"))
    if o.action == "memory-extended" and 0xF0 <= o.record <= 0xFD:
        parser.error(tr("Reserved extended record number", "Numéro d'enregistrement étendu réservé"))
    if o.tcp and (o.ca_file or o.client_cert or o.client_key):
        parser.error(tr("Certificate files require TLS", "Les certificats nécessitent TLS"))
    if o.client_key and not o.client_cert:
        parser.error(tr("Client key requires a certificate", "La clé client nécessite un certificat"))
    if network_only and (o.tcp or o.ca_file or o.client_cert or o.client_key or
                         o.tester is not None or o.ecu is not None):
        parser.error(tr("Session options require a diagnostic action",
                        "Les options de session nécessitent une action de diagnostic"))
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
    if not (o.broadcast_ip is not None or o.listen or ipv6_index is not None):
        if not o.host and (o.vin is None or not valid_vin(o.vin)):
            parser.error(tr("Host or valid expected VIN required", "IP ou VIN attendu valide requis"))
    if commands is not None and not sys.stdin.isatty():
        parser.error(tr("Interactive terminal required for confirmation", "Terminal interactif requis pour la confirmation"))
    # Validate TLS files before sending even a discovery datagram.
    context = ssl.create_default_context(cafile=o.ca_file) if (o.ca_file or o.client_cert) else None
    if o.client_cert:
        context.load_cert_chain(o.client_cert, o.client_key)
    trace_file = None
    if o.trace_file is not None:
        target = o.trace_file.resolve()
        protected = (getattr(o, "profile", None), getattr(o, "output", None),
                     o.ca_file, o.client_cert, o.client_key)
        if any(value is not None and target == Path(value).resolve() for value in protected):
            parser.error(tr("Trace file must differ from profile, backup and certificates",
                            "Journal distinct du profil, de la sauvegarde et des certificats requis"))
        # Exclusive create, including concurrent invocations. Restrict access on POSIX.
        trace_file = resources.enter_context(open(o.trace_file, "x", encoding="utf-8",
            newline="\n", opener=lambda path, flags: os.open(path, flags, 0o600)))
    trace, target_trace = _trace_sinks(o.trace, trace_file)
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
    if targets is not None:
        return result | inventory_ecus(gateway, o.vin, o.tester, targets, tls=not o.tcp,
            timeout=o.timeout, trace=trace, context=context, p2=p2, p2_star=p2_star,
            routing_type=o.routing_activation_type or 0, routing_oem_data=o.routing_oem_data,
            obd=o.action == "obd-ecu-inventory", target_trace=target_trace)
    if commands is not None and not confirm_commands(commands, gateway, o.tester, o.ecu,
            not o.tcp, input, lambda line: print(line, file=sys.stderr), fr):
        return result | {"cancelled": True, "commands_sent": 0}
    def verify_gateway():
        current = discover(gateway.host, o.vin, o.timeout, trace)
        if gateway_key(current) != gateway_key(gateway):
            raise DoIpError(tr("Gateway identity changed", "L'identité de la passerelle a changé"))

    # Recheck the selected endpoint after preparation (and consent for raw sequences).
    verify_gateway()
    with Session(gateway, o.tester, o.ecu, tls=not o.tcp, timeout=o.timeout,
                 trace=trace, context=context, p2=p2, p2_star=p2_star,
                 routing_type=o.routing_activation_type or 0,
                 routing_oem_data=o.routing_oem_data) as session:
        if o.action == "scaling-did":
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "did": o.did, "scaling_data": session.read_scaling_did(o.vin, o.did).hex().upper()}
        if multi_monitor:
            def on_multi_sample(row):
                print(json.dumps({"partial_sample": row}), file=sys.stderr)
            return result | {"ecu": o.ecu, "tester": o.tester, "tls": not o.tcp} | session.monitor_dids(
                o.vin, layout, o.samples, o.interval, obd=o.action == "obd-monitor-dids", on_sample=on_multi_sample)
        if layout is not None:
            return result | {"ecu": o.ecu, "tester": o.tester, "tls": not o.tcp} | session.read_dids(
                o.vin, layout, obd=o.action == "obd-dids")
        if o.action == "obd-identify":
            return result | {"ecu": o.ecu, "tester": o.tester, "tls": not o.tcp} | session.identify_obd(o.vin)
        if o.action == "obd-did":
            return result | {"ecu": o.ecu, "tester": o.tester, "tls": not o.tcp} | session.read_obd(o.vin, o.did)
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
        if o.action in ("severity-count", "severity-dtcs", "severity-info"):
            subfunction = {"severity-count": 7, "severity-dtcs": 8, "severity-info": 9}[o.action]
            report = session.read_dtc_severity(o.vin, subfunction, severity_mask=getattr(o, "severity_mask", 255),
                                              status_mask=getattr(o, "mask", 255), dtc=getattr(o, "dtc", None))
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | report
        if o.action == "stored-records":
            report = session.read_all_stored_dtc_records(o.vin, stored_lengths)
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | report
        if o.action == "stored-record":
            report = session.read_stored_dtc_record(o.vin, o.record)
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | report
        if o.action in legacy_actions:
            report = session.read_legacy_dtc(o.vin, legacy_actions[o.action], mask=getattr(o, "mask", 255),
                                            dtc=getattr(o, "dtc", None), record=getattr(o, "record", None))
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | report
        if o.action in ("extended-record-support", "extended-record-data"):
            report = session.read_extended_record(o.vin, o.record, size=getattr(o, "size", None))
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | report
        if o.action in ("memory-dtcs", "memory-snapshot", "memory-extended"):
            subfunction = {"memory-dtcs": 0x17, "memory-snapshot": 0x18, "memory-extended": 0x19}[o.action]
            report = session.read_user_memory_dtc(o.vin, o.memory, subfunction=subfunction,
                mask=getattr(o, "mask", 255), dtc=getattr(o, "dtc", None), record=getattr(o, "record", None))
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | report
        if o.action == "fault-counters":
            report = session.read_fault_counters(o.vin)
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | report
        if o.action in historical_actions:
            report = session.read_historical_dtc(o.vin, historical_actions[o.action])
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | report
        if o.action in ("supported-dtcs", "snapshot-index", "permanent-dtcs"):
            inventory = session.read_dtc_inventory(o.vin, snapshot_index=o.action == "snapshot-index",
                                                   permanent=o.action == "permanent-dtcs")
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "UDS"} | inventory
        if o.action in ("dtcs", "count", "snapshot", "extended",
                        "obd-dtcs", "obd-count", "obd-snapshot", "obd-extended"):
            obd = o.action.startswith("obd-")
            report = session.read_verified_dtc(o.vin, o.action.removeprefix("obd-"), mask=getattr(o, "mask", 255),
                                               dtc=getattr(o, "dtc", None), record=getattr(o, "record", None), obd=obd)
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "service_protocol": "OBDonUDS" if obd else "UDS"} | report
        if o.action == "did":
            data = session.read_verified_did(o.vin, o.did)
            return result | {"vin": o.vin, "ecu": o.ecu, "tester": o.tester, "tls": not o.tcp,
                             "did": o.did, "data": data.hex().upper()}
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
            verify_gateway()
            return result | save_capture(o.output, o.profile, scope, first, second)
        if o.action == "vin":
            return result
        raise DoIpError("Unsupported diagnostic action")


def main():
    try:
        print(json.dumps(run(), indent=2))
    except (OSError, EOFError, ValueError, TraceWriteError, InventoryTraceError) as exc:
        print(f"DoIP: {exc}", file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
