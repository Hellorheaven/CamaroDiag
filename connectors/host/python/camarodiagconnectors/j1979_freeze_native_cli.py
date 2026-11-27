"""Explicit native USB CU/2 capture and offline CF/1 inspection.

There is no automatic discovery, CU/1 fallback or pairing. The connector
must be locally qualified and already provisioned with the same credential.
"""
from __future__ import annotations

import argparse
import io
import json
import os
from pathlib import Path
import sys
import time
import zlib
import struct

from .j1979_freeze import FreezePlan, MAX_STEPS
from .j1979_freeze_native import NativeProfile, hello, proof, decode, inspect_file, HEADER, MAX_BODY


def capture_live(port, profile, key, capture, *, clock=time.monotonic, nonce=None):
    request = hello(profile, nonce)
    if not isinstance(key, bytes) or len(key) != 32:
        raise ValueError("A provisioned 32-byte credential is required")
    authenticated = False
    proof_sent = False
    cancelled = False
    error = None
    raw = bytearray()
    start = clock()

    def send(data, deadline):
        if clock() >= deadline or port.write(data) != len(data):
            raise OSError("Incomplete CU/2 transmission; no retry")
        if clock() >= deadline:
            raise TimeoutError("CU/2 write deadline exceeded")

    def receive(size, deadline, record=False):
        result = bytearray()
        while len(result) < size:
            if clock() >= deadline:
                raise TimeoutError("CU/2 receive deadline exceeded")
            piece = port.read(size - len(result))
            if not isinstance(piece, bytes) or len(piece) > size - len(result):
                raise OSError("Invalid serial read")
            if piece:
                result.extend(piece)
                if record:
                    raw.extend(piece)
                    if capture.write(piece) != len(piece):
                        raise OSError("Incomplete CF/1 file write")
            if clock() >= deadline:
                raise TimeoutError("CU/2 receive deadline exceeded")
        return bytes(result)

    try:
        auth_deadline = start + 5
        send(request, auth_deadline)
        challenge = receive(68, auth_deadline)
        client_proof = proof(request, challenge, key)
        authenticated = True
        send(client_proof, auth_deadline)
        proof_sent = True
        deadline = clock() + profile.plan.budget_ms / 1000 + 15
        for sequence in range(MAX_STEPS + 2):
            header = receive(HEADER.size, deadline, True)
            magic, version, kind, seq, length = HEADER.unpack(header)
            if (magic != b"CF" or version != 1 or seq != sequence
                    or kind not in (1, 2, 3) or not 1 <= length <= MAX_BODY):
                raise ValueError("Invalid live CF/1 header")
            body = receive(length, deadline, True)
            trailer = receive(4, deadline, True)
            if zlib.crc32(header + body) != struct.unpack("<I", trailer)[0]:
                raise ValueError("Live CF/1 CRC mismatch")
            if sequence == 0:
                if kind != 1 or NativeProfile.decode(body) != profile:
                    raise ValueError("Live CF/1 plan differs from approved CU/2 scope")
            elif kind == 1:
                raise ValueError("Duplicate live CF/1 plan")
            if kind == 3:
                if clock() >= deadline:
                    raise TimeoutError("CF/1 final deadline exceeded")
                trailing = port.read(1)
                if not isinstance(trailing, bytes) or len(trailing) > 1:
                    raise OSError("Invalid serial read after CF/1 terminal record")
                if trailing:
                    raw.extend(trailing)
                    if capture.write(trailing) != len(trailing):
                        raise OSError("Incomplete trailing evidence write")
                    raise ValueError("Trailing live CF/1 bytes")
                if clock() >= deadline:
                    raise TimeoutError("CF/1 final deadline exceeded")
                break
        else:
            raise ValueError("Live CF/1 record limit exceeded")
    except KeyboardInterrupt:
        cancelled = True
        error = "Cancelled by operator; no retry"
    except (OSError, ValueError, EOFError) as exc:
        error = str(exc)
    report = decode(io.BytesIO(raw), expected=profile)
    if error:
        report.update(capture_integrity_verified=False, error=error)
    report.update(source="LIVE_USB_CU2", device_credential_authenticated=authenticated,
                  client_proof_sent=proof_sent, cancelled=cancelled,
                  host_elapsed_ms=max(0, int((clock() - start) * 1000)))
    return report


def _exclusive(path, binary=False):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    return os.fdopen(fd, "wb") if binary else os.fdopen(fd, "w", encoding="utf-8")


def _write_report(stream, report):
    json.dump(report, stream, indent=2, ensure_ascii=True, allow_nan=False)
    stream.write("\n")
    stream.flush()
    os.fsync(stream.fileno())


def _byte(value):
    try:
        number = int(value, 16)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Expected hexadecimal byte") from exc
    if not 0 <= number <= 255:
        raise argparse.ArgumentTypeError("Byte must be 00..FF")
    return number


def run(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False, description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    offline = commands.add_parser("inspect", allow_abbrev=False)
    offline.add_argument("--capture", type=Path, required=True)
    offline.add_argument("--output", type=Path, required=True)
    live = commands.add_parser("capture", allow_abbrev=False)
    live.add_argument("--port", required=True)
    live.add_argument("--key-file", type=Path, required=True)
    live.add_argument("--tx", type=lambda x: int(x, 0), required=True)
    live.add_argument("--rx", type=lambda x: int(x, 0), required=True)
    live.add_argument("--extended", action="store_true")
    live.add_argument("--vin", required=True)
    live.add_argument("--frame", action="append", type=_byte, required=True)
    live.add_argument("--pid", action="append", type=_byte, default=[])
    live.add_argument("--budget-ms", type=int, default=180000)
    live.add_argument("--capture", type=Path, required=True)
    live.add_argument("--output", type=Path, required=True)
    live.add_argument("--confirm-physical-channel", action="store_true", required=True)
    args = parser.parse_args(argv)
    if args.capture.resolve() == args.output.resolve():
        parser.error("Capture and report must be different files")
    if args.command == "inspect":
        report = inspect_file(args.capture)
        with _exclusive(args.output) as output:
            _write_report(output, report)
        return report
    if args.key_file.resolve() in (args.capture.resolve(), args.output.resolve()):
        parser.error("Credential file must differ from evidence paths")
    profile = NativeProfile(args.tx, args.rx, args.extended,
                            FreezePlan(args.vin, tuple(args.frame), tuple(args.pid), args.budget_ms))
    profile.encode()
    with args.key_file.open("rb") as stream:
        key = stream.read(33)
    if len(key) != 32:
        parser.error("Key file must contain exactly 32 provisioned bytes")
    try:
        import serial
    except ImportError as exc:
        raise SystemExit("Install the serial extra before using native USB") from exc
    if input("Qualified locally approved connector, stationary vehicle, exact VIN and scope? [yes/no] ") != "yes":
        raise SystemExit("Cancelled before USB transmission")
    with _exclusive(args.output) as output:
        report = None
        try:
            with _exclusive(args.capture, True) as raw:
                with serial.Serial(args.port, baudrate=115200, timeout=0.1, write_timeout=1) as port:
                    report = capture_live(port, profile, key, raw)
                raw.flush()
                os.fsync(raw.fileno())
        except (OSError, ValueError, KeyboardInterrupt) as exc:
            if report is None:
                report = decode(io.BytesIO(b""), expected=profile)
                report.update(source="LIVE_USB_CU2", client_proof_sent=False)
            report.update(capture_integrity_verified=False,
                          cancelled=isinstance(exc, KeyboardInterrupt),
                          error="Cancelled" if isinstance(exc, KeyboardInterrupt) else str(exc))
        _write_report(output, report)
    return report


def main():
    try:
        report = run()
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2) from exc
    print(json.dumps({key: report.get(key) for key in
                      ("capture_integrity_verified", "observations_received", "error")}))
    if not report["capture_integrity_verified"] or not (report.get("summary") or {}).get("completed"):
        raise SystemExit(2)


if __name__ == "__main__":
    main()
