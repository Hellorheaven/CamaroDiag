"""Offline verifier for scoped DoIP archives produced by Android or Windows.

A matching archive and profile prove local integrity, not hardware provenance.
No socket, serial port, extraction, ECU operation or programming authorization.
"""
from __future__ import annotations

import hashlib
import json
import re
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


def verify_archive(path: Path, profile: Path) -> dict:
    scope = load_scope(profile)
    if path.stat().st_size > 2 * MAX_BYTES + 1024 * 1024:
        raise ValueError("Oversized DoIP ZIP")
    try:
        with ZipFile(path) as archive:
            entries = archive.infolist()
            limits = {"manifest.json": 16384, "original.bin": scope["Length"],
                      "verification.bin": scope["Length"]}
            if len(entries) != 3 or {entry.filename for entry in entries} != set(limits):
                raise ValueError("Unexpected or duplicate DoIP ZIP entries")
            content = {}
            for entry in entries:
                limit = limits[entry.filename]
                if entry.is_dir() or not 1 <= entry.file_size <= limit:
                    raise ValueError("Invalid DoIP archive entry length")
                with archive.open(entry) as stream:
                    data = stream.read(limit + 1)
                    if len(data) != entry.file_size or stream.read(1):
                        raise ValueError("Invalid DoIP ZIP entry data")
                content[entry.filename] = data
    except (BadZipFile, RuntimeError, NotImplementedError) as exc:
        raise ValueError("Invalid DoIP ZIP") from exc
    manifest = _json(content["manifest.json"])
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
    if not isinstance(timestamp, str):
        raise ValueError("Invalid capture timestamp")
    captured = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    if captured.tzinfo is None:
        raise ValueError("Capture timestamp needs explicit timezone")
    first, second = content["original.bin"], content["verification.bin"]
    digest = hashlib.sha256(first).hexdigest().upper()
    if (len(first) != scope["Length"] or first != second or manifest["sha256"] != digest):
        raise ValueError("Independent reads or SHA-256 mismatch")
    return {"action": "verify-doip", "scope": scope, "sha256": digest,
            "captured_at": timestamp, "archive_integrity_verified": True,
            "physical_read_verified": False, "write_authorized": False}
