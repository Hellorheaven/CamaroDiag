"""ELM ATH1 legacy application frames, including their link check byte.

No headerless fallback: source attribution is mandatory for vehicle-wide
mutation evidence. ELM does not expose J1850 IFR bytes.
"""
from __future__ import annotations

from dataclasses import dataclass
import re


@dataclass(frozen=True)
class LegacyFrame:
    source: int
    raw: bytes
    payload: bytes

    def evidence(self):
        return {"source": f"{self.source:02X}", "raw_hex": self.raw.hex().upper(),
                "payload_hex": self.payload.hex().upper()}


def checksum(protocol: int, data: bytes) -> int:
    if protocol in (1, 2):
        crc = 0xFF
        for byte in data:
            crc ^= byte
            for _ in range(8):
                crc = ((crc << 1) ^ (0x1D if crc & 0x80 else 0)) & 0xFF
        return crc ^ 0xFF
    if protocol in (3, 4, 5):
        return sum(data) & 0xFF
    raise ValueError("Not a legacy protocol")


def response_frames(protocol: int, command: str, raw: str) -> list[LegacyFrame]:
    if protocol not in range(1, 6):
        raise ValueError("Invalid legacy protocol")
    lines = ["".join(line.upper().split()) for line in raw.replace(">", "\n").splitlines()]
    lines = [line for line in lines if line and line != command and line != "SEARCHING..."]
    if not lines or lines == ["NODATA"]:
        return []
    if len(lines) > 128:
        raise ValueError("Too many legacy response records")
    result = []
    for line in lines:
        if not re.fullmatch(r"(?:[0-9A-F]{2}){5,11}", line):
            raise ValueError("Unattributed, mixed or malformed legacy response")
        data = bytes.fromhex(line)
        source = data[2]
        if source in (0, 0xF1, 0xFF):
            raise ValueError("Invalid response source")
        if protocol in (4, 5):
            if data[0] & 0xC0 != 0x80 or data[1] != 0xF1 or data[0] & 0x3F != len(data)-4:
                raise ValueError("Invalid addressed KWP response")
        elif data[:2] != bytes((0x41 if protocol == 1 else 0x48, 0x6B)):
            raise ValueError("Unexpected legacy response header")
        if checksum(protocol, data[:-1]) != data[-1]:
            raise ValueError("Legacy checksum mismatch")
        result.append(LegacyFrame(source, data, data[3:-1]))
    return result
