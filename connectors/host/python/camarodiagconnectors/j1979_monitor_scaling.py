"""Classic CAN Mode 06 engineering values, ISO 15031-5:2006 Annex E subset.

Raw records remain authoritative. This catalog does not establish whether
the monitor ran, the limits apply, or the vehicle passed a diagnostic test.
Do not apply these definitions to legacy Mode 05/06 or OEM-specific records.
"""
from __future__ import annotations

# Explicit definitions only; signed values are 16-bit two's complement.
_DEFINITIONS = {
    0x0A: ("mV", 0.122, False),
    0x0B: ("V", 0.001, False),
    0x0D: ("mA", 0.00390625, False),
    0x0E: ("A", 0.001, False),
    0x0F: ("A", 0.01, False),
    0x10: ("ms", 1, False),
    0x24: ("count", 1, False),
    0x25: ("km", 1, False),
    0x2F: ("%", 0.01, False),
    0x31: ("L", 0.001, False),
    0x84: ("1", 0.001, True),
    0x85: ("1", 0.0000305, True),
    0x86: ("1", 0.000305, True),
    0x8E: ("A", 0.001, True),
    0x90: ("ms", 1, True),
    0x96: ("degC", 0.1, True),
}


def scale_record(uasid: int, value: int, minimum: int, maximum: int) -> dict | None:
    """Return engineering values and numerical comparison, never monitor verdict."""
    if any(type(v) is not int or not 0 <= v <= 65535 for v in (value, minimum, maximum)):
        raise ValueError("Mode 06 values must be unsigned 16-bit encodings")
    if type(uasid) is not int or not 0 <= uasid <= 255:
        raise ValueError("Invalid unit and scaling identifier")
    definition = _DEFINITIONS.get(uasid)
    if definition is None:
        return None
    unit, factor, signed = definition
    def decode(raw):
        return ((raw - 65536) if signed and raw >= 32768 else raw) * factor

    decoded, low, high = map(decode, (value, minimum, maximum))
    relation = ("INVERTED_LIMITS" if low > high else "BELOW" if decoded < low
                else "ABOVE" if decoded > high else "WITHIN")
    return {"definition": "ISO15031-5:2006-ANNEX-E-SUBSET",
            "unit": unit, "value": decoded, "minimum": low, "maximum": high,
            "numeric_relation": relation, "monitor_verdict": None}
