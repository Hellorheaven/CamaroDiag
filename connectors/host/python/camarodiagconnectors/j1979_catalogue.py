"""Declarative, offline PID interpretation from a user-supplied reference.

No included catalogue is claimed to represent J1979DA. The source digest binds
the supplied reference bytes, not their authenticity, licensing or applicability.
Only explicitly scoped, correlated Mode 01 raw evidence may be projected.
"""
from __future__ import annotations

from dataclasses import dataclass
from fractions import Fraction
import hashlib
import re
from pathlib import Path
from .recovery_bundle import _regular, _json

SCHEMA = "camarodiag-j1979-pid-catalogue-v1"
MAX_CATALOGUE = 4 * 1024 * 1024
MAX_REFERENCE = 128 * 1024 * 1024
MAX_WIDTH = 4093
MAX_SIGNALS = 64
MAX_PROJECTION_ROWS = 100000

def require(condition, detail):
    if not condition:
        raise ValueError(detail)

def object_fields(value, required, optional=()):
    require(isinstance(value, dict), "Expected object")
    require(set(required) <= set(value) <= set(required) | set(optional),
            "Missing or unknown catalogue field")
    return value

def integer(value, low, high, label):
    require(type(value) is int and low <= value <= high, "Invalid " + label)
    return value

def label(value, name, maximum=256):
    require(isinstance(value, str) and 0 < len(value) <= maximum and
            value == value.strip() and not any(ord(c) < 32 or ord(c) == 127 for c in value),
            "Invalid " + name)
    return value

def digest(value):
    require(isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) is not None,
            "Invalid SHA-256")
    return value

def fraction(value, name):
    require(isinstance(value, list) and len(value) == 2, "Invalid " + name)
    return Fraction(integer(value[0], -10**12, 10**12, name + " numerator"),
                    integer(value[1], 1, 10**12, name + " denominator"))

def encoded(value, width, name):
    return integer(value, 0, (1 << width) - 1, name)

def exact(value):
    return {"numerator": str(value.numerator), "denominator": str(value.denominator)}

@dataclass(frozen=True)
class Bits:
    offset: int
    width: int
    order: str

    @classmethod
    def parse(cls, value, payload_width):
        row = object_fields(value, ("offset", "width", "order"))
        start = integer(row["offset"], 0, payload_width * 8 - 1, "bit offset")
        width = integer(row["width"], 1, 64, "bit width")
        require(start + width <= payload_width * 8, "Signal extends outside payload")
        order = row["order"]
        require(order in ("MSB0", "LE_BYTES"), "Unsupported bit order")
        require(order != "LE_BYTES" or start % 8 == 0 and width % 8 == 0,
                "Little-endian signals must use complete bytes")
        return cls(start, width, order)

    def extract(self, payload):
        if self.order == "LE_BYTES":
            return int.from_bytes(payload[self.offset//8:(self.offset+self.width)//8], "little")
        first = self.offset//8
        last = (self.offset+self.width+7)//8
        chunk = int.from_bytes(payload[first:last], "big")
        shift = last*8-self.offset-self.width
        return (chunk >> shift) & ((1 << self.width)-1)

@dataclass(frozen=True)
class Presence:
    bits: Bits
    mask: int
    equals: int

    @classmethod
    def parse(cls, value, width):
        row = object_fields(value, ("bits", "mask", "equals"))
        bits = Bits.parse(row["bits"], width)
        mask = integer(row["mask"], 1, (1 << bits.width)-1, "presence mask")
        expected = encoded(row["equals"], bits.width, "presence value")
        require(expected & ~mask == 0, "Presence value contains bits outside mask")
        return cls(bits, mask, expected)

    def matches(self, payload):
        return self.bits.extract(payload) & self.mask == self.equals

@dataclass(frozen=True)
class Signal:
    name: str
    unit: str
    bits: Bits
    signed: bool
    scale: Fraction
    offset: Fraction
    unavailable: tuple[int, ...]
    presence: Presence | None
    minimum: Fraction | None
    maximum: Fraction | None
    enumeration: tuple[tuple[int, str], ...]
    reference: str

    @classmethod
    def parse(cls, value, width):
        row = object_fields(value, ("name", "unit", "bits", "signed", "scale",
                                   "offset", "reference"),
                            ("unavailable", "presence", "range", "enumeration"))
        bits = Bits.parse(row["bits"], width)
        require(type(row["signed"]) is bool, "Invalid signed flag")
        raw = row.get("unavailable", [])
        require(isinstance(raw, list) and len(raw) <= 256, "Invalid unavailable values")
        unavailable = tuple(encoded(x, bits.width, "unavailable value") for x in raw)
        require(len(set(unavailable)) == len(unavailable), "Duplicate unavailable value")
        entries = row.get("enumeration", [])
        require(isinstance(entries, list) and len(entries) <= 256, "Invalid enumeration")
        enums = []
        for entry in entries:
            item = object_fields(entry, ("raw", "label"))
            enums.append((encoded(item["raw"], bits.width, "enumeration value"),
                          label(item["label"], "enumeration label")))
        require(len({k for k, _ in enums}) == len(enums), "Duplicate enumeration value")
        require(not set(unavailable).intersection(k for k, _ in enums),
                "Unavailable value also enumerated")
        scale = fraction(row["scale"], "scale")
        offset = fraction(row["offset"], "offset")
        limits = row.get("range")
        low = high = None
        if limits is not None:
            require(isinstance(limits, list) and len(limits) == 2, "Invalid range")
            low, high = (fraction(x, "range limit") for x in limits)
            require(low <= high, "Inverted engineering range")
        if enums:
            require(not row["signed"] and scale == 1 and offset == 0 and limits is None,
                    "Enumeration cannot also imply engineering scaling")
        else:
            require(scale != 0, "Numeric scale cannot be zero")
        return cls(label(row["name"], "signal name"), label(row["unit"], "unit", 64),
                   bits, row["signed"], scale, offset, unavailable,
                   Presence.parse(row["presence"], width) if "presence" in row else None,
                   low, high, tuple(enums), label(row["reference"], "signal reference"))

    def project(self, payload):
        result = {"name": self.name, "unit": self.unit, "reference": self.reference,
                  "status": None, "raw": None, "value": None, "enumeration": None}
        if self.presence is not None and not self.presence.matches(payload):
            result["status"] = "NOT_PRESENT"
            return result
        raw = self.bits.extract(payload)
        result["raw"] = str(raw)
        if raw in self.unavailable:
            result["status"] = "UNAVAILABLE"
            return result
        if self.enumeration:
            value = dict(self.enumeration).get(raw)
            result["enumeration"] = value
            result["status"] = "ENUMERATED" if value is not None else "UNDEFINED_ENUMERATION"
            return result
        signed = raw
        if self.signed and raw & (1 << (self.bits.width-1)):
            signed -= 1 << self.bits.width
        number = signed*self.scale + self.offset
        result["value"] = exact(number)
        result["status"] = ("OUTSIDE_DECLARED_RANGE" if
            self.minimum is not None and not self.minimum <= number <= self.maximum else "DECODED")
        return result

@dataclass(frozen=True)
class Definition:
    pid: int
    width: int
    protocols: tuple[str, ...]
    reference: str
    signals: tuple[Signal, ...]

    @classmethod
    def parse(cls, value):
        row = object_fields(value, ("pid", "payload_bytes", "protocols", "reference", "signals"))
        pid = integer(row["pid"], 1, 255, "PID")
        require(pid % 32 != 0, "Support bitmap cannot be a measurement definition")
        width = integer(row["payload_bytes"], 1, MAX_WIDTH, "payload width")
        protocols = row["protocols"]
        require(isinstance(protocols, list) and 1 <= len(protocols) <= 2 and
                all(x in ("CAN", "LEGACY") for x in protocols) and
                len(set(protocols)) == len(protocols), "Invalid protocol scope")
        require("LEGACY" not in protocols or width <= 5, "Legacy payload exceeds service envelope")
        signals = row["signals"]
        require(isinstance(signals, list) and 1 <= len(signals) <= MAX_SIGNALS,
                "Invalid signal list")
        parsed = tuple(Signal.parse(x, width) for x in signals)
        require(len({s.name for s in parsed}) == len(parsed), "Duplicate signal name")
        return cls(pid, width, tuple(protocols), label(row["reference"], "PID reference"), parsed)

@dataclass(frozen=True)
class Catalogue:
    edition: str
    reference_name: str
    reference_sha256: str
    definitions: tuple[Definition, ...]

    @classmethod
    def parse(cls, value):
        row = object_fields(value, ("schema", "edition", "reference_name",
                                   "reference_sha256", "definitions"))
        require(row["schema"] == SCHEMA, "Unsupported catalogue schema")
        definitions = row["definitions"]
        require(isinstance(definitions, list) and 1 <= len(definitions) <= 247,
                "Invalid definitions")
        parsed = tuple(Definition.parse(x) for x in definitions)
        seen = set()
        for item in parsed:
            for protocol in item.protocols:
                key = item.pid, protocol
                require(key not in seen, "Ambiguous PID/protocol definition")
                seen.add(key)
        return cls(label(row["edition"], "edition"), label(row["reference_name"], "reference name"),
                   digest(row["reference_sha256"]), parsed)

    def index(self):
        return {(item.pid, p): item for item in self.definitions for p in item.protocols}

    def inventory(self):
        return [{"pid": item.pid, "payload_bytes": item.width, "protocols": list(item.protocols),
                 "reference": item.reference, "signals": [s.name for s in item.signals]}
                for item in sorted(self.definitions, key=lambda x: (x.pid, x.protocols))]

def read_catalogue(path, reference_path, expected_sha256):
    """Require a separately pinned source digest; never fetch an online source."""
    raw, stamp = _regular(Path(path), MAX_CATALOGUE)
    source, source_stamp = _regular(Path(reference_path), MAX_REFERENCE)
    catalogue = Catalogue.parse(_json(raw))
    require(catalogue.reference_sha256 == digest(expected_sha256) == source_stamp.digest,
            "Reference digest does not match catalogue and operator pin")
    again, final = _regular(Path(path), MAX_CATALOGUE)
    require(raw == again and stamp == final, "Catalogue changed during inspection")
    _, final_source = _regular(Path(reference_path), MAX_REFERENCE)
    require(source_stamp == final_source, "Reference changed during inspection")
    return catalogue, stamp.digest

def project(replayed, catalogue):
    """Project raw-only replay output. This is not a new diagnostic recording."""
    require(replayed.get("schema") == "camarodiag-j1979-live-inspection-v1" and
            replayed.get("verified") is True and replayed.get("raw_only") is True,
            "An independently replayed raw-only live report is required")
    channel = replayed["channel"]
    protocol = "LEGACY" if channel["kind"] == "LEGACY" else "CAN"
    require(channel["kind"] in ("LEGACY", "CAN"), "Unsupported replay channel")
    index = catalogue.index()
    samples = replayed["samples"]
    result = []
    counts = {}
    for sample in samples:
        pid = sample["pid"]
        row = {"cycle": sample["cycle"], "pid": pid, "elapsed_ms": sample["elapsed_ms"],
               "source_status": sample["status"], "response_hex": sample.get("response_hex"),
               "projection_status": None, "signals": []}
        definition = index.get((pid, protocol))
        if sample["status"] != "RAW":
            row["projection_status"] = "NO_CORRELATED_RAW_PAYLOAD"
        elif definition is None:
            row["projection_status"] = "NO_DOCUMENTED_DEFINITION"
        else:
            response = bytes.fromhex(sample["response_hex"])
            require(response[:2] == bytes((0x41, pid)), "Replay payload mismatch")
            if len(response) != 2 + definition.width:
                row["projection_status"] = "DOCUMENTED_WIDTH_MISMATCH"
            else:
                row["signals"] = [s.project(response[2:]) for s in definition.signals]
                row["projection_status"] = "PROJECTED"
                row["reference"] = definition.reference
        counts[row["projection_status"]] = counts.get(row["projection_status"], 0) + 1
        result.append(row)
        require(len(result) <= MAX_PROJECTION_ROWS, "Projection row limit exceeded")
    return {"schema": "camarodiag-j1979-catalogue-projection-v1",
            "reference_edition": catalogue.edition,
            "reference_sha256": catalogue.reference_sha256,
            "reference_authenticity_verified": False,
            "normative_compliance_verified": False,
            "source_replay_verified": True,
            "source_completed": replayed["completed"],
            "declared_vin": replayed["declared_vin"], "channel": channel,
            "counts": counts, "rows": result,
            "physical_vehicle_verified": False, "authenticated": False,
            "permits_programming": False, "diagnostic_verdict": None}
