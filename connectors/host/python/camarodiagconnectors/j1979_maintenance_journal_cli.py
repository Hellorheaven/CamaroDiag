"""Offline inspection and cross-check of a native CM/1 maintenance journal."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

from .j1979_maintenance_journal import inspect, require, MAX_JOURNAL
from .recovery_bundle import _regular, _json
from .recovery_history import _compare_history_value

MAX_REPORT = 1024 * 1024


def inspect_file(path, *, expected_vin):
    raw, stamp = _regular(Path(path), MAX_JOURNAL)
    result = inspect(raw, expected_vin=expected_vin)
    again, after = _regular(Path(path), MAX_JOURNAL)
    require(raw == again and stamp == after, "CM/1 changed during inspection")
    result["journal_sha256"] = stamp.digest
    return result


def verify_files(journal, report, *, expected_vin):
    journal, report = Path(journal), Path(report)
    require(journal.resolve() != report.resolve(), "Input paths must differ")
    raw, stamp = _regular(journal, MAX_JOURNAL)
    original, report_stamp = _regular(report, MAX_REPORT)
    expected = inspect(raw, expected_vin=expected_vin)
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


def run(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False, description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("inspect", "verify"):
        cmd = sub.add_parser(name, allow_abbrev=False)
        cmd.add_argument("--journal", required=True, type=Path)
        cmd.add_argument("--vin", required=True)
        cmd.add_argument("--output", required=True, type=Path)
        if name == "verify":
            cmd.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    paths = [args.journal, args.output]
    if args.command == "verify":
        paths.append(args.report)
    require(len({path.resolve() for path in paths}) == len(paths), "Input and output paths must differ")
    result = (inspect_file(args.journal, expected_vin=args.vin) if args.command == "inspect"
              else verify_files(args.journal, args.report, expected_vin=args.vin))
    write_new(args.output, result)
    return result


def main():
    try:
        result = run()
    except (OSError, ValueError, EOFError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2) from exc
    source = result.get("replayed", result)
    print(json.dumps({"record_consistency_verified": True,
                      "terminal_present": source["terminal_present"],
                      "recovery_required": source["recovery_required"],
                      "permits_automatic_retry": False}))
    # A consistent report is not a successful physical operation.
    raise SystemExit(7 if source["recovery_required"] else 0)


if __name__ == "__main__":
    main()
