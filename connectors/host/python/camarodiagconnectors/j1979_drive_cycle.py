"""Contextual Mode 01 fuel trims and drive-cycle monitor status.

SAE J1979DA October 2011: Figure B3, tables B7-B10, B46 and B66-B69.
A second trim byte is only meaningful with vehicle bank-layout evidence.
A selected ECU's oxygen sensor bitmap is sufficient positive evidence,
but its absence cannot prove absence on other ECUs. No global cache.
The current monitoring cycle is not readiness since DTC clearing.
"""
from __future__ import annotations

TRIMS = frozenset((6,7,8,9,0x55,0x56,0x57,0x58))
CONTINUOUS = ("misfire","fuel-system","components")
SPARK = ("catalyst","heated-catalyst","evaporative-system","secondary-air",None,
         "oxygen-sensor","oxygen-sensor-heater","egr-vvt")
DIESEL = ("nmhc-catalyst","nox-aftertreatment",None,"boost-pressure",None,
          "exhaust-gas-sensor","particulate-filter","egr-vvt")


def _bytes(value):
    if not isinstance(value,str) or len(value)>8190 or len(value)%2:
        return None
    try:
        return bytes.fromhex(value)
    except ValueError:
        return None


def _records(steps):
    """Only positive, exact service/identifier echoes are usable."""
    for row in steps:
        if row.get("status") not in ("RAW","PASSED"):
            continue
        request = _bytes(row.get("request_hex"))
        response = _bytes(row.get("response_hex"))
        if not request or not response:
            continue
        if request[0]==1 and len(request)==2 and response[:2]==bytes((0x41,request[1])):
            yield request,response[2:]
        elif (request[0]==2 and len(request)==3 and request[2]==0 and
              response[:3]==bytes((0x42,request[1],0))):
            yield request,response[3:]


def trim_width(pid, width):
    return pid in TRIMS and width in (1,2)


def decode_trim(request, data, layout):
    if not isinstance(request,bytes) or not isinstance(data,bytes):
        return None
    if (len(request) not in (2,3) or request[0] not in (1,2) or
        len(request)!=(3 if request[0]==2 else 2) or
        (request[0]==2 and request[2]!=0) or
        (layout is not None and (type(layout) is not int or not 0<=layout<=255))):
        return None
    pid=request[1]
    if not trim_width(pid,len(data)):
        return None
    secondary=pid>=0x55
    short=pid in (6,8,0x55,0x57)
    bank=1 if pid in (6,7,0x55,0x56) else 2
    prefix=("short" if short else "long")+"-term-"+("secondary-o2-" if secondary else "")+"fuel-trim-bank-"
    # Byte A is explicitly the lower bank in the parameter definition.
    # Byte B is not displayed as a number until bank support is established.
    bank_mask=0x30 if bank==1 else 0xC0
    upper_verified=layout is not None and bool(layout & bank_mask)
    signals=[]
    for index,raw in enumerate(data):
        verified=index==0 or upper_verified
        signals.append({"bank":bank+index*2,"name":prefix+str(bank+index*2),
                        "raw_encoded":raw,"number":(raw-128)*100/128 if verified else None,
                        "unit":"%","attribution":"DEFINED" if verified else "UNVERIFIED_UPPER_BANK"})
    return {"request_hex":request.hex().upper(),"frame":request[2] if len(request)==3 else None,
            "layout_1d":layout,"signals":signals,
            "upper_bank_verified":upper_verified if len(data)==2 else None}


def cycle_data(data, baseline):
    """Return all defined monitor slots with independent support/enable state.

    A missing PID01 is not evidence that any monitor is unsupported. When a
    monitor is unsupported or support is unknown, do not claim completion.
    Both raw bytes are preserved to make an inconsistent report inspectable.
    """
    if len(data)!=4 or data[0]!=0 or data[1]&0x80:
        return None
    _,b,c,d=data
    compression=bool(b&8)
    reserved=0x14 if compression else 0x10
    if (c|d)&reserved:
        return None
    valid_baseline=(baseline is not None and len(baseline)==4 and
                    bool(baseline[1]&8)==compression)
    support_b=baseline[1] if valid_baseline else None
    support_c=baseline[2] if valid_baseline else None
    monitors=[]
    def add(names,support,enabled,incomplete):
        for bit,name in enumerate(names):
            if name is None:
                continue
            mask=1<<bit
            supported=None if support is None else bool(support&mask)
            raw_enabled=bool(enabled&mask)
            raw_incomplete=bool(incomplete&mask)
            consistent=supported is not False or not (raw_enabled or raw_incomplete)
            monitors.append({"name":name,"supported":supported,
                             "enabled":raw_enabled if supported is True else None,
                             "complete":not raw_incomplete if supported is True else None,
                             "enabled_bit":raw_enabled,"incomplete_bit":raw_incomplete,
                             "consistent":consistent})
    add(CONTINUOUS,support_b,b,b>>4)
    add(DIESEL if compression else SPARK,support_c,c,d)
    return {"request_hex":"0141","raw_hex":data.hex().upper(),
            "baseline_raw_hex":baseline.hex().upper() if baseline is not None else None,
            "support_verified":valid_baseline,"compression_ignition":compression,
            "monitors":monitors,"consistent":all(m["consistent"] for m in monitors)}


def project(steps):
    """One physical target or one explicitly selected legacy responder only."""
    rows=list(_records(steps))
    layouts=[data[0] for req,data in rows if req==b"\x01\x1d" and len(data)==1]
    layout=layouts[0] if len(layouts)==1 else None
    baseline=[data for req,data in rows if req==b"\x01\x01" and len(data)==4]
    baseline=baseline[0] if len(baseline)==1 else None
    cycles=[data for req,data in rows if req==b"\x01\x41"]
    cycle=cycle_data(cycles[0],baseline) if len(cycles)==1 else None
    trims=[decode_trim(req,data,layout) for req,data in rows if req[1] in TRIMS]
    return {"drive_cycle":cycle,"fuel_trims":[t for t in trims if t is not None]}


def legacy_project(steps, source):
    if not isinstance(source,str) or len(source)!=2:
        return project([])
    rows=[]
    for step in steps:
        if step.get("selected_response_source")!=source:
            continue
        records=step.get("records_hex")
        if not isinstance(records,list) or len(records)!=1:
            continue
        rows.append({"request_hex":step.get("request_hex"),"response_hex":records[0],
                     "status":step.get("status")})
    return project(rows)


def trim_layout(steps):
    """Derive admission layout from the original selected ECU evidence."""
    rows=list(_records(steps))
    pages=[data for req,data in rows if req==b"\x01\x00"]
    values=[data for req,data in rows if req==b"\x01\x1d"]
    if len(pages)!=1 or len(pages[0])!=4:
        return {"state":"UNVERIFIED","raw_encoded":None}
    advertised=bool(pages[0][3]&0x08) # PID 1D, index 28
    if not advertised:
        return {"state":"NOT_ADVERTISED_BY_SELECTED_ECU" if not values else "UNVERIFIED",
                "raw_encoded":None}
    if len(values)!=1 or len(values[0])!=1:
        return {"state":"UNVERIFIED","raw_encoded":None}
    return {"state":"VERIFIED","raw_encoded":values[0][0]}


def live_signals(request,response,layout):
    """Finite, attributed values only; unverified upper bytes remain in raw."""
    if len(request)!=2 or request[0]!=1 or response[:2]!=bytes((0x41,request[1])):
        return None
    record=decode_trim(request,response[2:],layout.get("raw_encoded"))
    if record is None:
        return None
    return [{"pid":request[1],"name":s["name"],"number":s["number"],"unit":s["unit"],
             "raw_encoded":s["raw_encoded"]} for s in record["signals"] if s["number"] is not None]
