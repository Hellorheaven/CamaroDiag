"""No USB adapter or vehicle is opened by CLI argument/port listing tests."""
import contextlib
import io
import json
import sys
import types
import unittest
from unittest.mock import patch

from camarodiagconnectors import __main__ as cli


class DiscoveryCliTests(unittest.TestCase):
    def test_port_listing_is_read_only_sorted_and_machine_readable(self):
        serial = types.ModuleType("serial")
        serial.__path__ = []
        tools = types.ModuleType("serial.tools")
        tools.__path__ = []
        list_ports = types.ModuleType("serial.tools.list_ports")
        serial.tools = tools
        tools.list_ports = list_ports
        serial.Serial = lambda *args, **kwargs: self.fail("Listing opened a serial port")

        def port(device, desc, vid=None, pid=None):
            return types.SimpleNamespace(
                device=device, description=desc, vid=vid, pid=pid)

        list_ports.comports = lambda: [
            port("COM7", "Product\nname", 0x1234, 0x0001),
            port("COM3", "ELM adapter"),
        ]
        output = io.StringIO()
        with patch.dict(sys.modules, {
            "serial": serial, "serial.tools": tools,
            "serial.tools.list_ports": list_ports,
        }):
            with contextlib.redirect_stdout(output):
                cli.main(["--list-ports"])
        lines = output.getvalue().splitlines()
        self.assertEqual(len(lines), 2)
        entries = [json.loads(line) for line in lines]
        self.assertEqual([p["port"] for p in entries], ["COM3", "COM7"])
        self.assertEqual(entries[1]["description"], "Product\nname")
        self.assertEqual(entries[1]["vid"], "1234")
        self.assertEqual(entries[1]["pid"], "0001")
        self.assertTrue(all(not item["authenticated"] and not item["ecu_access"]
                            for item in entries))

    def test_untrusted_usb_description_is_escaped_as_one_json_line(self):
        description = "evil\n{\"fake\":true}\r"
        text = cli._port_summary(types.SimpleNamespace(
            device="/dev/ttyACM0", description=description, vid=None, pid=None))
        self.assertNotIn("\n", text)
        self.assertNotIn("\r", text)
        self.assertEqual(json.loads(text)["description"], description)

    def test_list_ports_cannot_be_mixed_with_an_active_probe(self):
        for argv in (
            ["--list-ports", "--port", "COM4"],
            ["--list-ports", "verify"],
            ["--list-ports", "--binary"],
        ):
            with contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    cli.main(argv)
                self.assertEqual(caught.exception.code, 2)

    def test_manual_probe_requires_both_device_and_command(self):
        for argv in ([], ["--port", "COM4"], ["verify"]):
            with contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    cli.main(argv)
                self.assertEqual(caught.exception.code, 2)

    def test_help_is_available_without_importing_or_opening_serial(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            with self.assertRaises(SystemExit) as caught:
                cli.main(["--help"])
        self.assertEqual(caught.exception.code, 0)
        self.assertIn("--list-ports", output.getvalue())
        self.assertIn("--binary", output.getvalue())


if __name__ == "__main__":
    unittest.main()
