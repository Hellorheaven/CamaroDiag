"""Selected multi-signal SAE J1979 Mode 01 definitions.
Engineering values are derived from correlated application bytes, never from
unadvertised sensor slots. Unknown revisions remain raw. Not OBDonUDS.
Reference: J1979DA B tables, corroborated by DashLogic J1979 PID listing.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Field:
    name: str
    unit: str
    offset: int
    width: int
    multiplier: int
    divisor: int
    bias: int
    bit: int
    signed: bool
    unavailable: int | None


@dataclass(frozen=True)
class Definition:
    width: int
    support_mask: int
    fields: tuple[Field, ...]


DEFINITIONS = {
    0x14: Definition(2, 0x00, (
        Field("o2-sensor-1-voltage", "V", 0, 1, 1, 200, 0, -1, False, None),
        Field("o2-sensor-1-short-term-trim", "%", 1, 1, 100, 128, -100, -1, False, 255),
    )),
    0x15: Definition(2, 0x00, (
        Field("o2-sensor-2-voltage", "V", 0, 1, 1, 200, 0, -1, False, None),
        Field("o2-sensor-2-short-term-trim", "%", 1, 1, 100, 128, -100, -1, False, 255),
    )),
    0x16: Definition(2, 0x00, (
        Field("o2-sensor-3-voltage", "V", 0, 1, 1, 200, 0, -1, False, None),
        Field("o2-sensor-3-short-term-trim", "%", 1, 1, 100, 128, -100, -1, False, 255),
    )),
    0x17: Definition(2, 0x00, (
        Field("o2-sensor-4-voltage", "V", 0, 1, 1, 200, 0, -1, False, None),
        Field("o2-sensor-4-short-term-trim", "%", 1, 1, 100, 128, -100, -1, False, 255),
    )),
    0x18: Definition(2, 0x00, (
        Field("o2-sensor-5-voltage", "V", 0, 1, 1, 200, 0, -1, False, None),
        Field("o2-sensor-5-short-term-trim", "%", 1, 1, 100, 128, -100, -1, False, 255),
    )),
    0x19: Definition(2, 0x00, (
        Field("o2-sensor-6-voltage", "V", 0, 1, 1, 200, 0, -1, False, None),
        Field("o2-sensor-6-short-term-trim", "%", 1, 1, 100, 128, -100, -1, False, 255),
    )),
    0x1A: Definition(2, 0x00, (
        Field("o2-sensor-7-voltage", "V", 0, 1, 1, 200, 0, -1, False, None),
        Field("o2-sensor-7-short-term-trim", "%", 1, 1, 100, 128, -100, -1, False, 255),
    )),
    0x1B: Definition(2, 0x00, (
        Field("o2-sensor-8-voltage", "V", 0, 1, 1, 200, 0, -1, False, None),
        Field("o2-sensor-8-short-term-trim", "%", 1, 1, 100, 128, -100, -1, False, 255),
    )),
    0x24: Definition(4, 0x00, (
        Field("wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-1-voltage", "V", 2, 2, 1, 8192, 0, -1, False, None),
    )),
    0x25: Definition(4, 0x00, (
        Field("wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-2-voltage", "V", 2, 2, 1, 8192, 0, -1, False, None),
    )),
    0x26: Definition(4, 0x00, (
        Field("wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-3-voltage", "V", 2, 2, 1, 8192, 0, -1, False, None),
    )),
    0x27: Definition(4, 0x00, (
        Field("wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-4-voltage", "V", 2, 2, 1, 8192, 0, -1, False, None),
    )),
    0x28: Definition(4, 0x00, (
        Field("wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-5-voltage", "V", 2, 2, 1, 8192, 0, -1, False, None),
    )),
    0x29: Definition(4, 0x00, (
        Field("wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-6-voltage", "V", 2, 2, 1, 8192, 0, -1, False, None),
    )),
    0x2A: Definition(4, 0x00, (
        Field("wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-7-voltage", "V", 2, 2, 1, 8192, 0, -1, False, None),
    )),
    0x2B: Definition(4, 0x00, (
        Field("wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-8-voltage", "V", 2, 2, 1, 8192, 0, -1, False, None),
    )),
    0x34: Definition(4, 0x00, (
        Field("wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-1-current", "mA", 2, 2, 1, 256, -128, -1, False, None),
    )),
    0x35: Definition(4, 0x00, (
        Field("wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-2-current", "mA", 2, 2, 1, 256, -128, -1, False, None),
    )),
    0x36: Definition(4, 0x00, (
        Field("wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-3-current", "mA", 2, 2, 1, 256, -128, -1, False, None),
    )),
    0x37: Definition(4, 0x00, (
        Field("wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-4-current", "mA", 2, 2, 1, 256, -128, -1, False, None),
    )),
    0x38: Definition(4, 0x00, (
        Field("wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-5-current", "mA", 2, 2, 1, 256, -128, -1, False, None),
    )),
    0x39: Definition(4, 0x00, (
        Field("wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-6-current", "mA", 2, 2, 1, 256, -128, -1, False, None),
    )),
    0x3A: Definition(4, 0x00, (
        Field("wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-7-current", "mA", 2, 2, 1, 256, -128, -1, False, None),
    )),
    0x3B: Definition(4, 0x00, (
        Field("wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, False, None),
        Field("wideband-o2-sensor-8-current", "mA", 2, 2, 1, 256, -128, -1, False, None),
    )),
    0x64: Definition(5, 0x00, (
        Field("engine-torque-idle", "%", 0, 1, 1, 1, -125, -1, False, None),
        Field("engine-torque-point-2", "%", 1, 1, 1, 1, -125, -1, False, None),
        Field("engine-torque-point-3", "%", 2, 1, 1, 1, -125, -1, False, None),
        Field("engine-torque-point-4", "%", 3, 1, 1, 1, -125, -1, False, None),
        Field("engine-torque-point-5", "%", 4, 1, 1, 1, -125, -1, False, None),
    )),
    0x66: Definition(5, 0x03, (
        Field("maf-a", "g/s", 1, 2, 1, 32, 0, 0, False, None),
        Field("maf-b", "g/s", 3, 2, 1, 32, 0, 1, False, None),
    )),
    0x67: Definition(3, 0x03, (
        Field("coolant-1", "degC", 1, 1, 1, 1, -40, 0, False, None),
        Field("coolant-2", "degC", 2, 1, 1, 1, -40, 1, False, None),
    )),
    0x68: Definition(7, 0x3F, (
        Field("intake-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, False, None),
        Field("intake-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, False, None),
        Field("intake-temperature-bank-1-sensor-3", "degC", 3, 1, 1, 1, -40, 2, False, None),
        Field("intake-temperature-bank-2-sensor-1", "degC", 4, 1, 1, 1, -40, 3, False, None),
        Field("intake-temperature-bank-2-sensor-2", "degC", 5, 1, 1, 1, -40, 4, False, None),
        Field("intake-temperature-bank-2-sensor-3", "degC", 6, 1, 1, 1, -40, 5, False, None),
    )),
    0x69: Definition(7, 0x3F, (
        Field("egr-a-command", "%", 1, 1, 100, 255, 0, 0, False, None),
        Field("egr-a-actual", "%", 2, 1, 100, 255, 0, 1, False, None),
        Field("egr-a-error", "%", 3, 1, 100, 128, -100, 2, False, None),
        Field("egr-b-command", "%", 4, 1, 100, 255, 0, 3, False, None),
        Field("egr-b-actual", "%", 5, 1, 100, 255, 0, 4, False, None),
        Field("egr-b-error", "%", 6, 1, 100, 128, -100, 5, False, None),
    )),
    0x6A: Definition(5, 0x0F, (
        Field("diesel-intake-a-command", "%", 1, 1, 100, 255, 0, 0, False, None),
        Field("diesel-intake-a-actual", "%", 2, 1, 100, 255, 0, 1, False, None),
        Field("diesel-intake-b-command", "%", 3, 1, 100, 255, 0, 2, False, None),
        Field("diesel-intake-b-actual", "%", 4, 1, 100, 255, 0, 3, False, None),
    )),
    0x6C: Definition(5, 0x0F, (
        Field("throttle-a-command", "%", 1, 1, 100, 255, 0, 0, False, None),
        Field("throttle-a-actual", "%", 2, 1, 100, 255, 0, 1, False, None),
        Field("throttle-b-command", "%", 3, 1, 100, 255, 0, 2, False, None),
        Field("throttle-b-actual", "%", 4, 1, 100, 255, 0, 3, False, None),
    )),
    0x6D: Definition(11, 0x3F, (
        Field("fuel-rail-a-command", "kPa", 1, 2, 10, 1, 0, 0, False, None),
        Field("fuel-rail-a-pressure", "kPa", 3, 2, 10, 1, 0, 1, False, None),
        Field("fuel-rail-a-temperature", "degC", 5, 1, 1, 1, -40, 2, False, None),
        Field("fuel-rail-b-command", "kPa", 6, 2, 10, 1, 0, 3, False, None),
        Field("fuel-rail-b-pressure", "kPa", 8, 2, 10, 1, 0, 4, False, None),
        Field("fuel-rail-b-temperature", "degC", 10, 1, 1, 1, -40, 5, False, None),
    )),
    0x6E: Definition(9, 0x0F, (
        Field("injection-pressure-a-command", "kPa", 1, 2, 10, 1, 0, 0, False, None),
        Field("injection-pressure-a-actual", "kPa", 3, 2, 10, 1, 0, 1, False, None),
        Field("injection-pressure-b-command", "kPa", 5, 2, 10, 1, 0, 2, False, None),
        Field("injection-pressure-b-actual", "kPa", 7, 2, 10, 1, 0, 3, False, None),
    )),
    0x70: Definition(10, 0x3F, (
        Field("boost-a-command", "kPa", 1, 2, 1, 32, 0, 0, False, None),
        Field("boost-a-actual", "kPa", 3, 2, 1, 32, 0, 1, False, None),
        Field("boost-b-command", "kPa", 5, 2, 1, 32, 0, 3, False, None),
        Field("boost-b-actual", "kPa", 7, 2, 1, 32, 0, 4, False, None),
    )),
    0x71: Definition(6, 0x3F, (
        Field("vgt-a-command", "%", 1, 1, 100, 255, 0, 0, False, None),
        Field("vgt-a-actual", "%", 2, 1, 100, 255, 0, 1, False, None),
        Field("vgt-b-command", "%", 3, 1, 100, 255, 0, 3, False, None),
        Field("vgt-b-actual", "%", 4, 1, 100, 255, 0, 4, False, None),
    )),
    0x72: Definition(5, 0x0F, (
        Field("wastegate-a-command", "%", 1, 1, 100, 255, 0, 0, False, None),
        Field("wastegate-a-actual", "%", 2, 1, 100, 255, 0, 1, False, None),
        Field("wastegate-b-command", "%", 3, 1, 100, 255, 0, 2, False, None),
        Field("wastegate-b-actual", "%", 4, 1, 100, 255, 0, 3, False, None),
    )),
    0x73: Definition(5, 0x03, (
        Field("exhaust-pressure-bank-1", "kPa", 1, 2, 1, 100, 0, 0, False, None),
        Field("exhaust-pressure-bank-2", "kPa", 3, 2, 1, 100, 0, 1, False, None),
    )),
    0x74: Definition(5, 0x03, (
        Field("turbo-a-rpm", "rpm", 1, 2, 10, 1, 0, 0, False, None),
        Field("turbo-b-rpm", "rpm", 3, 2, 10, 1, 0, 1, False, None),
    )),
    0x75: Definition(7, 0x0F, (
        Field("turbo-a-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, False, None),
        Field("turbo-a-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, False, None),
        Field("turbo-a-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, False, None),
        Field("turbo-a-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, False, None),
    )),
    0x76: Definition(7, 0x0F, (
        Field("turbo-b-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, False, None),
        Field("turbo-b-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, False, None),
        Field("turbo-b-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, False, None),
        Field("turbo-b-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, False, None),
    )),
    0x77: Definition(5, 0x0F, (
        Field("charge-air-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, False, None),
        Field("charge-air-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, False, None),
        Field("charge-air-temperature-bank-2-sensor-1", "degC", 3, 1, 1, 1, -40, 2, False, None),
        Field("charge-air-temperature-bank-2-sensor-2", "degC", 4, 1, 1, 1, -40, 3, False, None),
    )),
    0x78: Definition(9, 0x0F, (
        Field("egt-bank-1-sensor-1", "degC", 1, 2, 1, 10, -40, 0, False, None),
        Field("egt-bank-1-sensor-2", "degC", 3, 2, 1, 10, -40, 1, False, None),
        Field("egt-bank-1-sensor-3", "degC", 5, 2, 1, 10, -40, 2, False, None),
        Field("egt-bank-1-sensor-4", "degC", 7, 2, 1, 10, -40, 3, False, None),
    )),
    0x79: Definition(9, 0x0F, (
        Field("egt-bank-2-sensor-1", "degC", 1, 2, 1, 10, -40, 0, False, None),
        Field("egt-bank-2-sensor-2", "degC", 3, 2, 1, 10, -40, 1, False, None),
        Field("egt-bank-2-sensor-3", "degC", 5, 2, 1, 10, -40, 2, False, None),
        Field("egt-bank-2-sensor-4", "degC", 7, 2, 1, 10, -40, 3, False, None),
    )),
    0x7A: Definition(7, 0x07, (
        Field("dpf-bank-1-delta", "kPa", 1, 2, 1, 100, 0, 0, True, None),
        Field("dpf-bank-1-inlet", "kPa", 3, 2, 1, 100, 0, 1, False, None),
        Field("dpf-bank-1-outlet", "kPa", 5, 2, 1, 100, 0, 2, False, None),
    )),
    0x7B: Definition(7, 0x07, (
        Field("dpf-bank-2-delta", "kPa", 1, 2, 1, 100, 0, 0, True, None),
        Field("dpf-bank-2-inlet", "kPa", 3, 2, 1, 100, 0, 1, False, None),
        Field("dpf-bank-2-outlet", "kPa", 5, 2, 1, 100, 0, 2, False, None),
    )),
    0x7C: Definition(9, 0x0F, (
        Field("dpf-bank-1-inlet-temperature", "degC", 1, 2, 1, 10, -40, 0, False, None),
        Field("dpf-bank-1-outlet-temperature", "degC", 3, 2, 1, 10, -40, 1, False, None),
        Field("dpf-bank-2-inlet-temperature", "degC", 5, 2, 1, 10, -40, 2, False, None),
        Field("dpf-bank-2-outlet-temperature", "degC", 7, 2, 1, 10, -40, 3, False, None),
    )),
    0x7F: Definition(13, 0x07, (
        Field("engine-total-runtime", "s", 1, 4, 1, 1, 0, 0, False, None),
        Field("engine-idle-runtime", "s", 5, 4, 1, 1, 0, 1, False, None),
        Field("engine-pto-runtime", "s", 9, 4, 1, 1, 0, 2, False, None),
    )),
    0x98: Definition(9, 0x0F, (
        Field("egt-bank-1-sensor-5", "degC", 1, 2, 1, 10, -40, 0, False, None),
        Field("egt-bank-1-sensor-6", "degC", 3, 2, 1, 10, -40, 1, False, None),
        Field("egt-bank-1-sensor-7", "degC", 5, 2, 1, 10, -40, 2, False, None),
        Field("egt-bank-1-sensor-8", "degC", 7, 2, 1, 10, -40, 3, False, None),
    )),
    0x99: Definition(9, 0x0F, (
        Field("egt-bank-2-sensor-5", "degC", 1, 2, 1, 10, -40, 0, False, None),
        Field("egt-bank-2-sensor-6", "degC", 3, 2, 1, 10, -40, 1, False, None),
        Field("egt-bank-2-sensor-7", "degC", 5, 2, 1, 10, -40, 2, False, None),
        Field("egt-bank-2-sensor-8", "degC", 7, 2, 1, 10, -40, 3, False, None),
    )),
}


def width(pid: int) -> int | None:
    definition = DEFINITIONS.get(pid)
    return definition.width if definition else None


def decode_data(pid: int, data: bytes) -> list[dict] | None:
    """None means unknown or malformed; [] is a valid no-sensor record."""
    definition = DEFINITIONS.get(pid)
    if definition is None or not isinstance(data, bytes) or len(data) != definition.width:
        return None
    if definition.support_mask and data[0] & ~definition.support_mask:
        return None
    measurements = []
    for field in definition.fields:
        if field.bit >= 0 and not data[0] & (1 << field.bit):
            continue
        raw = int.from_bytes(data[field.offset:field.offset+field.width], "big")
        if raw == field.unavailable:
            continue
        encoded = raw
        if field.signed and raw >= 1 << (field.width*8-1):
            encoded -= 1 << (field.width*8)
        measurements.append({"pid": pid, "name": field.name, "unit": field.unit,
                             "number": encoded * field.multiplier / field.divisor + field.bias,
                             "raw_encoded": raw})
    return measurements


def decode_response(request: bytes, response: bytes) -> list[dict] | None:
    # Mode 02 applicability varies by definition/revision. Do not assume it.
    if len(request) != 2 or request[0] != 1 or response[:2] != bytes((0x41, request[1])):
        return None
    return decode_data(request[1], response[2:])


def from_steps(steps: list[dict]) -> list[dict]:
    """Historical RAW/PASSED statuses are not rewritten by a derived view."""
    result = []
    for step in steps:
        if step.get("status") not in ("RAW", "PASSED"):
            continue
        try:
            req = bytes.fromhex(step["request_hex"])
            res = bytes.fromhex(step["response_hex"])
        except (ValueError, TypeError, KeyError):
            continue
        fields = decode_response(req, res)
        if fields is not None:
            result.append({"request_hex": step["request_hex"], "signals": fields})
    return result
