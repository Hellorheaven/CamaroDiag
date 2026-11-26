import socket
import json
import io
from contextlib import redirect_stderr
import tempfile
from pathlib import Path
import threading
import unittest
from unittest.mock import patch
from camarodiagconnectors.doip_cli import run

from camarodiagconnectors.doip import (
    Gateway, Session, DoIpError, NegativeResponse, TransportRefusal, decode, encode, parse_identity, positive,
    discover, discover_ipv4, discover_ipv6, discovery_request, discover_unique_vin, parse_commands, confirm_commands,
    endpoint, tls_server_name, status, parse_did_layout, decode_did_layout,
    parse_ecu_inventory, inventory_ecus, parse_stored_did_lengths, decode_all_stored_records
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
    def test_dtc_status_mask_and_flags_are_observed_not_assumed(self):
        fake = unittest.mock.Mock(spec=Session)
        fake.exchange.return_value = bytes.fromhex("59020F1234560D")
        result = Session.dtcs(fake, 0x05)
        self.assertEqual(result["effective_mask"], 0x05)
        self.assertTrue(result["filter_available"])
        self.assertEqual(result["records"], [{"dtc": "123456", "status": 0x0D,
            "status_flags": ["test_failed", "pending_dtc", "confirmed_dtc"]}])
        fake.exchange.assert_called_once_with(bytes.fromhex("190205"))
        fake.reset_mock()
        fake.exchange.return_value = bytes.fromhex("590201")
        result = Session.dtcs(fake, 0x80)
        self.assertEqual(result["records"], [])
        self.assertFalse(result["filter_available"])
        self.assertEqual(result["effective_mask"], 0)
        fake.exchange.return_value = bytes.fromhex("590101040000")
        count = Session.dtc_count(fake, 0x80)
        self.assertFalse(count["filter_available"])
        self.assertEqual(count["count"], 0)
        fake.exchange.return_value = bytes.fromhex("59020112345681")
        with self.assertRaises(DoIpError):
            Session.dtcs(fake, 0xFF)
        fake.close.assert_called_once()
        fake.reset_mock()
        for invalid in (-1, 256, True):
            with self.assertRaises(DoIpError):
                Session.dtcs(fake, invalid)
            with self.assertRaises(DoIpError):
                Session.dtc_count(fake, invalid)
        fake.exchange.assert_not_called()

    def test_cli_monitor_reports_verified_route_and_incomplete_capture(self):
        for action in ("monitor", "obd-monitor-dids"):
            for failure in ("none", "second_sample", "close"):
                with self.subTest(action=action, failure=failure):
                    output = io.StringIO()
                    args = ["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80",
                            "--ecu", "1234", "--tcp", action, "--samples", "2"]
                    args += ["--did", "F40D"] if action == "monitor" else ["--layout", "F40D:1"]
                    def monitor(*args, on_sample, **kwargs):
                        rows = [{"index": 1, "data": "01"}, {"index": 2, "data": "02"}]
                        on_sample(rows[0])
                        if failure == "second_sample":
                            raise DoIpError("Identity changed")
                        on_sample(rows[1])
                        return {"samples": rows}
                    with patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
                         patch("camarodiagconnectors.doip_cli.Session") as session, redirect_stderr(output):
                        active = session.return_value.__enter__.return_value
                        active.monitor.side_effect = monitor
                        active.monitor_dids.side_effect = monitor
                        if failure == "close":
                            session.return_value.__exit__.side_effect = OSError("Close failed")
                        if failure == "none":
                            report = run(args)
                            self.assertTrue(report["capture_complete"])
                            self.assertEqual(report["verified_samples"], 2)
                        else:
                            with self.assertRaises((DoIpError, OSError)):
                                run(args)
                    events = [json.loads(line) for line in output.getvalue().splitlines()]
                    verified = 1 if failure == "second_sample" else 2
                    samples = [event["partial_sample"] for event in events if "partial_sample" in event]
                    self.assertEqual(len(samples), verified)
                    self.assertEqual(samples[0]["vin"], VIN)
                    self.assertEqual(samples[0]["gateway_logical_address"], GATEWAY.logical_address)
                    self.assertEqual(samples[0]["ecu"], 0x1234)
                    self.assertEqual(samples[0]["tester"], 0x0E80)
                    self.assertEqual(samples[0]["transport"], "TCP")
                    if failure != "none":
                        self.assertFalse(events[-1]["monitor_summary"]["capture_complete"])
                        self.assertEqual(events[-1]["monitor_summary"]["verified_samples"], verified)
                    else:
                        self.assertNotIn("monitor_summary", events[-1])

    def test_trace_file_records_observed_discovery_and_never_overwrites(self):
        def observed(host, vin, timeout, trace):
            trace("TX", "UDP", host, "FF00000100000000")
            trace("RX", "UDP", host, "02FD000400000000")
            return GATEWAY
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.jsonl"
            args = ["--host", GATEWAY.host, "--trace-file", str(path), "discover"]
            with patch("camarodiagconnectors.doip_cli.discover", side_effect=observed) as discovery:
                result = run(args)
                self.assertEqual(result["gateway"]["host"], GATEWAY.host)
                original = path.read_text(encoding="utf-8")
                rows = [json.loads(line) for line in original.splitlines()]
                self.assertEqual([(r["direction"], r["transport"], r["pdu"]) for r in rows],
                                 [("TX", "UDP", "FF00000100000000"), ("RX", "UDP", "02FD000400000000")])
                self.assertTrue(all(r["utc"].endswith("+00:00") for r in rows))
                with self.assertRaises(FileExistsError):
                    run(args)
                self.assertEqual(discovery.call_count, 1)
                self.assertEqual(path.read_text(encoding="utf-8"), original)

    def test_trace_file_preserves_partial_evidence_after_network_failure(self):
        def failed(host, vin, timeout, trace):
            trace("TX", "UDP", host, "FF00000100000000")
            raise TimeoutError("No response")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.jsonl"
            with patch("camarodiagconnectors.doip_cli.discover", side_effect=failed):
                with self.assertRaises(TimeoutError):
                    run(["--host", GATEWAY.host, "--trace-file", str(path), "discover"])
            rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["direction"], "TX")
            path.rename(path.with_suffix(".saved"))

    def test_trace_file_inventory_attribution_without_stderr_flag(self):
        def inventory(gateway, expected, tester, targets, **kwargs):
            kwargs["target_trace"](targets[0], "RX", "TLS", gateway.host, "02FD800100000000")
            kwargs["trace"]("RX", "UDP", gateway.host, "02FD000400000000")
            return {"verified_count": 1}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.jsonl"
            with patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
                 patch("camarodiagconnectors.doip_cli.inventory_ecus", side_effect=inventory):
                run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80",
                     "--trace-file", str(path), "ecu-inventory", "--ecus", "1234"])
            rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(rows[0]["inventory_target"], "1234")
            self.assertNotIn("inventory_target", rows[1])

    def test_trace_file_refuses_backup_output_collision_before_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "backup.zip"
            profile = Path(directory) / "profile.json"
            scope = {"Vin": VIN, "Tester": 0x0E80, "Ecu": 0x1234, "Length": 8, "BlockSize": 4}
            with patch("camarodiagconnectors.doip_cli.load_scope", return_value=scope), \
                 patch("camarodiagconnectors.doip_cli.discover") as discovery:
                with self.assertRaises(SystemExit):
                    run(["--host", GATEWAY.host, "--trace-file", str(path),
                         "backup", "--profile", str(profile), "--output", str(path)])
                discovery.assert_not_called()
                self.assertFalse(path.exists())

    def test_explicit_inventory_rejects_ranges_and_duplicates(self):
        self.assertEqual(parse_ecu_inventory("1234,1235"), (0x1234, 0x1235))
        for invalid in ("", "1234-1235", "1234,1234", "123", ",".join(f"{n:04X}" for n in range(17))):
            with self.assertRaises(DoIpError):
                parse_ecu_inventory(invalid)
        with patch("camarodiagconnectors.doip.discover") as discover_mock:
            with self.assertRaises(DoIpError):
                inventory_ecus(GATEWAY, VIN, 0x0E80, (0x0E80,))
            discover_mock.assert_not_called()

    def test_inventory_separates_verified_and_unverified_targets(self):
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY) as discover_mock, \
             patch("camarodiagconnectors.doip.Session") as session:
            session.return_value.__enter__.return_value.verify_vin.side_effect = [VIN, NegativeResponse(0x22, 0x31)]
            result = inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235))
            self.assertEqual([row["status"] for row in result["targets"]], ["vin_verified", "unverified"])
            self.assertEqual(result["targets"][1]["nrc"], 0x31)
            self.assertEqual(result["verified_count"], 1)
            self.assertEqual(discover_mock.call_count, 3)
            self.assertEqual([call.args[2] for call in session.call_args_list], [0x1234, 0x1235])
            self.assertTrue(all(call.kwargs["tls"] for call in session.call_args_list))

    def test_obd_inventory_distinguishes_vin_and_protocol_support(self):
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY) as discover_mock, \
             patch("camarodiagconnectors.doip.Session") as session:
            active = session.return_value.__enter__.return_value
            active.identify_obd.side_effect = [
                {"vin": VIN, "protocol_value": 1, "obd_on_uds": True},
                {"vin": VIN, "protocol_value": 2, "obd_on_uds": False},
                NegativeResponse(0x22, 0x31)]
            result = inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235, 0x1236), obd=True)
            self.assertEqual(result["identification"], "F802/F810")
            self.assertEqual(result["verified_count"], 2)
            self.assertEqual(result["obd_on_uds_count"], 1)
            self.assertEqual(result["targets"][1]["protocol_value"], 2)
            self.assertFalse(result["targets"][1]["obd_on_uds"])
            self.assertEqual(result["targets"][2]["status"], "unverified")
            self.assertNotIn("obd_on_uds", result["targets"][2])
            self.assertEqual(discover_mock.call_count, 4)
            active.verify_vin.assert_not_called()

    def test_inventory_trace_attributes_real_events_and_final_discovery(self):
        events = []
        final = []
        def discover_target(host, expected, timeout, trace):
            trace("RX", "UDP", host, "0001")
            return GATEWAY
        def open_session(gateway, tester, ecu, **kwargs):
            kwargs["trace"]("TX", "TLS", gateway.host, "0005")
            context = unittest.mock.MagicMock()
            context.__enter__.return_value.verify_vin.return_value = VIN
            return context
        with patch("camarodiagconnectors.doip.discover", side_effect=discover_target), \
             patch("camarodiagconnectors.doip.Session", side_effect=open_session):
            result = inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235),
                trace=lambda *event: final.append(event), target_trace=lambda *event: events.append(event))
        self.assertEqual(result["verified_count"], 2)
        self.assertEqual([(row[0], row[2], row[4]) for row in events],
                         [(0x1234, "UDP", "0001"), (0x1234, "TLS", "0005"),
                          (0x1235, "UDP", "0001"), (0x1235, "TLS", "0005")])
        self.assertEqual(final, [("RX", "UDP", GATEWAY.host, "0001")])

    def test_inventory_trace_failure_aborts_without_next_ecu(self):
        def broken(*args):
            raise OSError("trace unavailable")
        def open_session(gateway, tester, ecu, **kwargs):
            kwargs["trace"]("TX", "TLS", gateway.host, "0005")
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip.Session", side_effect=open_session) as session:
            with self.assertRaisesRegex(RuntimeError, "trace failed"):
                inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235), target_trace=broken)
            self.assertEqual(session.call_count, 1)

    def test_obd_inventory_cli_selects_legislated_operation(self):
        with patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip_cli.inventory_ecus", return_value={"complete": True}) as inventory:
            result = run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80",
                          "obd-ecu-inventory", "--ecus", "1234,1235"])
            self.assertTrue(result["complete"])
            self.assertTrue(inventory.call_args.kwargs["obd"])
            self.assertTrue(inventory.call_args.kwargs["tls"])
            self.assertEqual(inventory.call_args.args[3], (0x1234, 0x1235))

    def test_inventory_close_failure_does_not_duplicate_target(self):
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip.Session") as session:
            context = session.return_value
            context.__enter__.return_value.verify_vin.return_value = VIN
            context.__exit__.side_effect = [OSError("close failed"), None]
            result = inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235))
            self.assertEqual([row["ecu"] for row in result["targets"]], ["1234", "1235"])
            self.assertEqual([row["status"] for row in result["targets"]],
                             ["unverified", "vin_verified"])
            self.assertEqual(result["verified_count"], 1)

    def test_inventory_distinguishes_transport_refusal_from_uds_nrc(self):
        failures = [TransportRefusal("header_nack", 2), TransportRefusal("routing_denied", 6),
                    TransportRefusal("diagnostic_nack", 6), NegativeResponse(0x22, 0x31)]
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip.Session") as session:
            session.return_value.__enter__.return_value.verify_vin.side_effect = failures
            result = inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235, 0x1236, 0x1237))
        rows = result["targets"]
        self.assertEqual([row["reason"] for row in rows],
                         ["header_nack", "routing_denied", "diagnostic_nack", "uds_negative_response"])
        self.assertEqual([row["failure_code"] for row in rows], [2, 6, 6, 0x31])
        self.assertNotIn("nrc", rows[2])
        self.assertEqual(rows[3]["requested_service"], 0x22)
        self.assertEqual(rows[3]["nrc"], 0x31)

    def test_header_nack_preserves_code_only_for_valid_length(self):
        from camarodiagconnectors.doip import require_no_header_nack
        for code in range(5):
            with self.assertRaises(TransportRefusal) as error:
                require_no_header_nack(0, bytes((code,)))
            self.assertEqual((error.exception.layer, error.exception.code), ("header_nack", code))
        for malformed in (b"", b"\x05", b"\x00\x01"):
            with self.assertRaises(DoIpError) as error:
                require_no_header_nack(0, malformed)
            self.assertNotIsInstance(error.exception, TransportRefusal)
        require_no_header_nack(0x8001, b"")
        with self.assertRaises(TransportRefusal):
            parse_identity(GATEWAY.host, encode(2, 0, b"\x02"), VIN)

    def test_inventory_progress_is_detached_and_follows_gateway_revalidation(self):
        events = []
        def discover_target(*args):
            events.append("gateway")
            return GATEWAY
        def progress(rows, total):
            self.assertEqual(total, 2)
            events.append(("progress", len(rows), rows[-1].get("reason")))
            rows[0]["status"] = "caller mutation"
        with patch("camarodiagconnectors.doip.discover", side_effect=discover_target), \
             patch("camarodiagconnectors.doip.Session") as session:
            active = session.return_value.__enter__.return_value
            active.verify_vin.side_effect = [VIN, TimeoutError("no response")]
            session.return_value.__exit__.side_effect = lambda *_: events.append("close")
            result = inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235), on_progress=progress)
        self.assertEqual(events, ["gateway", "close", "gateway", ("progress", 1, None),
                                  "close", "gateway", ("progress", 2, "timeout")])
        self.assertEqual(result["targets"][0]["status"], "vin_verified")
        self.assertEqual(result["targets"][1]["reason"], "timeout")

    def test_inventory_drift_prevents_progress_and_next_target(self):
        from dataclasses import replace
        progress = unittest.mock.Mock()
        with patch("camarodiagconnectors.doip.discover",
                   side_effect=[GATEWAY, replace(GATEWAY, logical_address=0x1235)]), \
             patch("camarodiagconnectors.doip.Session") as session:
            session.return_value.__enter__.return_value.verify_vin.return_value = VIN
            with self.assertRaises(DoIpError):
                inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235), on_progress=progress)
            progress.assert_not_called()
            self.assertEqual(session.call_count, 1)

    def test_inventory_progress_failure_never_becomes_an_unverified_ecu(self):
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip.Session") as session:
            session.return_value.__enter__.return_value.verify_vin.return_value = VIN
            with self.assertRaisesRegex(OSError, "output failed"):
                inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235),
                    on_progress=unittest.mock.Mock(side_effect=OSError("output failed")))
            self.assertEqual(session.call_count, 1)

    def test_inventory_tls_error_stops_all_remaining_targets(self):
        import ssl
        with patch("camarodiagconnectors.doip.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip.Session") as session:
            session.return_value.__enter__.side_effect = ssl.SSLCertVerificationError("untrusted")
            with self.assertRaises(ssl.SSLCertVerificationError):
                inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235), obd=True)
            self.assertEqual(session.call_count, 1)

    def test_non_ascii_vin_is_a_protocol_error(self):
        fake = unittest.mock.Mock(spec=Session)
        fake.gateway = GATEWAY
        fake.exchange.return_value = bytes.fromhex("62F190FF")
        with self.assertRaises(DoIpError):
            Session.verify_vin(fake, VIN)
        fake.close.assert_called_once()

    def test_inventory_stops_if_gateway_changes(self):
        from dataclasses import replace
        with patch("camarodiagconnectors.doip.discover", side_effect=[GATEWAY, replace(GATEWAY, logical_address=0x1235)]), \
             patch("camarodiagconnectors.doip.Session") as session:
            session.return_value.__enter__.return_value.verify_vin.return_value = VIN
            with self.assertRaises(DoIpError):
                inventory_ecus(GATEWAY, VIN, 0x0E80, (0x1234, 0x1235))
            self.assertEqual(session.call_count, 1)


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

    def test_diagnostic_options_are_exact_and_not_repeatable(self):
        with patch("camarodiagconnectors.doip_cli.discover") as discovery:
            for args in (
                ["--host", "127.0.0.1", "--host=127.0.0.2", "discover"],
                ["--hos", "127.0.0.1", "discover"],
                ["--host", "127.0.0.1", "--tcp", "discover"],
                ["--host", "127.0.0.1", "--tester", "0E80", "status"],
                ["--host", "127.0.0.1", "--vin", VIN, "--tester", "0E80", "--ecu", "1234",
                 "did", "--di", "F190"],
                ["--host", "127.0.0.1", "--vin", VIN, "--tester", "0E80", "--ecu", "1234",
                 "did", "--did", "F190", "--did=F187"],
            ):
                with self.subTest(args=args), self.assertRaises(SystemExit):
                    run(args)
            discovery.assert_not_called()

    def test_read_operations_revalidate_gateway_before_routing(self):
        from dataclasses import replace
        for action in (["vin"], ["did", "--did", "F190"], ["permanent-dtcs"]):
            with patch("camarodiagconnectors.doip_cli.discover",
                       side_effect=[GATEWAY, replace(GATEWAY, eid="010203040506")]) as discovery, \
                 patch("camarodiagconnectors.doip_cli.Session") as session:
                with self.assertRaises(DoIpError):
                    run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80", "--ecu", "1234", *action])
                self.assertEqual(discovery.call_count, 2)
                session.assert_not_called()

    def test_invalid_tls_files_fail_before_discovery(self):
        with patch("camarodiagconnectors.doip_cli.ssl.create_default_context",
                   side_effect=OSError("Certificate missing")), \
             patch("camarodiagconnectors.doip_cli.discover") as discovery:
            with self.assertRaises(OSError):
                run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80", "--ecu", "1234",
                     "--ca-file", "missing.pem", "vin"])
            discovery.assert_not_called()

    def test_backup_refuses_gateway_substitution_after_acquisition(self):
        from dataclasses import replace
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory) / "profile.json"
            output = Path(directory) / "backup.zip"
            scope = {"Vin": VIN, "Gateway": GATEWAY.logical_address, "Tester": 0x0E80,
                     "Ecu": 0x1234, "Length": 4, "BlockSize": 2}
            with patch("camarodiagconnectors.doip_cli.load_scope", return_value=scope), \
                 patch("camarodiagconnectors.doip_cli.discover",
                       side_effect=[GATEWAY, GATEWAY, replace(GATEWAY, gid="010203040506")]), \
                 patch("camarodiagconnectors.doip_cli.Session"), \
                 patch("camarodiagconnectors.doip_cli.capture_region", return_value=(b"ABCD", b"ABCD")), \
                 patch("camarodiagconnectors.doip_cli.save_capture") as save:
                with self.assertRaises(DoIpError):
                    run(["--host", GATEWAY.host, "backup", "--profile", str(profile), "--output", str(output)])
                save.assert_not_called()
                self.assertFalse(output.exists())

    def test_backup_publication_requires_successful_session_close(self):
        for close_fails in (False, True):
            with self.subTest(close_fails=close_fails), tempfile.TemporaryDirectory() as directory:
                profile = Path(directory) / "profile.json"
                output = Path(directory) / "backup.zip"
                scope = {"Vin": VIN, "Gateway": GATEWAY.logical_address, "Tester": 0x0E80,
                         "Ecu": 0x1234, "Length": 4, "BlockSize": 2}
                with patch("camarodiagconnectors.doip_cli.load_scope", return_value=scope), \
                     patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
                     patch("camarodiagconnectors.doip_cli.Session") as session, \
                     patch("camarodiagconnectors.doip_cli.capture_region", return_value=(b"ABCD", b"ABCD")), \
                     patch("camarodiagconnectors.doip_cli.save_capture") as save:
                    closed = []
                    def close(*_):
                        closed.append(True)
                        if close_fails:
                            raise OSError("Socket close failed")
                    session.return_value.__exit__.side_effect = close
                    def publish(*_):
                        self.assertEqual(closed, [True])
                        return {"archive_integrity_verified": True}
                    save.side_effect = publish
                    args = ["--host", GATEWAY.host, "backup", "--profile", str(profile),
                            "--output", str(output)]
                    if close_fails:
                        with self.assertRaises(OSError):
                            run(args)
                        save.assert_not_called()
                    else:
                        self.assertTrue(run(args)["archive_integrity_verified"])
                        save.assert_called_once()

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

    def test_permanent_dtc_inventory_is_distinct_from_supported_and_wwh(self):
        for payload, expected in (("591508", []), ("5915080102030001020408",
                                   [{"dtc": "010203", "status": 0}, {"dtc": "010204", "status": 8}])):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            result = Session.read_dtc_inventory(fake, VIN, permanent=True)
            self.assertEqual(result, {"kind": "permanent-dtcs", "records": expected, "availability_mask": 8})
            fake.exchange.assert_called_once_with(bytes.fromhex("1915"))
            self.assertEqual(fake.verify_vin.call_count, 2)
            fake.close.assert_not_called()
        for payload in ("590A08", "5915", "59150801", "59150801020301",
                        "5915080102030001020308"):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_dtc_inventory(fake, VIN, permanent=True)
            fake.close.assert_called_once()
        fake = unittest.mock.Mock(spec=Session)
        with self.assertRaises(DoIpError):
            Session.read_dtc_inventory(fake, VIN, snapshot_index=True, permanent=True)
        fake.exchange.assert_not_called()
        fake.verify_vin.assert_not_called()
        fake = unittest.mock.Mock(spec=Session)
        fake.exchange.return_value = bytes.fromhex("591508")
        fake.verify_vin.side_effect = [VIN, DoIpError("VIN changed")]
        with self.assertRaises(DoIpError):
            Session.read_dtc_inventory(fake, VIN, permanent=True)
        fake.close.assert_called_once()

    def test_permanent_dtc_cli_uses_verified_generic_uds(self):
        with patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip_cli.Session") as session:
            opened = session.return_value.__enter__.return_value
            opened.read_dtc_inventory.return_value = {"kind": "permanent-dtcs", "records": [], "availability_mask": 8}
            result = run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80",
                          "--ecu", "1234", "permanent-dtcs"])
            self.assertEqual(result["service_protocol"], "UDS")
            self.assertEqual(result["kind"], "permanent-dtcs")
            self.assertTrue(session.call_args.kwargs["tls"])
            opened.read_dtc_inventory.assert_called_once_with(VIN, snapshot_index=False, permanent=True)

    def test_dtc_severity_queries_preserve_raw_fields_and_identity(self):
        for sub, data, options, request in (
                (7, "590708010002", {"severity_mask": 0x80, "status_mask": 8}, "19078008"),
                (8, "590808801212345608", {"severity_mask": 0x80, "status_mask": 8}, "19088008"),
                (9, "590908401212345600", {"dtc": 0x123456}, "1909123456"),
                (9, "590908", {"dtc": 0x123456}, "1909123456")):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(data)
            report = Session.read_dtc_severity(fake, VIN, sub, **options)
            self.assertEqual(report["availability_mask"], 8)
            fake.exchange.assert_called_once_with(bytes.fromhex(request))
            self.assertEqual(fake.verify_vin.call_count, 2)
            if sub == 7:
                self.assertEqual(report["count"], 2)
                self.assertEqual(report["format_identifier"], 1)
            elif len(data) > 6:
                self.assertEqual(report["records"][0]["dtc"], "123456")
                self.assertEqual(report["records"][0]["functional_unit"], 0x12)
            else:
                self.assertEqual(report["records"], [])
        for sub, data, options in (
                (7, "59070801", {}),
                (8, "590808401212345608", {"severity_mask": 0x80, "status_mask": 8}),
                (8, "590808801212345601", {"severity_mask": 0x80, "status_mask": 8}),
                (9, "590908801212345708", {"dtc": 0x123456})):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(data)
            with self.assertRaises(DoIpError):
                Session.read_dtc_severity(fake, VIN, sub, **options)
            fake.close.assert_called_once()

    def test_legacy_mirror_and_emissions_reads(self):
        for sub in (0x0F, 0x11, 0x12, 0x13):
            fake = unittest.mock.Mock(spec=Session)
            count = sub in (0x11, 0x12)
            fake.exchange.return_value = bytes((0x59, sub, 8)) + (bytes((1, 0, 2)) if count else bytes((1, 2, 3, 8)))
            result = Session.read_legacy_dtc(fake, VIN, sub, mask=8)
            self.assertEqual(result["scope"], "mirror" if sub in (0x0F, 0x11) else "emissions")
            self.assertTrue(result["legacy"])
            self.assertEqual(result["count"] if count else result["records"][0]["dtc"], 2 if count else "010203")
            fake.exchange.assert_called_once_with(bytes((0x19, sub, 8)))
            self.assertEqual(fake.verify_vin.call_count, 2)
        for sub, payload in ((0x0F, "590F0801020301"), (0x13, "5913080102030801020308"),
                             (0x11, "5911080100"), (0x12, "59120801000200")):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_legacy_dtc(fake, VIN, sub, mask=8)
            fake.close.assert_called_once()
        fake = unittest.mock.Mock(spec=Session)
        fake.exchange.return_value = bytes.fromhex("59100102030801AABB")
        result = Session.read_legacy_dtc(fake, VIN, 0x10, dtc=0x010203, record=1)
        self.assertEqual(result["raw_records"], "01AABB")
        fake.exchange.assert_called_once_with(bytes.fromhex("191001020301"))
        for payload in ("59100102040801", "59100102030802"):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_legacy_dtc(fake, VIN, 0x10, dtc=0x010203, record=1)
        fake = unittest.mock.Mock(spec=Session)
        with self.assertRaises(DoIpError):
            Session.read_legacy_dtc(fake, VIN, 0x10, dtc=1, record=0xF0)
        fake.verify_vin.assert_not_called()

    def test_decoded_memory_records_require_exact_memory_and_dtc(self):
        for extended, request, response, lengths in (
                (False, "1918010203FF02", "591802010203080101F40D78", {0xF40D: 1}),
                (True, "1919010203FF02", "5919020102030801123490AA", {1: 2, 0x90: 1})):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(response)
            report = Session.read_decoded_user_memory(fake, VIN, 2, 0x010203, 255, lengths, extended=extended)
            self.assertEqual(report["memory"], 2)
            self.assertEqual(report["raw_response"], response)
            self.assertEqual(report["records"][0]["record"], 1)
            fake.exchange.assert_called_once_with(bytes.fromhex(request))
            self.assertEqual(fake.verify_vin.call_count, 2)
            for invalid in (response[:4] + "03" + response[6:], response[:-2]):
                fake = unittest.mock.Mock(spec=Session)
                fake.exchange.return_value = bytes.fromhex(invalid)
                with self.assertRaises(DoIpError):
                    Session.read_decoded_user_memory(fake, VIN, 2, 0x010203, 255, lengths, extended=extended)
                fake.close.assert_called_once()

    def test_decoded_extended_data_respects_record_scope_and_identity(self):
        lengths = {1: 2, 0x90: 1, 0x91: 2}
        for obd in (False, True):
            fake = unittest.mock.Mock(spec=Session)
            fake.read_obd.return_value = {"obd_on_uds": True}
            fake.exchange.return_value = bytes.fromhex("59060102030801123490AA91BBCC")
            report = Session.read_extended_records(fake, VIN, 0x010203, 255, lengths, obd=obd)
            self.assertEqual([r["record"] for r in report["records"]], [1, 0x90, 0x91])
            self.assertEqual(report["records"][2]["data"], "BBCC")
            self.assertEqual(report["raw_response"], "59060102030801123490AA91BBCC")
            fake.exchange.assert_called_once_with(bytes.fromhex("1906010203FF"))
            self.assertEqual(fake.read_obd.call_count if obd else fake.verify_vin.call_count, 2)
        for selector, payload in ((0xFE, "590601020308011234"),
                                  (1, "59060102030801123490AA"),
                                  (255, "59060102030890AA90BB"),
                                  (255, "5906010203080112"),
                                  (255, "59060102030802AA"),
                                  (255, "59060102040890AA")):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_extended_records(fake, VIN, 0x010203, selector, lengths)
            fake.close.assert_called_once()

    def test_decoded_snapshots_preserve_historical_data(self):
        lengths = {0xF40C: 2, 0xF40D: 1}
        response = bytes.fromhex("5904010203080102F40C1234F40D560201F40D78")
        for obd in (False, True):
            fake = unittest.mock.Mock(spec=Session)
            fake.read_obd.return_value = {"obd_on_uds": True}
            fake.exchange.return_value = response
            result = Session.read_snapshot_records(fake, VIN, 0x010203, 255, lengths, obd=obd)
            self.assertEqual([r["record"] for r in result["records"]], [1, 2])
            self.assertEqual(result["records"][1]["dids"][0]["data"], "78")
            self.assertEqual(result["raw_response"], response.hex().upper())
            fake.exchange.assert_called_once_with(bytes.fromhex("1904010203FF"))
            self.assertEqual(fake.read_obd.call_count if obd else fake.verify_vin.call_count, 2)
        for payload in ("59040102030801", "5904010203080101F40C12",
                        "5904010203080101FFFFAA", "59040102030801000100",
                        "5904010204080100"):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_snapshot_records(fake, VIN, 0x010203, 255, lengths)
            fake.close.assert_called_once()
        fake = unittest.mock.Mock(spec=Session)
        fake.exchange.return_value = bytes.fromhex("590401020308")
        self.assertEqual(Session.read_snapshot_records(fake, VIN, 0x010203, 0, lengths)["records"], [])

    def test_individual_decoded_stored_record_rejects_other_records(self):
        lengths = {0xF40D: 1}
        for response, count in (("5905010102030801F40D78", 1), ("590501", 0)):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(response)
            report = Session.read_decoded_stored_dtc_records(fake, VIN, 1, lengths)
            self.assertEqual(report["record"], 1)
            self.assertEqual(len(report["records"]), count)
            self.assertEqual(report["raw_response"], response)
            self.assertEqual(fake.verify_vin.call_count, 2)
            fake.exchange.assert_called_once_with(bytes.fromhex("190501"))
        for invalid in ("590502", "5905010102030801F40D", "5905010102030801F40D78020405060801F40D79"):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(invalid)
            with self.assertRaises(DoIpError):
                Session.read_decoded_stored_dtc_records(fake, VIN, 1, lengths)
            fake.close.assert_called_once()

    def test_all_stored_records_use_explicit_lengths_and_verify_identity(self):
        lengths = parse_stored_did_lengths("F40C:2,F40D:1")
        response = bytes.fromhex("5905010102030802F40C1234F40D56020405060001F40D78")
        fake = unittest.mock.Mock(spec=Session)
        fake.exchange.return_value = response
        result = Session.read_decoded_stored_dtc_records(fake, VIN, 255, lengths)
        self.assertEqual(result["records"][0]["dids"][0]["data"], "1234")
        self.assertEqual(result["records"][1]["record"], 2)
        self.assertEqual(result["records"][1]["dids"][0]["data"], "78")
        self.assertEqual(result["raw_response"], response.hex().upper())
        fake.exchange.assert_called_once_with(bytes.fromhex("1905FF"))
        self.assertEqual(fake.verify_vin.call_count, 2)
        self.assertEqual(decode_all_stored_records(bytes.fromhex("5905FF"), lengths), [])
        for invalid in ("590501", "5905010102030801F40C12",
                        "5905010102030801FFFFAA", "5905010102030800010405060000",
                        "590501010203080000"):
            with self.assertRaises(DoIpError):
                decode_all_stored_records(bytes.fromhex(invalid), lengths)
        for invalid in ("F40D:0", "F40D:1,F40D:2", "F40D:4097", "F40G:1"):
            with self.assertRaises(DoIpError):
                parse_stored_did_lengths(invalid)
        fake.verify_vin.side_effect = [VIN, DoIpError("VIN changed")]
        with self.assertRaises(DoIpError):
            Session.read_decoded_stored_dtc_records(fake, VIN, 255, lengths)
        fake.close.assert_called_once()

    def test_all_stored_records_cli_requires_documented_lengths(self):
        with patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip_cli.Session") as session:
            opened = session.return_value.__enter__.return_value
            opened.read_all_stored_dtc_records.return_value = {"records": []}
            run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80", "--ecu", "1234",
                 "stored-records", "--did-lengths", "F40D:1"])
            opened.read_all_stored_dtc_records.assert_called_once_with(VIN, {0xF40D: 1})

    def test_stored_record_preserves_data_and_validates_echo(self):
        for response, present in (("590501", False), ("5905010102030801F190AA", True)):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(response)
            result = Session.read_stored_dtc_record(fake, VIN, 1)
            self.assertEqual(result["present"], present)
            self.assertEqual(result["raw_response"], response)
            fake.exchange.assert_called_once_with(bytes.fromhex("190501"))
            self.assertEqual(fake.verify_vin.call_count, 2)
        for response in ("590502", "590501010203", "5905010102030802F190", "5905010102030800AA"):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(response)
            with self.assertRaises(DoIpError):
                Session.read_stored_dtc_record(fake, VIN, 1)
            fake.close.assert_called_once()
        with patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip_cli.Session") as session:
            opened = session.return_value.__enter__.return_value
            opened.read_stored_dtc_record.return_value = {"present": False}
            run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80", "--ecu", "1234",
                 "stored-record", "--record", "01"])
            opened.read_stored_dtc_record.assert_called_once_with(VIN, 1)

    def test_extended_record_support_and_data(self):
        for size, payload, expected in (
            (None, "591A0801", []),
            (None, "591A08010102030001020408", [{"dtc": "010203", "status": 0}, {"dtc": "010204", "status": 8}]),
            (2, "59160101020308AABB01020400CCDD", [{"dtc": "010203", "status": 8, "data": "AABB"},
                                                   {"dtc": "010204", "status": 0, "data": "CCDD"}]),
            (2, "591601", []),
        ):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            report = Session.read_extended_record(fake, VIN, 1, size=size)
            self.assertEqual(report["records"], expected)
            fake.exchange.assert_called_once_with(bytes((0x19, 0x1A if size is None else 0x16, 1)))
            self.assertEqual(fake.verify_vin.call_count, 2)
        for size, payload in ((None, "591A0802"), (None, "591A080101020301"),
                              (None, "591A08010102030801020300"), (2, "59160101020308AA"),
                              (2, "591602"), (2, "59160101020308AABB01020308CCDD")):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_extended_record(fake, VIN, 1, size=size)
            fake.close.assert_called_once()
        fake = unittest.mock.Mock(spec=Session)
        with self.assertRaises(DoIpError):
            Session.read_extended_record(fake, VIN, 0xFF, size=2)
        fake.verify_vin.assert_not_called()
        with patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip_cli.Session") as session:
            opened = session.return_value.__enter__.return_value
            opened.read_extended_record.return_value = {"records": []}
            run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80", "--ecu", "1234",
                 "extended-record-data", "--record", "01", "--size", "2"])
            opened.read_extended_record.assert_called_once_with(VIN, 1, size=2)
            self.assertTrue(session.call_args.kwargs["tls"])

    def test_user_memory_dtc_operations_validate_memory_and_record(self):
        for sub, payload, options, request in (
            (0x17, "59170208", {"mask": 8}, "19170802"),
            (0x17, "5917020801020308", {"mask": 8}, "19170802"),
            (0x18, "591802010203080102F190", {"dtc": 0x010203, "record": 1}, "19180102030102"),
            (0x19, "5919020102030801AABB", {"dtc": 0x010203, "record": 1}, "19190102030102"),
        ):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            result = Session.read_user_memory_dtc(fake, VIN, 2, subfunction=sub, **options)
            self.assertEqual(result["memory"], 2)
            self.assertEqual(result["subfunction"], sub)
            fake.exchange.assert_called_once_with(bytes.fromhex(request))
            self.assertEqual(fake.verify_vin.call_count, 2)
            if sub == 0x19:
                self.assertEqual(result["raw_records"], "01AABB")
            fake.close.assert_not_called()
        for sub, payload, options in (
            (0x17, "59170308", {"mask": 8}),
            (0x17, "5917020801020301", {"mask": 8}),
            (0x17, "591702080102030801020308", {"mask": 8}),
            (0x17, "5917020801", {"mask": 8}),
            (0x18, "5918020102040801", {"dtc": 0x010203, "record": 1}),
            (0x19, "5919020102030802", {"dtc": 0x010203, "record": 1}),
        ):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_user_memory_dtc(fake, VIN, 2, subfunction=sub, **options)
            fake.close.assert_called_once()
        fake = unittest.mock.Mock(spec=Session)
        with self.assertRaises(DoIpError):
            Session.read_user_memory_dtc(fake, VIN, 2, subfunction=0x19, dtc=1, record=0xF0)
        fake.exchange.assert_not_called()
        fake.verify_vin.assert_not_called()

    def test_user_memory_cli_uses_explicit_memory_and_tls(self):
        with patch("camarodiagconnectors.doip_cli.discover", return_value=GATEWAY), \
             patch("camarodiagconnectors.doip_cli.Session") as session:
            opened = session.return_value.__enter__.return_value
            opened.read_user_memory_dtc.return_value = {"memory": 2, "records": []}
            result = run(["--host", GATEWAY.host, "--vin", VIN, "--tester", "0E80",
                          "--ecu", "1234", "memory-dtcs", "--memory", "02", "--mask", "08"])
            self.assertEqual(result["memory"], 2)
            self.assertTrue(session.call_args.kwargs["tls"])
            opened.read_user_memory_dtc.assert_called_once_with(VIN, 2, subfunction=0x17,
                                                                mask=8, dtc=None, record=None)

    def test_fault_counters_are_signed_and_separate_from_status(self):
        for payload, counters in (("5914", []), ("59140102037F01020480010205FF", [127, -128, -1])):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            self.assertEqual([r["counter"] for r in Session.read_fault_counters(fake, VIN)["fault_counters"]], counters)
            fake.exchange.assert_called_once_with(bytes.fromhex("1914"))
            self.assertEqual(fake.verify_vin.call_count, 2)
        for payload in ("591401", "59140102030101020302", "5903"):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.return_value = bytes.fromhex(payload)
            with self.assertRaises(DoIpError):
                Session.read_fault_counters(fake, VIN)
            fake.close.assert_called_once()

    def test_historical_dtc_reports_zero_or_one_record(self):
        for sub in range(0x0B, 0x0F):
            for payload, expected in ((bytes((0x59, sub, 8)), None),
                                       (bytes((0x59, sub, 8, 1, 2, 3, 0)), {"dtc": "010203", "status": 0})):
                fake = unittest.mock.Mock(spec=Session)
                fake.exchange.return_value = payload
                self.assertEqual(Session.read_historical_dtc(fake, VIN, sub)["record"], expected)
                fake.exchange.assert_called_once_with(bytes((0x19, sub)))
                self.assertEqual(fake.verify_vin.call_count, 2)
            for payload in (bytes((0x59, sub, 8, 1)), bytes((0x59, sub, 8, 1, 2, 3, 1)),
                            bytes((0x59, sub, 8, 1, 2, 3, 0, 4, 5, 6, 0))):
                fake = unittest.mock.Mock(spec=Session)
                fake.exchange.return_value = payload
                with self.assertRaises(DoIpError):
                    Session.read_historical_dtc(fake, VIN, sub)
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
        fake.read_obd.side_effect = [{"obd_on_uds": True}] * 3
        def observe(row):
            row["dids"][0]["data"] = "BAD"
        report = Session.monitor_dids(fake, VIN, layout, 2, 0.25, obd=True, on_sample=observe)
        self.assertEqual([row["dids"][0]["data"] for row in report["samples"]], ["1234", "5678"])
        self.assertEqual(fake.exchange.call_count, 2)
        fake.wait.assert_called_once_with(0.25)
        self.assertEqual(fake.read_obd.call_count, 3)
        fake.close.assert_not_called()
        for obd in (False, True):
            fake = unittest.mock.Mock(spec=Session)
            fake.exchange.side_effect = [bytes.fromhex("62F40C1234F40D56"),
                                         bytes.fromhex("62F40C5678F40D90")]
            fake.read_obd.side_effect = [{"obd_on_uds": True}, {"obd_on_uds": True},
                                         {"obd_on_uds": False}]
            fake.verify_vin.side_effect = [VIN, VIN, DoIpError("ECU VIN changed")]
            observed = []
            with self.assertRaises(DoIpError):
                Session.monitor_dids(fake, VIN, layout, 2, 0.25, obd=obd, on_sample=observed.append)
            self.assertEqual([row["index"] for row in observed], [1])
            self.assertEqual(observed[0]["dids"][0]["data"], "1234")
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
                self.assertEqual(result["records"], [{"dtc": "123456", "status": 8,
                    "severity": severity, "status_flags": ["confirmed_dtc"]}])
                if subfunction == 0x42:
                    self.assertEqual(result["effective_mask"], 8)
                    self.assertEqual(result["effective_severity_mask"], 0xFC)
                    self.assertTrue(result["filter_available"])
                    self.assertTrue(result["severity_filter_available"])
                else:
                    self.assertNotIn("effective_mask", result)
                    self.assertNotIn("effective_severity_mask", result)
                fake.close.assert_not_called()
        # Zero intersection is not evidence of a clean emissions system.
        fake = Session.__new__(Session)
        fake.read_obd = unittest.mock.Mock(return_value={"obd_on_uds": True})
        fake.exchange = unittest.mock.Mock(return_value=bytes.fromhex("594233010104"))
        fake.close = unittest.mock.Mock()
        result = fake.wwh_dtcs(VIN, 0x42, status_mask=8, severity_mask=0x80)
        self.assertFalse(result["filter_available"])
        self.assertFalse(result["severity_filter_available"])
        self.assertEqual(result["records"], [])
        fake.exchange.assert_called_once_with(bytes.fromhex("1942338008"))
        fake.close.assert_not_called()
        # The raw status stays available for evidence; unsupported bits are
        # deliberately not interpreted as confirmed or pending faults.
        fake.exchange.return_value = bytes.fromhex("595533010412345608")
        result = fake.wwh_dtcs(VIN, 0x55)
        self.assertEqual(result["records"][0]["status"], 8)
        self.assertEqual(result["records"][0]["status_flags"], [])
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
                client._operation_lock = threading.Lock()
                client._drain_ready = lambda *_: None
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
        for values, ok, count in [([True, True, True], True, 2), ([False], False, 0),
                                   ([True, False], False, 0), ([True, True, False], False, 1)]:
            fake = Fake(values)
            partial = []
            if ok:
                result = Session.monitor(fake, VIN, 0xF40C, 2, .25, obd=True, on_sample=partial.append)
                self.assertEqual(result["service_protocol"], "OBDonUDS")
                self.assertEqual([r["data"] for r in result["samples"]], ["1234", "1234"])
                self.assertEqual(fake.calls, [("obd", VIN), ("did", 0xF40C), ("obd", VIN),
                                              ("wait", .25), ("did", 0xF40C), ("obd", VIN)])
            else:
                with self.assertRaises(DoIpError):
                    Session.monitor(fake, VIN, 0xF40C, 2, .25, obd=True, on_sample=partial.append)
                self.assertTrue(fake.closed)
            self.assertEqual(len(partial), count)
            self.assertNotIn(("uds", VIN), fake.calls)

    def test_uds_monitor_does_not_publish_sample_with_changed_vin(self):
        fake = unittest.mock.Mock(spec=Session)
        fake.verify_vin.side_effect = [VIN, VIN, DoIpError("ECU VIN changed")]
        fake.read_did.side_effect = [b"\x01", b"\x02"]
        observed = []
        with self.assertRaises(DoIpError):
            Session.monitor(fake, VIN, 0xF40D, 2, .25, on_sample=observed.append)
        self.assertEqual([(r["index"], r["data"]) for r in observed], [(1, "01")])
        self.assertEqual(fake.verify_vin.call_count, 3)
        fake.close.assert_called_once()

    def test_idle_interval_expiring_before_read_does_not_close_session(self):
        client = Session.__new__(Session)
        client._busy = False
        client._operation_lock = threading.Lock()
        wire = unittest.mock.Mock()
        client._wire = lambda: wire
        client.close = unittest.mock.Mock()
        with patch("camarodiagconnectors.doip.time.monotonic", side_effect=[0.0, 0.25]):
            client.wait(.25)
        wire.recv.assert_not_called()
        client.close.assert_not_called()

    def test_idle_header_nack_is_not_attributed_to_unsent_request(self):
        client = Session.__new__(Session)
        client.tester, client.ecu = 0x0E80, 0x1234
        client.timeout = client.p2 = client.p2_star = 2
        client._operation_lock = threading.Lock()
        client._busy = False
        client._socket, peer = socket.socketpair()
        client.gateway, client.tls, client.trace = GATEWAY, False, None
        peer.settimeout(2)
        try:
            peer.sendall(encode(2, 0, bytes((2,))))
            with self.assertRaises(DoIpError) as failure:
                client.exchange(bytes.fromhex("22F190"))
            self.assertNotIsInstance(failure.exception, TransportRefusal)
            self.assertIsNone(client._socket)
            self.assertEqual(peer.recv(1), b"")
        finally:
            client.close()
            peer.close()

    def test_cli_single_operation_refusal_has_no_success_output(self):
        from contextlib import redirect_stdout
        from camarodiagconnectors.doip_cli import main
        for error, reason, code in (
                (TransportRefusal("diagnostic_nack", 6), "diagnostic_nack", 6),
                (NegativeResponse(0x22, 0x31), "uds_negative_response", 0x31)):
            stderr, stdout = io.StringIO(), io.StringIO()
            with patch("camarodiagconnectors.doip_cli.run", side_effect=error), \
                 redirect_stderr(stderr), redirect_stdout(stdout):
                with self.assertRaises(SystemExit) as failure:
                    main()
            self.assertEqual(failure.exception.code, 2)
            self.assertEqual(stdout.getvalue(), "")
            report = json.loads(stderr.getvalue().splitlines()[0])["diagnostic_error"]
            self.assertEqual(report["reason"], reason)
            self.assertEqual(report["failure_code"], code)
            self.assertFalse(report["complete"])

    def test_wire_refusal_is_correlated_and_retires_session_without_retry(self):
        request = bytes.fromhex("22F190")
        for scenario in ("header", "diagnostic", "wrong_echo", "wrong_address", "bad_positive"):
            with self.subTest(scenario=scenario):
                client = Session.__new__(Session)
                client.tester, client.ecu = 0x0E80, 0x1234
                client.timeout = client.p2 = client.p2_star = 2
                client._operation_lock = threading.Lock()
                client._busy = False
                client._socket, peer = socket.socketpair()
                client.gateway, client.tls, client.trace = GATEWAY, False, None
                peer.settimeout(2)
                errors = []
                def server():
                    try:
                        self.assertEqual(receive(peer), (2, 0x8001, TESTER + ECU + request))
                        if scenario == "header":
                            frame = encode(2, 0, bytes((2,)))
                        else:
                            address = bytes.fromhex("1235") if scenario == "wrong_address" else ECU
                            echo = bytes.fromhex("19") if scenario == "wrong_echo" else request
                            frame = encode(2, 0x8002 if scenario == "bad_positive" else 0x8003,
                                           address + TESTER + bytes((6,)) + echo)
                        peer.sendall(frame)
                        self.assertEqual(peer.recv(1), b"")
                    except BaseException as error:
                        errors.append(error)
                worker = threading.Thread(target=server)
                worker.start()
                try:
                    with self.assertRaises(DoIpError) as failure:
                        client.exchange(request)
                    if scenario in ("header", "diagnostic"):
                        self.assertIsInstance(failure.exception, TransportRefusal)
                        self.assertEqual(failure.exception.layer,
                                         "header_nack" if scenario == "header" else "diagnostic_nack")
                        self.assertEqual(failure.exception.code, 2 if scenario == "header" else 6)
                    else:
                        self.assertNotIsInstance(failure.exception, TransportRefusal)
                    self.assertIsNone(client._socket)
                finally:
                    client.close()
                    worker.join(3)
                    peer.close()
                self.assertFalse(worker.is_alive())
                if errors:
                    raise errors[0]

    def test_queued_diagnostic_reply_retires_socket_before_next_transmission(self):
        client = Session.__new__(Session)
        client.tester, client.ecu = 0x0E80, 0x1234
        client.timeout = client.p2 = client.p2_star = 1
        client._operation_lock = threading.Lock()
        client._busy = False
        client._socket, peer = socket.socketpair()
        client.gateway, client.tls, client.trace = GATEWAY, False, None
        peer.settimeout(2)
        try:
            peer.sendall(encode(2, 0x8001, ECU + TESTER + bytes.fromhex("62123401")))
            with self.assertRaisesRegex(DoIpError, "before next request"):
                client.exchange(bytes.fromhex("221234"))
            self.assertIsNone(client._socket)
            self.assertEqual(peer.recv(1), b"")
        finally:
            client.close()
            peer.close()

    def test_queued_alivecheck_is_answered_before_next_request(self):
        client = Session.__new__(Session)
        client.tester, client.ecu = 0x0E80, 0x1234
        client.timeout = client.p2 = client.p2_star = 2
        client._operation_lock = threading.Lock()
        client._busy = False
        client._socket, peer = socket.socketpair()
        client.gateway, client.tls, client.trace = GATEWAY, False, None
        peer.settimeout(2)
        req = bytes.fromhex("221234")
        errors = []
        def server():
            try:
                self.assertEqual(receive(peer), (2, 8, TESTER))
                self.assertEqual(receive(peer), (2, 0x8001, TESTER + ECU + req))
                peer.sendall(encode(2, 0x8001, ECU + TESTER + bytes.fromhex("62123401")))
            except BaseException as error:
                errors.append(error)
        peer.sendall(encode(2, 7))
        worker = threading.Thread(target=server)
        worker.start()
        try:
            self.assertEqual(client.exchange(req), bytes.fromhex("62123401"))
        finally:
            client.close()
            worker.join(3)
            peer.close()
        self.assertFalse(worker.is_alive())
        if errors:
            raise errors[0]

    def test_concurrent_idle_and_exchange_are_rejected_without_closing_owner(self):
        client = Session.__new__(Session)
        client._operation_lock = threading.Lock()
        client.close = unittest.mock.Mock()
        with client._operation_lock:
            with self.assertRaises(DoIpError):
                client.exchange(bytes.fromhex("221234"))
            with self.assertRaises(DoIpError):
                client.wait(.25)
            client.close.assert_not_called()
        self.assertTrue(client._operation_lock.acquire(blocking=False))
        client._operation_lock.release()

    def test_sequence_holds_exclusive_lock_through_all_replies_and_callbacks(self):
        client = Session.__new__(Session)
        client._operation_lock = threading.Lock()
        client._busy = False
        client.close = unittest.mock.Mock()
        commands = (bytes.fromhex("22F190"), bytes.fromhex("22F187"))
        replies = (bytes.fromhex("62F190"), bytes.fromhex("62F187"))
        client._exchange_owned = unittest.mock.Mock(side_effect=replies)
        seen = []

        def on_reply(index, reply):
            self.assertTrue(client._busy)
            self.assertTrue(client._operation_lock.locked())
            seen.append((index, reply))
            # Even from a callback running on the owner thread, a nested
            # request must not consume a response or interrupt the sequence.
            with self.assertRaises(DoIpError):
                client.exchange(bytes.fromhex("221234"))
            with self.assertRaises(DoIpError):
                client.wait(.01)
            with self.assertRaises(DoIpError):
                client.exchange_sequence((bytes.fromhex("221234"),))
            client.close.assert_not_called()

        self.assertEqual(client.exchange_sequence(commands, on_reply), list(replies))
        self.assertEqual(seen, [(1, replies[0]), (2, replies[1])])
        self.assertEqual([call.args[0] for call in client._exchange_owned.call_args_list],
                         list(commands))
        self.assertFalse(client._busy)
        self.assertFalse(client._operation_lock.locked())
        client.close.assert_not_called()

    def test_sequence_rejects_other_thread_while_first_command_is_pending(self):
        client = Session.__new__(Session)
        client._operation_lock = threading.Lock()
        client._busy = False
        client.close = unittest.mock.Mock()
        entered, release = threading.Event(), threading.Event()
        errors, replies = [], []

        def fake_exchange(command, **_):
            if command == bytes.fromhex("22F190"):
                entered.set()
                if not release.wait(2):
                    raise AssertionError("Sequence was not released")
            return bytes((0x62,)) + command[1:]

        client._exchange_owned = unittest.mock.Mock(side_effect=fake_exchange)
        def worker():
            try:
                replies.extend(client.exchange_sequence(
                    (bytes.fromhex("22F190"), bytes.fromhex("22F187"))))
            except BaseException as error:
                errors.append(error)

        thread = threading.Thread(target=worker)
        thread.start()
        try:
            self.assertTrue(entered.wait(2))
            with self.assertRaises(DoIpError):
                client.exchange(bytes.fromhex("221234"))
            with self.assertRaises(DoIpError):
                client.exchange_sequence((bytes.fromhex("221234"),))
            client.close.assert_not_called()
        finally:
            release.set()
            thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(errors, [])
        self.assertEqual(replies, [bytes.fromhex("62F190"), bytes.fromhex("62F187")])
        self.assertFalse(client._operation_lock.locked())
        client.close.assert_not_called()

    def test_sequence_failure_retires_socket_and_releases_lock(self):
        for failure in ("exchange", "callback"):
            with self.subTest(failure=failure):
                client = Session.__new__(Session)
                client._operation_lock = threading.Lock()
                client._busy = False
                client.close = unittest.mock.Mock()
                client._exchange_owned = unittest.mock.Mock(
                    side_effect=DoIpError("Uncertain response") if failure == "exchange"
                    else [bytes.fromhex("62F190")])
                def callback(*_):
                    raise ValueError("Cannot publish reply")
                with self.assertRaises(DoIpError if failure == "exchange" else ValueError):
                    client.exchange_sequence((bytes.fromhex("22F190"),), callback)
                client.close.assert_called_once_with()
                self.assertFalse(client._busy)
                self.assertFalse(client._operation_lock.locked())

    def test_concurrent_sequence_never_closes_current_owner(self):
        client = Session.__new__(Session)
        client._operation_lock = threading.Lock()
        client.close = unittest.mock.Mock()
        client._exchange_owned = unittest.mock.Mock()
        with client._operation_lock:
            with self.assertRaises(DoIpError):
                client.exchange_sequence((bytes.fromhex("22F190"),))
        client.close.assert_not_called()
        client._exchange_owned.assert_not_called()

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

    def test_session_closed_by_final_rx_observer_cannot_publish_or_replay(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(3)
            errors = []
            def peer():
                try:
                    with listener.accept()[0] as sock:
                        sock.settimeout(3)
                        self.assertEqual(receive(sock), (2, 5, TESTER + bytes(5)))
                        sock.sendall(encode(2, 6, TESTER + ECU + b"\x10" + bytes(4)))
                        self.assertEqual(receive(sock), (2, 0x8001, TESTER + ECU + bytes.fromhex("22F190")))
                        sock.sendall(encode(2, 0x8001, ECU + TESTER + bytes.fromhex("62F190") + VIN.encode()))
                        self.assertEqual(sock.recv(1), b"")
                except BaseException as error:
                    errors.append(error)
            thread = threading.Thread(target=peer)
            thread.start()
            try:
                with Session(GATEWAY, 0xE80, 0x1234, tls=False, port=listener.getsockname()[1]) as client:
                    def observer(direction, transport, host, raw):
                        if direction == "RX" and raw[4:8] == "8001":
                            client.close()
                    client.trace = observer
                    with self.assertRaises(DoIpError):
                        client.exchange(bytes.fromhex("22F190"))
                    with self.assertRaises(DoIpError):
                        client.exchange(bytes.fromhex("22F190"))
            finally:
                thread.join(4)
            self.assertFalse(thread.is_alive())
            if errors:
                raise errors[0]

    def test_failed_close_still_retires_session(self):
        client = Session.__new__(Session)
        wire = unittest.mock.Mock()
        wire.close.side_effect = OSError("close failed")
        client._socket = wire
        with self.assertRaises(OSError):
            client.close()
        with self.assertRaises(DoIpError):
            client._wire()
        client.close()
        wire.close.assert_called_once()

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
