"""CU/4 native maintenance acquisition, separate from read-only USB.

An authenticated connection is not proof of the ECU, local operator consent,
electrical qualification or journal origin. No retries or generic write APIs.
"""
from __future__ import annotations

import hmac
import secrets
import struct
import time

from .j1979_maintenance_journal import Scope, SCOPE, MAX_JOURNAL, inspect, require

HELLO_SIZE = 4 + SCOPE.size + 32
CHALLENGE_SIZE = 68
PROOF_SIZE = 36
RESULT_SIZE = 12


def scope_bytes(scope):
    require(isinstance(scope, Scope), "Explicit maintenance scope required")
    data = SCOPE.pack(scope.operation, int(scope.extended), scope.tx, scope.rx,
                      scope.budget_ms, scope.vin.encode("ascii"))
    require(Scope.parse(data) == scope, "Noncanonical maintenance scope")
    return data


def hello(scope, nonce=None):
    nonce = secrets.token_bytes(32) if nonce is None else nonce
    require(type(nonce) is bytes and len(nonce) == 32 and any(nonce), "Invalid CU/4 client nonce")
    return b"CU\x04\x01" + scope_bytes(scope) + nonce


def proof(request, challenge, key):
    require(type(request) is bytes and len(request) == HELLO_SIZE and request[:4] == b"CU\x04\x01",
            "Invalid CU/4 hello")
    Scope.parse(request[4:4+SCOPE.size])
    require(any(request[-32:]) and type(challenge) is bytes and len(challenge) == CHALLENGE_SIZE
            and challenge[:4] == b"CU\x04\x02" and any(challenge[4:36]), "Invalid CU/4 challenge")
    require(type(key) is bytes and len(key) == 32, "Provisioned credential required")
    transcript = request[4:] + challenge[4:36]
    require(hmac.compare_digest(challenge[36:], hmac.digest(key, b"M"+transcript, "sha256")),
            "CU/4 device proof rejected")
    return b"CU\x04\x03" + hmac.digest(key, b"m"+transcript, "sha256")


def result_header(header):
    require(type(header) is bytes and len(header) == RESULT_SIZE and header[:4] == b"CU\x04\x04"
            and header[4] in (2,3) and not any(header[5:8]), "Invalid CU/4 result envelope")
    length, = struct.unpack_from("<I", header, 8)
    require(0 < length <= MAX_JOURNAL, "Invalid CU/4 journal length")
    return length, bool(header[4]&1)


def capture(port, scope, key, output, *, clock=time.monotonic, nonce=None, current=lambda: True):
    """Preserve original bytes even on failure; never reissue proof.

    The proof may cause the one-shot recipe to run. If transmission was
    attempted but no independently consistent terminal is available, the
    command outcome remains uncertain even if the host saw a serial error.
    """
    request = hello(scope, nonce)
    require(type(key) is bytes and len(key) == 32, "Provisioned credential required")
    raw = bytearray()
    authenticated = proof_attempted = proof_sent = cancelled = False
    envelope_recovery = None
    error = None
    inspection = None
    started = clock()

    def ensure(deadline):
        if not current():
            raise InterruptedError("Owner context revoked")
        if clock() >= deadline:
            raise TimeoutError("CU/4 absolute deadline exceeded")

    def send(data, deadline):
        ensure(deadline)
        require(port.write(data) == len(data), "CU/4 write incomplete; no retry")
        ensure(deadline)

    def receive(size, deadline, *, persist=False):
        result = bytearray()
        empty = 0
        while len(result) < size:
            ensure(deadline)
            part = port.read(size-len(result))
            require(type(part) is bytes and len(part) <= size-len(result), "Invalid CU/4 serial read")
            if part:
                result.extend(part)
                empty = 0
                if persist:
                    require(len(raw)+len(part) <= MAX_JOURNAL, "CM/1 journal exceeds limit")
                    raw.extend(part)
                    require(output.write(part) == len(part), "CM/1 persistence failed")
            else:
                empty += 1
                require(empty <= 65536, "CU/4 read made no progress")
            ensure(deadline)
        return bytes(result)

    try:
        auth_deadline = started+5
        send(request,auth_deadline)
        challenge = receive(CHALLENGE_SIZE,auth_deadline)
        answer = proof(request,challenge,key)
        authenticated = True
        ensure(auth_deadline)
        proof_attempted = True
        send(answer,auth_deadline)
        proof_sent = True
        deadline = clock()+scope.budget_ms/1000+40
        length,envelope_recovery = result_header(receive(RESULT_SIZE,deadline))
        receive(length,deadline,persist=True)
        ensure(deadline)
        trailing = port.read(1)
        require(type(trailing) is bytes and not trailing, "Trailing CU/4 journal bytes")
        ensure(deadline)
        inspection = inspect(bytes(raw),expected_vin=scope.vin)
        require(inspection["scope"] == scope.report(), "Journal differs from authenticated CU/4 scope")
        require(envelope_recovery == inspection["recovery_required"],
                "CU/4 recovery envelope differs from journal")
    except (KeyboardInterrupt, InterruptedError) as exc:
        cancelled = True
        error = str(exc) or "Cancelled; no retry"
        inspection = None
    except (OSError, ValueError, EOFError, struct.error) as exc:
        error = str(exc)
        inspection = None

    terminal = inspection["terminal"] if inspection else None
    recovery = bool(proof_attempted and (not inspection or inspection["recovery_required"]))
    return {"schema": "camarodiag-functional-j1979-native-maintenance-v1", "protocol": "SAE-J1979",
            "source": "LIVE_USB_CU4", "scope": scope.report(),
            "device_credential_authenticated": authenticated,
            "proof_transmission_attempted": proof_attempted, "client_proof_sent": proof_sent,
            "cancelled": cancelled, "journal_transfer_verified": inspection is not None,
            "journal_bytes_received": len(raw), "terminal_present": terminal is not None,
            "recovery_required": recovery, "command_outcome_unknown": proof_attempted and
            (terminal is None or terminal["outcome"] == "UNCERTAIN"),
            "declared_recovery_in_envelope": envelope_recovery, "inspection": inspection,
            "physical_vehicle_verified": False, "evidence_authenticated": False,
            "operator_consent_independently_verified": False, "permits_automatic_retry": False,
            "permits_programming": False, "host_elapsed_ms": max(0,int((clock()-started)*1000)),
            "error": error}
