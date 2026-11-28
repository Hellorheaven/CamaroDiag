"""Offline inspection and cross-check of a native CM/1 maintenance journal."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from datetime import datetime, timezone

from .j1979_maintenance_journal import inspect, inspect_prefix, require, MAX_JOURNAL, Scope
from .j1979_maintenance_usb import capture, scope_bytes
from .recovery_bundle import _regular, _json
from .recovery_history import _compare_history_value

MAX_REPORT = 1024 * 1024


def inspect_file(path, *, expected_vin, prefix=False):
    raw, stamp = _regular(Path(path), MAX_JOURNAL)
    result = (inspect_prefix if prefix else inspect)(raw, expected_vin=expected_vin)
    again, after = _regular(Path(path), MAX_JOURNAL)
    require(raw == again and stamp == after, "CM/1 changed during inspection")
    result["journal_sha256"] = stamp.digest
    return result


def verify_files(journal, report, *, expected_vin, prefix=False):
    journal, report = Path(journal), Path(report)
    require(journal.resolve() != report.resolve(), "Input paths must differ")
    raw, stamp = _regular(journal, MAX_JOURNAL)
    original, report_stamp = _regular(report, MAX_REPORT)
    expected = (inspect_prefix if prefix else inspect)(raw, expected_vin=expected_vin)
    expected["journal_sha256"] = stamp.digest
    actual = _json(original)
    _compare_history_value(actual, expected, "maintenance_journal")
    raw_again, after = _regular(journal, MAX_JOURNAL)
    report_again, report_after = _regular(report, MAX_REPORT)
    require(raw == raw_again and stamp == after and original == report_again
            and report_stamp == report_after, "CM/1 inputs changed during verification")
    return {"schema": "camarodiag-j1979-native-maintenance-verification-v1",
            "consistent": True, "journal_sha256": stamp.digest,
            "report_sha256": report_stamp.digest,
            "recovery_required": expected["recovery_required"],
            "physical_vehicle_verified": False, "permits_automatic_retry": False,
            "replayed": expected}


def write_new(path, report):
    data = (json.dumps(report, ensure_ascii=True, allow_nan=False, indent=2) + "\n").encode("ascii")
    require(len(data) <= MAX_REPORT, "CM/1 report exceeds byte limit")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def acquire(args, *, prompt=input):
    require(args.journal.resolve() != args.output.resolve(), "Distinct evidence paths required")
    require(args.key_file.resolve() not in (args.journal.resolve(), args.output.resolve()), "Credential path must differ")
    scope = Scope(1 if args.operation == "clear" else 2,args.extended,args.tx,args.rx,args.budget_ms,args.vin)
    scope_bytes(scope)
    key,stamp = _regular(args.key_file,32)
    require(len(key)==32,"A 32-byte provisioned credential is required")
    require(not args.journal.exists() and not args.output.exists(),"New journal and report paths required")
    try:
        import serial
    except ImportError as exc:
        raise ValueError("Install serial support before connecting") from exc
    if args.operation=="clear":
        warning=("Effacement des données de diagnostic et des états de préparation. Ce n'est pas une réparation."
                 if args.fr else "Clearing diagnostic data and readiness states is not a repair.")
    else:
        warning=("Préparation EVAP uniquement, pas un test de fuite."
                 if args.fr else "EVAP preparation only, not a leak test.")
    text=("Véhicule immobilisé, moteur arrêté, local ventilé, carte qualifiée et autorisation locale indépendante. OK : "
          if args.fr else "Stationary vehicle, engine stopped, ventilated area, qualified board and independent local approval. Type OK: ")
    require(prompt(warning+" "+text).strip()=="OK","Cancelled before USB transmission")
    confirmation=f"{args.vin} {args.tx:X}/{args.rx:X} {args.operation}: "
    require(prompt(confirmation+("OUI/NON : " if args.fr else "YES/NO: ")).strip()==("OUI" if args.fr else "YES"),
            "Cancelled before USB transmission")
    again,current_stamp=_regular(args.key_file,32)
    require(again==key and current_stamp==stamp,"Credential changed after confirmation")
    started=datetime.now(timezone.utc)
    # Reserve both paths before opening the serial port; failure to create
    # evidence must never be followed by command transmission.
    report_fd=os.open(args.output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    report=None
    entered_usb=False
    with os.fdopen(report_fd,"wb") as report_stream:
        try:
            write_new(args.output.with_name(args.output.name+".pending.json"), {
                "schema":"camarodiag-j1979-maintenance-host-pending-v1",
                "scope":scope.report(),"started_at_utc":started.isoformat(),
                "raw_journal_name":args.journal.name,"permits_automatic_retry":False})
            raw_fd=os.open(args.journal,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(raw_fd,"wb") as raw:
                with serial.Serial(args.port,baudrate=115200,timeout=0.1,write_timeout=1) as device:
                    entered_usb=True
                    report=capture(device,scope,key,raw)
                raw.flush()
                os.fsync(raw.fileno())
            saved,raw_stamp=_regular(args.journal,MAX_JOURNAL)
            report["journal_sha256"]=raw_stamp.digest
            if report["journal_transfer_verified"]:
                _compare_history_value(inspect(saved,expected_vin=args.vin),report["inspection"],"persisted CM/1")
        except (OSError,ValueError,KeyboardInterrupt) as exc:
            if report is None:
                report={"schema":"camarodiag-functional-j1979-native-maintenance-v1","protocol":"SAE-J1979",
                        "source":"LIVE_USB_CU4","scope":scope.report(),"device_credential_authenticated":False,
                        "proof_transmission_attempted":None if entered_usb else False,"client_proof_sent":False,"journal_bytes_received":0,
                        "cancelled":isinstance(exc,KeyboardInterrupt),"declared_recovery_in_envelope":None,
                        "physical_vehicle_verified":False,"evidence_authenticated":False,
                        "operator_consent_independently_verified":False,"permits_automatic_retry":False,
                        "permits_programming":False,"host_elapsed_ms":0}
            report.update(journal_transfer_verified=False,terminal_present=False,inspection=None,
                          recovery_required=report["proof_transmission_attempted"] is not False,
                          command_outcome_unknown=report["proof_transmission_attempted"] is not False,
                          error=str(exc) or type(exc).__name__)
        report["version"]="CU/4"
        report["declared_connector"]=args.port
        report["started_at_utc"]=started.isoformat().replace("+00:00","Z")
        report["finished_at_utc"]=max(started,datetime.now(timezone.utc)).isoformat().replace("+00:00","Z")
        report["raw_journal_name"]=args.journal.name
        data=(json.dumps(report,ensure_ascii=True,allow_nan=False,indent=2)+"\n").encode("ascii")
        require(len(data)<=MAX_REPORT,"Report size limit")
        report_stream.write(data)
        report_stream.flush()
        os.fsync(report_stream.fileno())
    return report


def acquire_archive(args, *, prompt=input):
    from .j1979_maintenance_archive_usb import capture_archive
    paths=(args.key_file,args.journal,args.output)
    require(len({p.resolve() for p in paths})==3,"Distinct archive paths required")
    scope=Scope(1 if args.operation=="clear" else 2,args.extended,args.tx,args.rx,args.budget_ms,args.vin)
    scope_bytes(scope)
    key,stamp=_regular(args.key_file,32)
    require(len(key)==32,"A 32-byte provisioned credential is required")
    require(not args.journal.exists() and not args.output.exists(),"New archive paths required")
    try:
        import serial
    except ImportError as exc:
        raise ValueError("Install serial support before connecting") from exc
    question=(f"Exporter le journal existant de {args.vin}, sans commande de maintenance ? OUI/NON : " if args.fr
              else f"Export existing journal for {args.vin} without a maintenance command? YES/NO: ")
    require(prompt(question).strip()==("OUI" if args.fr else "YES"),"Archive export cancelled")
    again,after=_regular(args.key_file,32)
    require(again==key and after==stamp,"Credential changed after confirmation")
    report=None
    report_fd=os.open(args.output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(report_fd,"wb") as report_file:
        try:
            raw_fd=os.open(args.journal,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(raw_fd,"wb") as raw:
                with serial.Serial(args.port,baudrate=115200,timeout=0.1,write_timeout=1) as device:
                    report=capture_archive(device,scope,key,raw)
                raw.flush()
                os.fsync(raw.fileno())
            saved,raw_stamp=_regular(args.journal,MAX_JOURNAL)
            report["journal_sha256"]=raw_stamp.digest
            if report["archive_transfer_verified"]:
                _compare_history_value(inspect(saved,expected_vin=scope.vin),report["inspection"],"persisted archive")
        except (OSError,ValueError,KeyboardInterrupt) as exc:
            if report is None:
                report={"schema":"camarodiag-j1979-native-maintenance-archive-v1","protocol":"SAE-J1979",
                        "source":"READ_ONLY_USB_CU5","scope":scope.report(),"device_credential_authenticated":False,
                        "journal_bytes_received":None,"declared_archive":None,"host_elapsed_ms":None}
            report.update(archive_transfer_verified=False,archive_close_receipt_verified=False,inspection=None,recovery_required=True,
                          maintenance_command_sent=False,physical_vehicle_verified=False,
                          physical_stop_independently_verified=False,evidence_authenticated=False,
                          permits_automatic_retry=False,permits_programming=False,error=str(exc) or type(exc).__name__)
        report["raw_journal_name"]=args.journal.name
        report["declared_connector"]=args.port
        payload=(json.dumps(report,ensure_ascii=True,allow_nan=False,indent=2)+"\n").encode("ascii")
        require(len(payload)<=MAX_REPORT,"Archive report size limit")
        report_file.write(payload)
        report_file.flush()
        os.fsync(report_file.fileno())
    return report


def can_id(text):
    try:
        value=int(text,16)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Hexadecimal CAN identifier required") from exc
    if not 0<=value<=0x1fffffff:
        raise argparse.ArgumentTypeError("CAN identifier out of range")
    return value


def run(argv=None, *, prompt=input):
    parser = argparse.ArgumentParser(allow_abbrev=False, description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("inspect", "verify", "inspect-prefix", "verify-prefix"):
        cmd = sub.add_parser(name, allow_abbrev=False)
        cmd.add_argument("--journal", required=True, type=Path)
        cmd.add_argument("--vin", required=True)
        cmd.add_argument("--output", required=True, type=Path)
        if name in ("verify", "verify-prefix"):
            cmd.add_argument("--report", required=True, type=Path)
    for action in ("capture","archive"):
        live=sub.add_parser(action,allow_abbrev=False)
        live.add_argument("--operation",choices=("clear","evap"),required=True)
        live.add_argument("--port",required=True)
        live.add_argument("--key-file",required=True,type=Path)
        live.add_argument("--vin",required=True)
        live.add_argument("--tx",required=True,type=can_id)
        live.add_argument("--rx",required=True,type=can_id)
        live.add_argument("--extended",action="store_true")
        live.add_argument("--budget-ms",type=int,default=60000)
        live.add_argument("--journal",required=True,type=Path)
        live.add_argument("--output",required=True,type=Path)
        live.add_argument("--fr",action="store_true")
    args = parser.parse_args(argv)
    if args.command=="capture":
        return acquire(args,prompt=prompt)
    if args.command=="archive":
        return acquire_archive(args,prompt=prompt)
    paths = [args.journal, args.output]
    if args.command in ("verify", "verify-prefix"):
        paths.append(args.report)
    require(len({path.resolve() for path in paths}) == len(paths), "Input and output paths must differ")
    prefix=args.command.endswith("-prefix")
    result = (inspect_file(args.journal, expected_vin=args.vin,prefix=prefix)
              if args.command in ("inspect","inspect-prefix")
              else verify_files(args.journal, args.report, expected_vin=args.vin,prefix=prefix))
    write_new(args.output, result)
    return result


def main():
    try:
        # Offline commands can use redirected stdin; live capture cannot.
        if len(sys.argv)>1 and sys.argv[1] in ("capture","archive") and not sys.stdin.isatty():
            raise ValueError("Interactive local confirmation required")
        result = run()
    except (OSError, ValueError, EOFError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2) from exc
    if result["schema"]=="camarodiag-functional-j1979-native-maintenance-v1":
        print(json.dumps({key:result[key] for key in ("journal_transfer_verified","recovery_required","command_outcome_unknown","error")}))
        raise SystemExit(2 if not result["journal_transfer_verified"] else 7 if result["recovery_required"] else 0)
    if result["schema"]=="camarodiag-j1979-native-maintenance-archive-v1":
        print(json.dumps({key:result[key] for key in ("archive_transfer_verified","recovery_required","error")}))
        raise SystemExit(2 if not result["archive_transfer_verified"] else 7 if result["recovery_required"] else 0)
    source = result.get("replayed", result)
    print(json.dumps({"record_consistency_verified": source["record_consistency_verified"],
                      "prefix_integrity_verified": source.get("prefix_integrity_verified",False),
                      "terminal_present": source["terminal_present"],
                      "recovery_required": source["recovery_required"],
                      "permits_automatic_retry": False}))
    # A consistent report is not a successful physical operation.
    raise SystemExit(7 if source["recovery_required"] else 0)


if __name__ == "__main__":
    main()
