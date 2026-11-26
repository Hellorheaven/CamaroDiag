"""CU/1 authenticated native USB J1979 read request codec.

No I/O or automatic pairing. A qualified device owner must provision the
same 32-byte credential and independently approve the physical profile.
"""
from __future__ import annotations

import hashlib
import hmac
import secrets
import struct

from .j1979_campaign_evidence import _physical_pair

_PROFILE = struct.Struct("<IIBI")
_NONCE = 32
_TAG = 32


def _key(key: bytes) -> bytes:
    if not isinstance(key, bytes) or len(key) != 32:
        raise ValueError("A provisioned 32-byte host credential is required")
    return key


def hello(tx: int, rx: int, extended: bool, budget_ms: int,
          *, nonce: bytes | None = None) -> bytes:
    if (type(extended) is not bool or not _physical_pair(tx, rx, int(extended)) or
            not 1 <= budget_ms <= 600000):
        raise ValueError("Invalid physical J1979 profile")
    nonce = secrets.token_bytes(_NONCE) if nonce is None else nonce
    if not isinstance(nonce, bytes) or len(nonce) != _NONCE or not any(nonce):
        raise ValueError("Invalid host nonce")
    return b"CU\x01\x01" + _PROFILE.pack(tx, rx, int(extended), budget_ms) + nonce


def proof(request: bytes, challenge: bytes, key: bytes) -> bytes:
    """Verify the device proof before returning the one-use client proof."""
    _key(key)
    if (len(request) != 49 or request[:4] != b"CU\x01\x01" or
            len(challenge) != 68 or challenge[:4] != b"CU\x01\x02"):
        raise ValueError("Invalid CU/1 message")
    tx, rx, extended, budget = _PROFILE.unpack_from(request, 4)
    if (not _physical_pair(tx, rx, extended) or not 1 <= budget <= 600000 or
            not any(request[17:]) or not any(challenge[4:36])):
        raise ValueError("Invalid CU/1 transcript")
    transcript = request[4:] + challenge[4:36]
    expected = hmac.digest(key, b"S" + transcript, hashlib.sha256)
    if not hmac.compare_digest(expected, challenge[36:]):
        raise ValueError("Device authentication failed")
    return b"CU\x01\x03" + hmac.digest(key, b"C" + transcript, hashlib.sha256)
