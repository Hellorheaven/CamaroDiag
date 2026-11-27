"""Classic CAN Mode 06 engineering values, ISO 15031-5:2006 Annex E subset.

Raw records remain authoritative. This catalog does not establish whether
the monitor ran, the limits apply, or the vehicle passed a diagnostic test.
Do not apply these definitions to legacy Mode 05/06 or OEM-specific records.
"""
from __future__ import annotations

# Explicit definitions only; signed values are 16-bit two's complement.
_DEFINITIONS = {
    0x01: ("1", 1, False, 0),
    0x02: ("1", 0.1, False, 0),
    0x03: ("1", 0.01, False, 0),
    0x04: ("1", 0.001, False, 0),
    0x05: ("1", 0.0000305, False, 0),
    0x06: ("1", 0.000305, False, 0),
    0x07: ("rpm", 0.25, False, 0),
    0x08: ("km/h", 0.01, False, 0),
    0x09: ("km/h", 1, False, 0),
    0x0A: ("mV", 0.122, False, 0),
    0x0B: ("V", 0.001, False, 0),
    0x0C: ("V", 0.01, False, 0),
    0x0D: ("mA", 0.00390625, False, 0),
    0x0E: ("A", 0.001, False, 0),
    0x0F: ("A", 0.01, False, 0),
    0x10: ("ms", 1, False, 0),
    0x11: ("ms", 100, False, 0),
    0x12: ("s", 1, False, 0),
    0x13: ("mOhm", 1, False, 0),
    0x14: ("ohm", 1, False, 0),
    0x15: ("kOhm", 1, False, 0),
    0x16: ("degC", 0.1, False, -40),
    0x17: ("kPa", 0.01, False, 0),
    0x18: ("kPa", 0.0117, False, 0),
    0x19: ("kPa", 0.079, False, 0),
    0x1A: ("kPa", 1, False, 0),
    0x1B: ("kPa", 10, False, 0),
    0x1C: ("deg", 0.01, False, 0),
    0x1D: ("deg", 0.5, False, 0),
    0x1E: ("ratio", 0.0000305, False, 0),
    0x1F: ("ratio", 0.05, False, 0),
    0x20: ("ratio", 0.00390625, False, 0),
    0x21: ("mHz", 1, False, 0),
    0x22: ("Hz", 1, False, 0),
    0x23: ("kHz", 1, False, 0),
    0x24: ("count", 1, False, 0),
    0x25: ("km", 1, False, 0),
    0x26: ("mV/ms", 0.1, False, 0),
    0x27: ("g/s", 0.01, False, 0),
    0x28: ("g/s", 1, False, 0),
    0x29: ("Pa/s", 0.25, False, 0),
    0x2A: ("kg/h", 0.001, False, 0),
    0x2B: ("count", 1, False, 0),
    0x2C: ("g/cyl", 0.01, False, 0),
    0x2D: ("mg/stroke", 0.01, False, 0),
    0x2E: ("bool", 1, False, 0),
    0x2F: ("%", 0.01, False, 0),
    0x30: ("%", 0.001526, False, 0),
    0x31: ("L", 0.001, False, 0),
    0x32: ("in", 0.0000305, False, 0),
    0x33: ("ratio", 0.00024414, False, 0),
    0x34: ("min", 1, False, 0),
    0x35: ("ms", 10, False, 0),
    0x36: ("g", 0.01, False, 0),
    0x37: ("g", 0.1, False, 0),
    0x38: ("g", 1, False, 0),
    0x39: ("%", 0.01, False, -327.68),
    0x81: ("1", 1, True, 0),
    0x82: ("1", 0.1, True, 0),
    0x83: ("1", 0.01, True, 0),
    0x84: ("1", 0.001, True, 0),
    0x85: ("1", 0.0000305, True, 0),
    0x86: ("1", 0.000305, True, 0),
    0x8A: ("mV", 0.122, True, 0),
    0x8B: ("V", 0.001, True, 0),
    0x8C: ("V", 0.01, True, 0),
    0x8D: ("mA", 0.00390625, True, 0),
    0x8E: ("A", 0.001, True, 0),
    0x90: ("ms", 1, True, 0),
    0x96: ("degC", 0.1, True, 0),
    0x99: ("kPa", 0.1, True, 0),
    0x9C: ("deg", 0.01, True, 0),
    0x9D: ("deg", 0.5, True, 0),
    0xA8: ("g/s", 1, True, 0),
    0xA9: ("Pa/s", 0.25, True, 0),
    0xAF: ("%", 0.01, True, 0),
    0xB0: ("%", 0.003052, True, 0),
    0xB1: ("mV/s", 2, True, 0),
    0xFD: ("kPa", 0.001, True, 0),
    0xFE: ("Pa", 0.25, True, 0),
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
    unit, factor, signed, offset = definition
    if uasid == 0x2E and any(raw not in (0, 1) for raw in (value, minimum, maximum)):
        return None
    def decode(raw):
        return ((raw - 65536) if signed and raw >= 32768 else raw) * factor + offset

    decoded, low, high = map(decode, (value, minimum, maximum))
    relation = ("INVERTED_LIMITS" if low > high else "BELOW" if decoded < low
                else "ABOVE" if decoded > high else "WITHIN")
    return {"definition": "ISO15031-5:2006-ANNEX-E-SUBSET",
            "unit": unit, "value": decoded, "minimum": low, "maximum": high,
            "numeric_relation": relation, "monitor_verdict": None}


def from_steps(steps):
    """Independent CAN Mode 06 engineering projection from original bytes.

    The service status describes the diagnostic exchange, not the monitor.
    Preserve unknown scaling and malformed state encodings without claiming
    a monitor verdict. A legacy seven-byte response cannot enter this view.
    """
    result = []
    for step in steps:
        if step.get("status") != "PASSED":
            continue
        request, response = step.get("request_hex"), step.get("response_hex")
        if not isinstance(request, str) or not isinstance(response, str):
            continue
        if len(request) != 4 or len(response) > 8190:
            continue
        try:
            req, res = bytes.fromhex(request), bytes.fromhex(response)
        except ValueError:
            continue
        if len(req) != 2 or req[0] != 6 or req[1] % 32 == 0:
            continue
        if len(res) < 10 or res[0] != 0x46 or (len(res)-1) % 9:
            continue
        if any(res[i] != req[1] for i in range(1, len(res), 9)):
            continue
        for index in range(1, len(res), 9):
            mid, tid, uasid = res[index:index+3]
            value = int.from_bytes(res[index+3:index+5], "big")
            minimum = int.from_bytes(res[index+5:index+7], "big")
            maximum = int.from_bytes(res[index+7:index+9], "big")
            scaled = scale_record(uasid,value,minimum,maximum)
            result.append({
                "request_hex":req.hex().upper(), "record_index":(index-1)//9,
                "monitor_id":mid, "test_id":tid, "unit_scaling_id":uasid,
                "value_encoded":value, "minimum_encoded":minimum, "maximum_encoded":maximum,
                "scaled":scaled,
            })
    return result
