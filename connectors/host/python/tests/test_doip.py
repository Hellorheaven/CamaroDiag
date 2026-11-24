import socket
import threading
import unittest

from camarodiagconnectors.doip import (
    Gateway, Session, DoIpError, NegativeResponse, decode, encode, parse_identity, positive,
    discover_unique_vin, parse_commands, confirm_commands
)

VIN = "1G1FA1RXXR0123456"
TESTER = bytes.fromhex("0E80")
ECU = bytes.fromhex("1234")
GATEWAY = Gateway("127.0.0.1", VIN, 0x1234, 2, "000000000000", "000000000000", 0, None)


def read_exact(sock, n):
    result = b""
    while len(result) < n:
        part = sock.recv(n - len(result))
        if not part:
            raise EOFError()
        result += part
    return result


def receive(sock):
    h = read_exact(sock, 8)
    return decode(h + read_exact(sock, int.from_bytes(h[4:8], "big")))


class DoIpTests(unittest.TestCase):
    def test_identity_and_correlation(self):
        raw = encode(2, 4, VIN.encode() + ECU + bytes(13))
        self.assertEqual(parse_identity("127.0.0.1", raw, VIN).logical_address, 0x1234)
        with self.assertRaises(DoIpError):
            parse_identity("127.0.0.1", raw, VIN[:-1] + "7")
        with self.assertRaises(DoIpError):
            positive(bytes.fromhex("221234"), bytes.fromhex("62123501"))
        with self.assertRaises(DoIpError):
            decode(raw[:-1])

    def test_unique_vin_discovery(self):
        for conflict in (False, True):
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
                server.bind(("127.0.0.1", 0))
                server.settimeout(2)
                errors = []
                def peer():
                    try:
                        raw, source = server.recvfrom(1024)
                        self.assertEqual(raw, encode(255, 3, VIN.encode()))
                        body = VIN.encode() + ECU + bytes(13)
                        server.sendto(encode(2, 4, body), source)
                        server.sendto(encode(2, 4, body), source)
                        if conflict:
                            server.sendto(encode(2, 4, VIN.encode() + ECU + bytes((1,)) + bytes(12)), source)
                    except BaseException as exc:
                        errors.append(exc)
                thread = threading.Thread(target=peer)
                thread.start()
                try:
                    if conflict:
                        with self.assertRaises(DoIpError):
                            discover_unique_vin(VIN, 0.4, destination="127.0.0.1", port=server.getsockname()[1])
                    else:
                        found = discover_unique_vin(VIN, 0.4, destination="127.0.0.1", port=server.getsockname()[1])
                        self.assertEqual(found.vin, VIN)
                finally:
                    thread.join(3)
                if errors:
                    raise errors[0]

    def test_sequence_requires_two_separate_confirmations(self):
        commands = parse_commands("22 F1 90;19 01 FF")
        self.assertEqual(commands, (bytes.fromhex("22F190"), bytes.fromhex("1901FF")))
        for invalid in ("", "22;", "22;;19", "GG", ";".join(["22"] * 17)):
            with self.assertRaises(DoIpError):
                parse_commands(invalid)
        for answers, expected, french in [(("NO",), False, False), (("OK", "NO"), False, False),
                                           (("OK", "YES"), True, False), (("OK", "OUI"), True, True)]:
            values = iter(answers)
            self.assertEqual(confirm_commands(commands, GATEWAY, 0xE80, 0x1234, True,
                lambda: next(values), lambda _: None, french), expected)

    def test_obd_did_requires_legislated_vin_and_identification(self):
        for protocol_value in (1, 2):
            class Fake:
                closed = False
                requests = []
                def verify_vin(self, expected, did):
                    self.requests.append(did)
                    return expected
                def read_did(self, did):
                    self.requests.append(did)
                    return bytes((protocol_value,)) if did == 0xF810 else b"\xab"
                def close(self):
                    self.closed = True
            fake = Fake()
            if protocol_value == 1:
                self.assertEqual(Session.read_obd(fake, VIN, 0xF40C)["data"], "AB")
                self.assertEqual(fake.requests, [0xF802, 0xF810, 0xF40C])
            else:
                with self.assertRaises(DoIpError):
                    Session.read_obd(fake, VIN, 0xF40C)
                self.assertEqual(fake.requests, [0xF802, 0xF810])
                self.assertTrue(fake.closed)

    def test_session_trace_pending_ack_alive_and_count(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(3)
            errors = []
            trace = []
            def peer():
                try:
                    with listener.accept()[0] as sock:
                        sock.settimeout(3)
                        self.assertEqual(receive(sock), (2, 5, TESTER + bytes(5)))
                        sock.sendall(encode(2, 6, TESTER + ECU + b"\x11" + bytes(4)))
                        sock.sendall(encode(2, 7))
                        self.assertEqual(receive(sock), (2, 8, TESTER))
                        sock.sendall(encode(2, 6, TESTER + ECU + b"\x10" + bytes(4)))
                        for req, reply in [(bytes.fromhex("22F190"), bytes.fromhex("62F190") + VIN.encode()),
                                           (bytes.fromhex("1901FF"), bytes.fromhex("5901FF041234"))]:
                            self.assertEqual(receive(sock), (2, 0x8001, TESTER + ECU + req))
                            sock.sendall(encode(2, 0x8002, ECU + TESTER + b"\x00" + req))
                            sock.sendall(encode(2, 0x8001, ECU + TESTER + bytes((0x7F, req[0], 0x78))))
                            wire = encode(2, 0x8001, ECU + TESTER + reply)
                            sock.sendall(wire[:3])
                            sock.sendall(wire[3:])
                except BaseException as error:
                    errors.append(error)
            thread = threading.Thread(target=peer)
            thread.start()
            try:
                with Session(GATEWAY, 0xE80, 0x1234, tls=False, port=listener.getsockname()[1],
                             trace=lambda *x: trace.append(x)) as client:
                    self.assertEqual(client.verify_vin(VIN), VIN)
                    self.assertEqual(client.dtc_count(255)["count"], 0x1234)
            finally:
                thread.join(4)
            self.assertFalse(thread.is_alive())
            if errors:
                raise errors[0]
            self.assertTrue(any(x[0] == "RX" and x[3][4:8] == "8002" for x in trace))
            self.assertTrue(any(x[0] == "TX" and x[3][4:8] == "0008" for x in trace))


if __name__ == "__main__":
    unittest.main()
