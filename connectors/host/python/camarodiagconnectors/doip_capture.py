"""Capture a documented ECU region twice over one verified DoIP route.

This is a read-only acquisition workflow, never a restoration or flash tool.
"""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import tempfile
import time
from zipfile import ZipFile, ZIP_DEFLATED

from .doip import Session, DoIpError
from .doip_archive import load_scope, verify_archive

MAX_BLOCKS = 8192
MAX_SECONDS = 600


def capture_region(session: Session, scope: dict, progress=lambda *_: None) -> tuple[bytes, bytes]:
    if (session.gateway.vin != scope["Vin"] or
        session.gateway.logical_address != scope["Gateway"] or
        session.tester != scope["Tester"] or session.ecu != scope["Ecu"]):
        raise DoIpError("Session differs from the approved memory profile")
    length, block = scope["Length"], scope["BlockSize"]
    if (length + block - 1) // block > MAX_BLOCKS:
        raise DoIpError("Memory scope exceeds bounded block count")
    end = time.monotonic() + MAX_SECONDS

    def check_deadline():
        if time.monotonic() >= end:
            raise TimeoutError("DoIP capture exceeded its overall deadline")

    def identity():
        check_deadline()
        session.verify_vin(scope["Vin"])
        check_deadline()
        if session.read_did(scope["IdentityDid"]).hex().upper() != scope["IdentityHex"]:
            raise DoIpError("ECU identity differs from the memory profile")
        check_deadline()

    def one_pass(index):
        identity()
        data = bytearray()
        blocks = 0
        while len(data) < length:
            check_deadline()
            if blocks and blocks % 32 == 0:
                identity()
            count = min(block, length - len(data))
            address = scope["Start"] + len(data)
            size_bytes = 1 if count <= 255 else 2
            req = bytes((0x23, (size_bytes << 4) | 4)) + address.to_bytes(4, "big") + count.to_bytes(size_bytes, "big")
            reply = session.exchange(req)
            check_deadline()
            if len(reply) != count + 1 or reply[0] != 0x63:
                raise DoIpError("Incomplete memory response")
            data.extend(reply[1:])
            blocks += 1
            if blocks % 32 == 0 or len(data) == length:
                progress(index, len(data), length)
        return bytes(data)

    try:
        first, second = one_pass(1), one_pass(2)
        identity()
        if not hmac.compare_digest(first, second):
            raise DoIpError("Independent region reads differ")
        return first, second
    except BaseException:
        session.close()
        raise


def save_capture(destination: Path, profile: Path, scope: dict,
                 first: bytes, second: bytes) -> dict:
    if (len(first) != scope["Length"] or not hmac.compare_digest(first, second)
            or load_scope(profile) != scope):
        raise DoIpError("Capture or profile changed before publication")
    manifest = {"format": "CamaroDiag-DoIP-memory-v1", "transport": "DoIP",
                "coverage": "SCOPED_REGION_ONLY", "origin": "REPORTED_HARDWARE_READ",
                "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
                "scope": scope, "sha256": hashlib.sha256(first).hexdigest().upper()}
    if destination.exists():
        raise FileExistsError("Never overwrite a backup")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".doip-", suffix=".zip",
                                         delete=False) as out:
            temporary = Path(out.name)
            with ZipFile(out, "w", ZIP_DEFLATED) as archive:
                archive.writestr("manifest.json", json.dumps(manifest))
                archive.writestr("original.bin", first)
                archive.writestr("verification.bin", second)
            out.flush()
            os.fsync(out.fileno())
        verified = verify_archive(temporary, profile)
        if verified["scope"] != scope or verified["sha256"] != manifest["sha256"]:
            raise DoIpError("Staged archive differs from the capture")
        # Atomic, exclusive publication on a filesystem supporting hardlinks.
        # If unsupported, fail without replacing the original or copying partially.
        os.link(temporary, destination)
        return verified | {"action": "capture-doip", "archive": str(destination),
                           "independent_reads_equal": True}
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
