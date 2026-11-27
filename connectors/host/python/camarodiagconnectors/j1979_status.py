"""Categorical SAE J1979 PID views, separate from numeric measurements.

Codes describe only the response. In particular regeneration status is
not an instruction to regenerate and is not proof of successful regeneration.
Reference: SAE J1979DA 2011, selected B tables.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Field:
    name: str
    offset: int
    shift: int
    mask: int
    codes: dict[int, str]
    support: int = -1


LOOP = {0: "RESERVED", 1: "OPEN_LOOP", 2: "CLOSED_LOOP", 3: "FAULT"}
FUEL_LOOP = {0: "UNAVAILABLE", 1: "OPEN_WARMUP", 2: "CLOSED_FEEDBACK",
             4: "OPEN_DRIVING", 8: "OPEN_FAULT", 16: "CLOSED_SENSOR_FAULT"}
ON = {0: "INACTIVE", 1: "ACTIVE"}
PRESENT = {0: "ABSENT", 1: "PRESENT"}
INDUCE = {0: "INACTIVE", 1: "ENABLED", 2: "ACTIVE", 3: "UNSUPPORTED"}
FUEL = dict(enumerate(("UNAVAILABLE", "GASOLINE", "METHANOL", "ETHANOL",
                       "DIESEL", "LPG", "CNG", "PROPANE", "ELECTRIC",
                       "BIFUEL_GASOLINE", "BIFUEL_METHANOL", "BIFUEL_ETHANOL",
                       "BIFUEL_LPG", "BIFUEL_CNG", "BIFUEL_PROPANE", "BIFUEL_ELECTRIC",
                       "BIFUEL_COMBINED", "HYBRID_GASOLINE", "HYBRID_ETHANOL",
                       "HYBRID_DIESEL", "HYBRID_ELECTRIC", "HYBRID_COMBINED",
                       "HYBRID_REGENERATION", "BIFUEL_DIESEL")))
OBD = {1:"OBD_II",2:"OBD",3:"OBD_AND_OBD_II",4:"OBD_I",5:"NOT_COMPLIANT",
       6:"EOBD",7:"EOBD_OBD_II",8:"EOBD_OBD",9:"EOBD_OBD_OBD_II",
       10:"JOBD",11:"JOBD_OBD_II",12:"JOBD_EOBD",13:"JOBD_EOBD_OBD_II",
       17:"EMD",18:"EMD_PLUS",19:"HD_OBD_PARTIAL",20:"HD_OBD",21:"WWH_OBD",
       23:"HD_EOBD_I_NO_NOX",24:"HD_EOBD_I_NOX",25:"HD_EOBD_II_NO_NOX",
       26:"HD_EOBD_II_NOX",28:"BR_OBD_I",29:"BR_OBD_II",30:"KOBD",
       31:"INDIA_OBD_I",32:"INDIA_OBD_II",33:"HD_EOBD_VI"}
MI = {0:"OFF",1:"ON_DEMAND",2:"SHORT",3:"CONTINUOUS",14:"ERROR",15:"UNAVAILABLE"}

# A definition contains exact byte length, reserved-bit masks and state fields.
# Reserved values within an enumeration remain visible as UNKNOWN.
DEFINITIONS = {
    0x03: (2, (), (Field("fuel-system-1",0,0,255,FUEL_LOOP),
                   Field("fuel-system-2",1,0,255,FUEL_LOOP))),
    0x12: (1, (), (Field("secondary-air",0,0,255,
                       {1:"UPSTREAM",2:"DOWNSTREAM",4:"ATMOSPHERE_OR_OFF",8:"DIAGNOSTIC"}),)),
    0x13: (1, (), tuple(Field(f"oxygen-bank-{i//4+1}-sensor-{i%4+1}",0,i,1,PRESENT)
                         for i in range(8))),
    0x1C: (1, (), (Field("declared-obd-standard",0,0,255,OBD),)),
    0x1D: (1, (), tuple(Field(f"oxygen-bank-{i//2+1}-sensor-{i%2+1}",0,i,1,PRESENT)
                         for i in range(8))),
    0x1E: (1, ((0,0xFE),), (Field("power-take-off",0,0,1,ON),)),
    0x51: (1, (), (Field("current-fuel-type",0,0,255,FUEL),)),
    0x70: (10, ((0,0xC0),(9,0xF0)), (Field("boost-control-a",9,0,3,LOOP,2),
                                        Field("boost-control-b",9,2,3,LOOP,5))),
    0x71: (6, ((0,0xC0),(5,0xF0)), (Field("vgt-control-a",5,0,3,LOOP,2),
                                       Field("vgt-control-b",5,2,3,LOOP,5))),
    0x7D: (1, ((0,0xF0),), tuple(Field("nox-nte-"+name,0,i,1,ON) for i,name in
              enumerate(("inside","outside","carve-out","deficiency")))),
    0x7E: (1, ((0,0xF0),), tuple(Field("pm-nte-"+name,0,i,1,ON) for i,name in
              enumerate(("inside","outside","carve-out","deficiency")))),
    0x8B: (7, ((0,0x80),(1,0xF0)), (Field("dpf-regeneration",1,0,1,ON,0),
                                     Field("dpf-regeneration-type",1,1,1,{0:"PASSIVE",1:"ACTIVE"},1),
                                     Field("nox-adsorber-regeneration",1,2,1,ON,2),
                                     Field("nox-adsorber-desulfurization",1,3,1,ON,3))),
    0x8F: (7, ((0,0xF0),(1,0xFC),(4,0xFC)), tuple(
        Field(f"pm-sensor-bank-{bank}-{name}",offset,bit,1,ON,support)
        for bank,offset,support in ((1,1,0),(2,4,2))
        for name,bit in (("measuring",0),("regenerating",1)))),
    0x90: (3, ((0,0x80),), (Field("vehicle-mi",0,2,15,MI),
                             Field("emissions-readiness",0,6,1,{0:"COMPLETE",1:"INCOMPLETE"}),
                             Field("mi-display-strategy",0,0,3,{0:"NONDISCRIMINATORY",1:"DISCRIMINATORY",3:"UNAVAILABLE"}))),
    0x91: (5, ((0,0xF0),), (Field("ecu-mi",0,0,15,MI),)),
    0x92: (2, (), tuple(Field(f"fuel-{name}-{bank}",1,(bank-1)*4+i,1,
                             {0:"NOT_CLOSED_LOOP",1:"CLOSED_LOOP"},(bank-1)*4+i)
                        for bank in (1,2) for i,name in enumerate(
                            ("pressure","quantity","timing","idle-balance")))),
    0x94: (12, ((0,0xC0),(1,0x80)), (Field("nox-warning",1,0,1,ON,0),
          Field("inducement-level-1",1,1,3,INDUCE,0),
          Field("inducement-level-2",1,3,3,INDUCE,0),
          Field("inducement-level-3",1,5,3,INDUCE,0))),
}


def width(pid):
    entry = DEFINITIONS.get(pid)
    return entry[0] if entry else None


def decode_data(pid: int, data: bytes):
    entry = DEFINITIONS.get(pid)
    if entry is None or len(data) != entry[0]:
        return None
    _, reserved, fields = entry
    if any(data[offset] & mask for offset,mask in reserved):
        return None
    states = []
    for field in fields:
        if field.support >= 0 and not data[0] & (1 << field.support):
            continue
        raw = (data[field.offset] >> field.shift) & field.mask
        states.append({"pid":pid, "name":field.name, "raw_encoded":raw,
                       "state":field.codes.get(raw,"UNKNOWN")})
    return states


def decode_response(request: bytes, response: bytes):
    if len(request) != 2 or request[0] != 1 or response[:2] != bytes((0x41,request[1])):
        return None
    return decode_data(request[1],response[2:])


def from_steps(steps):
    result = []
    for step in steps:
        if step.get("status") not in ("RAW","PASSED"):
            continue
        try:
            req = bytes.fromhex(step["request_hex"])
            res = bytes.fromhex(step["response_hex"])
        except (ValueError,TypeError,KeyError):
            continue
        states = decode_response(req,res)
        if states is not None:
            result.append({"request_hex":step["request_hex"],"states":states})
    return result
