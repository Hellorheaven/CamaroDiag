"""Command-line physical SAE J1979 live recorder for SocketCAN."""
from __future__ import annotations

import argparse
import csv
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import re
import sys

from . import __version__
from .j1979 import LinuxIsoTp,physical_pair
from .j1979_live import Plan,record,csv_rows


def parse(argv=None):
    parser=argparse.ArgumentParser(allow_abbrev=False,description="Read-only selected ECU numeric PID recorder")
    parser.add_argument("--interface",required=True)
    parser.add_argument("--tx",required=True,help="Physical request CAN ID in hex")
    parser.add_argument("--rx",required=True,help="Physical response CAN ID in hex")
    parser.add_argument("--vin",required=True,help="Expected 17-character VIN on this ECU")
    parser.add_argument("--pid",action="append",required=True,help="Numeric Mode 01 PID in hex; repeat to select")
    parser.add_argument("--cycles",type=int,default=30)
    parser.add_argument("--interval",type=float,default=1.0,help="Minimum cycle start-to-start seconds")
    parser.add_argument("--budget",type=float,default=180.0,help="Total recording seconds")
    parser.add_argument("--confirm-physical-channel",action="store_true",required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--csv",type=Path)
    parser.add_argument("--quiet",action="store_true",help="Suppress live sample lines; JSON is unchanged")
    args=parser.parse_args(argv)
    try:
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,15}",args.interface):
            raise ValueError("Invalid SocketCAN interface")
        if any(not re.fullmatch(r"[0-9A-Fa-f]{1,8}",x) for x in (args.tx,args.rx)):
            raise ValueError("Expected hexadecimal physical CAN IDs")
        args.tx,args.rx=int(args.tx,16),int(args.rx,16)
        if not physical_pair(args.tx,args.rx):
            raise ValueError("Invalid physical address pair")
        if any(not re.fullmatch(r"[0-9A-Fa-f]{2}",x) for x in args.pid):
            raise ValueError("Expected two-digit numeric PID")
        plan=Plan(tuple(int(x,16) for x in args.pid),args.vin,args.cycles,args.interval,args.budget).validate()
        if args.csv is not None and args.csv.resolve()==args.output.resolve():
            raise ValueError("JSON and CSV need distinct new paths")
    except (ValueError,OSError) as exc:
        parser.error(str(exc))
    return args,plan


def _open(path):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    return os.fdopen(fd,"w",encoding="utf-8",newline="")


def _flush(stream):
    stream.flush()
    os.fsync(stream.fileno())


def run(argv=None):
    args,plan=parse(argv)
    # Both paths are exclusively reserved before any hardware I/O.
    with _open(args.output) as output:
        csv_output=_open(args.csv) if args.csv is not None else None
        try:
            started=datetime.now(timezone.utc).isoformat()
            result=None
            def show(row):
                if args.quiet:
                    return
                value=row["measurement"]
                display=f'{value["number"]:.3f} {value["unit"]}' if value else row["status"]
                print(f'{row["elapsed_ms"]} ms {row["pid"]:02X} {display}',flush=True)
            try:
                with LinuxIsoTp(args.interface,args.tx,args.rx) as transport:
                    result=record(transport.exchange,plan,on_observation=show)
            except (KeyboardInterrupt,OSError,ValueError,RuntimeError) as exc:
                if result is None:
                    result={"observations":[],"admission":[],"summary":[],"observed_vin":None,
                            "selected_pids":list(plan.pids),"completed_cycles":0}
                result.update(completed=False,stop_reason=result.get("stop_reason") or
                              ("CANCELLED" if isinstance(exc,KeyboardInterrupt) else type(exc).__name__))
            report={"schema":"camarodiag-j1979-live-v1","protocol":"SAE-J1979",
                    "transport":"LINUX_SOCKETCAN_ISOTP","version":__version__,
                    "interface":args.interface,"tx_can_id":f"{args.tx:X}","rx_can_id":f"{args.rx:X}",
                    "declared_vin":plan.expected_vin,"started_at_utc":started,
                    "finished_at_utc":datetime.now(timezone.utc).isoformat(),
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
        print(f"J1979 live: {exc}",file=sys.stderr)
        raise SystemExit(2) from exc
    print(json.dumps({"completed":report["completed"],"cycles":report["completed_cycles"],
                      "stop_reason":report.get("stop_reason"),"observations":len(report["observations"])}))
    if not report["completed"]:
        raise SystemExit(2)


if __name__=="__main__":
    main()
