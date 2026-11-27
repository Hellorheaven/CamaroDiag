"""ECU-local Mode 01 scaling, ISO 15031-5:2006 B.60/B.61.

The context never spans responders or sessions. Legacy anonymous records
are deliberately excluded. Historical application evidence is immutable;
this is the authoritative contextual view for affected measurements.
"""
from dataclasses import dataclass

CONFIG_PIDS = (0x4F, 0x50)
DEPENDENT_PIDS = frozenset((0x0B, 0x10, 0x44, *range(0x24, 0x2C), *range(0x34, 0x3C)))
GOOD = ("PASSED", "RAW")


def config_data(pid, data):
    if pid not in CONFIG_PIDS or not isinstance(data, bytes) or len(data) != 4:
        return None
    if pid == 0x50 and any(data[1:]):
        return None
    return data


def dependency(pid):
    if pid == 0x10:
        return 0x50
    if pid in DEPENDENT_PIDS:
        return 0x4F
    return None


@dataclass(frozen=True)
class Range:
    state: str
    data: bytes | None = None

    def report(self, pid):
        return {"pid": pid, "state": self.state,
                "data_hex": self.data.hex().upper() if self.data is not None else None}


@dataclass(frozen=True)
class Context:
    first: Range
    second: Range

    def get(self, pid):
        return self.first if pid == 0x4F else self.second

    def report(self):
        return [self.first.report(0x4F), self.second.report(0x50)]

    @classmethod
    def from_inventory(cls, pages, configs):
        """pages: validated Mode 01 page bytes, configs: all attempted replies.
        A verified terminating earlier page also proves later pages absent.
        Duplicate page/configuration records are ambiguous, even if equal.
        """
        by_base = {}
        for base, response in pages:
            by_base.setdefault(base, []).append(response)
        supported = set()
        seen = set()
        ended = False
        for base in range(0, 256, 32):
            candidates = by_base.get(base, [])
            if len(candidates) != 1:
                break
            response = candidates[0]
            if not isinstance(response, bytes) or len(response) != 6 or response[:2] != bytes((0x41, base)):
                break
            if base == 0xE0 and response[-1] & 1:
                break
            seen.add(base)
            bits = int.from_bytes(response[2:], "big")
            for index in range(1, 32):
                if bits & (1 << (32-index)):
                    supported.add(base+index)
            if not bits & 1:
                ended = True
                break

        def resolve(pid):
            candidates = configs.get(pid, [])
            if pid not in supported:
                if candidates:
                    return Range("UNVERIFIED")
                if (pid-1)//32*32 in seen or ended:
                    return Range("DEFAULT_UNSUPPORTED")
                return Range("UNVERIFIED")
            if len(candidates) != 1:
                return Range("UNVERIFIED")
            response = candidates[0]
            if not isinstance(response, bytes) or response[:2] != bytes((0x41, pid)):
                return Range("UNVERIFIED")
            data = config_data(pid, response[2:])
            if data is None:
                return Range("UNVERIFIED")
            return Range("ECU_CONFIGURATION", data)

        return cls(resolve(0x4F), resolve(0x50))

    def decode(self, request, response):
        """Return a record for each affected Mode 01/02 sample.
        Unknown configuration deliberately produces null engineering values.
        Mode 02 uses the same ECU's Mode 01 equipment configuration;
        no other freeze frame or controller is inferred.
        """
        if not isinstance(request, bytes) or not isinstance(response, bytes):
            return None
        if len(request) not in (2, 3) or request[0] not in (1, 2):
            return None
        mode, pid = request[:2]
        if len(request) != (3 if mode == 2 else 2) or (mode == 2 and request[2] != 0):
            return None
        dep = dependency(pid)
        if dep is None or response[:len(request)] != bytes((mode+0x40,))+request[1:]:
            return None
        data = response[len(request):]
        width = 1 if pid == 0x0B else 2 if pid in (0x10,0x44) else 4
        if len(data) != width:
            return None
        config = self.get(dep)
        maxima = config.data or bytes(4)

        def field(name, unit, raw, index, default_scale, custom_scale, offset=0):
            custom = maxima[index]
            scale = custom_scale(custom) if custom else default_scale
            return {"name": name, "unit": unit, "raw_encoded": raw,
                    "number": None if config.state == "UNVERIFIED" else (raw-offset)*scale,
                    "scale_source": "UNVERIFIED" if config.state == "UNVERIFIED" else
                        "ECU_CONFIGURATION" if custom else "DEFAULT"}

        a = int.from_bytes(data[:2], "big")
        if pid == 0x0B:
            fields = [field("intake-manifold-absolute-pressure","kPa",a,3,1,lambda x:x*10/255)]
        elif pid == 0x10:
            fields = [field("mass-air-flow","g/s",a,0,.01,lambda x:x*10/65535)]
        elif pid == 0x44:
            fields = [field("commanded-equivalence-ratio","ratio",a,0,1/32768,lambda x:x/65535)]
        else:
            voltage = pid < 0x34
            sensor = pid-(0x24 if voltage else 0x34)+1
            prefix = f"wideband-o2-sensor-{sensor}-"
            fields = [field(prefix+"equivalence-ratio","ratio",a,0,1/32768,lambda x:x/65535)]
            b = int.from_bytes(data[2:], "big")
            if voltage:
                fields.append(field(prefix+"voltage","V",b,1,1/8192,lambda x:x/65535))
            else:
                # Existing J1979 PID 34-3B representation uses offset binary
                # for the signed current. Scale the centered signed value.
                fields.append(field(prefix+"current","mA",b,2,1/256,lambda x:x/32768,32768))
        return {"request_hex":request.hex().upper(),"configuration_pid":dep,"signals":fields}


def _bytes(value):
    try:
        if isinstance(value,str) and len(value)<=8190:
            return bytes.fromhex(value)
    except ValueError:
        pass
    return None


def project(steps, *, legacy_source=None, legacy=False):
    """Produce scoped measurements from a single selected ECU's campaign.
    Only exactly one correlated legacy record is admissible, and never
    associate headerless observations with configuration from another ECU.
    """
    pages, configs, samples = [], {}, []
    for step in steps:
        req = _bytes(step.get("request_hex"))
        if req is None or len(req) not in (2,3) or req[0] not in (1,2):
            continue
        pid = req[1]
        if legacy:
            if not legacy_source or step.get("selected_response_source") != legacy_source:
                res = None
            else:
                records = step.get("records_hex")
                res = _bytes(records[0]) if isinstance(records,list) and len(records)==1 else None
        else:
            res = _bytes(step.get("response_hex"))
        if step.get("status") not in GOOD:
            res = None
        if req[0] == 1 and len(req)==2:
            if pid % 32 == 0:
                pages.append((pid,res))
            elif pid in CONFIG_PIDS:
                configs.setdefault(pid,[]).append(res)
        if res is not None and pid in DEPENDENT_PIDS:
            samples.append((req,res))
    ctx = Context.from_inventory(pages,configs)
    result = []
    for req,res in samples:
        record = ctx.decode(req,res)
        if record is not None:
            result.append(record)
    return {"configuration":ctx.report(),"records":result}
