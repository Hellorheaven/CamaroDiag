import socket
import threading
import unittest
from unittest.mock import patch
from camarodiagconnectors.doip_cli import run

from camarodiagconnectors.doip import (
    Gateway, Session, DoIpError, NegativeResponse, decode, encode, parse_identity, positive,
    discover, discover_ipv4, discover_ipv6, discovery_request, discover_unique_vin, parse_commands, confirm_commands,
    endpoint, tls_server_name, status, parse_did_layout, decode_did_layout
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

    def test_ipv4_discovery_eid_filter_and_unicast_correlation(self):
        eid = bytes.fromhex("010203040506")
        self.assertEqual(discovery_request(eid=eid), encode(255, 2, eid))
        self.assertEqual(discovery_request(), encode(255, 1))
        with self.assertRaises(DoIpError):
            discovery_request(VIN, eid)
        with self.assertRaises(DoIpError):
            discovery_request(eid=b"bad")
        for unicast in (False, True):
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
                server.bind(("127.0.0.1", 0))
                server.settimeout(2)
                errors = []
                def peer():
                    try:
                        raw, source = server.recvfrom(1024)
                        self.assertEqual(raw, encode(255, 2, eid))
                        body = VIN.encode() + ECU + eid + bytes(7)
                        if not unicast:
                            server.sendto(encode(2, 4, VIN.encode() + ECU + bytes(13)), source)
                        server.sendto(encode(2, 4, body), source)
                        if not unicast:
                            server.sendto(encode(2, 4, body), source)
                    except BaseException as exc:
                        errors.append(exc)
                thread = threading.Thread(target=peer)
                thread.start()
                try:
                    port = server.getsockname()[1]
                    if unicast:
                        found = [discover("127.0.0.1", timeout=0.5, port=port, eid=eid)]
                    else:
                        found = discover_ipv4(0.5, destination="127.0.0.1", port=port, eid=eid)
                    self.assertEqual(len(found), 1)
                    self.assertEqual(found[0].eid, eid.hex().upper())
                finally:
                    thread.join(3)
                if errors:
                    raise errors[0]

    def test_scoped_ipv6_discovery_filters_interface_and_source(self):
        body = VIN.encode() + ECU + bytes(13)
        announcement = encode(2, 4, body)
        for passive in (False, True):
            with self.subTest(passive=passive), patch("camarodiagconnectors.doip.socket.socket") as factory:
                sock = factory.return_value.__enter__.return_value
                sock.recvfrom.side_effect = [
                    (announcement, ("fe80::2", 13400, 0, 4)),
                    (announcement, ("2001:db8::2", 13400, 0, 3)),
                    (announcement, ("fe80::2", 9999, 0, 3)),
                    (announcement, ("fe80::2", 13400, 0, 3)),
                    (announcement, ("fe80::2", 13400, 0, 3)),
                    TimeoutError(),
                ]
                trace = []
                found = discover_ipv6(3, 0.5, lambda *event: trace.append(event),
                                      passive=passive, vin=VIN)
                self.assertEqual(len(found), 1)
                self.assertEqual(found[0].host, "fe80::2%3")
                if passive:
                    sock.bind.assert_called_once_with(("::", 13400))
                    sock.sendto.assert_not_called()
                    self.assertFalse(any(row[0] == "TX" for row in trace))
                else:
                    sock.sendto.assert_called_once_with(encode(255, 3, VIN.encode()),
                                                        ("ff02::1", 13400, 0, 3))
                sock.send.assert_not_called()
        with self.assertRaises(DoIpError):
            discover_ipv6(0)
        with self.assertRaises(DoIpError):
            endpoint("fe80::2")
        self.assertEqual(endpoint("fe80::2%3"), "fe80::2%3")
        self.assertEqual(tls_server_name("fe80::2%3"), "fe80::2")

    def test_ipv6_discovery_cli_is_explicit_and_read_only(self):
        with patch("camarodiagconnectors.doip_cli.discover_ipv6", return_value=[GATEWAY]) as discover_mock:
            result = run(["--multicast-ipv6-ifindex", "3", "discover"])
            self.assertEqual(len(result["gateways"]), 1)
            self.assertFalse(result["ecu_authenticated"])
            discover_mock.assert_called_once_with(3, 3, None, passive=False, vin=None, eid=None)
        with patch("camarodiagconnectors.doip_cli.discover_ipv6") as discover_mock:
            with self.assertRaises(SystemExit):
                run(["--listen-ipv6-ifindex", "3", "--host", "127.0.0.1", "discover"])
            discover_mock.assert_not_called()

    def test_control_query_revalidates_gateway_identity_and_node_type(self):
        from dataclasses import replace
        with patch("camarodiagconnectors.doip.discover", return_value=replace(GATEWAY, gid="010203040506")), \
             patch("camarodiagconnectors.doip._udp") as udp:
            with self.assertRaises(DoIpError):
                status(GATEWAY)
            udp.assert_not_called()
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip._udp", return_value=encode(2, 0x4002, bytes((2, 4, 1)))):
            with self.assertRaises(DoIpError):
                status(GATEWAY)
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip._udp", return_value=encode(2, 0x4002, bytes((1, 4, 1)))):
            self.assertEqual(status(GATEWAY)["current_sockets"], 1)

    def test_passive_discovery_sends_nothing(self):
        with patch("camarodiagconnectors.doip.socket.socket") as factory:
            sock = factory.return_value.__enter__.return_value
            sock.recvfrom.side_effect = TimeoutError
            self.assertEqual(discover_ipv4(0.2, listen_host="127.0.0.1"), [])
            sock.bind.assert_called_once_with(("127.0.0.1", 13400))
            sock.sendto.assert_not_called()
            sock.send.assert_not_called()

    def test_confirmed_sequence_revalidates_gateway_before_connect(self):
        from dataclasses import replace
        with (patch("camarodiagconnectors.doip_cli.discover",
                    side_effect=[GATEWAY, replace(GATEWAY, gid="010203040506")]) as discover_mock,
              patch("camarodiagconnectors.doip_cli.confirm_commands", return_value=True),
              patch("camarodiagconnectors.doip_cli.sys.stdin.isatty", return_value=True),
              patch("camarodiagconnectors.doip_cli.Session") as session):
            with self.assertRaises(DoIpError):
                run(["--host", "127.0.0.1", "--vin", VIN, "--tester", "0E80", "--ecu", "1234",
                     "--tcp", "sequence", "--commands", "22F190"])
            self.assertEqual(discover_mock.call_count, 2)
            session.assert_not_called()

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

    def test_raw_dtc_responses_correlate_record_and_functional_group(self):
        for request, reply in (("1903", "5903"), ("190A", "590A08"),
                               ("190412345601", "5904123456080100"),
                               ("1906123456FE", "5906123456089001"),
                               ("19563307", "595633FF0407"), ("241234", "64123400")):
            self.assertEqual(positive(bytes.fromhex(request), bytes.fromhex(reply)), bytes.fromhex(reply))
        for request, reply in (("1903", "590301"), ("190A", "590A"), ("190A", "590A0801"),
                               ("190412345601", "5904123456080200"),
                               ("190612345690", "5906123457089001"),
                               ("195533", "595534FF04"), ("19563307", "595633FF0408"),
                               ("241234", "64123500")):
            with self.assertRaises(DoIpError):
                positive(bytes.fromhex(request), bytes.fromhex(reply))

    def test_verified_dtc_reads_require_final_vin(self):
        for action, method, arguments in (
                ("dtcs", "dtcs", (8,)), ("count", "dtc_count", (8,)),
                ("snapshot", "dtc_detail", (0x123456, 1, False)),
                ("extended", "dtc_detail", (0x123456, 1, True))):
            for final_matches in (True, False):
                with self.subTest(action=action, final_matches=final_matches):
                    fake = unittest.mock.Mock(spec=Session)
                    fake.verify_vin.side_effect = [VIN, VIN if final_matches else DoIpError("VIN changed")]
                    read = getattr(fake, method)
                    read.return_value = {"data": "test"}
                    if final_matches:
                        self.assertEqual(Session.read_verified_dtc(fake, VIN, action, mask=8,
                                                                  dtc=0x123456, record=1), {"data": "test"})
                        fake.close.assert_not_called()
                    else:
                        with self.assertRaises(DoIpError):
                            Session.read_verified_dtc(fake, VIN, action, mask=8, dtc=0x123456, record=1)
                        fake.close.assert_called_once()
                    read.assert_called_once_with(*arguments)
                    self.assertEqual(fake.mock_calls[:3], [
                        unittest.mock.call.verify_vin(VIN),
                        getattr(unittest.mock.call, method)(*arguments),
                        unittest.mock.call.verify_vin(VIN)])
        fake = unittest.mock.Mock(spec=Session)
        fake.verify_vin.side_effect = DoIpError("VIN changed")
        with self.assertRaises(DoIpError):
            Session.read_verified_dtc(fake, VIN, "dtcs")
        fake.dtcs.assert_not_called()
        fake.close.assert_called_once()

    def test_verified_obd_dtc_reads_use_legislated_identity(self):
        for action, method, args in (
                ("dtcs", "dtcs", (8,)), ("count", "dtc_count", (8,)),
                ("snapshot", "dtc_detail", (0x123456, 1, False)),
                ("extended", "dtc_detail", (0x123456, 1, True))):
            for initial, final in ((True, True), (False, True), (True, False)):
                with self.subTest(action=action, initial=initial, final=final):
                    fake = unittest.mock.Mock(spec=Session)
                    fake.read_obd.side_effect = [{"obd_on_uds": initial}, {"obd_on_uds": final}]
                    read = getattr(fake, method)
                    read.return_value = {"data": "test"}
                    if initial and final:
                        self.assertEqual(Session.read_verified_dtc(fake, VIN, action, mask=8,
                                         dtc=0x123456, record=1, obd=True), {"data": "test"})
                        fake.close.assert_not_called()
                    else:
                        with self.assertRaises(DoIpError):
                            Session.read_verified_dtc(fake, VIN, action, mask=8,
                                                      dtc=0x123456, record=1, obd=True)
                        fake.close.assert_called_once()
                    if initial:
                        read.assert_called_once_with(*args)
                        self.assertEqual(fake.read_obd.call_count, 2)
                    else:
                        read.assert_not_called()
                    fake.verify_vin.assert_not_called()

    def test_dtc_inventory_preserves_inactive_codes_and_snapshot_pairs(self):
        for snapshot, payload, expected in (
                (False, "590A0801020300", [{"dtc": "010203", "status": 0}]),
                (True, "59030102030101020302", [{"dtc": "010203", "record": 1}, {"dtc": "010203", "record": 2}])):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            result = Session.read_dtc_inventory(fake, VIN, snapshot_index=snapshot)
            self.assertEqual(result["records"], expected)
            fake.exchange.assert_called_once_with(bytes((0x19, 3 if snapshot else 10)))
            self.assertEqual(fake.verify_vin.call_count, 2)
            fake.close.assert_not_called()
        for snapshot, payload in ((False, "590A0801020301"), (False, "590A080102030001020308"),
                                  (True, "590301"), (True, "59030102030101020301")):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_dtc_inventory(fake, VIN, snapshot_index=snapshot)
            fake.close.assert_called_once()

    def test_multi_monitor_rejects_contradictory_sample_identity(self):
        fake = unittest.mock.Mock(spec=Session)
        fake.read_obd.return_value = {"obd_on_uds": True}
        fake.exchange.return_value = bytes.fromhex("62F81002F40D56")
        observed = []
        with self.assertRaises(DoIpError):
            Session.monitor_dids(fake, VIN, ((0xF810, 1), (0xF40D, 1)), 1, 0.25,
                                 obd=True, on_sample=observed.append)
        self.assertEqual(observed, [])
        fake.close.assert_called_once()

    def test_multi_did_monitoring_revalidates_and_keeps_samples_isolated(self):
        layout = parse_did_layout("F40C:2,F40D:1")
        fake = unittest.mock.Mock(spec=Session)
        fake.exchange.side_effect = [bytes.fromhex("62F40C1234F40D56"), bytes.fromhex("62F40C5678F40D90")]
        fake.read_obd.side_effect = [{"obd_on_uds": True}, {"obd_on_uds": True}]
        def observe(row):
            row["dids"][0]["data"] = "BAD"
        report = Session.monitor_dids(fake, VIN, layout, 2, 0.25, obd=True, on_sample=observe)
        self.assertEqual([row["dids"][0]["data"] for row in report["samples"]], ["1234", "5678"])
        self.assertEqual(fake.exchange.call_count, 2)
        fake.wait.assert_called_once_with(0.25)
        self.assertEqual(fake.read_obd.call_count, 2)
        fake.close.assert_not_called()
        fake = unittest.mock.Mock(spec=Session)
        fake.exchange.return_value = bytes.fromhex("62F40C1234F40D56")
        fake.read_obd.side_effect = [{"obd_on_uds": True}, {"obd_on_uds": False}]
        with self.assertRaises(DoIpError):
            Session.monitor_dids(fake, VIN, layout, 1, 0.25, obd=True)
        fake.close.assert_called_once()
        fake = unittest.mock.Mock(spec=Session)
        with self.assertRaises(DoIpError):
            Session.monitor_dids(fake, VIN, ((0xF40C, 4096), (0xF40D, 1)))
        fake.exchange.assert_not_called()

    def test_verified_single_did_uses_actual_vin_and_revalidates_data(self):
        fake = unittest.mock.Mock(spec=Session)
        fake.verify_vin.return_value = VIN
        self.assertEqual(Session.read_verified_did(fake, VIN, 0xF190), VIN.encode("ascii"))
        fake.read_did.assert_not_called()
        fake.verify_vin.assert_called_once_with(VIN)
        fake.reset_mock()
        fake.read_did.return_value = b"\x12\x34"
        self.assertEqual(Session.read_verified_did(fake, VIN, 0xF187), b"\x12\x34")
        self.assertEqual(fake.verify_vin.call_count, 2)
        fake.read_did.assert_called_once_with(0xF187)
        fake.reset_mock()
        fake.verify_vin.side_effect = [VIN, DoIpError("changed VIN")]
        with self.assertRaises(DoIpError):
            Session.read_verified_did(fake, VIN, 0xF187)
        fake.close.assert_called_once()

    def test_scaling_did_identity_and_correlation(self):
        fake = unittest.mock.Mock(spec=Session)
        fake.verify_vin.return_value = VIN
        fake.exchange.return_value = bytes.fromhex("64F40C0102")
        self.assertEqual(Session.read_scaling_did(fake, VIN, 0xF40C), b"\x01\x02")
        fake.exchange.assert_called_once_with(bytes.fromhex("24F40C"))
        self.assertEqual(fake.verify_vin.call_count, 2)
        for reply in ("64F40D0102", "64F40C", "62F40C0102"):
            fake.exchange.return_value = bytes.fromhex(reply)
            with self.assertRaises(DoIpError):
                Session.read_scaling_did(fake, VIN, 0xF40C)
        self.assertEqual(fake.close.call_count, 3)

    def test_multi_did_layout_and_same_session(self):
        layout = parse_did_layout("F40C:2,F40D:1")
        reply = bytes.fromhex("62F40C1234F40D56")
        self.assertEqual(decode_did_layout(reply, layout)[1]["data"], "56")
        fake = Session.__new__(Session)
        fake.read_obd = unittest.mock.Mock(return_value={"obd_on_uds": True})
        fake.exchange = unittest.mock.Mock(return_value=reply)
        fake.close = unittest.mock.Mock()
        self.assertEqual(fake.read_dids(VIN, layout, obd=True)["dids"][0]["data"], "1234")
        fake.exchange.assert_called_once_with(bytes.fromhex("22F40CF40D"))
        self.assertEqual(fake.read_obd.call_count, 2)
        for malformed in ("62F40D56F40C1234", "62F40C1234F40D", "62F40C1234F40D5600"):
            fake.exchange.return_value = bytes.fromhex(malformed)
            with self.assertRaises(DoIpError):
                fake.read_dids(VIN, layout, obd=True)
        fake.close.assert_called()
        for invalid in ("F40C:2,F40C:1", "F40C:0,F40D:1", "F40C:2", "F40C:2,F40D:4097"):
            with self.assertRaises(DoIpError):
                parse_did_layout(invalid)

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
                self.assertEqual(fake.requests, [0xF802, 0xF810, 0xF40C, 0xF802, 0xF810])
            else:
                with self.assertRaises(DoIpError):
                    Session.read_obd(fake, VIN, 0xF40C)
                self.assertEqual(fake.requests, [0xF802, 0xF810])
                self.assertTrue(fake.closed)

    def test_obd_discovery_preserves_stable_unknown_protocol(self):
        for first, second in ((1, 1), (2, 2), (1, 2)):
            fake = unittest.mock.Mock(spec=Session)
            fake.read_obd.side_effect = [
                {"vin": VIN, "protocol_value": value, "obd_on_uds": value == 1}
                for value in (first, second)]
            if first == second:
                self.assertEqual(Session.identify_obd(fake, VIN)["protocol_value"], first)
                fake.close.assert_not_called()
            else:
                with self.assertRaises(DoIpError):
                    Session.identify_obd(fake, VIN)
                fake.close.assert_called_once()
            self.assertEqual(fake.read_obd.call_count, 2)

    def test_obd_single_did_rejects_changed_protocol(self):
        fake = unittest.mock.Mock(spec=Session)
        fake.verify_vin.return_value = VIN
        fake.read_did.side_effect = [b"\x01", b"\xab", b"\x02"]
        with self.assertRaises(DoIpError):
            Session.read_obd(fake, VIN, 0xF40C)
        fake.close.assert_called_once()

    def test_verified_wwh_dtc_requests_and_correlation(self):
        cases = [
            (0x42, None, "194233FF08", "594233FFFC04A012345608", 0xA0),
            (0x55, None, "195533", "595533FF0412345608", None),
            (0x56, 0x12, "19563312", "595633FF041212345608", None),
        ]
        for subfunction, group, request, reply, severity in cases:
            with self.subTest(subfunction=subfunction):
                fake = Session.__new__(Session)
                fake.read_obd = unittest.mock.Mock(return_value={"obd_on_uds": True})
                fake.exchange = unittest.mock.Mock(return_value=bytes.fromhex(reply))
                fake.close = unittest.mock.Mock()
                result = fake.wwh_dtcs(VIN, subfunction, status_mask=8, readiness_group=group)
                fake.exchange.assert_called_once_with(bytes.fromhex(request))
                self.assertEqual(fake.read_obd.call_count, 2)
                self.assertEqual(result["records"], [{"dtc": "123456", "status": 8, "severity": severity}])
                fake.close.assert_not_called()
        for reply in ("595633FF041312345608", "595633FF0412123456", "595634FF0412"):
            fake = Session.__new__(Session)
            fake.read_obd = unittest.mock.Mock(return_value={"obd_on_uds": True})
            fake.exchange = unittest.mock.Mock(return_value=bytes.fromhex(reply))
            fake.close = unittest.mock.Mock()
            with self.assertRaises(DoIpError):
                fake.wwh_dtcs(VIN, 0x56, readiness_group=0x12)
            fake.close.assert_called_once()
        fake = Session.__new__(Session)
        fake.read_obd = unittest.mock.Mock(return_value={"obd_on_uds": False})
        fake.exchange = unittest.mock.Mock()
        fake.close = unittest.mock.Mock()
        with self.assertRaises(DoIpError):
            fake.wwh_dtcs(VIN, 0x55)
        fake.exchange.assert_not_called()

    def test_deadlines_extend_only_on_pending_and_never_beyond_overall(self):
        req = bytes.fromhex("221234")
        pending = (0x8001, ECU + TESTER + bytes.fromhex("7F2278"))
        ack = (0x8002, ECU + TESTER + b"\x00" + req)
        reply = (0x8001, ECU + TESTER + bytes.fromhex("62123401"))
        cases = [
            ([(0.25, ack), (0.5, pending), (1.0, pending), (1.5, reply)], True),
            ([(0.25, ack), (0.8, reply)], False),
            ([(0.5, pending), (1.25, pending), (2.1, reply)], False),
            ([(0.6, pending), (1.0, reply)], False),
        ]
        # Use a deterministic clock: no timing-sensitive sleeps in unit tests.
        for events, expected in cases:
            with self.subTest(events=events):
                client = Session.__new__(Session)
                client.tester, client.ecu = 0xE80, 0x1234
                client.timeout, client.p2, client.p2_star = 2.0, 0.55, 1.0
                client._busy = False
                clock = [0.0]
                closed = []
                received_deadlines = []
                queue = iter(events)
                client._send = lambda *_: None
                client.close = lambda: closed.append(True)
                def receive_frame(deadline):
                    received_deadlines.append(deadline)
                    clock[0], frame = next(queue)
                    return frame
                client._receive = receive_frame
                with patch("camarodiagconnectors.doip.time.monotonic", side_effect=lambda: clock[0]):
                    if expected:
                        self.assertEqual(client.exchange(req), bytes.fromhex("62123401"))
                        self.assertEqual(received_deadlines, [0.55, 0.55, 1.5, 2.0])
                        self.assertFalse(closed)
                    else:
                        with self.assertRaises(TimeoutError):
                            client.exchange(req)
                        self.assertTrue(closed)
                self.assertFalse(client._busy)

    def test_obd_monitor_uses_legislated_identity_and_revalidates(self):
        class Fake:
            def __init__(self, values):
                self.values = iter(values)
                self.calls = []
                self.closed = False
            def read_obd(self, vin):
                self.calls.append(("obd", vin))
                return {"obd_on_uds": next(self.values)}
            def verify_vin(self, vin):
                self.calls.append(("uds", vin))
            def read_did(self, did):
                self.calls.append(("did", did))
                return b"\x12\x34"
            def wait(self, interval):
                self.calls.append(("wait", interval))
            def close(self):
                self.closed = True
        for values, ok, count in [([True, True], True, 2), ([False], False, 0),
                                   ([True, False], False, 2)]:
            fake = Fake(values)
            partial = []
            if ok:
                result = Session.monitor(fake, VIN, 0xF40C, 2, .25, obd=True, on_sample=partial.append)
                self.assertEqual(result["service_protocol"], "OBDonUDS")
                self.assertEqual([r["data"] for r in result["samples"]], ["1234", "1234"])
                self.assertEqual(fake.calls, [("obd", VIN), ("did", 0xF40C), ("wait", .25),
                                              ("did", 0xF40C), ("obd", VIN)])
            else:
                with self.assertRaises(DoIpError):
                    Session.monitor(fake, VIN, 0xF40C, 2, .25, obd=True, on_sample=partial.append)
                self.assertTrue(fake.closed)
            self.assertEqual(len(partial), count)
            self.assertNotIn(("uds", VIN), fake.calls)

    def test_routing_options_on_wire(self):
        for routing_type, oem in ((0, None), (0xE0, bytes.fromhex("01020304"))):
            with self.subTest(routing_type=routing_type), socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                listener.listen(1)
                listener.settimeout(3)
                errors = []
                def peer():
                    try:
                        with listener.accept()[0] as sock:
                            sock.settimeout(3)
                            self.assertEqual(receive(sock), (2, 5, TESTER + bytes((routing_type,)) +
                                                             bytes(4) + (oem or b"")))
                            sock.sendall(encode(2, 6, TESTER + ECU + b"\x10" + bytes(4)))
                    except BaseException as exc:
                        errors.append(exc)
                thread = threading.Thread(target=peer)
                thread.start()
                try:
                    with Session(GATEWAY, 0xE80, 0x1234, tls=False, port=listener.getsockname()[1],
                                 routing_type=routing_type, routing_oem_data=oem):
                        pass
                finally:
                    thread.join(4)
                self.assertFalse(thread.is_alive())
                if errors:
                    raise errors[0]

    def test_invalid_routing_and_timing_fail_before_connect(self):
        with patch("camarodiagconnectors.doip.socket.create_connection") as connect:
            for options in ({"p2": 0}, {"p2": 2, "p2_star": 1}, {"p2_star": 4},
                            {"routing_type": 256}, {"routing_oem_data": b"123"}):
                with self.subTest(options=options), self.assertRaises(DoIpError):
                    Session(GATEWAY, 0xE80, 0x1234, **options)
            connect.assert_not_called()
        with patch("camarodiagconnectors.doip_cli.discover") as discover:
            for args in (["--p2-ms", "0", "vin"], ["--routing-oem-data", "0102", "vin"],
                         ["--routing-activation-type", "E0", "discover"]):
                with self.subTest(args=args), self.assertRaises(SystemExit):
                    run(["--host", "127.0.0.1", "--vin", VIN, "--tester", "0E80",
                         "--ecu", "1234", *args])
            discover.assert_not_called()

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
