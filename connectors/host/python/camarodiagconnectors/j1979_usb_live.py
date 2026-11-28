"""Live CU/1 host campaign. Explicit port, one authenticated read-only session.

The handshake authenticates a provisioned credential, not the vehicle or
the origin of a subsequent unsigned CJ/1 capture. Preserve partial bytes.
"""
from __future__ import annotations

import io
import struct
import time

from .j1979_usb_auth import hello, proof
from .j1979_campaign_evidence import decode

_HEADER = struct.Struct("<2sBBHH")


def run_campaign(port, *, key: bytes, tx: int, rx: int, extended: bool,
                 budget_ms: int, capture, clock=time.monotonic, nonce=None):
    request = hello(tx, rx, extended, budget_ms, nonce=nonce)
    if not isinstance(key, bytes) or len(key) != 32:
        raise ValueError("A provisioned 32-byte host credential is required")
    authenticated = False
    started = clock()
    error = None
    cancelled = False
    raw = bytearray()

    def write_once(data, deadline):
        if clock() >= deadline:
            raise TimeoutError("USB write deadline exceeded")
        if port.write(data) != len(data):
            raise OSError("Incomplete USB command; no retry")
        if clock() >= deadline:
            raise TimeoutError("USB write deadline exceeded; no retry")

    def read_exact(size, deadline, *, record=False):
        result = bytearray()
        while len(result) < size:
            if clock() >= deadline:
                raise TimeoutError("USB read deadline exceeded")
            piece = port.read(size - len(result))
            if not isinstance(piece, bytes) or len(piece) > size - len(result):
                raise OSError("Invalid USB read")
            if piece:
                result.extend(piece)
                if record:
                    raw.extend(piece)
                    if capture.write(piece) != len(piece):
                        raise OSError("Incomplete capture write")
            if clock() >= deadline:
                raise TimeoutError("USB read deadline exceeded")
        return bytes(result)

    try:
        write_once(request, started + 5.0)
        challenge = read_exact(68, started + 5.0)
        client = proof(request, challenge, key)
        authenticated = True
        write_once(client, started + 5.0)
        # Campaign budget is diagnostic execution time; a bounded allowance
        # covers start/stop and stream overhead. No per-record timeout reset.
        deadline = clock() + budget_ms / 1000 + 15.0
        for sequence in range(2048):
            header = read_exact(_HEADER.size, deadline, record=True)
            magic, version, kind, seq, length = _HEADER.unpack(header)
            if (magic != b"CJ" or version != 1 or seq != sequence or
                    length > 4110 or kind not in (1, 2, 3)):
                raise ValueError("Invalid CJ/1 record header")
            read_exact(length + 4, deadline, record=True)
            if kind == 3:
                # A serial connection has no EOF. A bounded quiet read only
                # detects immediately available trailing bytes; it does not
                # authenticate the stream or prove permanent closure.
                if clock() >= deadline:
                    raise TimeoutError("USB summary deadline exceeded")
                extra = port.read(1)
                if clock() >= deadline:
                    raise TimeoutError("USB summary deadline exceeded")
                if extra:
                    raw.extend(extra)
                    if capture.write(extra) != len(extra):
                        raise OSError("Incomplete capture write")
                    raise ValueError("Trailing bytes after campaign summary")
                break
        else:
            raise ValueError("Campaign record limit exceeded")
    except KeyboardInterrupt:
        cancelled = True
        error = "USB campaign cancelled by operator; no retry"
    except (ValueError, OSError, EOFError) as exc:
        error = str(exc)
    report = decode(io.BytesIO(raw))
    expected = (f"{tx:X}", f"{rx:X}", extended, budget_ms)
    actual = tuple(report.get(name) for name in
                   ("request_can_id", "response_can_id", "extended", "budget_ms"))
    if actual != expected:
        report["capture_integrity_verified"] = False
        error = error or "Capture profile differs from authenticated request"
    if error:
        report["capture_integrity_verified"] = False
        report["error"] = error
    report.update(source="LIVE_USB_CU1", device_credential_authenticated=authenticated,
                  cancelled=cancelled,
                  hardware_provenance_verified=False, evidence_authenticated=False,
                  duration_ms=max(0, int((clock() - started) * 1000)))
    return report
