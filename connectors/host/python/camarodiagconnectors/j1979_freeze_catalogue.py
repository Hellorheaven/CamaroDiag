"""Explicit Mode 02 catalogue projection from original CF/1 evidence.

The operator supplies the reference and its independent digest. A separate
schema scopes definitions to individual freeze frames; Mode 01 definitions
are never applied to stored values, even for the same PID.
"""
from dataclasses import dataclass
from pathlib import Path
from .j1979_catalogue import (
    Definition, require, object_fields, integer, label, digest,
    MAX_CATALOGUE, MAX_REFERENCE, MAX_PROJECTION_ROWS,
)
from .j1979_freeze_native import inspect_file
from .recovery_bundle import _regular, _json

SCHEMA = "camarodiag-j1979-freeze-catalogue-v1"
PROJECTION = "camarodiag-j1979-native-freeze-catalogue-projection-v1"


@dataclass(frozen=True)
class FreezeCatalogue:
    edition: str
    reference_name: str
    reference_sha256: str
    definitions: tuple

    @classmethod
    def parse(cls, value):
        root = object_fields(value, ("schema", "edition", "reference_name", "reference_sha256", "frames"))
        require(root["schema"] == SCHEMA, "Explicit Mode 02 catalogue required")
        frames = root["frames"]
        require(isinstance(frames,list) and 1 <= len(frames) <= 8, "Invalid frame definitions")
        definitions = []
        seen = set()
        for entry in frames:
            frame = object_fields(entry, ("frame", "definitions"))
            index = integer(frame["frame"],0,255,"freeze frame")
            require(index not in seen,"Duplicate frame definition")
            seen.add(index)
            rows = frame["definitions"]
            require(isinstance(rows,list) and 1 <= len(rows) <= 247,"Invalid freeze PID definitions")
            parsed = tuple(Definition.parse(row) for row in rows)
            require(all(d.pid != 2 and d.width <= 4092 and d.protocols == ("CAN",) for d in parsed),
                    "Native freeze catalogue must scope measurements to CAN, excluding trigger DTC")
            require(len({d.pid for d in parsed}) == len(parsed),"Duplicate frame/PID definition")
            definitions.extend((index,d) for d in parsed)
        return cls(label(root["edition"],"edition"),label(root["reference_name"],"reference name"),
                   digest(root["reference_sha256"]),tuple(definitions))

    def inventory(self):
        return [{"frame":frame,"pid":d.pid,"payload_bytes":d.width,"protocols":list(d.protocols),
                 "reference":d.reference,"signals":[s.name for s in d.signals]}
                for frame,d in sorted(self.definitions,key=lambda x:(x[0],x[1].pid))]


def read_freeze_catalogue(path, reference, pin):
    path,reference=Path(path),Path(reference)
    require(path.resolve()!=reference.resolve(),"Distinct reference and catalogue required")
    raw,stamp=_regular(path,MAX_CATALOGUE)
    _,ref_stamp=_regular(reference,MAX_REFERENCE)
    cat=FreezeCatalogue.parse(_json(raw))
    require(cat.reference_sha256==digest(pin)==ref_stamp.digest,"Reference digest differs from pin")
    again,after=_regular(path,MAX_CATALOGUE)
    _,ref_after=_regular(reference,MAX_REFERENCE)
    require(raw==again and stamp==after and ref_stamp==ref_after,"Catalogue or reference changed")
    return cat,stamp.digest


def project_freeze(source, catalogue, catalogue_sha, expected_vin):
    require(source.get("schema")=="camarodiag-functional-j1979-native-freeze-v1" and
            source.get("capture_integrity_verified") is True,"Original independently replayed CF/1 required")
    profile=source["profile"]
    result=source["result"]
    require(profile["plan"]["expected_vin"]==expected_vin and result["plan"]["expected_vin"]==expected_vin,
            "CF/1 differs from independently supplied VIN")
    index={(f,d.pid):d for f,d in catalogue.definitions}
    identity=(result["identity_rechecked"] and result["initial_vin"]==expected_vin
              and result["final_vin"]==expected_vin)
    rows=[]
    frames=[]
    counts={}
    signals=0
    consumed=set()
    for frame in result["frames"]:
        number=frame["frame"]
        stable=bool(frame["trigger_stable"] and frame["trigger_dtc"] is not None and
                    frame["trigger_dtc"]==frame["final_trigger_dtc"])
        frames.append({"frame":number,"source_status":frame["status"],"trigger_dtc":frame["trigger_dtc"],
                       "final_trigger_dtc":frame["final_trigger_dtc"],"trigger_stable":stable,
                       "inventory_complete":frame["inventory_complete"]})
        for step_index in frame["measurement_steps"]:
            require(type(step_index) is int and 0<=step_index<len(result["steps"]) and step_index not in consumed,
                    "Invalid replay measurement index")
            consumed.add(step_index)
            step=result["steps"][step_index]
            req=bytes.fromhex(step["request_hex"])
            require(len(req)==3 and req[0]==2 and req[2]==number and req[1]!=2 and req[1]%32!=0 and
                    req[1] in frame["advertised_pids"],"Uncorrelated freeze measurement")
            pid=req[1]
            row={"frame":number,"pid":pid,"step_index":step_index,"source_status":step["status"],
                 "response_hex":step["response_hex"],"projection_status":None,"signals":[]}
            d=index.get((number,pid))
            if not identity:
                row["projection_status"]="IDENTITY_NOT_RECHECKED"
            elif not stable:
                row["projection_status"]="TRIGGER_NOT_STABLE"
            elif step["status"] not in ("RAW","PASSED"):
                row["projection_status"]="NO_CORRELATED_RAW_PAYLOAD"
            else:
                reply=bytes.fromhex(step["response_hex"])
                require(len(reply)>3 and reply[:3]==bytes((0x42,pid,number)),
                        "CF/1 replay response differs from frame/PID")
                if d is None:
                    row["projection_status"]="NO_DOCUMENTED_DEFINITION"
                elif len(reply)!=d.width+3:
                    row["projection_status"]="DOCUMENTED_WIDTH_MISMATCH"
                else:
                    signals+=len(d.signals)
                    require(signals<=MAX_PROJECTION_ROWS,"Projection signal limit")
                    row.update(projection_status="PROJECTED",reference=d.reference,
                               signals=[s.project(reply[3:]) for s in d.signals])
            counts[row["projection_status"]]=counts.get(row["projection_status"],0)+1
            rows.append(row)
            require(len(rows)<=MAX_PROJECTION_ROWS,"Projection row limit")
    return {"schema":PROJECTION,"reference_edition":catalogue.edition,
            "reference_sha256":catalogue.reference_sha256,"catalogue_sha256":catalogue_sha,
            "source_capture_sha256":source["capture_sha256"],"expected_vin_independently_supplied":True,
            "declared_vin":expected_vin,"channel":{"kind":"can","tx":int(profile["request_can_id"],16),
                "rx":int(profile["response_can_id"],16),"extended":profile["extended"]},
            "source_completed":result["completed"],"final_identity_verified":identity,
            "source_replay_verified":True,"native_capture_replayed":True,"transport_frames_replayed":False,
            "reference_authenticity_verified":False,"normative_compliance_verified":False,
            "physical_vehicle_verified":False,"authenticated":False,"permits_programming":False,
            "diagnostic_verdict":None,"frames":frames,"unvisited_frames":result["unvisited_frames"],
            "counts":counts,"rows":rows}


def build_freeze_projection(args):
    paths=(Path(args.catalogue),Path(args.reference),Path(args.capture))
    require(len({p.resolve() for p in paths})==3,"Distinct source inputs required")
    cat,sha=read_freeze_catalogue(paths[0],paths[1],args.reference_sha256)
    source=inspect_file(paths[2])
    result=project_freeze(source,cat,sha,args.vin)
    _,current_sha=read_freeze_catalogue(paths[0],paths[1],args.reference_sha256)
    require(sha==current_sha and source==inspect_file(paths[2]),"Projection inputs changed")
    return result
