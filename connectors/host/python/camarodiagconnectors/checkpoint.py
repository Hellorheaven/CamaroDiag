"""Isolated CDJ/1 NVS export. Read only; this is not an authorization protocol."""
import os
import re
import zlib
from time import monotonic

def validate(record: bytes) -> bytes:
    if len(record) != 64 or record[:4] != b"CDJ\x01" or record[5:8] != b"\0"*3:
        raise ValueError("Invalid checkpoint format")
    stage = record[4]
    seq = int.from_bytes(record[8:12], "little")
    valid_stage = ((1 <= stage <= 8 and seq == stage-1) or
                   (stage == 9 and seq == 1) or (stage == 10 and 2 <= seq <= 7))
    if not valid_stage or not any(record[12:28]) or not any(record[28:60]):
        raise ValueError("Invalid checkpoint stage, transaction or digest")
    if zlib.crc32(record[:60]) != int.from_bytes(record[60:], "little"):
        raise ValueError("Checkpoint CRC mismatch")
    return record

def retrieve_checkpoint(port, *, clock=monotonic):
    """Dedicated service firmware only. Caller owns port, with bounded I/O."""
    # Refuse unsolicited input before issuing this one-shot recovery request.
    if getattr(port, "in_waiting", 0):
        raise IOError("Stale checkpoint input before request")
    deadline = clock()+3
    command = b"CDJ/1 READ\n"
    # pyserial's default timeout=None can block a stalled USB read indefinitely.
    original_timeout = getattr(port, "timeout", None)
    if hasattr(port, "timeout"):
        port.timeout = 0.1
    try:
        return _read_checkpoint(port, command, deadline, clock)
    finally:
        if hasattr(port, "timeout"):
            port.timeout = original_timeout

def _read_checkpoint(port, command, deadline, clock):
    if port.write(command) != len(command):
        raise IOError("Short checkpoint request")
    data = bytearray()
    while len(data) < 135:
        if clock() >= deadline:
            raise TimeoutError("Checkpoint deadline")
        value = port.read(1)
        if clock() >= deadline or not value or len(value) != 1:
            raise IOError("Incomplete checkpoint reply")
        data.extend(value)
        if value == b"\n":
            break
    if getattr(port, "in_waiting", 0):
        raise IOError("Unexpected trailing checkpoint data")
    if data == b"CDJ/1 NONE\n":
        return None
    if data == b"CDJ/1 CORRUPT\n":
        raise IOError("Device journal corrupt or locked")
    if not re.fullmatch(rb"CDJ/1 [0-9a-f]{128}\n", data):
        raise ValueError("Unexpected checkpoint reply")
    return validate(bytes.fromhex(data[6:-1].decode("ascii")))

def save_checkpoint(path, record):
    frozen = validate(bytes(record))
    # Never overwrite existing evidence; preserve incomplete output on failure.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(frozen)
        output.flush()
        os.fsync(output.fileno())
