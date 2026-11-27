"""Offline catalogue inspection and replay projection; never opens a transport."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

from .j1979_catalogue import read_catalogue, project, require, digest
from .j1979_live_inspection import inspect, MAX_REPORT_BYTES
from .recovery_bundle import _regular, _json
from .recovery_history import _compare_history_value

MAX_PROJECTION = 128 * 1024 * 1024

def replay_file(path, vin):
    path = Path(path)
    raw, stamp = _regular(path, MAX_REPORT_BYTES)
    result = inspect(_json(raw), expected_vin=vin)
    again, final = _regular(path, MAX_REPORT_BYTES)
    require(raw == again and stamp == final, "Live report changed during replay")
    return result, stamp.digest

def build_projection(args):
    catalogue, catalogue_sha = read_catalogue(args.catalogue, args.reference, args.reference_sha256)
    replay, source_sha = replay_file(args.report, args.vin)
    result = project(replay, catalogue)
    result.update({"catalogue_sha256": catalogue_sha,
                   "source_report_sha256": source_sha,
                   "expected_vin_independently_supplied": True})
    # Revalidate the independently supplied material before writing any output.
    _, current_catalogue_sha = read_catalogue(args.catalogue, args.reference, args.reference_sha256)
    _, current_source_sha = replay_file(args.report, args.vin)
    require(catalogue_sha == current_catalogue_sha and source_sha == current_source_sha,
            "Inputs changed during projection")
    return result

def write_new(path, result):
    # Serialize before exclusive creation so an invalid result does not leave
    # a newly created file with incomplete JSON.
    data = (json.dumps(result, ensure_ascii=False, allow_nan=False,
                       indent=2, sort_keys=True) + "\n").encode("utf-8")
    require(len(data) <= MAX_PROJECTION, "Projection exceeds output limit")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        # A failed write is not a valid export. Do not remove a path that
        # might have been replaced by another process.
        raise

def common(parser):
    parser.add_argument("--catalogue", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True,
                        help="Local documented reference, supplied by operator")
    parser.add_argument("--reference-sha256", required=True,
                        help="Independently pinned lowercase SHA-256 of reference bytes")

def projection_arguments(parser):
    common(parser)
    parser.add_argument("--report", type=Path, required=True,
                        help="Original raw-only live recording, not an inspection export")
    parser.add_argument("--vin", required=True,
                        help="Independently supplied expected VIN")

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    inventory = commands.add_parser("inspect-catalogue")
    common(inventory)
    inventory.add_argument("--output", type=Path, required=True)
    projection = commands.add_parser("project-live")
    projection_arguments(projection)
    projection.add_argument("--output", type=Path, required=True)
    verify = commands.add_parser("verify-projection")
    projection_arguments(verify)
    verify.add_argument("--projection", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        digest(args.reference_sha256)
        if args.command == "inspect-catalogue":
            catalogue, sha = read_catalogue(args.catalogue, args.reference, args.reference_sha256)
            report = {"schema": "camarodiag-j1979-catalogue-inspection-v1",
                      "edition": catalogue.edition, "reference_name": catalogue.reference_name,
                      "reference_sha256": catalogue.reference_sha256,
                      "catalogue_sha256": sha, "definitions": catalogue.inventory(),
                      "reference_authenticity_verified": False,
                      "normative_compliance_verified": False,
                      "permits_programming": False}
            write_new(args.output, report)
        elif args.command == "project-live":
            write_new(args.output, build_projection(args))
        else:
            raw, stamp = _regular(args.projection, MAX_PROJECTION)
            actual = _json(raw)
            expected = build_projection(args)
            _compare_history_value(actual, expected, "catalogue projection")
            again, final = _regular(args.projection, MAX_PROJECTION)
            require(raw == again and stamp == final, "Projection changed during verification")
            print(json.dumps({"verified": True,
                              "projection_sha256": stamp.digest,
                              "source_report_sha256": expected["source_report_sha256"],
                              "catalogue_sha256": expected["catalogue_sha256"],
                              "normative_compliance_verified": False,
                              "physical_vehicle_verified": False}))
        return 0
    except (ValueError, OSError, TypeError, KeyError, OverflowError, RecursionError) as exc:
        print("Catalogue rejected: " + str(exc), file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
