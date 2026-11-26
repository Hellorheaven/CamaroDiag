"""Offline verifier for scoped DoIP archives produced by Android or Windows.

A matching archive and profile prove local integrity, not hardware provenance.
No socket, serial port, extraction, ECU operation or programming authorization.
"""
from __future__ import annotations

import hashlib
import json
import re
import struct
from datetime import datetime
from pathlib import Path
from zipfile import BadZipFile, ZipFile

MAX_BYTES = 16 * 1024 * 1024
REGIONS = {"CALIBRATION", "PROGRAM_FLASH", "DATA_FLASH", "EXTERNAL_EEPROM", "OTHER"}


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def _json(raw: bytes) -> dict:
    obj = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique)
    if not isinstance(obj, dict):
        raise ValueError("Expected JSON object")
    return obj


def _hex(value, pattern):
    if not isinstance(value, str) or not re.fullmatch(pattern, value):
        raise ValueError("Invalid hexadecimal profile field")
    return int(value, 16)


def load_scope(path: Path) -> dict:
    with path.open("rb") as stream:
        raw = stream.read(16385)
    if not 0 < len(raw) <= 16384:
        raise ValueError("Invalid DoIP profile length")
    p = _json(raw)
    if set(p) != {"vin", "gateway", "tester", "ecu", "identityDid", "identityHex",
                  "startAddress", "lengthBytes", "blockSize", "region"}:
        raise ValueError("Incomplete or unknown DoIP profile fields")
    vin, identity, region = p["vin"], p["identityHex"], p["region"]
    if not isinstance(vin, str) or not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", vin):
        raise ValueError("Invalid VIN")
    if not isinstance(identity, str) or not re.fullmatch(r"(?:[0-9A-Fa-f]{2}){1,256}", identity):
        raise ValueError("Invalid ECU identity")
    identity = identity.upper()
    if set(identity) in ({"0"}, {"F"}):
        raise ValueError("Placeholder ECU identity")
    gateway, tester, ecu, did = (_hex(p[k], r"[0-9A-Fa-f]{4}") for k in
                                 ("gateway", "tester", "ecu", "identityDid"))
    start = _hex(p["startAddress"], r"[0-9A-Fa-f]{1,8}")
    length, block = p["lengthBytes"], p["blockSize"]
    if (type(length) is not int or type(block) is not int or
        not 1 <= length <= MAX_BYTES or not 1 <= block <= 256 or
        start + length > 2**32 or tester in (gateway, ecu) or did == 0xF190 or
        not isinstance(region, str) or region not in REGIONS):
        raise ValueError("Invalid DoIP ECU memory scope")
    return {"Vin": vin, "Gateway": gateway, "Tester": tester, "Ecu": ecu,
            "IdentityDid": did, "IdentityHex": identity, "Start": start,
            "Length": length, "BlockSize": block, "Region": region}


def _verify_footer(stream):
    """Require a single-disk, complete ZIP ending at its declared comment.
    
    Scoped backups fit in classic ZIP; no self-extracting prefix, ZIP64 or
    unrelated bytes after the central directory are needed by this format.
    """
    stream.seek(0, 2)
    length = stream.tell()
    if not 22 <= length <= 2 * MAX_BYTES + 1024 * 1024:
        raise ValueError("Invalid DoIP ZIP length")
    stream.seek(max(0, length - 65557))
    tail = stream.read()
    for offset in range(len(tail) - 22, -1, -1):
        if tail[offset:offset + 4] != b"PK\x05\x06":
            continue
        _, disk, central_disk, on_disk, total, size, start, comment = struct.unpack_from(
            "<4s4H2IH", tail, offset)
        end = length - len(tail) + offset
        if end + 22 + comment != length:
            continue
        if disk or central_disk or on_disk != 3 or total != 3 or start + size != end:
            raise ValueError("Invalid DoIP ZIP central directory")
        return start
    raise ValueError("Incomplete DoIP ZIP footer")


def verify_archive(path: Path, profile: Path) -> dict:
    scope = load_scope(profile)
    try:
        with path.open("rb") as source:
            central_start = _verify_footer(source)
            with ZipFile(source) as archive:
                entries = archive.infolist()
                limits = {"manifest.json": 16384, "original.bin": scope["Length"],
                          "verification.bin": scope["Length"]}
                if len(entries) != 3 or {entry.filename for entry in entries} != set(limits):
                    raise ValueError("Unexpected or duplicate DoIP ZIP entries")
                offsets = set()
                for entry in entries:
                    limit = limits[entry.filename]
                    if (entry.is_dir() or not 1 <= entry.file_size <= limit or
                            (entry.filename != "manifest.json" and entry.file_size != limit) or
                            entry.header_offset in offsets or not 0 <= entry.header_offset < central_start or
                            entry.flag_bits & 1 or entry.compress_type not in (0, 8)):
                        raise ValueError("Invalid DoIP archive entry metadata")
                    offsets.add(entry.header_offset)
                if min(offsets) != 0:
                    raise ValueError("Unexpected DoIP ZIP prefix")
                with archive.open("manifest.json") as stream:
                    metadata = stream.read(16385)
                    if len(metadata) != archive.getinfo("manifest.json").file_size or stream.read(1):
                        raise ValueError("Invalid DoIP manifest data")
                # Compare and hash in bounded chunks, rather than allocating
                # two complete 16 MiB regions. ZipExtFile checks the CRC at EOF.
                digest_builder = hashlib.sha256()
                remaining = scope["Length"]
                with archive.open("original.bin") as first, archive.open("verification.bin") as second:
                    while remaining:
                        count = min(65536, remaining)
                        left, right = first.read(count), second.read(count)
                        if len(left) != count or left != right:
                            raise ValueError("Independent reads differ")
                        digest_builder.update(left)
                        remaining -= count
                    if first.read(1) or second.read(1):
                        raise ValueError("Oversized DoIP region")
                digest = digest_builder.hexdigest().upper()
    except (BadZipFile, RuntimeError, NotImplementedError, EOFError, struct.error) as exc:
        raise ValueError("Invalid DoIP ZIP") from exc
    manifest = _json(metadata)
    if set(manifest) != {"format", "transport", "coverage", "origin",
                         "capturedAtUtc", "scope", "sha256"}:
        raise ValueError("Invalid manifest fields")
    if (manifest["format"] != "CamaroDiag-DoIP-memory-v1" or
        manifest["transport"] != "DoIP" or manifest["coverage"] != "SCOPED_REGION_ONLY" or
        manifest["origin"] != "REPORTED_HARDWARE_READ"):
        raise ValueError("Invalid DoIP manifest")
    stored = manifest["scope"]
    if (not isinstance(stored, dict) or stored.keys() != scope.keys() or
        any(type(stored[k]) is not type(v) or stored[k] != v for k, v in scope.items())):
        raise ValueError("Archive does not match selected ECU profile")
    timestamp = manifest["capturedAtUtc"]
    if not isinstance(timestamp, str) or not re.fullmatch(
            r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})",
            timestamp):
        raise ValueError("Invalid capture timestamp")
    captured = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    if captured.tzinfo is None:
        raise ValueError("Capture timestamp needs explicit timezone")
    if manifest["sha256"] != digest:
        raise ValueError("Independent reads or SHA-256 mismatch")
    return {"action": "verify-doip", "scope": scope, "sha256": digest,
            "captured_at": timestamp, "archive_integrity_verified": True,
            "physical_read_verified": False, "write_authorized": False}
