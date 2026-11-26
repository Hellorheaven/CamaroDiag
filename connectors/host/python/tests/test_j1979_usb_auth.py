import hashlib
import hmac
import unittest

from camarodiagconnectors.j1979_usb_auth import hello, proof


class UsbAuthTests(unittest.TestCase):
    def setUp(self):
        self.key = bytes(range(32))
        self.request = hello(0x7E0, 0x7E8, False, 10000, nonce=b"H" * 32)
        self.transcript = self.request[4:] + b"D" * 32
        self.challenge = b"CU\x01\x02" + b"D" * 32 + hmac.digest(
            self.key, b"S" + self.transcript, hashlib.sha256)

    def test_mutual_proof(self):
        self.assertEqual(len(self.request), 49)
        self.assertEqual(proof(self.request, self.challenge, self.key),
            b"CU\x01\x03" + hmac.digest(self.key, b"C" + self.transcript, hashlib.sha256))

    def test_wrong_key_profile_and_nonce(self):
        other = hello(0x7E1, 0x7E9, False, 10000, nonce=b"H" * 32)
        changed = hello(0x7E0, 0x7E8, False, 10000, nonce=b"X" * 32)
        for request, key in ((other, self.key), (changed, self.key), (self.request, b"x" * 32)):
            with self.assertRaises(ValueError):
                proof(request, self.challenge, key)

    def test_truncation_and_invalid_profile(self):
        for challenge in (self.challenge[:-1], self.challenge + b"x",
                          self.challenge[:4] + bytes(32) + self.challenge[36:]):
            with self.assertRaises(ValueError):
                proof(self.request, challenge, self.key)
        with self.assertRaises(ValueError):
            hello(0x7E0, 0x7E9, False, 10000)
        with self.assertRaises(ValueError):
            hello(0x7E0, 0x7E8, False, 10000, nonce=bytes(32))
