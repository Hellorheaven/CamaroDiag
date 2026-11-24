"""Binary USB discovery for isolated development firmware only.

CRC and sequencing detect accidental errors; neither authenticates a device.
No vehicle, memory, or programming operation is exposed.
"""
from time import monotonic
from .framing import Frame, Op, encode_frame, decode_frame, MAX_PAYLOAD
_EXPECTED = {
    Op.PING: "PONG CamaroDiagConnectors 0.1.0",
    Op.CAPABILITIES: "CAPS hs_can=disabled sw_can=disabled eeprom_read=disabled eeprom_write=disabled ecu_flash=disabled j2534=disabled",
    Op.STATUS: "STATUS mode=prototype write=disabled",
}

class BinaryDiscoveryClient:
    def __init__(self, transport, *, clock=monotonic):
        self._transport = transport
        self._clock = clock
        self._next = 0
        self._closed = False

    @property
    def closed(self):
        return self._closed

    def _remaining(self, deadline):
        remaining = deadline - self._clock()
        if remaining <= 0:
            raise TimeoutError("Discovery response deadline exceeded")
        return remaining

    def _read_exact(self, count, deadline):
        data = bytearray()
        while len(data) < count:
            self._remaining(deadline)
            chunk = self._transport.read(count - len(data))
            self._remaining(deadline)
            if not chunk:
                # A bounded pyserial read may expire before the full reply;
                # use the shared deadline rather than failing on the first gap.
                if hasattr(self._transport, "timeout"):
                    continue
                raise IOError("Incomplete binary reply")
            if len(chunk) > count - len(data):
                raise IOError("Overlong binary reply")
            data.extend(chunk)
        return bytes(data)

    def execute(self, op):
        if self._closed:
            raise IOError("Discovery session closed; reset device before reconnecting")
        try:
            if not isinstance(op, Op) or int(op) != self._next + 1:
                raise ValueError("Unexpected discovery operation order")
            # Bound blocking pyserial operations; restore caller settings below.
            port = self._transport
            has_timeout = hasattr(port, "timeout")
            has_write_timeout = hasattr(port, "write_timeout")
            old_timeout = getattr(port, "timeout", None)
            old_write_timeout = getattr(port, "write_timeout", None)
            if has_timeout:
                port.timeout = 0.2
            if has_write_timeout:
                port.write_timeout = 0.2
            deadline = self._clock() + 3.0
            wire = encode_frame(Frame(op, self._next, b""))
            if self._transport.write(wire) != len(wire):
                raise IOError("Short binary request write")
            header = self._read_exact(8, deadline)
            if header[:3] != b"CD\x01":
                raise IOError("Unsupported binary header")
            size = int.from_bytes(header[6:8], "little")
            # Public discovery replies are small and never empty.
            if not 0 < size <= min(MAX_PAYLOAD, 244):
                raise IOError("Unexpected discovery reply length")
            frame = decode_frame(header + self._read_exact(size + 4, deadline))
            if frame.op != op or frame.sequence != self._next:
                raise IOError("Unexpected reply correlation")
            text = frame.payload.decode("ascii")
            if not all(32 <= ord(c) <= 126 for c in text):
                raise ValueError("Non-printable discovery payload")
            if text != _EXPECTED[op]:
                raise ValueError("Noncanonical discovery response")
            self._remaining(deadline)
            if self._next == 2:
                self._closed = True
            else:
                self._next += 1
            return text
        except BaseException:
            self._closed = True
            raise
        finally:
            if "has_timeout" in locals() and has_timeout:
                port.timeout = old_timeout
            if "has_write_timeout" in locals() and has_write_timeout:
                port.write_timeout = old_write_timeout

    def verify_read_only(self):
        return tuple(self.execute(op) for op in (Op.PING, Op.CAPABILITIES, Op.STATUS))
