import io
import pathlib
import tempfile
import unittest
from camarodiagconnectors.checkpoint import retrieve_checkpoint, save_checkpoint, validate

FIXTURE = bytes.fromhex("43444a0102000000010000000102030405060708090a0b0c0d0e0f10202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3fa75e21b6")
class Port:
    def __init__(self, response):
        self.stream = io.BytesIO(response)
        self.writes = []
    def write(self, data):
        self.writes.append(data)
        return len(data)
    def read(self, count):
        return self.stream.read(count)
    @property
    def in_waiting(self):
        return len(self.stream.getbuffer())-self.stream.tell()

class CheckpointTest(unittest.TestCase):
    def test_cross_language_fixture_and_exclusive_output(self):
        port = Port(b"CDJ/1 "+FIXTURE.hex().encode()+b"\n")
        result = retrieve_checkpoint(port)
        self.assertEqual(FIXTURE, result)
        self.assertEqual([b"CDJ/1 READ\n"], port.writes)
        with tempfile.TemporaryDirectory() as root:
            path = pathlib.Path(root)/"checkpoint.cdj"
            save_checkpoint(path, result)
            self.assertEqual(FIXTURE, path.read_bytes())
            with self.assertRaises(FileExistsError):
                save_checkpoint(path, result)

    def test_missing_and_invalid_record_responses(self):
        self.assertIsNone(retrieve_checkpoint(Port(b"CDJ/1 NONE\n")))
        for wire in (b"CDJ/1 CORRUPT\n", b"CDJ/1 NONE\nanything",
                     b"CDJ/1 "+b"00"*64+b"\n", b"CDJ/1 "+FIXTURE.hex().encode(),
                     b"CDJ/1 "+FIXTURE.hex().encode()+b"\nextra"):
            with self.subTest(wire=wire), self.assertRaises((ValueError, OSError)):
                retrieve_checkpoint(Port(wire))
        changed = bytearray(FIXTURE); changed[30] ^= 1
        with self.assertRaises(ValueError):
            validate(bytes(changed))

    def test_deadline(self):
        ticks = iter([0.0, 3.0])
        with self.assertRaises(TimeoutError):
            retrieve_checkpoint(Port(b"CDJ/1 NONE\n"), clock=lambda: next(ticks))
