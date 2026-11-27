"""Explicit native USB CU/3 capture and offline CL/1 inspection.

Requires a locally qualified connector with independently approved scope and
a provisioned credential. No discovery fallback or automatic pairing.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
from importlib.metadata import version, PackageNotFoundError
import io
import json
import os
from pathlib import Path
import sys
import time
import struct
import zlib

from .j1979_live_native import (
    NativeLiveProfile, hello, proof, decode, inspect_file, require, valid_vin,
    HEADER, MAX_BODY, MAX_CAPTURE, MAX_STEPS,
)
from .recovery_bundle import _regular, _json
from .recovery_history import _compare_history_value

MAX_REPORT = 128 * 1024 * 1024


def capture_live(port, profile, key, capture, *, clock=time.monotonic, nonce=None):
    profile.validate()
    require(isinstance(key, bytes) and len(key) == 32, "Provisioned credential required")
    request = hello(profile, nonce)
    authenticated = sent = cancelled = False
    error = None
    raw = bytearray()
    start = clock()

    def send(data, deadline):
        if clock() >= deadline or port.write(data) != len(data):
            raise OSError("Incomplete CU/3 transmission; no retry")
        if clock() >= deadline:
            raise TimeoutError("CU/3 write deadline exceeded")

    def persist(piece):
        if len(raw) + len(piece) > MAX_CAPTURE:
            raise ValueError("CL/1 capture byte limit")
        raw.extend(piece)
        if capture.write(piece) != len(piece):
            raise OSError("Incomplete CL/1 file write")

    def receive(size, deadline, record=False):
        result = bytearray()
        empty = 0
        while len(result) < size:
            if clock() >= deadline:
                raise TimeoutError("CU/3 receive deadline exceeded")
            piece = port.read(size - len(result))
            if not isinstance(piece, bytes) or len(piece) > size - len(result):
                raise OSError("Invalid serial read")
            if piece:
                result.extend(piece)
                if record:
                    persist(piece)
                empty = 0
            else:
                empty += 1
                if empty > 65536:
                    raise TimeoutError("CU/3 receive did not advance")
            if clock() >= deadline:
                raise TimeoutError("CU/3 receive deadline exceeded")
        return bytes(result)

    try:
        auth_deadline = start + 5
        send(request, auth_deadline)
        challenge = receive(68, auth_deadline)
        client_proof = proof(request, challenge, key)
        authenticated = True
        send(client_proof, auth_deadline)
        sent = True
        deadline = clock() + profile.budget_ms / 1000 + 15
        for sequence in range(MAX_STEPS + 2):
            header = receive(HEADER.size, deadline, True)
            magic, version, kind, seq, length = HEADER.unpack(header)
            require(magic == b"CL" and version == 1 and seq == sequence and
                    kind in (1, 2, 3) and 1 <= length <= MAX_BODY, "Invalid live CL/1 header")
            body = receive(length, deadline, True)
            trailer = receive(4, deadline, True)
            require(zlib.crc32(header + body) == struct.unpack("<I", trailer)[0], "Live CL/1 CRC mismatch")
            if sequence == 0:
                require(kind == 1 and NativeLiveProfile.from_capture_plan(body) == profile,
                        "Live CL/1 plan differs from approved CU/3 scope")
            else:
                require(kind != 1, "Duplicate live CL/1 plan")
            if kind == 3:
                if clock() >= deadline:
                    raise TimeoutError("CL/1 final deadline exceeded")
                trailing = port.read(1)
                if not isinstance(trailing, bytes) or len(trailing) > 1:
                    raise OSError("Invalid serial read after CL/1 terminal record")
                if trailing:
                    persist(trailing)
                    raise ValueError("Trailing live CL/1 bytes")
                if clock() >= deadline:
                    raise TimeoutError("CL/1 final deadline exceeded")
                break
        else:
            raise ValueError("Live CL/1 record count exceeded")
    except KeyboardInterrupt:
        cancelled = True
        error = "Cancelled by operator; no retry"
    except (OSError, ValueError, EOFError, struct.error) as exc:
        error = str(exc)
    report = decode(io.BytesIO(raw), expected=profile)
    if error is not None:
        report.update(capture_integrity_verified=False, error=error, summary=None, result=None)
    report.update(source="LIVE_USB_CU3", device_credential_authenticated=authenticated,
                  client_proof_sent=sent, cancelled=cancelled,
                  host_elapsed_ms=max(0, int((clock() - start) * 1000)))
    return report


def verify_files(capture, report, *, expected_vin):
    """Verify the saved capture independently; never verify a live HMAC claim."""
    require(valid_vin(expected_vin), "Independently supplied VIN required")
    capture, report = Path(capture), Path(report)
    require(capture.resolve() != report.resolve(), "Evidence files must differ")
    raw, capture_stamp = _regular(capture, MAX_CAPTURE)
    report_raw, report_stamp = _regular(report, MAX_REPORT)
    actual = _json(report_raw)
    replay = decode(io.BytesIO(raw))
    core = set(replay)
    extra = {"client_proof_sent", "cancelled", "host_elapsed_ms", "capture_sha256", "raw_capture_name",
             "version", "declared_connector", "declared_ecu", "connection_generation",
             "started_at_utc", "finished_at_utc", "observed_vin"}

    require(isinstance(actual, dict) and core <= actual.keys() <= core | extra,
            "Unexpected native live report fields")
    require(actual["source"] in ("LIVE_USB_CU3", "OFFLINE_CL1"), "Unexpected CL/1 source")
    require(replay["profile"] is not None and replay["profile"]["expected_vin"] == expected_vin,
            "CL/1 plan differs from independent VIN")
    require(actual.get("capture_sha256") == capture_stamp.digest, "CL/1 digest differs from report")
    for field in ("schema", "protocol", "profile", "hardware_provenance_verified", "evidence_authenticated",
                  "physical_vehicle_verified", "permits_programming", "observations_received"):
        _compare_history_value(actual[field], replay[field], "native_live." + field)
    for flag in ("capture_integrity_verified", "device_credential_authenticated", "client_proof_sent", "cancelled"):
        if flag in actual:
            require(type(actual[flag]) is bool, "Invalid flag: " + flag)
    live = actual["source"] == "LIVE_USB_CU3"
    authenticated = actual["device_credential_authenticated"]
    sent = actual.get("client_proof_sent", False)
    cancelled = actual.get("cancelled", False)
    if live:
        require("client_proof_sent" in actual and "cancelled" in actual and (not sent or authenticated),
                "Missing or contradictory live credential declaration")
    else:
        require(not authenticated and not sent and not cancelled, "Offline report claims live authentication")
    for field in ("version", "declared_connector", "declared_ecu"):
        if field in actual:
            value = actual[field]
            require(isinstance(value, str) and 0 < len(value) <= 1024 and
                    all(ord(ch) >= 32 and ord(ch) != 127 for ch in value), "Invalid declared metadata: " + field)
    if "connection_generation" in actual:
        require(type(actual["connection_generation"]) is int and 1 <= actual["connection_generation"] < 2**63,
                "Invalid connection generation")
    instants = {}
    for field in ("started_at_utc", "finished_at_utc"):
        if field in actual:
            value = actual[field]
            require(isinstance(value, str) and 0 < len(value) <= 64 and value.endswith("Z"),
                    "Expected UTC timestamp: " + field)
            try:
                instants[field] = datetime.fromisoformat(value[:-1] + "+00:00")
            except ValueError as exc:
                raise ValueError("Invalid native UTC timestamp: " + field) from exc
    if len(instants) == 2:
        require(instants["finished_at_utc"] >= instants["started_at_utc"], "Native capture timestamps reversed")
    if "observed_vin" in actual:
        summary = replay.get("summary") or {}
        expected_observed = expected_vin if summary.get("initial_identity_verified") else None
        require(actual["observed_vin"] is None or actual["observed_vin"] == expected_observed,
                "Observed VIN not established by the native capture")
        if actual["capture_integrity_verified"]:
            require(actual["observed_vin"] == expected_observed, "Verified report omitted observed identity")
    if "host_elapsed_ms" in actual:
        require(type(actual["host_elapsed_ms"]) is int and 0 <= actual["host_elapsed_ms"] < 2**63,
                "Invalid host duration")
    if "raw_capture_name" in actual:
        require(actual["raw_capture_name"] is None or
                isinstance(actual["raw_capture_name"], str) and 0 < len(actual["raw_capture_name"]) <= 1024,
                "Invalid capture name")
    valid = replay["capture_integrity_verified"]
    claimed = actual["capture_integrity_verified"]
    require(actual["error"] is None or isinstance(actual["error"], str) and 0 < len(actual["error"]) <= 8192,
            "Invalid reported error")
    require(not claimed or valid and actual["error"] is None and not cancelled, "Unsupported integrity claim")
    if live:
        require(not claimed or authenticated and sent, "Live report lacks declared credential exchange")
    else:
        require(claimed == valid and actual["error"] == replay["error"], "Offline result differs")
    for field in ("summary", "result"):
        if actual[field] is not None or claimed:
            _compare_history_value(actual[field], replay[field], "native_live." + field)
        else:
            require(not claimed, "Verified report lacks replay")
    raw_after, capture_after = _regular(capture, MAX_CAPTURE)
    report_after, report_stamp_after = _regular(report, MAX_REPORT)
    require(raw_after == raw and capture_after == capture_stamp and report_after == report_raw
            and report_stamp_after == report_stamp, "Native live evidence changed during replay")
    return {"schema": "camarodiag-j1979-native-live-verification-v1",
            "consistent": True, "verified": claimed and valid,
            "execution_completed": claimed and valid and bool(replay["summary"]["completed"]),
            "capture_sha256": capture_stamp.digest, "report_sha256": report_stamp.digest,
            "expected_vin_independently_supplied": True,
            "declared_live_credential_authenticated": live and authenticated,
            "credential_independently_verified": False, "physical_vehicle_verified": False,
            "hardware_provenance_verified": False, "permits_programming": False, "replayed": replay}


def export_csv(capture, output, *, expected_vin):
    """Replay original CL/1 before exporting raw samples, not engineering units."""
    require(valid_vin(expected_vin), "Independently supplied VIN required")
    capture, output = Path(capture), Path(output)
    require(capture.resolve() != output.resolve(), "Input and output must differ")
    raw, stamp = _regular(capture, MAX_CAPTURE)
    report = decode(io.BytesIO(raw))
    require(report["capture_integrity_verified"] and
            report["profile"]["expected_vin"] == expected_vin, "Invalid capture or mismatched VIN")
    profile = report["profile"]
    result = report["result"]
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\r\n")
    writer.writerow(("cycle", "pid_hex", "elapsed_ms", "status", "response_hex",
                     "request_can_id", "response_can_id", "vin", "source_completed"))
    for sample in result["samples"]:
        writer.writerow((sample["cycle"], f'{sample["pid"]:02X}', sample["elapsed_ms"],
                         sample["status"], sample["response_hex"] or "", profile["request_can_id"],
                         profile["response_can_id"], expected_vin, str(result["completed"]).lower()))
    content = buffer.getvalue().encode("ascii")
    require(len(content) <= 40 * 1024 * 1024, "CSV size limit exceeded")
    again, current = _regular(capture, MAX_CAPTURE)
    require(raw == again and stamp == current, "CL/1 changed during export")
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as target:
        target.write(content)
        target.flush()
        os.fsync(target.fileno())
    return {"schema": "camarodiag-j1979-native-live-csv-export-v1",
            "verified": True, "execution_completed": result["completed"],
            "capture_sha256": stamp.digest, "samples": len(result["samples"]),
            "physical_vehicle_verified": False, "permits_programming": False}


def write_new(path, value):
    data = (json.dumps(value, ensure_ascii=True, allow_nan=False, indent=2) + "\n").encode("utf-8")
    require(len(data) <= MAX_REPORT, "Native live report too large")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def _byte(value):
    try:
        require(len(value) == 2 and all(c in "0123456789abcdefABCDEF" for c in value),
                "Expected exactly two hexadecimal digits")
        return int(value, 16)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc


def _id(value):
    try:
        text = value[2:] if value.lower().startswith("0x") else value
        require(1 <= len(text) <= 8 and all(c in "0123456789abcdefABCDEF" for c in text),
                "Expected physical hexadecimal CAN ID")
        return int(text, 16)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc


def parser():
    root = argparse.ArgumentParser(allow_abbrev=False, description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    offline = commands.add_parser("inspect", allow_abbrev=False)
    offline.add_argument("--capture", type=Path, required=True)
    offline.add_argument("--output", type=Path, required=True)
    offline.add_argument("--vin", required=True)
    csv_export = commands.add_parser("export-csv", allow_abbrev=False)
    csv_export.add_argument("--capture", type=Path, required=True)
    csv_export.add_argument("--output", type=Path, required=True)
    csv_export.add_argument("--vin", required=True)
    verify = commands.add_parser("verify", allow_abbrev=False)
    verify.add_argument("--capture", type=Path, required=True)
    verify.add_argument("--report", type=Path, required=True)
    verify.add_argument("--vin", required=True)
    verify.add_argument("--output", type=Path, required=True)
    live = commands.add_parser("capture", allow_abbrev=False)
    live.add_argument("--port", required=True)
    live.add_argument("--key-file", type=Path, required=True)
    live.add_argument("--tx", type=_id, required=True)
    live.add_argument("--rx", type=_id, required=True)
    live.add_argument("--extended", action="store_true")
    live.add_argument("--vin", required=True)
    live.add_argument("--pid", action="append", type=_byte, required=True)
    live.add_argument("--cycles", type=int, default=30)
    live.add_argument("--interval-ms", type=int, default=1000)
    live.add_argument("--budget-ms", type=int, default=180000)
    live.add_argument("--capture", type=Path, required=True)
    live.add_argument("--output", type=Path, required=True)
    live.add_argument("--confirm-physical-channel", action="store_true", required=True)
    return root


def run(argv=None):
    args = parser().parse_args(argv)
    require(valid_vin(args.vin), "Expected VIN is invalid")
    paths = [args.capture, args.output]
    if args.command == "verify":
        paths.append(args.report)
    if args.command == "capture":
        paths.append(args.key_file)
    require(len({p.resolve() for p in paths}) == len(paths), "Input and output paths must differ")
    if args.command == "export-csv":
        return export_csv(args.capture, args.output, expected_vin=args.vin)
    if args.command == "verify":
        report = verify_files(args.capture, args.report, expected_vin=args.vin)
        write_new(args.output, report)
        return report
    if args.command == "inspect":
        report = inspect_file(args.capture)
        require(report["profile"] is not None and report["profile"]["expected_vin"] == args.vin,
                "CL/1 plan differs from independently supplied VIN")
        write_new(args.output, report)
        return report
    profile = NativeLiveProfile(args.tx, args.rx, args.extended, args.vin, tuple(args.pid),
                                args.cycles, args.interval_ms, args.budget_ms).validate()
    key, key_stamp = _regular(args.key_file, 32)
    require(len(key) == 32, "Credential must contain exactly 32 provisioned bytes")
    try:
        import serial
    except ImportError as exc:
        raise ValueError("Install the serial extra before using native USB") from exc
    require(not args.capture.exists() and not args.output.exists(), "New evidence paths required")
    if input("Qualified locally approved connector, stationary vehicle, exact VIN and scope? [yes/no] ") != "yes":
        raise ValueError("Cancelled before USB transmission")
    # Check the credential again after operator confirmation. Do not print
    # or include it in a report, and never open the port on changed inputs.
    again, final = _regular(args.key_file, 32)
    require(again == key and final == key_stamp, "Credential changed before acquisition")
    report = None
    started_at = datetime.now(timezone.utc)
    fd = os.open(args.capture, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as capture:
            with serial.Serial(args.port, baudrate=115200, timeout=0.1, write_timeout=1) as port:
                report = capture_live(port, profile, key, capture)
            capture.flush()
            os.fsync(capture.fileno())
        persisted = inspect_file(args.capture, expected=profile)
        report["capture_sha256"] = persisted["capture_sha256"]
        for field in ("profile", "observations_received", "summary", "result"):
            if report["capture_integrity_verified"]:
                _compare_history_value(report[field], persisted[field], "persisted." + field)
        require(not report["capture_integrity_verified"] or persisted["capture_integrity_verified"],
                "Persisted CL/1 failed independent replay")
    except (OSError, ValueError, KeyboardInterrupt) as exc:
        if report is None:
            report = decode(io.BytesIO(b""), expected=profile)
            report.update(source="LIVE_USB_CU3", client_proof_sent=False,
                          device_credential_authenticated=False)
        report.update(capture_integrity_verified=False, summary=None, result=None,
                      cancelled=isinstance(exc, KeyboardInterrupt) or report.get("cancelled", False),
                      error="Cancelled" if isinstance(exc, KeyboardInterrupt) else str(exc))
    if "capture_sha256" not in report:
        try:
            persisted = inspect_file(args.capture, expected=profile)
            report["capture_sha256"] = persisted["capture_sha256"]
        except (OSError, ValueError):
            report["capture_integrity_verified"] = False
    report["raw_capture_name"] = args.capture.name
    try:
        report["version"] = version("camarodiagconnectors")
    except PackageNotFoundError:
        report["version"] = "source"
    report["declared_connector"] = args.port
    report["declared_ecu"] = f"CAN {profile.tx:X}/{profile.rx:X}"
    report["started_at_utc"] = started_at.isoformat().replace("+00:00", "Z")
    report["finished_at_utc"] = max(started_at, datetime.now(timezone.utc)).isoformat().replace("+00:00", "Z")
    report["observed_vin"] = (profile.vin if report["capture_integrity_verified"] and
                              (report["summary"] or {}).get("initial_identity_verified") else None)
    write_new(args.output, report)
    return report


def main():
    try:
        report = run()
    except (OSError, ValueError, EOFError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2) from exc
    if report["schema"] in ("camarodiag-j1979-native-live-verification-v1",
                             "camarodiag-j1979-native-live-csv-export-v1"):
        integrity = report["verified"]
        complete = report["execution_completed"]
    else:
        integrity = report["capture_integrity_verified"]
        complete = integrity and bool((report.get("summary") or {}).get("completed"))
    print(json.dumps({"verified": integrity, "execution_completed": complete}))
    if not integrity:
        raise SystemExit(2)
    if not complete:
        raise SystemExit(7)


if __name__ == "__main__":
    main()
