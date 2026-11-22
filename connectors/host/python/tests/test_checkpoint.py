import io
import pathlib
import tempfile
import unittest
import zlib
from unittest.mock import patch
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

    def test_corrupt_checkpoint_never_creates_evidence_file(self):
        corrupted = bytearray(FIXTURE)
        corrupted[28] ^= 0x01
        with tempfile.TemporaryDirectory() as root:
            path = pathlib.Path(root) / "invalid.cdj"
            with self.assertRaises(ValueError):
                save_checkpoint(path, corrupted)
            self.assertFalse(path.exists())

    def test_checkpoint_file_cannot_overwrite_unrelated_existing_data(self):
        with tempfile.TemporaryDirectory() as root:
            path = pathlib.Path(root) / "existing.cdj"
            path.write_bytes(b"keep this unrelated evidence")
            with self.assertRaises(FileExistsError):
                save_checkpoint(path, FIXTURE)
            self.assertEqual(b"keep this unrelated evidence", path.read_bytes())

    def test_checkpoint_export_does_not_publish_partial_file_when_commit_fails(self):
        with tempfile.TemporaryDirectory() as root:
            final = pathlib.Path(root) / "checkpoint.cdj"
            with patch("camarodiagconnectors.checkpoint.os.link",
                       side_effect=OSError("filesystem error")):
                with self.assertRaisesRegex(OSError, "filesystem error"):
                    save_checkpoint(final, FIXTURE)
            self.assertFalse(final.exists())
            self.assertEqual([], list(pathlib.Path(root).iterdir()))

    def test_checkpoint_atomic_publish_preserves_unrelated_existing_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            final = pathlib.Path(root) / "checkpoint.cdj"
            final.write_bytes(b"already published")
            with self.assertRaises(FileExistsError):
                save_checkpoint(final, FIXTURE)
            self.assertEqual(b"already published", final.read_bytes())
            self.assertEqual([final], list(pathlib.Path(root).iterdir()))

    def test_checkpoint_stage_sequence_matches_native_firmware_contract(self):
        def record(stage, sequence):
            encoded = bytearray(FIXTURE)
            encoded[4] = stage
            encoded[8:12] = sequence.to_bytes(4, "little")
            encoded[60:64] = (zlib.crc32(encoded[:60]) & 0xffffffff).to_bytes(4, "little")
            return bytes(encoded)
        for stage, sequence in ((1, 0), (2, 1), (8, 7), (9, 1), (10, 2), (10, 7)):
            with self.subTest(stage=stage, sequence=sequence):
                self.assertEqual(record(stage, sequence), validate(record(stage, sequence)))
        for stage, sequence in ((0, 0), (1, 1), (2, 0), (8, 8), (9, 0),
                                (10, 1), (10, 8), (11, 10)):
            with self.subTest(stage=stage, sequence=sequence):
                with self.assertRaises(ValueError):
                    validate(record(stage, sequence))
    def test_serial_read_timeout_is_bounded_and_restored(self):
        class SerialPort(Port):
            def __init__(self, response):
                super().__init__(response)
                self.timeout = None
                self.read_timeouts = []
            def read(self, count):
                self.read_timeouts.append(self.timeout)
                return super().read(count)

        for reply in (b"CDJ/1 NONE\n", b"CDJ/1 CORRUPT\n"):
            with self.subTest(reply=reply):
                port = SerialPort(reply)
                try:
                    retrieve_checkpoint(port)
                except OSError:
                    pass
                self.assertEqual([0.1] * len(port.read_timeouts), port.read_timeouts)
                self.assertIsNone(port.timeout)

    def test_serial_timeout_restored_when_write_fails(self):
        class ShortWritePort(Port):
            timeout = None
            def write(self, data):
                return 0
        port = ShortWritePort(b"")
        with self.assertRaisesRegex(OSError, "Short checkpoint"):
            retrieve_checkpoint(port)
        self.assertIsNone(port.timeout)

    def test_short_serial_read_timeout_can_recover_before_deadline(self):
        class SlowPort(Port):
            timeout = None
            write_timeout = None
            empty_reads = 2
            def read(self, count):
                if self.empty_reads:
                    self.empty_reads -= 1
                    return b""
                return super().read(count)

        port = SlowPort(b"CDJ/1 NONE\n")
        self.assertIsNone(retrieve_checkpoint(port))
        self.assertEqual([b"CDJ/1 READ\n"], port.writes)
        self.assertIsNone(port.timeout)
        self.assertIsNone(port.write_timeout)

    def test_write_timeout_is_restored_after_failure(self):
        class ShortWritePort(Port):
            timeout = None
            write_timeout = None
            def write(self, data):
                self.observed_write_timeout = self.write_timeout
                return 0

        port = ShortWritePort(b"")
        with self.assertRaisesRegex(OSError, "Short checkpoint"):
            retrieve_checkpoint(port)
        self.assertEqual(0.1, port.observed_write_timeout)
        self.assertIsNone(port.write_timeout)

    def test_deadline(self):
        ticks = iter([0.0, 3.0])
        with self.assertRaises(TimeoutError):
            retrieve_checkpoint(Port(b"CDJ/1 NONE\n"), clock=lambda: next(ticks))
