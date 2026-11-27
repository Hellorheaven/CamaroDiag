"""Legacy OBD monitor interpretation, independent of CAN UASID scaling.

Service 05 uses the historical ISO 15031-5:2006 Annex C standard TIDs.
Service 06 on J1850, ISO 9141 and KWP can use manufacturer-specific
definitions even in the nominal standard TID range. Keep its unsigned
words and limit direction, without assigning engineering units.
"""
from __future__ import annotations

_DEFINITIONS = {
    1: ("rich-lean-threshold", "V", .005),
    2: ("lean-rich-threshold", "V", .005),
    3: ("switch-low-voltage", "V", .005),
    4: ("switch-high-voltage", "V", .005),
    5: ("rich-lean-switch-time", "s", .004),
    6: ("lean-rich-switch-time", "s", .004),
    7: ("cycle-minimum-voltage", "V", .005),
    8: ("cycle-maximum-voltage", "V", .005),
    9: ("transition-interval", "s", .04),
    10: ("sensor-period", "s", .04),
}


def decode(request: bytes, response: bytes) -> dict | None:
    if not isinstance(request, bytes) or not isinstance(response, bytes):
        return None
    if len(request) not in (2,3) or request[0] not in (5,6):
        return None
    mode, tid = request[:2]
    if tid % 32 == 0 or len(request) != (3 if mode == 5 else 2):
        return None
    if mode == 5:
        mask = request[2]
        if mask not in (1,2,4,8,16,32,64,128):
            return None
        if len(response) != (4 if 1 <= tid <= 4 else 6) or response[:3] != bytes((0x45,tid,mask)):
            return None
        value = response[3]
        low, high = (None,None) if tid <= 4 else response[4:6]
        definition = _DEFINITIONS.get(tid)
        scaled = None
        if definition is not None:
            name, unit, factor = definition
            relation = ("NOT_APPLICABLE" if low is None else
                        "INVERTED_LIMITS" if low > high else
                        "BELOW" if value < low else "ABOVE" if value > high else "WITHIN")
            scaled = {"definition": "ISO15031-5:2006-ANNEX-C",
                      "name":name, "unit":unit, "value":value*factor,
                      "minimum":None if low is None else low*factor,
                      "maximum":None if high is None else high*factor,
                      "numeric_relation":relation}
        return {"mode":5,"test_id":tid,"sensor_mask":mask,
                "encoded_data_hex":response[3:].hex().upper(),
                "value_encoded":value,"minimum_encoded":low,"maximum_encoded":high,
                "scaled":scaled,"monitor_verdict":None}
    if len(response) != 7 or response[:2] != bytes((0x46,tid)):
        return None
    component = response[2]
    value = int.from_bytes(response[3:5],"big")
    limit = int.from_bytes(response[5:7],"big")
    minimum = bool(component & 128)
    return {"mode":6,"test_id":tid,"sensor_mask":None,
            "encoded_data_hex":response[2:].hex().upper(),
            "component_and_limit_encoded":component,"component_id":component & 127,
            "limit_type":"MINIMUM" if minimum else "MAXIMUM",
            "value_encoded":value,"limit_encoded":limit,
            "encoded_relation":"BELOW" if value < limit else "ABOVE" if value > limit else "EQUAL",
            "scaled":None,"monitor_verdict":None}


def project(steps):
    """Correlated application records only, preserving source and record order.

    Caller establishes physical source attribution; anonymous records remain
    independent. A malformed record cannot supply a partial result.
    """
    results = []
    for step in steps:
        if step.get("status") != "PASSED":
            continue
        request = step.get("request_hex")
        if not isinstance(request,str) or len(request) not in (4,6):
            continue
        records = step.get("accepted_records",step.get("records_hex"))
        if not isinstance(records,list) or len(records)>128:
            continue
        try:
            req = bytes.fromhex(request)
            decoded = []
            for index,raw in enumerate(records):
                if not isinstance(raw,str) or len(raw)>14:
                    break
                result = decode(req,bytes.fromhex(raw))
                if result is None:
                    break
                decoded.append({**result,"request_hex":request.upper(),"record_index":index})
            else:
                results.extend(decoded)
        except ValueError:
            continue
    return results
