"""Direct ISO 13400 network client on the host, not the USB prototype firmware."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import ipaddress
import re
import select
import socket
import ssl
import struct
import threading
import time
from typing import Callable

MAX_PAYLOAD = 1024 * 1024
VERSIONS = (2, 3, 4)
Trace = Callable[[str, str, str, str], None]
InventoryTrace = Callable[[int, str, str, str, str], None]


class InventoryTraceError(RuntimeError):
    """A failed trace sink must not be mistaken for an unverified ECU."""


class DoIpError(ValueError):
    pass


class TransportRefusal(DoIpError):
    """Exact ISO 13400 refusal layer and numeric code, never an inferred NRC."""
    def __init__(self, layer: str, code: int):
        if layer not in ("header_nack", "routing_denied", "diagnostic_nack") or (
                type(code) is not int or not 0 <= code <= 255):
            raise ValueError("Invalid DoIP refusal layer or code")
        self.layer, self.code = layer, code
        super().__init__(f"DoIP {layer} 0x{code:02X}")


class NegativeResponse(DoIpError):
    def __init__(self, service: int, code: int):
        self.service, self.code = service, code
        super().__init__(f"UDS NRC {service:02X}/{code:02X}")


def dtc_status_flags(status: int, availability: int) -> list[str]:
    """ISO 14229 status bit identifiers, not manufacturer DTC definitions."""
    names = ("test_failed", "test_failed_this_operation_cycle", "pending_dtc", "confirmed_dtc",
             "test_not_completed_since_last_clear", "test_failed_since_last_clear",
             "test_not_completed_this_operation_cycle", "warning_indicator_requested")
    return [name for bit, name in enumerate(names) if status & availability & (1 << bit)]


def dtc_filter(mask: int, availability: int) -> dict:
    effective = mask & availability
    return {"requested_mask": mask, "availability_mask": availability,
            "effective_mask": effective, "filter_available": bool(effective)}


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


def require_no_header_nack(kind: int, payload: bytes) -> None:
    """Classify only a structurally valid generic DoIP NACK (one code byte)."""
    if kind != 0:
        return
    if len(payload) != 1 or payload[0] not in range(5):
        raise DoIpError("Malformed generic DoIP header NACK")
    raise TransportRefusal("header_nack", payload[0])


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
    require_no_header_nack(kind, body)
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
    current = discover(gateway.host, gateway.vin, timeout, trace, port)
    if gateway_key(current) != gateway_key(gateway):
        raise DoIpError("Gateway identity changed before control query")
    request = encode(gateway.version, 0x4003 if power else 0x4001)
    version, kind, body = decode(_udp(gateway.host, request, timeout, trace, port))
    if version != gateway.version:
        raise DoIpError("Gateway version changed")
    require_no_header_nack(kind, body)
    if power:
        if kind != 0x4004 or len(body) != 1 or body[0] not in (0, 1, 2):
            raise DoIpError("Invalid diagnostic power mode")
        return {"power_mode": body[0]}
    if kind != 0x4002 or len(body) not in (3, 7) or body[0] not in (0, 1) or body[2] > body[1]:
        raise DoIpError("Invalid entity status")
    return {"node_type": body[0], "max_sockets": body[1], "current_sockets": body[2],
            "max_data_size": int.from_bytes(body[3:], "big") if len(body) == 7 else None}


def parse_did_layout(text: str) -> tuple[tuple[int, int], ...]:
    """Explicit DID lengths from ODX/standard, never infer geometry from an identifier."""
    fields = text.split(",")
    if not 2 <= len(fields) <= 32:
        raise DoIpError("Expected 2..32 DID:length entries")
    layout = []
    for field in fields:
        parts = field.split(":")
        if len(parts) != 2 or len(parts[0]) != 4 or any(c not in "0123456789abcdefABCDEF" for c in parts[0]) or not parts[1].isascii() or not parts[1].isdigit() or len(parts[1]) > 4:
            raise DoIpError("Expected hexadecimal DID and decimal byte length")
        layout.append((int(parts[0], 16), int(parts[1])))
    return validate_did_layout(tuple(layout))


def validate_did_layout(layout: tuple[tuple[int, int], ...]) -> tuple[tuple[int, int], ...]:
    layout = tuple(tuple(entry) for entry in layout)
    if not 2 <= len(layout) <= 32:
        raise DoIpError("Expected 2..32 DIDs")
    if any(len(entry) != 2 or type(entry[0]) is not int or type(entry[1]) is not int or
           not 0 <= entry[0] <= 65535 or not 1 <= entry[1] <= 4096 for entry in layout):
        raise DoIpError("Invalid DID or length")
    if len({entry[0] for entry in layout}) != len(layout) or 1 + sum(2 + entry[1] for entry in layout) > 65536:
        raise DoIpError("Duplicate DID or response size exceeds limit")
    return layout


def validate_monitor_layout(layout: tuple[tuple[int, int], ...]) -> tuple[tuple[int, int], ...]:
    layout = validate_did_layout(layout)
    if 1 + sum(2 + length for _, length in layout) > 4096:
        raise DoIpError("Multi-DID monitor sample exceeds 4096 bytes")
    return layout


def decode_did_layout(reply: bytes, layout: tuple[tuple[int, int], ...]) -> list[dict]:
    layout = validate_did_layout(layout)
    if len(reply) != 1 + sum(2 + length for _, length in layout) or reply[0] != 0x62:
        raise DoIpError("Multi-DID response length or service mismatch")
    offset = 1
    result = []
    for did, length in layout:
        if reply[offset:offset+2] != logical(did):
            raise DoIpError("Multi-DID response order or identifier mismatch")
        offset += 2
        result.append({"did": f"{did:04X}", "length": length, "data": reply[offset:offset+length].hex().upper()})
        offset += length
    return result


def require_sample_identity(rows: list[dict], expected: str, obd: bool) -> None:
    for row in rows:
        did = int(row["did"], 16)
        data = bytes.fromhex(row["data"])
        if did == (0xF802 if obd else 0xF190) and data != expected.encode("ascii"):
            raise DoIpError("Sample VIN differs from selected ECU")
        if obd and did == 0xF810 and data != b"\x01":
            raise DoIpError("Sample protocol differs from verified OBDonUDS")


def parse_stored_did_lengths(text: str) -> dict[int, int]:
    """A DID length catalog, not an assumed ordering of stored data."""
    fields = text.split(",")
    if not 1 <= len(fields) <= 255:
        raise DoIpError("Expected 1..255 DID:length entries")
    result = {}
    for field in fields:
        parts = field.split(":")
        if (len(parts) != 2 or len(parts[0]) != 4 or
                any(c not in "0123456789abcdefABCDEF" for c in parts[0]) or
                not parts[1].isascii() or not parts[1].isdigit() or len(parts[1]) > 4):
            raise DoIpError("Expected hexadecimal DID and decimal byte length")
        did, length = int(parts[0], 16), int(parts[1])
        if did in result or not 1 <= length <= 4096:
            raise DoIpError("Duplicate DID or invalid stored DID length")
        result[did] = length
    return result


def validate_stored_did_lengths(lengths: dict[int, int]) -> dict[int, int]:
    if (not isinstance(lengths, dict) or not 1 <= len(lengths) <= 255 or
            any(type(did) is not int or not 0 <= did <= 65535 or
                type(size) is not int or not 1 <= size <= 4096
                for did, size in lengths.items())):
        raise DoIpError("Invalid stored DID length catalog")
    return dict(lengths)


def decode_stored_records(record: int, reply: bytes, lengths: dict[int, int]) -> list[dict]:
    if type(record) is not int or not 0 <= record <= 255:
        raise DoIpError("Invalid stored record selector")
    catalog = validate_stored_did_lengths(lengths)
    positive(bytes((0x19, 5, record)), reply)
    if len(reply) == 3:
        return []
    rows = decode_all_stored_records(reply, catalog)
    if record != 255 and (len(rows) != 1 or rows[0]["record"] != record):
        raise DoIpError("Unexpected stored record")
    return rows


def decode_all_stored_records(reply: bytes, lengths: dict[int, int]) -> list[dict]:
    """19/05 FF: parse successive records only with documented DID lengths."""
    catalog = validate_stored_did_lengths(lengths)
    if len(reply) < 3 or reply[:2] != b"\x59\x05":
        raise DoIpError("Invalid stored record response")
    if reply == b"\x59\x05\xff":
        return []
    offset, rows, seen = 2, [], set()
    while offset < len(reply):
        start = offset
        if len(reply) - offset < 6:
            raise DoIpError("Truncated stored record header")
        record, dtc, status, count = (reply[offset], reply[offset+1:offset+4].hex().upper(),
                                      reply[offset+4], reply[offset+5])
        if record == 255 or record in seen:
            raise DoIpError("Invalid or duplicate stored record number")
        seen.add(record)
        offset += 6
        dids = []
        for _ in range(count):
            if len(reply) - offset < 2:
                raise DoIpError("Truncated stored DID")
            did = int.from_bytes(reply[offset:offset+2], "big")
            size = catalog.get(did)
            if size is None or len(reply) - offset - 2 < size:
                raise DoIpError("Unknown DID length or truncated stored data")
            offset += 2
            dids.append({"did": f"{did:04X}", "length": size, "data": reply[offset:offset+size].hex().upper()})
            offset += size
        rows.append({"record": record, "dtc": dtc, "status": status, "did_count": count,
                     "dids": dids, "raw_record": reply[start:offset].hex().upper()})
    return rows


def parse_extended_record_lengths(text: str) -> dict[int, int]:
    """Documented extended-record number and payload length (not DID length)."""
    catalog = {}
    fields = text.split(",")
    if not 1 <= len(fields) <= 239:
        raise DoIpError("Expected 1..239 record:length entries")
    for field in fields:
        parts = field.split(":")
        if (len(parts) != 2 or not re.fullmatch(r"[0-9A-Fa-f]{2}", parts[0]) or
                not re.fullmatch(r"[0-9]{1,4}", parts[1])):
            raise DoIpError("Expected hexadecimal record and decimal length")
        number, size = int(parts[0], 16), int(parts[1])
        if number in catalog or not 1 <= number <= 0xEF or not 1 <= size <= 4090:
            raise DoIpError("Invalid or duplicate extended record length")
        catalog[number] = size
    return catalog


def extended_records_request(dtc: int, record: int, lengths: dict[int, int]) -> bytes:
    if (type(dtc) is not int or not 0 <= dtc <= 0xFFFFFF or type(record) is not int or
            not (1 <= record <= 0xEF or record in (0xFE, 0xFF))):
        raise DoIpError("Invalid extended data selector")
    if (not isinstance(lengths, dict) or not 1 <= len(lengths) <= 239 or
            any(type(n) is not int or not 1 <= n <= 0xEF or type(size) is not int or
                not 1 <= size <= 4090 for n, size in lengths.items())):
        raise DoIpError("Invalid extended record length catalog")
    if record <= 0xEF and record not in lengths:
        raise DoIpError("Selected record length is undocumented")
    return b"\x19\x06" + dtc.to_bytes(3, "big") + bytes((record,))


def decode_extended_records(dtc: int, record: int, reply: bytes, lengths: dict[int, int]) -> dict:
    catalog = dict(lengths)
    request = extended_records_request(dtc, record, catalog)
    positive(request, reply)
    rows, seen, offset = [], set(), 6
    while offset < len(reply):
        number = reply[offset]
        size = catalog.get(number)
        if (size is None or number in seen or
                (record == 0xFE and not 0x90 <= number <= 0x9F) or
                (record <= 0xEF and number != record) or len(reply) - offset - 1 < size):
            raise DoIpError("Unexpected, duplicate, unknown or truncated extended record")
        seen.add(number)
        end = offset + 1 + size
        rows.append({"record": number, "length": size, "data": reply[offset+1:end].hex().upper()})
        offset = end
    return {"dtc": f"{dtc:06X}", "status": reply[5], "requested_record": record,
            "records": rows, "raw_response": reply.hex().upper()}


def decode_snapshot_records(dtc: int, record: int, reply: bytes, lengths: dict[int, int]) -> dict:
    """Decode 19/04 with documented DID lengths; retain historical values as bytes."""
    catalog = validate_stored_did_lengths(lengths)
    if type(dtc) is not int or not 0 <= dtc <= 0xFFFFFF or type(record) is not int or not 0 <= record <= 255:
        raise DoIpError("Invalid snapshot selector")
    request = b"\x19\x04" + dtc.to_bytes(3, "big") + bytes((record,))
    positive(request, reply)
    offset, rows, seen = 6, [], set()
    while offset < len(reply):
        start = offset
        if len(reply) - offset < 2:
            raise DoIpError("Truncated snapshot header")
        number, count = reply[offset:offset+2]
        if number == 255 or number in seen or (record != 255 and record != number):
            raise DoIpError("Invalid, duplicate or unexpected snapshot record")
        seen.add(number)
        offset += 2
        dids = []
        for _ in range(count):
            if len(reply) - offset < 2:
                raise DoIpError("Truncated snapshot DID")
            did = int.from_bytes(reply[offset:offset+2], "big")
            size = catalog.get(did)
            offset += 2
            if size is None or len(reply) - offset < size:
                raise DoIpError("Unknown DID length or truncated snapshot data")
            dids.append({"did": f"{did:04X}", "length": size, "data": reply[offset:offset+size].hex().upper()})
            offset += size
        rows.append({"record": number, "did_count": count, "dids": dids,
                     "raw_record": reply[start:offset].hex().upper()})
    return {"dtc": f"{dtc:06X}", "status": reply[5], "requested_record": record,
            "records": rows, "raw_response": reply.hex().upper()}


def positive(request: bytes, reply: bytes) -> bytes:
    if not request or not reply or reply[0] != (request[0] + 0x40) & 0xFF:
        raise DoIpError("UDS response service mismatch")
    echo = {0x22: 2, 0x24: 2, 0x2E: 2, 0x2F: 2, 0x36: 1}.get(request[0], 0)
    if echo and (len(request) < echo + 1 or len(reply) < echo + 1 or
                 reply[1:echo+1] != request[1:echo+1]):
        raise DoIpError("UDS response echo mismatch")
    if request[0] == 0x22 and len(reply) < 4:
        raise DoIpError("Missing DID data")
    if request[0] == 0x23 and len(reply) < 2:
        raise DoIpError("Missing memory data")
    if request[0] == 0x19:
        if len(request) < 2 or len(reply) < 2 or reply[1] != request[1] & 0x7F:
            raise DoIpError("UDS DTC subfunction mismatch")
        subfunction = request[1] & 0x7F
        if subfunction in (3, 0x14):
            if len(request) != 2 or (len(reply) - 2) % 4:
                raise DoIpError("Malformed DTC index or fault counter report")
        elif len(reply) < 3 or (subfunction in (0x0A, 0x15) and (len(request) != 2 or (len(reply) - 3) % 4)):
            raise DoIpError("Malformed DTC report")
        if subfunction == 5:
            if len(request) != 3 or len(reply) < 3 or (request[2] != 255 and reply[2] != request[2]):
                raise DoIpError("Stored DTC record response mismatch")
            if 3 < len(reply) < 8 or (request[2] == 255 and
                    ((len(reply) == 3 and reply[2] != 255) or (len(reply) > 3 and reply[2] == 255))):
                raise DoIpError("Truncated or invalid stored DTC record")
            if len(reply) >= 8 and (len(reply) - 8 < reply[7] * 2 or
                    (request[2] != 255 and reply[7] == 0 and len(reply) != 8)):
                raise DoIpError("Truncated or inconsistent stored DTC DID count")
        if subfunction in (0x16, 0x1A):
            offset = 2 if subfunction == 0x16 else 3
            if (len(request) != 3 or len(reply) <= offset or reply[offset] != request[2] or
                    (subfunction == 0x1A and (len(reply) - 4) % 4)):
                raise DoIpError("Extended data record response mismatch")
        if subfunction == 0x17:
            if len(request) != 4 or len(reply) < 4 or reply[2] != request[3] or (len(reply) - 4) % 4:
                raise DoIpError("User-defined DTC memory response mismatch")
        if subfunction in (0x18, 0x19):
            if len(request) != 7 or len(reply) < 7 or reply[2] != request[6] or reply[3:6] != request[2:5]:
                raise DoIpError("User-defined DTC detail identity mismatch")
            selected = request[5]
            if len(reply) >= 8 and selected != 0xFF and (subfunction != 0x19 or selected != 0xFE) and reply[7] != selected:
                raise DoIpError("User-defined DTC record mismatch")
        if subfunction == 9:
            if len(request) != 5 or len(reply) not in (3, 9) or (len(reply) == 9 and reply[5:8] != request[2:5]):
                raise DoIpError("DTC severity identity mismatch")
        if subfunction in (0x0F, 0x13) and (len(request) != 3 or (len(reply) - 3) % 4):
            raise DoIpError("Malformed legacy DTC list")
        if subfunction in (0x11, 0x12) and (len(request) != 3 or len(reply) != 6):
            raise DoIpError("Malformed legacy DTC count")
        if subfunction in (0x04, 0x06, 0x10):
            if len(request) != 6 or len(reply) < 6 or reply[2:5] != request[2:5]:
                raise DoIpError("UDS DTC identity mismatch")
            selected = request[5]
            if len(reply) >= 7 and selected != 0xFF and (subfunction == 0x04 or selected != 0xFE) and reply[6] != selected:
                raise DoIpError("UDS DTC record mismatch")
        if subfunction in (0x42, 0x55, 0x56):
            if len(request) < 3 or reply[2] != request[2]:
                raise DoIpError("WWH functional group mismatch")
            if subfunction == 0x56 and (len(request) != 4 or len(reply) < 6 or reply[5] != request[3]):
                raise DoIpError("WWH readiness group mismatch")
    if request[0] in (0x10, 0x11, 0x27, 0x28, 0x3E, 0x85, 0x31):
        if len(request) < 2 or len(reply) < 2 or reply[1] != request[1] & 0x7F:
            raise DoIpError("UDS subfunction mismatch")
        if request[0] == 0x31 and (len(request) < 4 or len(reply) < 4 or reply[2:4] != request[2:4]):
            raise DoIpError("UDS routine identifier mismatch")
    return reply


def parse_ecu_inventory(value: str) -> tuple[int, ...]:
    """Only explicitly supplied logical addresses; no ranges or implicit scans."""
    items = value.split(",")
    if not 1 <= len(items) <= 16 or any(not re.fullmatch(r"[0-9A-Fa-f]{4}", item) for item in items):
        raise DoIpError("Supply 1..16 explicit four-digit logical ECU addresses")
    targets = tuple(int(item, 16) for item in items)
    if len(set(targets)) != len(targets):
        raise DoIpError("Duplicate ECU address")
    return targets


def inventory_ecus(gateway: Gateway, expected: str, tester: int, targets: tuple[int, ...],
                   *, tls: bool = True, timeout: float = 3, trace: Trace | None = None,
                   context: ssl.SSLContext | None = None, p2: float | None = None,
                   p2_star: float | None = None, routing_type: int = 0,
                   routing_oem_data: bytes | None = None, obd: bool = False,
                   target_trace: InventoryTrace | None = None,
                   on_progress: Callable[[list[dict], int], None] | None = None) -> dict:
    """Identify explicit ECU addresses independently using the requested diagnostic operation."""
    if type(obd) is not bool:
        raise DoIpError("Invalid ECU inventory identification mode")
    targets = tuple(targets)
    logical(tester)
    for ecu in targets:
        logical(ecu)
    if (not valid_vin(expected) or gateway.vin != expected or tester == gateway.logical_address or
            not 1 <= len(targets) <= 16 or len(set(targets)) != len(targets) or tester in targets):
        raise DoIpError("Invalid ECU inventory target or gateway identity")
    _timeout(timeout)
    rows = []
    def require_gateway(sink: Trace | None):
        current = discover(gateway.host, expected, timeout, sink)
        if gateway_key(current) != gateway_key(gateway):
            raise DoIpError("Gateway identity changed during ECU inventory")

    def sink_for(ecu: int) -> Trace | None:
        if target_trace is None and trace is None:
            return None
        def sink(direction: str, transport: str, host: str, pdu: str):
            try:
                if target_trace is not None:
                    target_trace(ecu, direction, transport, host, pdu)
                elif trace is not None:
                    trace(direction, transport, host, pdu)
            except Exception as error:
                raise InventoryTraceError("ECU inventory trace failed") from error
        return sink

    for ecu in targets:
        sink = sink_for(ecu)
        require_gateway(sink)
        try:
            with Session(gateway, tester, ecu, tls=tls, timeout=timeout, trace=sink, context=context,
                         p2=p2, p2_star=p2_star, routing_type=routing_type,
                         routing_oem_data=routing_oem_data) as session:
                if obd:
                    identity = session.identify_obd(expected)
                    vin = identity["vin"]
                    protocol = identity["protocol_value"]
                    details = {"protocol_value": protocol, "obd_on_uds": identity["obd_on_uds"]}
                else:
                    vin = session.verify_vin(expected)
                    details = {}
                if vin != expected:
                    raise DoIpError("ECU VIN does not match selected vehicle")
                verified = {"ecu": f"{ecu:04X}", "status": "vin_verified", "vin": vin} | details
            # A successful VIN read does not complete the target if session
            # shutdown fails. Publish exactly one observation per target.
            rows.append(verified)
        except ssl.SSLError:
            # Never continue or downgrade a failed TLS authentication.
            raise
        except TransportRefusal as error:
            rows.append({"ecu": f"{ecu:04X}", "status": "unverified",
                         "reason": error.layer, "failure_code": error.code})
        except NegativeResponse as error:
            rows.append({"ecu": f"{ecu:04X}", "status": "unverified",
                         "reason": "uds_negative_response", "failure_code": error.code,
                         "nrc": error.code, "requested_service": error.service})
        except (TimeoutError, socket.timeout):
            rows.append({"ecu": f"{ecu:04X}", "status": "unverified", "reason": "timeout"})
        except (DoIpError, OSError, EOFError):
            rows.append({"ecu": f"{ecu:04X}", "status": "unverified",
                         "reason": "communication_or_identity_failure"})
        # Closing the routed socket and verifying the *same* gateway are
        # prerequisites to exposing this row. The sink is never invoked for
        # an uncertain target or a substituted gateway.
        require_gateway(sink)
        if on_progress is not None:
            on_progress([dict(row) for row in rows], len(targets))
    return {"vin": expected, "complete": True, "targets": rows,
            "identification": "F802/F810" if obd else "F190",
            "verified_count": sum(row["status"] == "vin_verified" for row in rows),
            **({"obd_on_uds_count": sum(row.get("obd_on_uds") is True for row in rows)} if obd else {}),
            "physical_qualification": False}


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
        self._operation_lock = threading.Lock()
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
                    raise TransportRefusal("routing_denied", body[4])
        except BaseException:
            wire.close()
            self._socket = None
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        # Retire the session even if closing the operating-system socket fails.
        wire, self._socket = self._socket, None
        if wire is not None:
            wire.close()

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
        if self._socket is not wire:
            raise DoIpError("Session closed during DoIP transmission")
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
        wire = self._wire()
        raw_header = first + self._read_exact(8 - len(first), deadline)
        version, kind, length = header(raw_header)
        body = self._read_exact(length, deadline)
        _emit(self.trace, "RX", "TLS" if self.tls else "TCP", self.gateway.host, raw_header + body)
        if self._socket is not wire:
            raise DoIpError("Session closed during DoIP reception")
        remaining(deadline)
        if version != self.gateway.version:
            raise DoIpError("DoIP response changed negotiated protocol version")
        require_no_header_nack(kind, body)
        return kind, body

    def _alive(self, kind: int, body: bytes, deadline: float) -> bool:
        if kind != 7:
            return False
        if body:
            raise DoIpError("Invalid AliveCheck request")
        self._send(8, logical(self.tester), deadline)
        return True

    def _drain_ready(self, deadline: float):
        """Service already queued control frames before another UDS request.
        
        A queued diagnostic response belongs to no outstanding request. This
        cannot detect every late frame racing the next transmission.
        """
        while True:
            remaining(deadline)
            wire = self._wire()
            tls_pending = isinstance(wire, ssl.SSLSocket) and wire.pending() > 0
            if not tls_pending and not select.select([wire], [], [], 0)[0]:
                return
            # TLS control traffic can make the underlying socket readable
            # without an application byte. Do not wait a full P2 interval.
            wire.settimeout(min(0.01, remaining(deadline)))
            try:
                first = wire.recv(1)
            except socket.timeout:
                if isinstance(wire, ssl.SSLSocket):
                    return
                raise
            if not first:
                raise EOFError("DoIP socket closed before next exchange")
            try:
                kind, body = self._receive(deadline, first)
            except TransportRefusal as error:
                # No request was outstanding: do not attribute a stale NACK
                # to the next, unsent diagnostic command.
                raise DoIpError("Unsolicited header NACK before request") from error
            if not self._alive(kind, body, deadline):
                raise DoIpError("Unexpected diagnostic data before next request")

    def wait(self, seconds: float):
        """Answer gateway AliveChecks while idle, without consuming stale UDS replies."""
        if not 0 <= seconds <= 60 or not self._operation_lock.acquire(blocking=False):
            raise DoIpError("Invalid or concurrent idle interval")
        self._busy = True
        try:
            end = time.monotonic() + seconds
            while True:
                wire = self._wire()
                # An expired idle interval before the first byte is normal,
                # including expiration between clock evaluation and settimeout.
                try:
                    budget = remaining(end)
                except TimeoutError:
                    return
                wire.settimeout(budget)
                try:
                    first = wire.recv(1)
                except socket.timeout:
                    return
                if not first:
                    raise EOFError("DoIP socket closed while idle")
                frame_end = time.monotonic() + self.timeout
                try:
                    kind, body = self._receive(frame_end, first)
                except TransportRefusal as error:
                    raise DoIpError("Unsolicited header NACK while idle") from error
                if not self._alive(kind, body, frame_end):
                    raise DoIpError("Unexpected diagnostic data while idle")
        except BaseException:
            self.close()
            raise
        finally:
            self._busy = False
            self._operation_lock.release()

    def exchange(self, request: bytes, *, overall_deadline: float | None = None) -> bytes:
        """One exclusive UDS request; contention must not close its owner."""
        request = bytes(request)
        if not 1 <= len(request) <= 4091:
            raise DoIpError("Invalid diagnostic request")
        if not self._operation_lock.acquire(blocking=False):
            raise DoIpError("Concurrent diagnostic request")
        self._busy = True
        try:
            return self._exchange_owned(request, overall_deadline=overall_deadline)
        except BaseException:
            self.close()
            raise
        finally:
            self._busy = False
            self._operation_lock.release()

    def _exchange_owned(self, request: bytes, *, overall_deadline: float | None = None) -> bytes:
        """Exchange with the exclusive operation lock already held.

        Callers must retire this session on any uncertain exchange or callback
        failure. Do not call from another thread without the operation lock.
        """
        deadline = time.monotonic() + self.timeout
        if overall_deadline is not None:
            deadline = min(deadline, overall_deadline)
        expected = logical(self.ecu) + logical(self.tester)
        self._drain_ready(deadline)
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
                if body[5:] != request[:len(body)-5]:
                    raise DoIpError("Uncorrelated diagnostic acknowledgement echo")
                if kind == 0x8003:
                    if body[4] == 0:
                        raise DoIpError("Invalid diagnostic NACK code")
                    raise TransportRefusal("diagnostic_nack", body[4])
                if body[4] != 0:
                    raise DoIpError("Invalid positive diagnostic acknowledgement")
                continue
            reply = body[4:]
            if reply[0] == 0x7F:
                if len(reply) != 3 or reply[1] != request[0]:
                    raise DoIpError("Uncorrelated UDS negative response")
                if reply[2] == 0x78:
                    response_deadline = min(deadline, time.monotonic() + self.p2_star)
                    continue
                raise NegativeResponse(reply[1], reply[2])
            result = positive(request, reply)
            remaining(response_deadline)
            return result

    def exchange_sequence(self, commands: tuple[bytes, ...], on_reply=lambda *_: None, *,
                          expected_vin: str | None = None) -> list[bytes]:
        """One exclusive sequence including the ECU VIN check before command TX."""
        frozen = tuple(bytes(command) for command in commands)
        if (not 1 <= len(frozen) <= 16 or
                any(not 1 <= len(command) <= 4091 for command in frozen) or
                sum(map(len, frozen)) > 4091):
            raise DoIpError("Invalid command sequence")
        if expected_vin is not None and (
                not valid_vin(expected_vin) or self.gateway.vin != expected_vin):
            raise DoIpError("Sequence VIN differs from observed gateway")
        if not self._operation_lock.acquire(blocking=False):
            raise DoIpError("Concurrent diagnostic sequence")
        self._busy = True
        replies = []
        try:
            if expected_vin is not None:
                # The VIN request and the first approved command share the
                # same lock. No caller can change the ECU context in between.
                observed_reply = self._exchange_owned(bytes.fromhex("22F190"))
                if len(observed_reply) != 20:
                    raise DoIpError("Malformed ECU VIN during confirmed sequence")
                try:
                    observed_vin = observed_reply[3:].decode("ascii")
                except UnicodeDecodeError as error:
                    raise DoIpError("Malformed ECU VIN during confirmed sequence") from error
                if observed_vin != expected_vin:
                    raise DoIpError("ECU VIN changed before confirmed sequence")
            for index, command in enumerate(frozen, 1):
                reply = self._exchange_owned(command)
                replies.append(reply)
                on_reply(index, reply)
            return replies
        except BaseException:
            self.close()
            raise
        finally:
            self._busy = False
            self._operation_lock.release()

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
                if not data:
                    raise DoIpError("Missing DID data")
                self.verify_vin(expected, 0xF802)
                if self.read_did(0xF810) != protocol:
                    raise DoIpError("OBDonUDS identification changed during DID read")
                result.update(did=did, data=data.hex().upper())
            return result
        except BaseException:
            self.close()
            raise

    def identify_obd(self, expected: str) -> dict:
        """Stable discovery; unknown protocol values do not authorize a data route."""
        try:
            first = self.read_obd(expected)
            second = self.read_obd(expected)
            if first != second:
                raise DoIpError("OBD protocol identification changed during discovery")
            return second
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
        def verify():
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("Selected ECU did not identify as OBDonUDS")
            else:
                self.verify_vin(expected)
        try:
            verify()
            rows = []
            start = time.monotonic()
            for index in range(1, samples + 1):
                data = self.read_did(did)
                if not 1 <= len(data) <= 4096:
                    raise DoIpError("Missing or oversized DID monitoring sample")
                require_sample_identity([{"did": f"{did:04X}", "data": data.hex()}], expected, obd)
                row = {"index": index, "utc": datetime.now(timezone.utc).isoformat(),
                       "elapsed_ms": int((time.monotonic() - start) * 1000),
                       "data": data.hex().upper()}
                # Publish only after identity has been checked on this same route.
                # A subsequent failure must not invalidate already verified rows.
                verify()
                rows.append(row)
                on_sample(dict(row))
                if index < samples:
                    self.wait(interval)
            return {"vin": expected, "did": did, "service_protocol": "OBDonUDS" if obd else "UDS",
                    "samples": rows}
        except BaseException:
            self.close()
            raise

    def monitor_dids(self, expected: str, layout: tuple[tuple[int, int], ...],
                     samples: int = 10, interval: float = 1, *, obd: bool = False,
                     on_sample=lambda _: None) -> dict:
        layout = validate_monitor_layout(layout)
        if type(samples) is not int or not 1 <= samples <= 3600 or not 0.25 <= interval <= 60:
            raise DoIpError("Invalid monitoring sample count or interval")
        if obd and any(not 0xF400 <= did <= 0xF8FF for did, _ in layout):
            raise DoIpError("Expected legislated OBDonUDS DIDs")
        def verify():
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("Selected ECU did not identify as OBDonUDS")
            else:
                self.verify_vin(expected)
        try:
            verify()
            request = b"\x22" + b"".join(logical(did) for did, _ in layout)
            rows = []
            start = time.monotonic()
            for index in range(1, samples + 1):
                values = decode_did_layout(self.exchange(request), layout)
                require_sample_identity(values, expected, obd)
                row = {"index": index, "utc": datetime.now(timezone.utc).isoformat(),
                       "elapsed_ms": int((time.monotonic() - start) * 1000), "dids": values}
                verify()
                rows.append(row)
                on_sample({**row, "dids": [dict(item) for item in values]})
                if index < samples:
                    self.wait(interval)
            return {"vin": expected, "service_protocol": "OBDonUDS" if obd else "UDS", "samples": rows}
        except BaseException:
            self.close()
            raise

    def verify_vin(self, expected: str, vin_did: int = 0xF190, *,
                   overall_deadline: float | None = None) -> str:
        if not valid_vin(expected) or self.gateway.vin != expected:
            self.close()
            raise DoIpError("Expected VIN differs from the observed gateway")
        try:
            if vin_did not in (0xF190, 0xF802):
                raise DoIpError("Invalid VIN DID")
            request = b"\x22" + logical(vin_did)
            reply = (self.exchange(request) if overall_deadline is None else
                     self.exchange(request, overall_deadline=overall_deadline))
            try:
                vin = reply[3:].decode("ascii")
            except UnicodeDecodeError as error:
                raise DoIpError("Malformed ECU VIN encoding") from error
            if vin != expected:
                raise DoIpError("ECU VIN mismatch")
            return vin
        except BaseException:
            self.close()
            raise

    def read_did(self, did: int, *, overall_deadline: float | None = None) -> bytes:
        request = b"\x22" + logical(did)
        reply = (self.exchange(request) if overall_deadline is None else
                 self.exchange(request, overall_deadline=overall_deadline))
        if len(reply) < 4:
            self.close()
            raise DoIpError("Missing DID data")
        return reply[3:]

    def read_verified_did(self, expected: str, did: int) -> bytes:
        """Read one generic DID and validate any returned identity before publication."""
        logical(did)
        try:
            vin = self.verify_vin(expected)
            if did == 0xF190:
                return vin.encode("ascii")
            data = self.read_did(did)
            if not data:
                raise DoIpError("Missing DID data")
            self.verify_vin(expected)
            return data
        except BaseException:
            self.close()
            raise

    def read_scaling_did(self, expected: str, did: int) -> bytes:
        """Read ISO 14229 SID 24 metadata; retain raw ECU-provided scaling records."""
        request = b"\x24" + logical(did)
        try:
            self.verify_vin(expected)
            reply = self.exchange(request)
            if len(reply) < 4 or reply[:3] != b"\x64" + request[1:]:
                raise DoIpError("Invalid scaling DID response")
            self.verify_vin(expected)
            return reply[3:]
        except BaseException:
            self.close()
            raise

    def read_dids(self, expected: str, layout: tuple[tuple[int, int], ...], *, obd: bool = False) -> dict:
        layout = validate_did_layout(layout)
        if obd and any(not 0xF400 <= did <= 0xF8FF for did, _ in layout):
            raise DoIpError("Expected legislated OBDonUDS DIDs")
        try:
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("Selected ECU did not identify as OBDonUDS")
            else:
                self.verify_vin(expected)
            request = b"\x22" + b"".join(logical(did) for did, _ in layout)
            rows = decode_did_layout(self.exchange(request), layout)
            require_sample_identity(rows, expected, obd)
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("OBDonUDS identification changed")
            else:
                self.verify_vin(expected)
            return {"vin": expected, "service_protocol": "OBDonUDS" if obd else "UDS", "dids": rows}
        except BaseException:
            self.close()
            raise

    def read_verified_dtc(self, expected: str, action: str, *, mask: int = 255,
                          dtc: int | None = None, record: int | None = None, obd: bool = False) -> dict:
        """Publish typed DTC data after same-session UDS or OBDonUDS identity revalidation."""
        if action not in ("dtcs", "count", "snapshot", "extended"):
            raise DoIpError("Unsupported DTC read action")
        if action in ("dtcs", "count"):
            if type(mask) is not int or not 0 <= mask <= 255:
                raise DoIpError("Invalid DTC mask")
        elif (type(dtc) is not int or not 0 <= dtc <= 0xFFFFFF or
              type(record) is not int or not 1 <= record <= 255):
            raise DoIpError("Invalid DTC or record")
        def verify():
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("Selected ECU did not identify as OBDonUDS")
            else:
                self.verify_vin(expected)
        try:
            verify()
            if action == "dtcs":
                result = self.dtcs(mask)
            elif action == "count":
                result = self.dtc_count(mask)
            else:
                result = self.dtc_detail(dtc, record, action == "extended")
            verify()
            return result
        except BaseException:
            self.close()
            raise

    def read_dtc_severity(self, expected: str, subfunction: int, *,
                          severity_mask: int = 255, status_mask: int = 255, dtc: int | None = None) -> dict:
        """ISO 14229 19/07, 08 and 09; preserve ECU severity and functional-unit bytes."""
        if type(subfunction) is not int or subfunction not in (7, 8, 9):
            raise DoIpError("Invalid DTC severity selector")
        if subfunction == 9:
            if type(dtc) is not int or not 0 <= dtc <= 0xFFFFFF:
                raise DoIpError("Explicit 24-bit DTC required")
            request = bytes((0x19, 9)) + dtc.to_bytes(3, "big")
        else:
            if dtc is not None or any(type(mask) is not int or not 0 <= mask <= 255
                                      for mask in (severity_mask, status_mask)):
                raise DoIpError("Invalid DTC severity/status masks")
            request = bytes((0x19, subfunction, severity_mask, status_mask))
        try:
            self.verify_vin(expected)
            reply = self.exchange(request)
            if len(reply) < 3 or reply[:2] != bytes((0x59, subfunction)):
                raise DoIpError("Malformed DTC severity response")
            availability = reply[2]
            result = {"subfunction": subfunction, "availability_mask": availability}
            if subfunction == 7:
                if len(reply) != 6:
                    raise DoIpError("Invalid severity count response")
                count = int.from_bytes(reply[4:6], "big")
                if count and (not severity_mask or not (status_mask & availability)):
                    raise DoIpError("DTC count contradicts requested masks")
                result.update(format_identifier=reply[3], count=count)
            else:
                if (len(reply) - 3) % 6 or (subfunction == 9 and len(reply) not in (3, 9)):
                    raise DoIpError("Invalid DTC severity record length")
                rows, seen = [], set()
                for offset in range(3, len(reply), 6):
                    severity, unit = reply[offset:offset+2]
                    code = int.from_bytes(reply[offset+2:offset+5], "big")
                    status = reply[offset+5]
                    if code in seen or status & ~availability:
                        raise DoIpError("Duplicate DTC or unavailable status bit")
                    if subfunction == 9 and code != dtc:
                        raise DoIpError("Severity record belongs to another DTC")
                    if subfunction == 8 and (not (severity & severity_mask) or not (status & status_mask & availability)):
                        raise DoIpError("DTC severity record does not match requested masks")
                    seen.add(code)
                    rows.append({"dtc": f"{code:06X}", "severity": severity,
                                 "functional_unit": unit, "status": status})
                result["records"] = rows
            self.verify_vin(expected)
            return result
        except BaseException:
            self.close()
            raise

    def read_fault_counters(self, expected: str) -> dict:
        """Preserve raw signed FDCs; they are not confirmed DTC status records."""
        try:
            self.verify_vin(expected)
            reply = self.exchange(b"\x19\x14")
            if len(reply) < 2 or reply[:2] != b"\x59\x14" or (len(reply) - 2) % 4:
                raise DoIpError("Malformed fault counter report")
            rows, seen = [], set()
            for offset in range(2, len(reply), 4):
                dtc = reply[offset:offset+3].hex().upper()
                if dtc in seen:
                    raise DoIpError("Duplicate fault counter DTC")
                seen.add(dtc)
                rows.append({"dtc": dtc, "counter": int.from_bytes(reply[offset+3:offset+4], "big", signed=True)})
            self.verify_vin(expected)
            return {"fault_counters": rows}
        except BaseException:
            self.close()
            raise

    def read_historical_dtc(self, expected: str, subfunction: int) -> dict:
        """Retrieve a single first/most recent failed/confirmed DTC or an empty result."""
        if type(subfunction) is not int or not 0x0B <= subfunction <= 0x0E:
            raise DoIpError("Invalid historical DTC selector")
        try:
            self.verify_vin(expected)
            reply = self.exchange(bytes((0x19, subfunction)))
            if len(reply) not in (3, 7) or reply[:2] != bytes((0x59, subfunction)):
                raise DoIpError("Malformed historical DTC report")
            if len(reply) == 7 and reply[6] & ~reply[2]:
                raise DoIpError("Historical DTC uses unavailable status bits")
            record = None if len(reply) == 3 else {"dtc": reply[3:6].hex().upper(), "status": reply[6]}
            self.verify_vin(expected)
            return {"subfunction": subfunction, "availability_mask": reply[2], "record": record}
        except BaseException:
            self.close()
            raise

    def read_dtc_inventory(self, expected: str, *, snapshot_index: bool = False, permanent: bool = False) -> dict:
        """Read supported or permanent DTCs, or snapshot references, on one verified ECU."""
        if snapshot_index and permanent:
            raise DoIpError("Conflicting DTC inventory selectors")
        try:
            self.verify_vin(expected)
            sub = 3 if snapshot_index else (0x15 if permanent else 0x0A)
            reply = self.exchange(bytes((0x19, sub)))
            start = 2 if snapshot_index else 3
            if len(reply) < start or reply[:2] != bytes((0x59, sub)) or (len(reply) - start) % 4:
                raise DoIpError("Malformed DTC inventory")
            rows = []
            seen = set()
            for offset in range(start, len(reply), 4):
                dtc = reply[offset:offset + 3].hex().upper()
                value = reply[offset + 3]
                key = (dtc, value) if snapshot_index else dtc
                if key in seen or (not snapshot_index and value & ~reply[2]):
                    raise DoIpError("Duplicate DTC reference or unavailable status bit")
                seen.add(key)
                rows.append({"dtc": dtc, "record" if snapshot_index else "status": value})
            self.verify_vin(expected)
            return {"kind": "snapshot-index" if snapshot_index else ("permanent-dtcs" if permanent else "supported-dtcs"),
                    "records": rows, **({} if snapshot_index else {"availability_mask": reply[2]})}
        except BaseException:
            self.close()
            raise

    def read_stored_dtc_record(self, expected: str, record: int) -> dict:
        """Read one stored-data record (19/05); retain OEM-specific data verbatim."""
        if type(record) is not int or not 0 <= record < 255:
            raise DoIpError("Expected individual stored record 00..FE")
        request = bytes((0x19, 5, record))
        try:
            self.verify_vin(expected)
            reply = positive(request, self.exchange(request))
            result = {"subfunction": 5, "record": record, "present": len(reply) > 3,
                      "did_count": reply[7] if len(reply) > 3 else None,
                      "raw_response": reply.hex().upper()}
            if len(reply) > 3:
                result.update(dtc=reply[3:6].hex().upper(), status=reply[6],
                              raw_stored_data=reply[7:].hex().upper())
            self.verify_vin(expected)
            return result
        except BaseException:
            self.close()
            raise

    def read_extended_records(self, expected: str, dtc: int, record: int,
                              lengths: dict[int, int], *, obd: bool = False) -> dict:
        request = extended_records_request(dtc, record, lengths)
        catalog = dict(lengths)
        def verify():
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("Selected ECU did not identify as OBDonUDS")
            else:
                self.verify_vin(expected)
        try:
            verify()
            result = decode_extended_records(dtc, record, self.exchange(request), catalog)
            verify()
            return result
        except BaseException:
            self.close()
            raise

    def read_snapshot_records(self, expected: str, dtc: int, record: int,
                              lengths: dict[int, int], *, obd: bool = False) -> dict:
        catalog = validate_stored_did_lengths(lengths)
        if type(dtc) is not int or not 0 <= dtc <= 0xFFFFFF or type(record) is not int or not 0 <= record <= 255:
            raise DoIpError("Invalid snapshot selector")
        def verify():
            if obd:
                if not self.read_obd(expected)["obd_on_uds"]:
                    raise DoIpError("Selected ECU did not identify as OBDonUDS")
            else:
                self.verify_vin(expected)
        try:
            verify()
            request = b"\x19\x04" + dtc.to_bytes(3, "big") + bytes((record,))
            result = decode_snapshot_records(dtc, record, self.exchange(request), catalog)
            verify()
            return result
        except BaseException:
            self.close()
            raise

    def read_all_stored_dtc_records(self, expected: str, lengths: dict[int, int]) -> dict:
        return self.read_decoded_stored_dtc_records(expected, 255, lengths)

    def read_decoded_stored_dtc_records(self, expected: str, record: int, lengths: dict[int, int]) -> dict:
        if type(record) is not int or not 0 <= record <= 255:
            raise DoIpError("Invalid stored record selector")
        catalog = validate_stored_did_lengths(lengths)
        request = bytes((0x19, 5, record))
        try:
            self.verify_vin(expected)
            reply = positive(request, self.exchange(request))
            rows = decode_stored_records(record, reply, catalog)
            self.verify_vin(expected)
            return {"subfunction": 5, "record": record, "records": rows,
                    "raw_response": reply.hex().upper()}
        except BaseException:
            self.close()
            raise

    def read_legacy_dtc(self, expected: str, subfunction: int, *, mask: int = 255,
                        dtc: int | None = None, record: int | None = None) -> dict:
        """Explicit ISO 14229 legacy operations; never substitute for primary memory or OBDonUDS."""
        if subfunction in (0x0F, 0x11, 0x12, 0x13):
            if type(mask) is not int or not 0 <= mask <= 255 or dtc is not None or record is not None:
                raise DoIpError("Invalid legacy DTC filter")
            request = bytes((0x19, subfunction, mask))
        elif subfunction == 0x10:
            if (type(dtc) is not int or not 0 <= dtc <= 0xFFFFFF or
                    type(record) is not int or not 1 <= record <= 255 or 0xF0 <= record <= 0xFD):
                raise DoIpError("Invalid mirror memory DTC record")
            request = bytes((0x19, subfunction)) + dtc.to_bytes(3, "big") + bytes((record,))
        else:
            raise DoIpError("Unsupported legacy DTC operation")
        try:
            self.verify_vin(expected)
            reply = positive(request, self.exchange(request))
            if subfunction in (0x11, 0x12):
                result = {"requested_mask": mask, "availability_mask": reply[2],
                          "format": reply[3], "count": int.from_bytes(reply[4:], "big")}
            elif subfunction in (0x0F, 0x13):
                available = reply[2]
                rows = [{"dtc": reply[i:i+3].hex().upper(), "status": reply[i+3]}
                        for i in range(3, len(reply), 4)]
                if len({r["dtc"] for r in rows}) != len(rows) or any(
                        not r["status"] & available & mask or r["status"] & ~available for r in rows):
                    raise DoIpError("Uncorrelated legacy DTC status")
                result = {"requested_mask": mask, "availability_mask": available, "records": rows}
            else:
                result = {"dtc": reply[2:5].hex().upper(), "status": reply[5],
                          "requested_record": record, "raw_records": reply[6:].hex().upper()}
            self.verify_vin(expected)
            return {"subfunction": subfunction, "legacy": True,
                    "scope": "mirror" if subfunction in (0x0F, 0x10, 0x11) else "emissions"} | result
        except BaseException:
            self.close()
            raise

    def read_extended_record(self, expected: str, record: int, *, size: int | None = None) -> dict:
        """19/1A lists supporting DTCs; 19/16 reads a record with an explicit uniform OEM size."""
        if type(record) is not int or not 1 <= record <= 0xEF:
            raise DoIpError("Expected specific extended data record 01..EF")
        if size is not None and (type(size) is not int or not 1 <= size <= 4090):
            raise DoIpError("Expected documented record byte length 1..4090")
        sub = 0x1A if size is None else 0x16
        request = bytes((0x19, sub, record))
        try:
            self.verify_vin(expected)
            reply = positive(request, self.exchange(request))
            start = 4 if size is None else 3
            width = 4 if size is None else 4 + size
            if (len(reply) - start) % width:
                raise DoIpError("Extended data response does not match documented record length")
            rows = []
            seen = set()
            for offset in range(start, len(reply), width):
                dtc = reply[offset:offset+3].hex().upper()
                status = reply[offset+3]
                if dtc in seen or (size is None and status & ~reply[2]):
                    raise DoIpError("Duplicate DTC or unavailable status bit")
                seen.add(dtc)
                row = {"dtc": dtc, "status": status}
                if size is not None:
                    row["data"] = reply[offset+4:offset+width].hex().upper()
                rows.append(row)
            self.verify_vin(expected)
            return {"subfunction": sub, "record": record, "records": rows,
                    **({"availability_mask": reply[2]} if size is None else {"record_size": size})}
        except BaseException:
            self.close()
            raise

    def read_decoded_user_memory(self, expected: str, memory: int, dtc: int, record: int,
                                 lengths: dict[int, int], *, extended: bool = False) -> dict:
        """Decode only the explicit memory selected by the caller."""
        if type(memory) is not int or not 0 <= memory <= 255:
            raise DoIpError("Invalid DTC memory selection")
        if extended:
            catalog = dict(lengths)
            base = extended_records_request(dtc, record, catalog)
        else:
            catalog = validate_stored_did_lengths(lengths)
            if type(dtc) is not int or not 0 <= dtc <= 0xFFFFFF or type(record) is not int or not 1 <= record <= 255:
                raise DoIpError("Invalid user-memory snapshot selector")
            base = b"\x19\x04" + dtc.to_bytes(3, "big") + bytes((record,))
        sub = 0x19 if extended else 0x18
        request = bytes((0x19, sub)) + base[2:] + bytes((memory,))
        try:
            self.verify_vin(expected)
            reply = positive(request, self.exchange(request))
            normalized = bytes((0x59, base[1])) + reply[3:]
            result = (decode_extended_records if extended else decode_snapshot_records)(dtc, record, normalized, catalog)
            self.verify_vin(expected)
            return result | {"subfunction": sub, "memory": memory, "raw_response": reply.hex().upper()}
        except BaseException:
            self.close()
            raise

    def read_user_memory_dtc(self, expected: str, memory: int, *, subfunction: int = 0x17,
                             mask: int = 255, dtc: int | None = None, record: int | None = None) -> dict:
        """Read an explicitly selected DTC memory, never infer an OEM memory mapping."""
        if type(memory) is not int or not 0 <= memory <= 255:
            raise DoIpError("Invalid DTC memory selection")
        if subfunction == 0x17:
            if type(mask) is not int or not 0 <= mask <= 255 or dtc is not None or record is not None:
                raise DoIpError("Invalid user-memory DTC filter")
            request = bytes((0x19, subfunction, mask, memory))
        elif subfunction in (0x18, 0x19):
            if (type(dtc) is not int or not 0 <= dtc <= 0xFFFFFF or
                type(record) is not int or not 1 <= record <= 255 or
                (subfunction == 0x19 and 0xF0 <= record <= 0xFD)):
                raise DoIpError("Invalid user-memory DTC record")
            request = bytes((0x19, subfunction)) + dtc.to_bytes(3, "big") + bytes((record, memory))
        else:
            raise DoIpError("Unsupported user-memory DTC action")
        try:
            self.verify_vin(expected)
            reply = positive(request, self.exchange(request))
            if subfunction == 0x17:
                available = reply[3]
                rows = [{"dtc": reply[i:i+3].hex().upper(), "status": reply[i+3]}
                        for i in range(4, len(reply), 4)]
                if len({r["dtc"] for r in rows}) != len(rows) or any(
                        not r["status"] & available & mask or r["status"] & ~available for r in rows):
                    raise DoIpError("Uncorrelated user-memory DTC status")
                result = {"requested_mask": mask, "availability_mask": available, "records": rows}
            else:
                # Record boundaries and DID lengths are OEM-specific. Preserve raw bytes.
                result = {"dtc": reply[3:6].hex().upper(), "status": reply[6],
                          "requested_record": record, "raw_records": reply[7:].hex().upper()}
            self.verify_vin(expected)
            return {"subfunction": subfunction, "memory": memory} | result
        except BaseException:
            self.close()
            raise

    def dtc_count(self, mask: int) -> dict:
        if type(mask) is not int or not 0 <= mask <= 255:
            raise DoIpError("Invalid DTC mask")
        reply = self.exchange(bytes((0x19, 1, mask)))
        if len(reply) != 6:
            self.close()
            raise DoIpError("Invalid DTC count response")
        return dtc_filter(mask, reply[2]) | {
            "format": reply[3], "count": int.from_bytes(reply[4:], "big")}

    def dtcs(self, mask: int) -> dict:
        if type(mask) is not int or not 0 <= mask <= 255:
            raise DoIpError("Invalid DTC mask")
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
        for row in records:
            row["status_flags"] = dtc_status_flags(row["status"], available)
        return dtc_filter(mask, available) | {"records": records}

    def wwh_dtcs(self, expected: str, subfunction: int, *, status_mask: int = 255,
                 severity_mask: int = 255, readiness_group: int | None = None) -> dict:
        """Verified OBDonUDS route, ISO 14229 WWH emissions group 0x33."""
        if subfunction not in (0x42, 0x55, 0x56):
            raise DoIpError("Unsupported WWH DTC subfunction")
        if any(type(value) is not int or not 0 <= value <= 255
               for value in (status_mask, severity_mask)):
            raise DoIpError("Invalid WWH mask")
        if subfunction == 0x56:
            if type(readiness_group) is not int or not 0 <= readiness_group <= 255:
                raise DoIpError("Explicit readiness group required")
        elif readiness_group is not None:
            raise DoIpError("Readiness group only applies to 0x56")
        request = bytes((0x19, subfunction, 0x33))
        if subfunction == 0x42:
            request += bytes((severity_mask, status_mask))
        elif subfunction == 0x56:
            request += bytes((readiness_group,))
        try:
            if not self.read_obd(expected)["obd_on_uds"]:
                raise DoIpError("Selected ECU did not identify as OBDonUDS")
            reply = self.exchange(request)
            width = 5 if subfunction == 0x42 else 4
            start = 5 if subfunction == 0x55 else 6
            if len(reply) < start or reply[:3] != bytes((0x59, subfunction, 0x33)) or (len(reply) - start) % width:
                raise DoIpError("Malformed WWH emissions DTC response")
            if subfunction == 0x56 and reply[5] != readiness_group:
                raise DoIpError("WWH readiness group mismatch")
            report = {"vin": expected, "service_protocol": "OBDonUDS", "subfunction": subfunction,
                      "functional_group": reply[2], "status_availability_mask": reply[3],
                      "severity_availability_mask": reply[4] if subfunction == 0x42 else None,
                      "dtc_format_identifier": reply[5] if subfunction == 0x42 else reply[4],
                      "records": []}
            if subfunction == 0x42:
                # Only 0x42 sends status and severity masks. The other
                # services must not be presented as filtered by CLI defaults.
                report.update(dtc_filter(status_mask, reply[3]))
                report["requested_severity_mask"] = severity_mask
                report["effective_severity_mask"] = severity_mask & reply[4]
                report["severity_filter_available"] = bool(severity_mask & reply[4])
            if subfunction == 0x56:
                report["readiness_group"] = readiness_group
            for offset in range(start, len(reply), width):
                index = offset + (1 if subfunction == 0x42 else 0)
                report["records"].append({"dtc": reply[index:index+3].hex().upper(),
                    "status": reply[index+3], "severity": reply[offset] if width == 5 else None,
                    "status_flags": dtc_status_flags(reply[index+3], reply[3])})
            if not self.read_obd(expected)["obd_on_uds"]:
                raise DoIpError("OBDonUDS identification changed during DTC read")
            return report
        except BaseException:
            self.close()
            raise

    def dtc_detail(self, dtc: int, record: int, extended: bool) -> dict:
        if not 0 <= dtc <= 0xFFFFFF or not 1 <= record <= 255:
            raise DoIpError("Invalid DTC or record")
        code = dtc.to_bytes(3, "big")
        reply = self.exchange(bytes((0x19, 6 if extended else 4)) + code + bytes((record,)))
        if len(reply) < 6 or reply[2:5] != code:
            self.close()
            raise DoIpError("DTC detail mismatch")
        return {"dtc": code.hex().upper(), "status": reply[5], "raw_records": reply[6:].hex().upper()}
