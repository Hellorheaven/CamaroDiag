"""Additional classic J1979 Mode 09 records, 2011 Digital Annex G8–G15.

Do not infer monitor success, emission compliance, ECU identity or OEM
authenticity from these counters and text. Historical CJ/1 shape is retained.
"""
from __future__ import annotations

_SPARK = ["catalyst-bank-1","catalyst-bank-2","oxygen-sensor-bank-1","oxygen-sensor-bank-2","egr-vvt","secondary-air","evap","secondary-oxygen-bank-1","secondary-oxygen-bank-2"]
_DIESEL = ["nmhc-catalyst","nox-scr","nox-adsorber","particulate-filter","exhaust-gas-sensor","egr-vvt","boost-pressure","fuel-system"]


def counter_report(kind: int, data: bytes) -> dict:
    """Decode a complete unsigned counter vector for an explicit InfoType."""
    sizes = {8: (32, 40), 11: (32, 36)}
    if kind not in sizes or len(data) not in sizes[kind]:
        raise ValueError("Incorrect IPT counter count for InfoType")
    values = [int.from_bytes(data[i:i+2], "big") for i in range(0, len(data), 2)]
    names = _SPARK if kind == 8 else _DIESEL
    monitors = []
    for index, name in enumerate(names[:(len(values)-2)//2]):
        numerator, denominator = values[2+2*index:4+2*index]
        monitors.append({"monitor": name, "numerator": numerator,
                         "denominator": denominator,
                         "ratio": numerator / denominator if denominator else None})
    return {"info_type": kind, "general_denominator": values[0],
            "ignition_cycles": values[1], "monitors": monitors,
            "counter_count": len(values), "regulatory_verdict": None}


def text_record(kind: int, data: bytes) -> str:
    if kind == 10:
        if len(data) != 20 or data[4] != 45:
            raise ValueError("Malformed ECU name")
        fields = [data[:4].rstrip(b"\x00"), data[5:].rstrip(b"\x00")]
        if any(not f or any(ch < 32 or ch > 126 for ch in f) for f in fields):
            raise ValueError("Invalid ECU name field")
        return "-".join(f.decode("ascii") for f in fields)
    if kind not in (13, 15) or len(data) != 17:
        raise ValueError("Invalid identification record width")
    field = data.lstrip(b"\x00")
    if not field or any(ch < 32 or ch > 126 for ch in field):
        raise ValueError("Invalid identification text or padding")
    return field.decode("ascii")


def can_info(kind: int, response: bytes) -> dict:
    """Decode a correlated reassembled CAN reply, not a legacy fragment."""
    if len(response) < 3 or response[:2] != bytes((0x49, kind)):
        raise ValueError("Unrelated Mode 09 response")
    count = response[2]
    if kind in (8, 11):
        if len(response) != 3+2*count:
            raise ValueError("Inconsistent IPT item count")
        return counter_report(kind, response[3:])
    if kind in (13, 15) and count == 1:
        return {"info_type": kind, "values": [text_record(kind, response[3:])]}
    raise ValueError("Unrecognized or invalid extended InfoType")


def legacy_info(kind: int, count: int, rows: list[bytes]) -> dict:
    """One selected source; no reassembly across responders or missing rows."""
    if count < 1 or count > 10 or len(rows) != count:
        raise ValueError("Incorrect legacy message count")
    for index, row in enumerate(rows, 1):
        if len(row) != 7 or row[:3] != bytes((0x49, kind, index)):
            raise ValueError("Invalid legacy identity fragment or sequence")
    data = b"".join(row[3:] for row in rows)
    if kind in (8, 11):
        return counter_report(kind, data)
    if kind == 10 and count == 5:
        return {"info_type": kind, "values": [text_record(kind, data)]}
    if kind in (13, 15) and count == 5 and data[:3] == bytes(3):
        return {"info_type": kind, "values": [text_record(kind, data[3:])]}
    raise ValueError("Unrecognized or invalid legacy extended InfoType")
