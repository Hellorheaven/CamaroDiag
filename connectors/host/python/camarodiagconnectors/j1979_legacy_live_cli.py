"""Dedicated ELM legacy live recorder with raw source-attributed evidence."""
from __future__ import annotations

import argparse
import csv
from datetime import datetime,timezone
import json
from pathlib import Path
import re
import sys

from . import __version__
from .j1979_legacy import LegacyElmSerial,PROTOCOLS
from .j1979_legacy_discovery import valid_source
from .j1979_live import Plan,csv_rows,legacy_numeric_pid
from .j1979_live_cli import _open,_flush
from .j1979_legacy_live import record_legacy


def parse(argv=None):
    parser=argparse.ArgumentParser(allow_abbrev=False,
        description="Record selected numeric PIDs from a documented legacy response source")
    parser.add_argument("--port",required=True)
    parser.add_argument("--baud",type=int,default=38400,choices=(9600,19200,38400,57600,115200,230400))
    parser.add_argument("--protocol",type=int,required=True,choices=PROTOCOLS)
    parser.add_argument("--source",required=True,help="Two-digit response source; not physical addressing")
    parser.add_argument("--vin",required=True)
    parser.add_argument("--pid",action="append",required=True)
    parser.add_argument("--cycles",type=int,default=30)
    parser.add_argument("--interval",type=float,default=1.0)
    parser.add_argument("--budget",type=float,default=180.0)
    parser.add_argument("--confirm-dedicated-adapter",action="store_true",required=True)
    parser.add_argument("--confirm-functional-scope",action="store_true",required=True,
                        help="Confirm functional requests may reach multiple ECUs")
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--csv",type=Path)
    parser.add_argument("--quiet",action="store_true")
    args=parser.parse_args(argv)
    try:
        if not re.fullmatch(r"[0-9A-Fa-f]{2}",args.source) or not valid_source(int(args.source,16)):
            raise ValueError("Expected documented response source")
        args.source=int(args.source,16)
        if any(not re.fullmatch(r"[0-9A-Fa-f]{2}",x) for x in args.pid):
            raise ValueError("Expected two-digit numeric PID")
        plan=Plan(tuple(int(x,16) for x in args.pid),args.vin,args.cycles,args.interval,args.budget).validate()
        if not all(legacy_numeric_pid(pid) for pid in plan.pids):
            raise ValueError("Selected PID exceeds supported legacy application width")
        if args.csv is not None and args.csv.resolve()==args.output.resolve():
            raise ValueError("JSON and CSV paths must be distinct")
    except (ValueError,OSError) as exc:
        parser.error(str(exc))
    return args,plan


def run(argv=None):
    args,plan=parse(argv)
    with _open(args.output) as output:
        csv_output=_open(args.csv) if args.csv is not None else None
        try:
            started=datetime.now(timezone.utc).isoformat()
            result=None
            def show(row):
                if not args.quiet:
                    value=row["measurement"]
                    signals=row.get("multi_signals")
                    text=("; ".join(f'{v["name"]}={v["number"]:.3f} {v["unit"]}' for v in signals)
                          if signals else f'{value["number"]:.3f} {value["unit"]}' if value else row["status"])
                    print(f'{row["elapsed_ms"]} ms {args.source:02X}/{row["pid"]:02X} {text}',flush=True)
            try:
                with LegacyElmSerial(args.port,args.baud,args.protocol,headered=True) as link:
                    result=record_legacy(link.exchange,link.observed_protocol,args.protocol,args.source,
                                         plan,on_observation=show)
            except (KeyboardInterrupt,OSError,ValueError,RuntimeError,ImportError) as exc:
                if result is None:
                    result={"observations":[],"admission":[],"summary":[],"observed_vin":None,
                            "observed_protocol":None,"transport_exchanges":[],"completed_cycles":0,
                            "selected_pids":list(plan.pids)}
                result.update(completed=False,stop_reason=result.get("stop_reason") or
                    ("CANCELLED" if isinstance(exc,KeyboardInterrupt) else type(exc).__name__))
            report={"schema":"camarodiag-j1979-legacy-live-v1","protocol":"SAE-J1979",
                    "transport":"ELM_SERIAL_LEGACY_HEADERED","version":__version__,
                    "scope":"FUNCTIONAL_SELECTED_RESPONSE_LIVE","physically_addressed":False,
                    "port":args.port,"baud":args.baud,"expected_protocol":args.protocol,
                    "selected_response_source":f"{args.source:02X}","declared_vin":plan.expected_vin,
                    "started_at_utc":started,"finished_at_utc":datetime.now(timezone.utc).isoformat(),
                    "physical_vehicle_verified":False,"whole_vehicle_coverage_verified":False,**result}
            json.dump(report,output,indent=2,ensure_ascii=True,allow_nan=False)
            output.write("\n")
            _flush(output)
            if csv_output is not None:
                csv.writer(csv_output).writerows(csv_rows(report))
                _flush(csv_output)
            return report
        finally:
            if csv_output is not None:
                csv_output.close()


def main():
    try:
        report=run()
    except (OSError,ValueError) as exc:
        print(f"J1979 legacy live: {exc}",file=sys.stderr)
        raise SystemExit(2) from exc
    print(json.dumps({"completed":report["completed"],"cycles":report["completed_cycles"],
                      "stop_reason":report.get("stop_reason"),"observations":len(report["observations"])}))
    if not report["completed"]:
        raise SystemExit(2)


if __name__=="__main__":
    main()
