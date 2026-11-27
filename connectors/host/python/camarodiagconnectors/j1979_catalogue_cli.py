"""Offline catalogue inspection and replay projection; never opens a transport."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

from .j1979_catalogue import read_catalogue, project, require, digest
from .j1979_live_inspection import inspect, _unwrap, MAX_REPORT_BYTES
from .recovery_bundle import _regular, _json
from .recovery_history import _compare_history_value

MAX_PROJECTION = 128 * 1024 * 1024
LOCAL_SCHEMA = "camarodiag-j1979-catalogue-local-projection-v1"
NATIVE_SCHEMA = "camarodiag-j1979-native-catalogue-projection-v1"

def replay_file(path, vin):
    path = Path(path)
    raw, stamp = _regular(path, MAX_REPORT_BYTES)
    document = _json(raw)
    result = inspect(document, expected_vin=vin)
    wrapper, original = _unwrap(document)
    if wrapper is not original and "completed" in wrapper:
        require(type(wrapper["completed"]) is bool, "Invalid outer completion status")
        # A Windows UI cancellation can invalidate completion after a fully
        # recorded acquisition. Do not promote it back to completed.
        if not wrapper["completed"]:
            result["completed"] = False
    again, final = _regular(path, MAX_REPORT_BYTES)
    require(raw == again and stamp == final, "Live report changed during replay")
    return result, stamp.digest

def build_projection(args, *, local=False):
    catalogue, catalogue_sha = read_catalogue(args.catalogue, args.reference, args.reference_sha256)
    replay, source_sha = replay_file(args.report, args.vin)
    result = project(replay, catalogue)
    result.update({"catalogue_sha256": catalogue_sha,
                   "source_report_sha256": source_sha,
                   "expected_vin_independently_supplied": True})
    if local:
        # Native applications project application-level bytes and explicitly
        # decline to claim an independent transport replay. Compare every
        # projected field with a fresh independent replay of the original.
        result.update(schema=LOCAL_SCHEMA, source_replay_verified=False,
                      source_application_replay_verified=True,
                      final_identity_verified=replay["final_identity_verified"])
        result["channel"] = {key: value for key, value in result["channel"].items()
                             if key != "link_checksum_verified"}
        require(bool(result["rows"]), "Local projection requires admitted samples")
    # Revalidate the independently supplied material before writing any output.
    _, current_catalogue_sha = read_catalogue(args.catalogue, args.reference, args.reference_sha256)
    _, current_source_sha = replay_file(args.report, args.vin)
    require(catalogue_sha == current_catalogue_sha and source_sha == current_source_sha,
            "Inputs changed during projection")
    return result

def build_native_projection(args):
    from .j1979_live_native import inspect_file, valid_vin
    require(valid_vin(args.vin), "Independently supplied VIN required")
    catalogue, catalogue_sha = read_catalogue(args.catalogue, args.reference, args.reference_sha256)
    source = inspect_file(args.capture)
    require(source["capture_integrity_verified"] and source["profile"]["expected_vin"] == args.vin,
            "Original independently replayed CL/1 capture with matching VIN required")
    result = project(source["result"], catalogue)
    result.update(schema=NATIVE_SCHEMA, catalogue_sha256=catalogue_sha,
                  source_capture_sha256=source["capture_sha256"], expected_vin_independently_supplied=True,
                  final_identity_verified=source["summary"]["final_identity_verified"],
                  native_capture_replayed=True, transport_frames_replayed=False)
    _, current_catalogue_sha = read_catalogue(args.catalogue, args.reference, args.reference_sha256)
    again = inspect_file(args.capture)
    require(catalogue_sha == current_catalogue_sha and source == again,
            "Native capture or catalogue changed during projection")
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
    local = commands.add_parser("verify-local-projection",
                                help="Independently verify an Android or Windows projection")
    projection_arguments(local)
    local.add_argument("--projection", type=Path, required=True)
    native = commands.add_parser("project-native-live", help="Project original CL/1 capture")
    native_verify = commands.add_parser("verify-native-projection", help="Verify native CL/1 projection")
    for command in (native, native_verify):
        common(command)
        command.add_argument("--capture", type=Path, required=True)
        command.add_argument("--vin", required=True)
    native.add_argument("--output", type=Path, required=True)
    native_verify.add_argument("--projection", type=Path, required=True)
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
        elif args.command == "project-native-live":
            write_new(args.output, build_native_projection(args))
        else:
            raw, stamp = _regular(args.projection, MAX_PROJECTION)
            actual = _json(raw)
            expected = (build_native_projection(args) if args.command == "verify-native-projection" else
                        build_projection(args, local=args.command == "verify-local-projection"))
            _compare_history_value(actual, expected, "catalogue projection")
            again, final = _regular(args.projection, MAX_PROJECTION)
            require(raw == again and stamp == final, "Projection changed during verification")
            print(json.dumps({"verified": True,
                              "independent_source_replay_performed": True,
                              "projection_schema": expected["schema"],
                              "projection_sha256": stamp.digest,
                              **{key: expected[key] for key in ("source_report_sha256", "source_capture_sha256") if key in expected},
                              "catalogue_sha256": expected["catalogue_sha256"],
                              "normative_compliance_verified": False,
                              "physical_vehicle_verified": False}))
        return 0
    except (ValueError, OSError, TypeError, KeyError, OverflowError, RecursionError) as exc:
        print("Catalogue rejected: " + str(exc), file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
