"""CU/5 read-only recovery journal export; no operation is executed."""
from __future__ import annotations

import hmac
import secrets
import struct
import time
from .j1979_maintenance_journal import inspect, MAX_JOURNAL, require
from .j1979_maintenance_usb import scope_bytes


def capture_archive(port, scope, key, output, *, clock=time.monotonic, nonce=None, current=lambda: True):
    require(type(key) is bytes and len(key)==32,"Provisioned credential required")
    nonce=secrets.token_bytes(32) if nonce is None else nonce
    require(type(nonce) is bytes and len(nonce)==32 and any(nonce),"Invalid CU/5 nonce")
    request=b"CU\x05\x01"+scope_bytes(scope)+nonce
    raw=bytearray()
    authenticated=False
    inspection=None
    declared=None
    error=None
    start=clock()

    def guard(deadline):
        if not current():
            raise InterruptedError("Archive context revoked")
        if clock()>=deadline:
            raise TimeoutError("CU/5 deadline exceeded")

    def send(data,deadline):
        guard(deadline)
        require(port.write(data)==len(data),"Incomplete CU/5 write; no retry")
        guard(deadline)

    def read(n,deadline,persist=False):
        result=bytearray()
        empty=0
        while len(result)<n:
            guard(deadline)
            part=port.read(n-len(result))
            require(type(part) is bytes and len(part)<=n-len(result),"Invalid CU/5 serial read")
            if not part:
                empty+=1
                require(empty<=65536,"CU/5 read made no progress")
                continue
            empty=0
            result.extend(part)
            if persist:
                require(len(raw)+len(part)<=MAX_JOURNAL,"Archive exceeds byte limit")
                raw.extend(part)
                require(output.write(part)==len(part),"Archive persistence failed")
            guard(deadline)
        return bytes(result)

    try:
        deadline=start+5
        send(request,deadline)
        challenge=read(68,deadline)
        require(challenge[:4]==b"CU\x05\x02" and any(challenge[4:36]),"Invalid CU/5 challenge")
        transcript=request[4:]+challenge[4:36]
        require(hmac.compare_digest(challenge[36:],hmac.digest(key,b"R"+transcript,"sha256")),
                "CU/5 device proof rejected")
        authenticated=True
        require(not getattr(port,"in_waiting",0),"Unexpected buffered CU/5 data")
        send(b"CU\x05\x03"+hmac.digest(key,b"r"+transcript,"sha256"),deadline)
        deadline=clock()+40
        header=read(12,deadline)
        require(header[:4]==b"CU\x05\x04" and not header[4]&~7 and 1<=header[5]<=4
                and not any(header[6:8]),"Invalid CU/5 archive header")
        size,=struct.unpack_from("<I",header,8)
        terminal,damaged,recovery=(bool(header[4]&bit) for bit in (1,2,4))
        require(47<=size<=MAX_JOURNAL and (not terminal or header[5]>=2)
                and ((terminal and not damaged) or recovery),"Invalid CU/5 archive declaration")
        declared={"bytes":size,"records":header[5],"terminal_present":terminal,
                  "damaged_tail":damaged,"recovery_required":recovery}
        read(size,deadline,True)
        guard(deadline)
        require(not port.read(1),"Trailing CU/5 archive bytes")
        guard(deadline)
        result=inspect(bytes(raw),expected_vin=scope.vin)
        require(result["scope"]==scope.report() and result["terminal_present"]==terminal
                and len(result["records"])==declared["records"] and
                (not result["recovery_required"] or recovery),"CM/1 archive differs from declaration")
        inspection=result
    except (OSError,ValueError,KeyboardInterrupt,EOFError,struct.error) as exc:
        error=str(exc) or type(exc).__name__
    return {"schema":"camarodiag-j1979-native-maintenance-archive-v1","protocol":"SAE-J1979",
            "source":"READ_ONLY_USB_CU5","scope":scope.report(),"device_credential_authenticated":authenticated,
            "archive_transfer_verified":inspection is not None,"journal_bytes_received":len(raw),
            "declared_archive":declared,"inspection":inspection,
            "recovery_required":inspection is None or declared["recovery_required"],
            "maintenance_command_sent":False,"physical_vehicle_verified":False,
            "physical_stop_independently_verified":False,"evidence_authenticated":False,
            "permits_automatic_retry":False,"permits_programming":False,
            "host_elapsed_ms":max(0,int((clock()-start)*1000)),"error":error}
