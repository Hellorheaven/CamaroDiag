"""Offline recovery bundle inspector for Android and Windows E39 evidence.

Never communicates with an ECU, edits a journal, creates a recovery image,
or authorizes programming. Hashes bind local bytes, not provenance.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass,asdict
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct

MAX_JOURNAL=8*1024*1024
MAX_ANDROID_BLOCK=64*1024*1024
MAX_WINDOWS_BLOCK=1024*1024
MAX_TOTAL=512*1024*1024
MAX_SECTORS=4096
E39_IMAGE_BYTES=0x300000
_REGIONS={"CALIBRATION","PROGRAM_FLASH","DATA_FLASH","EXTERNAL_EEPROM","OTHER"}
_SHA=re.compile(r"[0-9a-fA-F]{64}")
_VIN=re.compile(r"[A-HJ-NPR-Z0-9]{17}")


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _digest(value):
    if not isinstance(value,str) or not _SHA.fullmatch(value):
        raise ValueError("Invalid SHA-256 field")
    return value.lower()


def _int(value,low,high):
    if type(value) is not int or not low<=value<=high:
        raise ValueError("Invalid integer field")
    return value


def _unique(pairs):
    result={}
    for name,value in pairs:
        if name in result:
            raise ValueError("Duplicate JSON property")
        result[name]=value
    return result


def _json(raw):
    def reject(value):
        raise ValueError("Nonfinite JSON number")
    return json.loads(raw.decode("utf-8","strict"),object_pairs_hook=_unique,parse_constant=reject)


def _identity(info):
    return (info.st_dev,info.st_ino,info.st_mode,info.st_size,info.st_mtime_ns,info.st_ctime_ns)


@dataclass(frozen=True)
class Snapshot:
    identity: tuple
    digest: str
    size: int


def _regular(path,maximum,active=lambda:None):
    """Read a bounded exact file through a held descriptor, then check path.
    Revalidation catches ordinary concurrent changes but not all hostile races.
    """
    active()
    before=path.lstat()
    if not stat.S_ISREG(before.st_mode) or not 0<before.st_size<=maximum:
        raise ValueError("Expected regular nonempty bounded file: "+path.name)
    flags=os.O_RDONLY|getattr(os,"O_BINARY",0)|getattr(os,"O_NOFOLLOW",0)|getattr(os,"O_NONBLOCK",0)
    fd=os.open(path,flags)
    try:
        info=os.fstat(fd)
        if _identity(info)!=_identity(before):
            raise ValueError("File changed before read: "+path.name)
        chunks=bytearray()
        digest=hashlib.sha256()
        left=info.st_size
        while left:
            active()
            data=os.read(fd,min(left,1024*1024))
            if not data:
                raise ValueError("File truncated during read: "+path.name)
            chunks.extend(data);digest.update(data);left-=len(data)
        if os.read(fd,1) or _identity(os.fstat(fd))!=_identity(info) or _identity(path.lstat())!=_identity(info):
            raise ValueError("File changed during read: "+path.name)
        return bytes(chunks),Snapshot(_identity(info),digest.hexdigest(),info.st_size)
    finally:
        os.close(fd)


def _directory(path,count,active):
    active()
    before=path.lstat()
    if not stat.S_ISDIR(before.st_mode):
        raise ValueError("Missing or linked original-sector directory")
    names=[]
    with os.scandir(path) as entries:
        for entry in entries:
            active()
            if len(names)>=count or len(names)>=MAX_SECTORS:
                raise ValueError("Unexpected original-sector file")
            if not entry.is_file(follow_symlinks=False):
                raise ValueError("Linked or nonregular sector entry")
            names.append(entry.name)
    if len(names)!=count or len(set(names))!=count or _identity(path.lstat())!=_identity(before):
        raise ValueError("Sector inventory incomplete or changed")
    return tuple(sorted(names)),_identity(before)


def snapshot_digest(journal_sha,envelope_hashes):
    """Canonical Windows/Android SHA256_JOURNAL_AND_ENVELOPES_V1."""
    digest=hashlib.sha256(bytes.fromhex(_digest(journal_sha)))
    for name,value in sorted(envelope_hashes.items()):
        encoded=name.encode("utf-8")
        if not encoded or "/" in name or "\\" in name:
            raise ValueError("Invalid sector evidence filename")
        digest.update(struct.pack("<I",len(encoded)))
        digest.update(encoded)
        digest.update(bytes.fromhex(_digest(value)))
    return digest.hexdigest().upper()


def _header_field(value):
    if not re.fullmatch(r"[A-Za-z0-9_-]+",value):
        raise ValueError("Invalid Android journal identity encoding")
    try:
        raw=base64.b64decode(value+"="*((-len(value))%4),altchars=b"-_",validate=True)
        decoded=raw.decode("utf-8","strict")
    except (ValueError,UnicodeError) as error:
        raise ValueError("Invalid Android journal identity encoding") from error
    if not decoded.strip() or base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")!=value:
        raise ValueError("Noncanonical Android journal identity")
    return decoded


@dataclass(frozen=True)
class AndroidIdentity:
    family:str
    controller:str
    firmware:str
    vin:str

    def validate(self):
        if any(not isinstance(x,str) or not x.strip() or len(x.encode("utf-8"))>65535
               for x in (self.family,self.controller,self.firmware)) or not _VIN.fullmatch(self.vin):
            raise ValueError("Explicit Android family/controller/firmware/VIN required")
        return self


def _android_journal(raw,identity):
    identity.validate()
    if not raw.endswith(b"\n"):
        raise ValueError("Incomplete Android journal record")
    lines=raw.decode("utf-8","strict")[:-1].split("\n")
    header=lines[0].split("\t")
    if len(header)!=5 or header[0]!="CAMARODIAG-ECU-JOURNAL-V1":
        raise ValueError("Unsupported Android journal schema")
    found=AndroidIdentity(*(_header_field(x) for x in header[1:4]),header[4])
    if found!=identity:
        raise ValueError("Journal identity does not match declared target")
    tail=_sha(lines[0].encode("utf-8"))
    baseline=[]
    completed=0
    readbacks=0
    flight=None
    erase=False
    done=False
    stage=None
    for index,line in enumerate(lines[1:],1):
        fields=line.split("\t")
        if len(fields)!=8 or fields[0]!=str(index) or not re.fullmatch(r"-?[0-9]+",fields[1]) or \
                not re.fullmatch(r"[0-9]+",fields[4]):
            raise ValueError("Invalid Android journal row")
        _int(int(fields[1]),-(1<<63),(1<<63)-1)
        address=_int(int(fields[4]),0,(1<<63)-1)
        stage,region=fields[2:4]
        if region not in _REGIONS:
            raise ValueError("Unknown Android ECU region")
        before,after=_digest(fields[5]),_digest(fields[6])
        expected_hash=_sha((tail+"\t"+"\t".join(fields[:7])).encode("utf-8"))
        if fields[7]!=expected_hash:
            raise ValueError("Android journal hash-chain mismatch")
        tail=expected_hash
        sector=(region,address,before,after)
        if done:
            raise ValueError("Record after completed Android transaction")
        if stage=="BASELINE_CHECKED":
            if erase or flight is not None or len(baseline)>=MAX_SECTORS or \
                    baseline and (region!=baseline[0][0] or address<=baseline[-1][1]):
                raise ValueError("Invalid Android original-sector sequence")
            baseline.append(sector)
        elif stage=="BEFORE_ERASE":
            if flight is not None or completed>=len(baseline) or sector!=baseline[completed]:
                raise ValueError("Erase intent not tied to next verified original")
            erase=True;flight=sector
        elif stage=="VERIFIED_AFTER_WRITE":
            if flight!=sector:
                raise ValueError("Write receipt not tied to active sector")
            completed+=1;flight=None
        elif stage=="FINAL_READBACK_VERIFIED":
            if flight is not None or not baseline or completed!=len(baseline) or \
                    readbacks>=len(baseline) or sector!=baseline[readbacks]:
                raise ValueError("Final readback out of sequence")
            readbacks+=1
        elif stage=="TRANSACTION_COMPLETED":
            if flight is not None or not baseline or completed!=len(baseline) or \
                    readbacks!=len(baseline) or sector!=baseline[-1]:
                raise ValueError("Premature Android completion")
            done=True
        else:
            raise ValueError("Unsupported Android journal stage")
    disposition=("ERASE_OR_WRITE_MAY_BE_INCOMPLETE" if flight else "NO_ERASE_RECORDED" if not completed
        else "ADDITIONAL_BASELINES_NOT_WRITTEN" if completed<len(baseline) else
        "TRANSACTION_COMPLETION_RECORDED" if done else "FINAL_READBACK_INCOMPLETE" if readbacks
        else "ALL_RECORDED_SECTORS_VERIFIED")
    audit={"sequence_valid":True,"records":len(lines)-1,"last_stage":stage,
           "baseline_sectors":len(baseline),"verified_sectors":completed,"final_readback_sectors":readbacks,
           "completion_recorded":done,"recovery_disposition":disposition,
           "uncertain_address":flight[1] if flight else None,"uncertain_region":flight[0] if flight else None}
    return audit,{(region,address):before for region,address,before,_ in baseline}


class _Reader:
    def __init__(self,data):
        self.data=data
        self.offset=0
    def take(self,count):
        if count<0 or self.offset+count>len(self.data):
            raise ValueError("Truncated Android sector envelope")
        value=self.data[self.offset:self.offset+count];self.offset+=count;return value
    def number(self,fmt):
        return struct.unpack(fmt,self.take(struct.calcsize(fmt)))[0]
    def utf(self):
        # Java DataOutputStream.writeUTF encodes UTF-16 code units in modified
        # UTF-8 (including C0 80 for NUL and encoded surrogate pairs).
        raw=self.take(self.number(">H"))
        units=[]
        i=0
        while i<len(raw):
            first=raw[i];i+=1
            if 1<=first<=0x7F:
                code=first
            elif 0xC0<=first<=0xDF:
                if i>=len(raw) or raw[i]&0xC0!=0x80:raise ValueError("Invalid modified UTF-8")
                code=((first&0x1F)<<6)|(raw[i]&0x3F);i+=1
                if code<0x80 and code!=0:raise ValueError("Overlong modified UTF-8")
            elif 0xE0<=first<=0xEF:
                if i+1>=len(raw) or raw[i]&0xC0!=0x80 or raw[i+1]&0xC0!=0x80:
                    raise ValueError("Invalid modified UTF-8")
                code=((first&15)<<12)|((raw[i]&63)<<6)|(raw[i+1]&63);i+=2
                if code<0x800:raise ValueError("Overlong modified UTF-8")
            else:
                raise ValueError("Invalid modified UTF-8 code unit")
            units.append(code)
        try:
            return b"".join(struct.pack(">H",u) for u in units).decode("utf-16-be","strict")
        except UnicodeError as error:
            raise ValueError("Invalid Java UTF-16 identity") from error


def _android_envelope(name,data,identity,expected):
    reader=_Reader(data)
    if reader.utf()!="CAMARODIAG-PHYSICAL-BLOCK-V1":
        raise ValueError("Unknown Android sector envelope")
    found=AndroidIdentity(reader.utf(),reader.utf(),reader.utf(),reader.utf())
    if found!=identity:
        raise ValueError("Android sector identity mismatch")
    region=reader.utf()
    address=reader.number(">q")
    length=reader.number(">q")
    digest=_digest(reader.utf())
    stored=reader.number(">i")
    _int(address,0,(1<<63)-1)
    _int(length,1,MAX_ANDROID_BLOCK)
    if address+length>(1<<63)-1 or region not in _REGIONS or stored!=length or \
            name!=f"{region}-{address:x}-{length}.ecu-block" or expected.get((region,address))!=digest:
        raise ValueError("Android sector descriptor does not match journal")
    payload=reader.take(length)
    if reader.offset!=len(data) or _sha(payload)!=digest:
        raise ValueError("Android original-sector bytes differ from baseline")
    return (region,address),length,digest,payload


def _windows_journal(raw):
    if not raw.endswith(b"\n"):
        raise ValueError("Incomplete Windows journal record")
    records=[_json(line) for line in raw[:-1].split(b"\n")]
    sectors={}
    expected={}
    crc=set()
    verified=set()
    preflight=False
    destructive=False
    flight=None
    done=False
    recovery=None
    last=None
    keys={"Schema","UtcTimestamp","Phase","Address","Detail"}
    for record in records:
        if not isinstance(record,dict) or set(record)!=keys or _int(record["Schema"],3,3)!=3:
            raise ValueError("Unsupported or ambiguous Windows journal schema")
        phase=record["Phase"]
        address=_int(record["Address"],0,0xFFFFFFFF)
        detail=record["Detail"]
        if not isinstance(phase,str) or not isinstance(detail,str) or not isinstance(record["UtcTimestamp"],str):
            raise ValueError("Invalid Windows journal types")
        if done or recovery is not None:
            raise ValueError("Record after terminal Windows journal phase")
        last=phase
        def advance(before):
            if sectors.get(address)!=before:
                raise ValueError("Windows sector phase out of sequence")
            sectors[address]=phase
        if phase=="Prepared":
            if preflight or address in sectors or len(sectors)>=MAX_SECTORS:
                raise ValueError("Invalid Windows prepared sequence")
            expected[address]=_digest(detail);sectors[address]=phase
        elif phase=="OriginalCrcVerified":
            if destructive or address not in expected or address in crc or not re.fullmatch(r"[0-9a-fA-F]{8}",detail):
                raise ValueError("Invalid Windows original CRC receipt")
            preflight=True;advance("Prepared");crc.add(address)
        elif phase=="OriginalBytesVerified":
            if destructive or address not in crc or address in verified or _digest(detail)!=expected.get(address):
                raise ValueError("Invalid Windows original SHA receipt")
            advance("OriginalCrcVerified");verified.add(address)
        elif phase=="TestWriteAccepted":
            if destructive:raise ValueError("Late Windows test-write receipt")
            advance("OriginalBytesVerified")
        elif phase=="EraseStarted":
            if flight is not None or not sectors or set(verified)!=set(expected) or \
                    any(x not in ("TestWriteAccepted","WrittenBytesVerified") for x in sectors.values()):
                raise ValueError("Erase before all verified original receipts")
            advance("TestWriteAccepted");destructive=True;flight=address
        elif phase in ("EraseAcknowledged","WriteStarted","WrittenCrcVerified","WrittenBytesVerified"):
            if flight!=address:raise ValueError("Windows active-sector mismatch")
            previous={"EraseAcknowledged":"EraseStarted","WriteStarted":"EraseAcknowledged",
                "WrittenCrcVerified":"WriteStarted","WrittenBytesVerified":"WrittenCrcVerified"}[phase]
            advance(previous)
            if phase=="WrittenBytesVerified":flight=None
        elif phase=="FinalBytesVerified":
            if not destructive or flight is not None or any(x not in ("WrittenBytesVerified","FinalBytesVerified") for x in sectors.values()):
                raise ValueError("Windows final readback out of sequence")
            advance("WrittenBytesVerified")
        elif phase=="Completed":
            if address!=0 or not destructive or flight is not None or not sectors or \
                    any(x!="FinalBytesVerified" for x in sectors.values()):
                raise ValueError("Premature Windows completion")
            done=True
        elif phase=="RecoveryRequired":
            recovery=address
        else:
            raise ValueError("Unsupported Windows journal phase")
    audit={"sequence_valid":recovery is None,"records":len(records),"last_stage":last,
           "baseline_sectors":len(expected),"verified_sectors":sum(x in ("WrittenBytesVerified","FinalBytesVerified") for x in sectors.values()),
           "final_readback_sectors":sum(x=="FinalBytesVerified" for x in sectors.values()),
           "completion_recorded":done,"recovery_disposition":"RECOVERY_REQUIRED" if recovery is not None else
                "COMPLETED" if done else "INTERRUPTED","uncertain_address":recovery if recovery is not None else flight}
    if not expected or set(verified)!=set(expected):
        raise ValueError("Not all Windows originals have physical-read receipts")
    return audit,{("E39_MAIN_FLASH",address):value for address,value in expected.items()}


def _windows_metadata(raw,expected_vin=None,expected_osid=None):
    data=_json(raw)
    keys={"Schema","Utc","Family","ExpectedVin","ExpectedOsId","ProfileSha256","DriverSha256",
          "RegistryView","OriginalSha256","TargetSha256","KernelSha256"}
    if not isinstance(data,dict) or set(data)!=keys or _int(data["Schema"],1,1)!=1:
        raise ValueError("Invalid Windows E39 target metadata schema")
    if data["Family"] not in ("E39","E39A") or data["RegistryView"] not in ("Registry32","Registry64") or \
            not isinstance(data["ExpectedVin"],str) or not _VIN.fullmatch(data["ExpectedVin"]):
        raise ValueError("Invalid declared E39 identity")
    osid=_int(data["ExpectedOsId"],1,0xFFFFFFFF)
    if expected_vin is not None and expected_vin!=data["ExpectedVin"] or expected_osid is not None and expected_osid!=osid:
        raise ValueError("Declared Windows target does not match expected identity")
    datetime.fromisoformat(data["Utc"].replace("Z","+00:00"))
    for key in ("ProfileSha256","DriverSha256","OriginalSha256","TargetSha256","KernelSha256"):
        _digest(data[key])
    return {"family":data["Family"],"vin":data["ExpectedVin"],"osid":osid,
            "original_image_sha256":data["OriginalSha256"].lower(),
            "metadata_sha256":_sha(raw),"declared_identity_matches":None if expected_vin is None and expected_osid is None else True}


def _windows_envelope(name,data,expected):
    split=data.find(b"\n",0,1025)
    if not 1<=split<=1024:raise ValueError("Invalid Windows sector metadata length")
    info=_json(data[:split])
    if not isinstance(info,dict) or set(info)!={"Schema","Region","Address","Length","OriginalSha256"} or \
            _int(info["Schema"],1,1)!=1 or info["Region"]!="E39_MAIN_FLASH":
        raise ValueError("Unsupported Windows sector envelope")
    address=_int(info["Address"],0,0xFFFFFFFF)
    length=_int(info["Length"],1,MAX_WINDOWS_BLOCK)
    digest=_digest(info["OriginalSha256"])
    key=("E39_MAIN_FLASH",address)
    payload=data[split+1:]
    if length!=len(payload) or address+length>E39_IMAGE_BYTES or name!=f"{address:08X}-{length}.ecu-block" or \
            expected.get(key)!=digest or _sha(payload)!=digest:
        raise ValueError("Windows sector descriptor or original bytes mismatch")
    return key,length,digest,payload


def inspect_bundle(journal:Path,platform:str,*,android_identity:AndroidIdentity|None=None,
                   expected_vin=None,expected_osid=None,original_bin:Path|None=None,
                   expected_snapshot=None,require_current=lambda:None):
    """Validate original-sector inventory against the journal. On error
    preserve a conservative negative report, never repair the input.
    """
    if platform not in ("android","windows"):
        raise ValueError("Select explicit recovery journal platform")
    if platform=="android" and (android_identity is None or original_bin is not None or expected_osid is not None):
        raise ValueError("Android recovery requires explicit identity and no generic BIN offset assumption")
    if platform=="windows" and android_identity is not None:
        raise ValueError("Android target fields cannot identify Windows E39 evidence")
    if expected_snapshot is not None:expected_snapshot=_digest(expected_snapshot).upper()
    if expected_vin is not None and (not isinstance(expected_vin,str) or not _VIN.fullmatch(expected_vin)):
        raise ValueError("Invalid expected VIN")
    if expected_osid is not None:_int(expected_osid,1,0xFFFFFFFF)
    journal=Path(journal)
    result={"schema":"camarodiag-offline-recovery-bundle-v1","platform":platform.upper(),
            "journal_name":journal.name,"identity":None,"journal_sha256":None,"snapshot_sha256":None,
            "snapshot_algorithm":"SHA256_JOURNAL_AND_ENVELOPES_V1","journal_audit":None,
            "expected_sectors":None,"sectors":[],"verified_bytes":0,"verified":False,
            "expected_snapshot_sha256":expected_snapshot,"snapshot_matches":None,"original_bin":None,
            "problem":None,"physical_vehicle_verified":False,"physical_restore_qualified":False,
            "restore_authorized":False,"authenticated":False}
    try:
        raw,journal_state=_regular(journal,MAX_JOURNAL,require_current)
        result["journal_sha256"]=journal_state.digest
        metadata_path=None;metadata_state=None;image_state=None;image=None
        if platform=="android":
            audit,expected=_android_journal(raw,android_identity)
            result["identity"]=asdict(android_identity)
            maximum=MAX_ANDROID_BLOCK+65536
            parse=lambda name,data:_android_envelope(name,data,android_identity,expected)
        else:
            audit,expected=_windows_journal(raw)
            metadata_path=Path(str(journal)+".meta.json")
            metadata,metadata_state=_regular(metadata_path,16384,require_current)
            identity=_windows_metadata(metadata,expected_vin,expected_osid)
            result["identity"]=identity
            maximum=MAX_WINDOWS_BLOCK+1024
            parse=lambda name,data:_windows_envelope(name,data,expected)
            if original_bin is not None:
                image,image_state=_regular(Path(original_bin),E39_IMAGE_BYTES,require_current)
                result["original_bin"]={"name":Path(original_bin).name,"sha256":image_state.digest,
                    "verified":False,"verified_sectors":0,"verified_bytes":0}
                if len(image)!=E39_IMAGE_BYTES or image_state.digest!=identity["original_image_sha256"]:
                    raise ValueError("Original BIN length or digest differs from target manifest")
        result["journal_audit"]=audit
        result["expected_sectors"]=len(expected)
        if not 0<len(expected)<=MAX_SECTORS:
            raise ValueError("No original-sector evidence")
        directory=Path(str(journal)+".blocks")
        names,dir_state=_directory(directory,len(expected),require_current)
        observed=set();file_states={};envelopes={}
        identities={(journal_state.identity[0],journal_state.identity[1])}
        if metadata_state:identities.add(metadata_state.identity[:2])
        total=0
        for name in names:
            require_current()
            data,state=_regular(directory/name,maximum,require_current)
            if state.identity[:2] in identities:
                raise ValueError("Aliased original-sector evidence")
            identities.add(state.identity[:2])
            total+=state.size
            if total>MAX_TOTAL:raise ValueError("Recovery bundle exceeds inspection bound")
            key,length,digest,payload=parse(name,data)
            if key in observed:raise ValueError("Duplicate sector baseline")
            observed.add(key)
            region,address=key
            if image is not None and image[address:address+length]!=payload:
                raise ValueError(f"Original BIN differs from sector at 0x{address:X}")
            result["sectors"].append({"region":region,"address":address,"length":length,
                "original_sha256":digest,"envelope_sha256":state.digest,"name":name})
            result["verified_bytes"]+=length
            file_states[name]=state;envelopes[name]=state.digest
            if image is not None:
                result["original_bin"]["verified_sectors"]+=1
                result["original_bin"]["verified_bytes"]+=length
        if observed!=set(expected):raise ValueError("Not every original-sector baseline was matched")
        result["sectors"].sort(key=lambda row:(row["region"],row["address"]))
        ends={}
        for row in result["sectors"]:
            if row["address"]<ends.get(row["region"],0):
                raise ValueError("Overlapping original-sector ranges")
            ends[row["region"]]=row["address"]+row["length"]
        # Reacquire every input independently; a matching initial digest
        # is not enough if another process changes the evidence meanwhile.
        for name in names:
            _,state=_regular(directory/name,maximum,require_current)
            if state!=file_states[name]:raise ValueError("Sector envelope changed after inspection")
        if _directory(directory,len(expected),require_current)!=(names,dir_state):
            raise ValueError("Sector inventory changed after inspection")
        if _regular(journal,MAX_JOURNAL,require_current)[1]!=journal_state:
            raise ValueError("Journal changed after inspection")
        if metadata_path is not None and _regular(metadata_path,16384,require_current)[1]!=metadata_state:
            raise ValueError("Target metadata changed after inspection")
        if image_state is not None and _regular(Path(original_bin),E39_IMAGE_BYTES,require_current)[1]!=image_state:
            raise ValueError("Original BIN changed after inspection")
        require_current()
        snapshot=snapshot_digest(journal_state.digest,envelopes)
        result["snapshot_sha256"]=snapshot
        if expected_snapshot is not None:
            result["snapshot_matches"]=snapshot==expected_snapshot
            if not result["snapshot_matches"]:raise ValueError("Snapshot fingerprint differs from expected report")
        result["verified"]=True
        if image is not None:result["original_bin"]["verified"]=True
    except KeyboardInterrupt:
        result["problem"]="CANCELLED"
    except (OSError,ValueError,TypeError,KeyError,OverflowError,struct.error,RuntimeError) as error:
        result["problem"]=str(error)
    return result


def write_report(path:Path,report):
    """Exclusive report creation; original files remain untouched."""
    encoded=(json.dumps(report,indent=2,ensure_ascii=True,allow_nan=False)+"\n").encode("utf-8")
    fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,"wb") as output:
        output.write(encoded);output.flush();os.fsync(output.fileno())
    return report
