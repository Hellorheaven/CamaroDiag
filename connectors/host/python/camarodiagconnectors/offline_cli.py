"""Offline-only backup, verify and targeted patch preview. No device IO."""

from __future__ import annotations

import argparse
from dataclasses import asdict
import json
from pathlib import Path
from typing import Sequence

from .backup import backup_manifest, write_bundle, verify_bundle
from .doip_archive import verify_archive
from .memory import Geometry, Patch, Region, Technology, plan_patches
from .recovery_bundle import AndroidIdentity,inspect_bundle,write_report
from .j1979_live_inspection import inspect_file, compare_files
from .connector_checkpoint import inspect_file as inspect_checkpoint, inspect_history, verify_report as verify_checkpoint_report


def _number(text: str) -> int:
    try:
        return int(text, 0)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"Invalid integer: {text}") from exc


def _region(text: str) -> Region:
    try:
        start, end = text.split(":", 1)
        return Region(_number(start), _number(end))
    except (ValueError, argparse.ArgumentTypeError) as exc:
        raise argparse.ArgumentTypeError("Region must be START:END") from exc


def _patch(text: str) -> Patch:
    try:
        offset, hex_bytes = text.split(":", 1)
        return Patch(_number(offset), bytes.fromhex(hex_bytes))
    except (ValueError, argparse.ArgumentTypeError) as exc:
        raise argparse.ArgumentTypeError("Patch must be OFFSET:HEXBYTES") from exc


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="CamaroDiagConnectors OFFLINE tooling")
    commands = parser.add_subparsers(dest="action", required=True)

    backup = commands.add_parser("backup", help="Save a local original file + integrity manifest")
    backup.add_argument("--target", required=True)
    backup.add_argument("--capacity", type=_number, required=True)
    backup.add_argument("--offset", type=_number, default=0)
    backup.add_argument("--input", required=True, type=Path)
    backup.add_argument("--second-read", type=Path)
    backup.add_argument("--output-prefix", type=Path, required=True)

    verify = commands.add_parser("verify", help="Verify a local backup bundle")
    verify.add_argument("--prefix", required=True, type=Path)

    doip = commands.add_parser("verify-doip", help="Verify an Android or Windows DoIP backup against its profile")
    doip.add_argument("--archive", type=Path, required=True)
    doip.add_argument("--profile", type=Path, required=True)

    recovery = commands.add_parser("verify-recovery",help="Verify local Android or Windows recovery evidence without ECU I/O")
    recovery.add_argument("--platform",choices=("android","windows"),required=True)
    recovery.add_argument("--journal",type=Path,required=True)
    recovery.add_argument("--family")
    recovery.add_argument("--controller")
    recovery.add_argument("--firmware")
    recovery.add_argument("--vin")
    recovery.add_argument("--osid",type=_number)
    recovery.add_argument("--original-bin",type=Path)
    recovery.add_argument("--expected-snapshot")
    recovery.add_argument("--output",type=Path,required=True)

    live = commands.add_parser("verify-live", help="Replay and verify a J1979 numeric live recording")
    live.add_argument("--report", type=Path, required=True)
    live.add_argument("--vin")
    live.add_argument("--output", type=Path, required=True)
    compare = commands.add_parser("compare-live", help="Compare independently verified recordings without time alignment")
    compare.add_argument("--report", type=Path, action="append", required=True)
    compare.add_argument("--vin")
    compare.add_argument("--output", type=Path, required=True)

    checkpoint = commands.add_parser("inspect-checkpoint", help="Inspect one imported CDJ/1 record")
    checkpoint.add_argument("--input", type=Path, required=True)
    checkpoint.add_argument("--transaction")
    checkpoint.add_argument("--manifest")
    checkpoint.add_argument("--output", type=Path, required=True)
    history = commands.add_parser("inspect-checkpoint-history", help="Inspect explicitly ordered CDJ/1 records")
    history.add_argument("--input", type=Path, action="append", required=True)
    history.add_argument("--transaction")
    history.add_argument("--manifest")
    history.add_argument("--output", type=Path, required=True)
    verify_checkpoint = commands.add_parser("verify-checkpoint-report", help="Recheck exported checkpoint JSON against its binary")
    verify_checkpoint.add_argument("--input", type=Path, required=True)
    verify_checkpoint.add_argument("--report", type=Path, required=True)
    verify_checkpoint.add_argument("--output", type=Path, required=True)

    plan = commands.add_parser("plan", help="Dry-run changes; no device programming")
    plan.add_argument("--target", required=True)
    plan.add_argument("--technology", required=True, choices=[t.value for t in Technology])
    plan.add_argument("--page-size", required=True, type=_number)
    plan.add_argument("--erase-size", type=_number)
    plan.add_argument("--input", required=True, type=Path)
    plan.add_argument("--patch", required=True, action="append", type=_patch)
    plan.add_argument("--protected", action="append", type=_region, default=[])
    plan.add_argument("--proposed-output", type=Path)
    return parser


def run(argv: Sequence[str] | None = None) -> dict[str, object]:
    args = _parser().parse_args(argv)
    if args.action == "backup":
        image = args.input.read_bytes()
        second = args.second_read.read_bytes() if args.second_read else None
        manifest = backup_manifest(args.target, args.capacity, args.offset, image, second)
        write_bundle(args.output_prefix, image, manifest)
        return {"action": "backup", "manifest": asdict(manifest), "physical_read_verified": False}

    if args.action == "verify":
        manifest = verify_bundle(args.prefix)
        return {"action": "verify", "manifest": asdict(manifest), "physical_read_verified": False}

    if args.action == "verify-doip":
        return verify_archive(args.archive, args.profile)

    if args.action == "verify-recovery":
        identity=None
        if args.platform=="android":
            if not all((args.family,args.controller,args.firmware,args.vin)) or args.osid is not None:
                raise ValueError("Android requires --family --controller --firmware --vin")
            identity=AndroidIdentity(args.family,args.controller,args.firmware,args.vin).validate()
        elif any((args.family,args.controller,args.firmware)):
            raise ValueError("Windows identity is read from E39 metadata; use --vin and --osid to compare")
        report=inspect_bundle(args.journal,args.platform,android_identity=identity,
                              expected_vin=args.vin,expected_osid=args.osid,
                              original_bin=args.original_bin,expected_snapshot=args.expected_snapshot)
        return write_report(args.output,report)

    if args.action == "verify-live":
        return write_report(args.output, inspect_file(args.report, expected_vin=args.vin))
    if args.action == "compare-live":
        return write_report(args.output, compare_files(args.report, expected_vin=args.vin))

    if args.action == "inspect-checkpoint":
        return write_report(args.output, inspect_checkpoint(args.input,args.transaction,args.manifest))
    if args.action == "inspect-checkpoint-history":
        return write_report(args.output, inspect_history(args.input,args.transaction,args.manifest))
    if args.action == "verify-checkpoint-report":
        return write_report(args.output, verify_checkpoint_report(args.input,args.report))

    original = args.input.read_bytes()
    geometry = Geometry(args.target, Technology(args.technology), len(original), args.page_size,
                        args.erase_size, tuple(args.protected))
    plan = plan_patches(geometry, original, args.patch)
    if args.proposed_output is not None:
        args.proposed_output.parent.mkdir(parents=True, exist_ok=True)
        with args.proposed_output.open("xb") as out:
            out.write(plan.proposed_image)
    return {
        "action": "plan",
        "chip": plan.chip,
        "changed_bytes": plan.changed_bytes,
        "before_sha256": plan.before_sha256,
        "after_sha256": plan.after_sha256,
        "write_authorized": False,
        "physical_read_verified": False,
        "operations": [
            {"start": o.region.start, "end_exclusive": o.region.end, "method": o.method,
             "changed_bytes": o.changed_bytes, "original_sha256": o.original_sha256,
             "proposed_sha256": o.proposed_sha256}
            for o in plan.operations
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    try:
        result = run()
    except (OSError, ValueError) as exc:
        parser.exit(status=2, message=f"OFFLINE ERROR: {exc}\n")
    print(json.dumps(result, indent=2, sort_keys=True))
    if result.get("schema")=="camarodiag-offline-recovery-bundle-v1" and not result["verified"]:
        raise SystemExit(2)
    if result.get("schema")=="camarodiag-offline-connector-checkpoint-v1" and result["inspection"]["status"]!="SAFE_BUT_BLOCKED":
        raise SystemExit(8)
    if result.get("schema")=="camarodiag-offline-connector-checkpoint-history-v1" and (
            not result["consistent"] or result["disposition"]!="SAFE_BUT_BLOCKED"):
        raise SystemExit(8)


if __name__ == "__main__":
    main()
