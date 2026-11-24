"""Direct ISO 13400 network client on the host, not the USB prototype firmware."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import ipaddress
import re
import socket
import ssl
import struct
import time
from typing import Callable

MAX_PAYLOAD = 1024 * 1024
VERSIONS = (2, 3, 4)
Trace = Callable[[str, str, str, str], None]


class DoIpError(ValueError):
    pass


class NegativeResponse(DoIpError):
    def __init__(self, service: int, code: int):
        self.service, self.code = service, code
        super().__init__(f"UDS NRC {service:02X}/{code:02X}")


def valid_vin(vin: str) -> bool:
    return bool(re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", vin))


def endpoint(host: str) -> str:
    address = ipaddress.ip_address(host)
    if address.is_unspecified or address.is_multicast or str(address) == "255.255.255.255":
        raise DoIpError("Explicit unicast IP required")
    if isinstance(address, ipaddress.IPv6Address) and address.is_link_local:
        if not address.scope_id or address.scope_id == "0":
            raise DoIpError("Link-local IPv6 requires an explicit interface scope")
    return str(address)


def tls_server_name(host: str) -> str:
    # The scope routes the socket but is not part of the certificate IP SAN.
    return endpoint(host).split("%", 1)[0]


def logical(value: int) -> bytes:
    if type(value) is not int or not 0 <= value <= 65535:
        raise DoIpError("Invalid logical address")
    return value.to_bytes(2, "big")


def encode(version: int, kind: int, body: bytes = b"") -> bytes:
    if version not in (*VERSIONS, 255) or not 0 <= kind <= 65535 or len(body) > MAX_PAYLOAD:
        raise DoIpError("Invalid DoIP header")
    return struct.pack(">BBHI", version, version ^ 255, kind, len(body)) + body


def header(raw: bytes) -> tuple[int, int, int]:
    if len(raw) != 8:
        raise DoIpError("Incomplete DoIP header")
    version, inverse, kind, length = struct.unpack(">BBHI", raw)
    if version not in VERSIONS or version ^ inverse != 255 or length > MAX_PAYLOAD:
        raise DoIpError("Invalid DoIP header")
    return version, kind, length


def decode(raw: bytes) -> tuple[int, int, bytes]:
    version, kind, length = header(raw[:8])
    if len(raw) != 8 + length:
        raise DoIpError("Invalid DoIP length")
    return version, kind, raw[8:]


def remaining(deadline: float) -> float:
    left = deadline - time.monotonic()
    if left <= 0:
        raise TimeoutError("DoIP deadline expired")
    return left


def _timeout(timeout: float) -> None:
    if not 0.1 <= timeout <= 30:
        raise DoIpError("Timeout must be 0.1..30 seconds")


def _emit(trace: Trace | None, direction: str, transport: str, host: str, raw: bytes) -> None:
    if trace is not None:
        trace(direction, transport, host, raw.hex().upper())


@dataclass(frozen=True)
class Gateway:
    host: str
    vin: str | None
    logical_address: int
    version: int
    eid: str
    gid: str
    further_action: int
    sync_status: int | None


def parse_identity(host: str, raw: bytes, expected_vin: str | None = None) -> Gateway:
    version, kind, body = decode(raw)
    if kind != 4 or len(body) not in (32, 33):
        raise DoIpError("Invalid DoIP vehicle identification response")
    raw_vin = body[:17]
    vin = None if raw_vin in (b"\x00" * 17, b"\xff" * 17) else raw_vin.decode("ascii")
    if vin is not None and not valid_vin(vin):
        raise DoIpError("Invalid announced VIN")
    if expected_vin is not None and vin != expected_vin:
        raise DoIpError("Announced VIN does not match selected vehicle")
    return Gateway(host, vin, int.from_bytes(body[17:19], "big"), version,
                   body[19:25].hex().upper(), body[25:31].hex().upper(),
                   body[31], body[32] if len(body) == 33 else None)


def _udp(host: str, request: bytes, timeout: float, trace: Trace | None,
         port: int = 13400) -> bytes:
    host = endpoint(host)
    _timeout(timeout)
    deadline = time.monotonic() + timeout
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    with socket.socket(family, socket.SOCK_DGRAM) as sock:
        sock.settimeout(remaining(deadline))
        sock.connect((host, port))
        sock.send(request)
        _emit(trace, "TX", "UDP", host, request)
        sock.settimeout(remaining(deadline))
        response = sock.recv(65536)
        _emit(trace, "RX", "UDP", host, response)
        remaining(deadline)
        return response


def discovery_request(vin: str | None = None, eid: bytes | None = None) -> bytes:
    if vin is not None and eid is not None:
        raise DoIpError("Choose VIN or EID identification, not both")
    if vin is not None:
        if not valid_vin(vin):
            raise DoIpError("Invalid expected VIN")
        return encode(255, 3, vin.encode("ascii"))
    if eid is not None:
        if not isinstance(eid, bytes) or len(eid) != 6:
            raise DoIpError("EID must contain six bytes")
        return encode(255, 2, eid)
    return encode(255, 1)


def discover(host: str, vin: str | None = None, timeout: float = 3,
             trace: Trace | None = None, port: int = 13400, *,
             eid: bytes | None = None) -> Gateway:
    request = discovery_request(vin, eid)
    found = parse_identity(endpoint(host), _udp(host, request, timeout, trace, port), vin)
    if eid is not None and found.eid != eid.hex().upper():
        raise DoIpError("Announced EID does not match requested gateway")
    return found


def gateway_key(gateway: Gateway) -> tuple:
    return (gateway.host, gateway.vin, gateway.logical_address,
            gateway.version, gateway.eid, gateway.gid)


def discover_ipv4(timeout: float = 3, trace: Trace | None = None, *,
                  destination: str | None = None, vin: str | None = None,
                  eid: bytes | None = None, port: int = 13400,
                  max_results: int = 32, listen_host: str = "0.0.0.0") -> list[Gateway]:
    """Explicit IPv4 broadcast or passive announcement collection. No UDS traffic."""
    _timeout(timeout)
    if type(port) is not int or not 1 <= port <= 65535 or type(max_results) is not int or not 1 <= max_results <= 32:
        raise DoIpError("Invalid discovery port or result limit")
    request = discovery_request(vin, eid)
    if destination is not None:
        ip = ipaddress.IPv4Address(destination)
        if ip.is_multicast or ip.is_unspecified:
            raise DoIpError("Invalid IPv4 discovery destination")
    bind_address = ipaddress.IPv4Address(listen_host)
    if bind_address.is_multicast or str(bind_address) == "255.255.255.255":
        raise DoIpError("Invalid IPv4 listen address")
    deadline = time.monotonic() + timeout
    matches = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        if destination is None:
            sock.bind((str(bind_address), port))
        else:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind((str(bind_address), 0))
            sock.settimeout(remaining(deadline))
            sock.sendto(request, (destination, port))
            _emit(trace, "TX", "UDP", destination, request)
        while time.monotonic() < deadline:
            try:
                sock.settimeout(remaining(deadline))
                raw, peer = sock.recvfrom(65536)
                remaining(deadline)
            except TimeoutError:
                break
            if peer[1] != port:
                continue
            _emit(trace, "RX", "UDP", peer[0], raw)
            try:
                observed = parse_identity(peer[0], raw, vin)
            except ValueError:
                continue
            if eid is not None and observed.eid != eid.hex().upper():
                continue
            matches[gateway_key(observed)] = observed
            if len(matches) >= max_results:
                break
    return list(matches.values())


def discover_ipv6(interface_index: int, timeout: float = 3, trace: Trace | None = None, *,
                  passive: bool = False, vin: str | None = None, eid: bytes | None = None,
                  port: int = 13400, max_results: int = 32) -> list[Gateway]:
    """Link-local IPv6 ff02::1 discovery on one explicit interface; no UDS traffic."""
    _timeout(timeout)
    if type(interface_index) is not int or not 1 <= interface_index <= 0x7FFFFFFF:
        raise DoIpError("An explicit positive IPv6 interface index is required")
    if type(port) is not int or not 1 <= port <= 65535 or type(max_results) is not int or not 1 <= max_results <= 32:
        raise DoIpError("Invalid discovery port or result limit")
    request = discovery_request(vin, eid)
    group = "ff02::1"
    scoped_group = f"{group}%{interface_index}"
    deadline = time.monotonic() + timeout
    matches = {}
    with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        if passive:
            sock.bind(("::", port))
            membership = socket.inet_pton(socket.AF_INET6, group) + struct.pack("=I", interface_index)
            sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_JOIN_GROUP, membership)
        else:
            sock.bind(("::", 0))
            sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, interface_index)
            sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_HOPS, 1)
            sock.settimeout(remaining(deadline))
            sock.sendto(request, (group, port, 0, interface_index))
            _emit(trace, "TX", "UDP", scoped_group, request)
        while time.monotonic() < deadline:
            try:
                sock.settimeout(remaining(deadline))
                raw, peer = sock.recvfrom(65536)
                remaining(deadline)
            except TimeoutError:
                break
            if peer[1] != port or len(peer) != 4 or peer[3] != interface_index:
                continue
            try:
                address = ipaddress.IPv6Address(peer[0].split("%", 1)[0])
                if not address.is_link_local:
                    continue
                host = f"{address.compressed}%{interface_index}"
                _emit(trace, "RX", "UDP", host, raw)
                observed = parse_identity(host, raw, vin)
            except ValueError:
                continue
            if eid is not None and observed.eid != eid.hex().upper():
                continue
            matches[gateway_key(observed)] = observed
            if len(matches) >= max_results:
                break
    return list(matches.values())


def discover_unique_vin(vin: str, timeout: float = 3, trace: Trace | None = None,
                        destination: str = "255.255.255.255", port: int = 13400) -> Gateway:
    """Directed VIN query; ambiguity never chooses a target."""
    if not valid_vin(vin):
        raise DoIpError("Invalid expected VIN")
    matches = discover_ipv4(timeout, trace, destination=destination, vin=vin, port=port, max_results=2)
    if len(matches) > 1:
        raise DoIpError("Multiple gateways announce the selected VIN")
    if not matches:
        raise DoIpError("No matching DoIP gateway observed")
    return matches[0]


def parse_commands(text: str) -> tuple[bytes, ...]:
    parts = text.split(";")
    if not 1 <= len(parts) <= 16:
        raise DoIpError("Expected 1..16 commands")
    commands = []
    for part in parts:
        compact = "".join(part.split())
        if not re.fullmatch(r"(?:[0-9A-Fa-f]{2}){1,4091}", compact):
            raise DoIpError("Invalid hexadecimal command")
        commands.append(bytes.fromhex(compact))
    if sum(map(len, commands)) > 4091:
        raise DoIpError("Command sequence exceeds 4091 bytes")
    return tuple(commands)


def confirm_commands(commands: tuple[bytes, ...], gateway: Gateway, tester: int, ecu: int,
                     tls: bool, read, display, french: bool = False) -> bool:
    """Only a separate explicit YES after OK permits the frozen sequence."""
    display(f"VIN {gateway.vin} | {gateway.host} | tester 0x{tester:04X} | ECU 0x{ecu:04X} | {'TLS' if tls else 'TCP'}")
    for index, command in enumerate(commands, 1):
        display(f"{index}: {command.hex().upper()}")
    display("Risque de modification ou d'effacement ECU. Saisir OK pour continuer."
            if french else "Risk of ECU modification or erasure. Type OK to continue.")
    if read().strip().upper() != "OK":
        return False
    display("Confirmer l'envoi exact de la séquence ? Saisir OUI." if french
            else "Confirm sending this exact sequence? Type YES.")
    return read().strip().upper() == ("OUI" if french else "YES")


def status(gateway: Gateway, power: bool = False, timeout: float = 3,
           trace: Trace | None = None, port: int = 13400) -> dict:
    request = encode(gateway.version, 0x4003 if power else 0x4001)
    version, kind, body = decode(_udp(gateway.host, request, timeout, trace, port))
    if version != gateway.version:
        raise DoIpError("Gateway version changed")
    if power:
        if kind != 0x4004 or len(body) != 1 or body[0] not in (0, 1, 2):
            raise DoIpError("Invalid diagnostic power mode")
        return {"power_mode": body[0]}
    if kind != 0x4002 or len(body) not in (3, 7) or body[2] > body[1]:
        raise DoIpError("Invalid entity status")
    return {"node_type": body[0], "max_sockets": body[1], "current_sockets": body[2],
            "max_data_size": int.from_bytes(body[3:], "big") if len(body) == 7 else None}


def positive(request: bytes, reply: bytes) -> bytes:
    if not reply or reply[0] != request[0] + 0x40:
        raise DoIpError("UDS response service mismatch")
    echo = {0x22: 2, 0x2E: 2, 0x2F: 2, 0x19: 1, 0x36: 1}.get(request[0], 0)
    if echo and (len(request) < echo + 1 or len(reply) < echo + 1 or
                 reply[1:echo+1] != request[1:echo+1]):
        raise DoIpError("UDS response echo mismatch")
    if request[0] in (0x10, 0x11, 0x27, 0x28, 0x3E, 0x85, 0x31):
        if len(request) < 2 or len(reply) < 2 or reply[1] != request[1] & 0x7F:
            raise DoIpError("UDS subfunction mismatch")
        if request[0] == 0x31 and (len(request) < 4 or len(reply) < 4 or reply[2:4] != request[2:4]):
            raise DoIpError("UDS routine identifier mismatch")
    return reply


class Session:
    """Sequential, one-owner routed socket. Any uncertain exchange retires it."""
    def __init__(self, gateway: Gateway, tester: int, ecu: int, *,
                 tls: bool = True, timeout: float = 3, trace: Trace | None = None,
                 context: ssl.SSLContext | None = None, port: int | None = None,
                 p2: float | None = None, p2_star: float | None = None,
                 routing_type: int = 0, routing_oem_data: bytes | None = None):
        _timeout(timeout)
        p2 = timeout if p2 is None else p2
        p2_star = timeout if p2_star is None else p2_star
        if not 0.001 <= p2 <= timeout or not p2 <= p2_star <= timeout:
            raise DoIpError("Expected 0.001 <= P2 <= P2* <= overall timeout")
        if type(routing_type) is not int or not 0 <= routing_type <= 255:
            raise DoIpError("Invalid routing activation type")
        if routing_oem_data is not None and (not isinstance(routing_oem_data, bytes) or
                                             len(routing_oem_data) != 4):
            raise DoIpError("Routing OEM data must contain exactly four bytes")
        activation = logical(tester) + bytes((routing_type,)) + bytes(4) + (routing_oem_data or b"")
        endpoint(gateway.host)
        logical(tester)
        logical(ecu)
        if tester in (ecu, gateway.logical_address) or gateway.version not in VERSIONS:
            raise DoIpError("Invalid DoIP target")
        if context is not None and (not tls or not context.check_hostname or
                                    context.verify_mode != ssl.CERT_REQUIRED):
            raise DoIpError("TLS certificate and endpoint verification required")
        self.gateway, self.tester, self.ecu = gateway, tester, ecu
        self.timeout, self.trace, self.tls = timeout, trace, tls
        self.p2, self.p2_star = p2, p2_star
        self._socket: socket.socket | None = None
        self._busy = False
        deadline = time.monotonic() + timeout
        wire = socket.create_connection((gateway.host, port or (3496 if tls else 13400)),
                                        timeout=remaining(deadline))
        try:
            if tls:
                secure = context or ssl.create_default_context()
                secure.minimum_version = ssl.TLSVersion.TLSv1_2
                wire.settimeout(remaining(deadline))
                wire = secure.wrap_socket(wire, server_hostname=tls_server_name(gateway.host))
            self._socket = wire
            self._send(5, activation, deadline)
            while True:
                kind, body = self._receive(deadline)
                if self._alive(kind, body, deadline):
                    continue
                if (kind != 6 or len(body) not in (9, 13) or
                    body[:4] != logical(tester) + logical(gateway.logical_address)):
                    raise DoIpError("Routing activation identity mismatch")
                if body[4] == 0x10:
                    break
                if body[4] != 0x11:
                    raise DoIpError("Routing activation denied")
        except BaseException:
            wire.close()
            self._socket = None
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        if self._socket is not None:
            self._socket.close()
            self._socket = None

    def _wire(self) -> socket.socket:
        if self._socket is None:
            raise DoIpError("Session closed; reconnect explicitly")
        return self._socket

    def _send(self, kind: int, body: bytes, deadline: float):
        raw = encode(self.gateway.version, kind, body)
        wire = self._wire()
        wire.settimeout(remaining(deadline))
        wire.sendall(raw)
        _emit(self.trace, "TX", "TLS" if self.tls else "TCP", self.gateway.host, raw)
        remaining(deadline)

    def _read_exact(self, count: int, deadline: float) -> bytes:
        data = bytearray()
        wire = self._wire()
        while len(data) < count:
            wire.settimeout(remaining(deadline))
            part = wire.recv(count - len(data))
            if not part:
                raise EOFError("DoIP socket closed")
            data.extend(part)
        remaining(deadline)
        return bytes(data)

    def _receive(self, deadline: float, first: bytes = b"") -> tuple[int, bytes]:
        raw_header = first + self._read_exact(8 - len(first), deadline)
        version, kind, length = header(raw_header)
        body = self._read_exact(length, deadline)
        _emit(self.trace, "RX", "TLS" if self.tls else "TCP", self.gateway.host, raw_header + body)
        if version != self.gateway.version or kind == 0:
            raise DoIpError("DoIP version changed or generic negative acknowledgement")
        return kind, body

    def _alive(self, kind: int, body: bytes, deadline: float) -> bool:
        if kind != 7:
            return False
        if body:
            raise DoIpError("Invalid AliveCheck request")
        self._send(8, logical(self.tester), deadline)
        return True

    def wait(self, seconds: float):
        """Answer gateway AliveChecks while idle, without consuming stale UDS replies."""
        if not 0 <= seconds <= 60 or self._busy:
            raise DoIpError("Invalid idle interval")
        end = time.monotonic() + seconds
        try:
            while time.monotonic() < end:
                wire = self._wire()
                wire.settimeout(remaining(end))
                try:
                    first = wire.recv(1)
                except socket.timeout:
                    return
                if not first:
                    raise EOFError("DoIP socket closed while idle")
                frame_end = time.monotonic() + self.timeout
                kind, body = self._receive(frame_end, first)
                if not self._alive(kind, body, frame_end):
                    raise DoIpError("Unexpected diagnostic data while idle")
        except BaseException:
            self.close()
            raise

    def exchange(self, request: bytes) -> bytes:
        """Generic protocol transport. No automatic retry; one outstanding request."""
        request = bytes(request)
        if not 1 <= len(request) <= 4091 or self._busy:
            raise DoIpError("Invalid or concurrent diagnostic request")
        self._busy = True
        deadline = time.monotonic() + self.timeout
        expected = logical(self.ecu) + logical(self.tester)
        try:
            self._send(0x8001, logical(self.tester) + logical(self.ecu) + request, deadline)
            response_deadline = min(deadline, time.monotonic() + self.p2)
            while True:
                kind, body = self._receive(response_deadline)
                remaining(response_deadline)
                if self._alive(kind, body, response_deadline):
                    continue
                if kind not in (0x8001, 0x8002, 0x8003) or len(body) < 5 or body[:4] != expected:
                    raise DoIpError("Uncorrelated diagnostic PDU")
                if kind in (0x8002, 0x8003):
                    if body[5:] != request[:len(body)-5] or kind == 0x8003 or body[4] != 0:
                        raise DoIpError("Diagnostic acknowledgement rejected")
                    continue
                reply = body[4:]
                if reply[0] == 0x7F:
                    if len(reply) != 3 or reply[1] != request[0]:
                        raise DoIpError("Uncorrelated UDS negative response")
                    if reply[2] == 0x78:
                        response_deadline = min(deadline, time.monotonic() + self.p2_star)
                        continue
                    raise NegativeResponse(reply[1], reply[2])
                return positive(request, reply)
        except BaseException:
            self.close()
            raise
        finally:
            self._busy = False

    def exchange_sequence(self, commands: tuple[bytes, ...], on_reply=lambda *_: None) -> list[bytes]:
        frozen = tuple(bytes(command) for command in commands)
        if not 1 <= len(frozen) <= 16 or any(not 1 <= len(c) <= 4091 for c in frozen) or sum(map(len, frozen)) > 4091:
            raise DoIpError("Invalid command sequence")
        replies = []
        try:
            for index, command in enumerate(frozen, 1):
                reply = self.exchange(command)
                replies.append(reply)
                on_reply(index, reply)
            return replies
        except BaseException:
            self.close()
            raise

    def read_obd(self, expected: str, did: int | None = None) -> dict:
        if did is not None and not 0xF400 <= did <= 0xF8FF:
            raise DoIpError("Expected legislated OBDonUDS DID F400..F8FF")
        try:
            vin = self.verify_vin(expected, 0xF802)
            protocol = self.read_did(0xF810)
            if len(protocol) != 1:
                raise DoIpError("Invalid F810 protocol identification")
            result = {"vin": vin, "protocol_value": protocol[0], "obd_on_uds": protocol[0] == 1}
            if did is not None:
                if not result["obd_on_uds"]:
                    raise DoIpError("Selected ECU did not identify as OBDonUDS")
                data = vin.encode("ascii") if did == 0xF802 else protocol if did == 0xF810 else self.read_did(did)
                result.update(did=did, data=data.hex().upper())
            return result
        except BaseException:
            self.close()
            raise

    def monitor(self, expected: str, did: int, samples: int = 10,
                interval: float = 1, *, obd: bool = False, on_sample=lambda _: None) -> dict:
        """Read a single DID repeatedly on the same verified ECU and revalidate identity."""
        if type(samples) is not int or not 1 <= samples <= 3600 or not 0.25 <= interval <= 60:
            raise DoIpError("Invalid monitoring sample count or interval")
        logical(did)
        if obd and not 0xF400 <= did <= 0xF8FF:
            raise DoIpError("Expected legislated OBDonUDS DID F400..F8FF")
        try:
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("Selected ECU did not identify as OBDonUDS")
            else:
                self.verify_vin(expected)
            rows = []
            start = time.monotonic()
            for index in range(1, samples + 1):
                data = self.read_did(did)
                if not 1 <= len(data) <= 4096:
                    raise DoIpError("Missing or oversized DID monitoring sample")
                row = {"index": index, "utc": datetime.now(timezone.utc).isoformat(),
                       "elapsed_ms": int((time.monotonic() - start) * 1000),
                       "data": data.hex().upper()}
                rows.append(row)
                on_sample(dict(row))
                if index < samples:
                    self.wait(interval)
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("OBDonUDS identification changed during monitoring")
            else:
                self.verify_vin(expected)
            return {"vin": expected, "did": did, "service_protocol": "OBDonUDS" if obd else "UDS",
                    "samples": rows}
        except BaseException:
            self.close()
            raise

    def verify_vin(self, expected: str, vin_did: int = 0xF190) -> str:
        if not valid_vin(expected) or self.gateway.vin != expected:
            self.close()
            raise DoIpError("Expected VIN differs from the observed gateway")
        try:
            if vin_did not in (0xF190, 0xF802):
                raise DoIpError("Invalid VIN DID")
            reply = self.exchange(b"\x22" + logical(vin_did))
            vin = reply[3:].decode("ascii")
            if vin != expected:
                raise DoIpError("ECU VIN mismatch")
            return vin
        except BaseException:
            self.close()
            raise

    def read_did(self, did: int) -> bytes:
        reply = self.exchange(b"\x22" + logical(did))
        if len(reply) < 4:
            self.close()
            raise DoIpError("Missing DID data")
        return reply[3:]

    def dtc_count(self, mask: int) -> dict:
        reply = self.exchange(bytes((0x19, 1, mask)))
        if len(reply) != 6:
            self.close()
            raise DoIpError("Invalid DTC count response")
        return {"requested_mask": mask, "availability_mask": reply[2],
                "format": reply[3], "count": int.from_bytes(reply[4:], "big")}

    def dtcs(self, mask: int) -> dict:
        reply = self.exchange(bytes((0x19, 2, mask)))
        if len(reply) < 3 or (len(reply) - 3) % 4:
            self.close()
            raise DoIpError("Invalid DTC list")
        available = reply[2]
        records = [{"dtc": reply[i:i+3].hex().upper(), "status": reply[i+3]}
                   for i in range(3, len(reply), 4)]
        if len({x["dtc"] for x in records}) != len(records) or any(
                not x["status"] & mask & available or x["status"] & ~available for x in records):
            self.close()
            raise DoIpError("Uncorrelated DTC status")
        return {"requested_mask": mask, "availability_mask": available, "records": records}

    def dtc_detail(self, dtc: int, record: int, extended: bool) -> dict:
        if not 0 <= dtc <= 0xFFFFFF or not 1 <= record <= 255:
            raise DoIpError("Invalid DTC or record")
        code = dtc.to_bytes(3, "big")
        reply = self.exchange(bytes((0x19, 6 if extended else 4)) + code + bytes((record,)))
        if len(reply) < 6 or reply[2:5] != code:
            self.close()
            raise DoIpError("DTC detail mismatch")
        return {"dtc": code.hex().upper(), "status": reply[5], "raw_records": reply[6:].hex().upper()}
