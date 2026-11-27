import base64
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from camarodiagconnectors.recovery_bundle import AndroidIdentity,inspect_bundle,snapshot_digest,E39_IMAGE_BYTES
from camarodiagconnectors.offline_cli import run

VIN="1G1FA1RXXR0123456"
IDENTITY=AndroidIdentity("E39","ECM","OS-A",VIN)

def sha(data):return hashlib.sha256(data).hexdigest()
def encoded(s):return base64.urlsafe_b64encode(s.encode()).decode().rstrip("=")
def utf(value):
    raw=value.encode("utf-16-be")
    result=bytearray()
    for offset in range(0,len(raw),2):
        c=int.from_bytes(raw[offset:offset+2],"big")
        if 0<c<128:result.append(c)
        elif c<2048:result.extend((0xC0|(c>>6),0x80|(c&63)))
        else:result.extend((0xE0|(c>>12),0x80|((c>>6)&63),0x80|(c&63)))
    return struct.pack(">H",len(result))+result

def android_fixture(root,identity=IDENTITY,phases=()):
    journal=root/"journal.tsv"
    blocks=root/"journal.tsv.blocks";blocks.mkdir()
    payload=b"original"
    original=sha(payload);updated=sha(b"changed!")
    header="\t".join(("CAMARODIAG-ECU-JOURNAL-V1",encoded(identity.family),encoded(identity.controller),
                      encoded(identity.firmware),identity.vin))
    tail=sha(header.encode());lines=[header]
    for i,stage in enumerate(("BASELINE_CHECKED",)+tuple(phases),1):
        row="\t".join((str(i),"123",stage,"CALIBRATION","4096",original,updated))
        tail=sha((tail+"\t"+row).encode());lines.append(row+"\t"+tail)
    journal.write_bytes(("\n".join(lines)+"\n").encode())
    data=b"".join(utf(x) for x in ("CAMARODIAG-PHYSICAL-BLOCK-V1",identity.family,identity.controller,
                                   identity.firmware,identity.vin,"CALIBRATION"))
    name="CALIBRATION-1000-8.ecu-block"
    envelope=blocks/name
    envelope.write_bytes(data+struct.pack(">qq",4096,len(payload))+utf(original)+struct.pack(">i",len(payload))+payload)
    return journal,envelope

def windows_fixture(root,phases=(),payload=b"original"):
    journal=root/"journal.jsonl";blocks=root/"journal.jsonl.blocks";blocks.mkdir()
    address=0x1000
    image=bytearray(E39_IMAGE_BYTES);image[address:address+len(payload)]=payload
    original=root/"original.bin";original.write_bytes(image)
    meta={"Schema":1,"Utc":"2026-11-27T12:00:00+00:00","Family":"E39","ExpectedVin":VIN,
          "ExpectedOsId":123,"RegistryView":"Registry64","OriginalSha256":sha(image),
          "TargetSha256":"a"*64,"DriverSha256":"b"*64,"ProfileSha256":"c"*64,"KernelSha256":"d"*64}
    Path(str(journal)+".meta.json").write_text(json.dumps(meta),encoding="utf-8")
    entries=[("Prepared",sha(payload)),("OriginalCrcVerified","1234ABCD"),("OriginalBytesVerified",sha(payload))]
    entries+=list(phases)
    journal.write_text("".join(json.dumps({"Schema":3,"UtcTimestamp":"2026-11-27T12:00:00+00:00",
        "Phase":phase,"Address":0 if phase=="Completed" else address,"Detail":detail})+"\n"
        for phase,detail in entries),encoding="utf-8")
    name=f"{address:08X}-{len(payload)}.ecu-block"
    envelope=blocks/name
    envelope.write_bytes(json.dumps({"Schema":1,"Region":"E39_MAIN_FLASH","Address":address,
        "Length":len(payload),"OriginalSha256":sha(payload)}).encode()+b"\n"+payload)
    return journal,envelope,original

class TestRecoveryBundle(unittest.TestCase):
    def test_android_matches_canonical_snapshot_and_preserves_pending_erase(self):
        with tempfile.TemporaryDirectory() as folder:
            journal,envelope=android_fixture(Path(folder),phases=("BEFORE_ERASE",))
            report=inspect_bundle(journal,"android",android_identity=IDENTITY)
            self.assertTrue(report["verified"],report)
            self.assertEqual("ERASE_OR_WRITE_MAY_BE_INCOMPLETE",report["journal_audit"]["recovery_disposition"])
            self.assertEqual(4096,report["journal_audit"]["uncertain_address"])
            self.assertEqual(8,report["verified_bytes"])
            self.assertEqual(snapshot_digest(sha(journal.read_bytes()),{envelope.name:sha(envelope.read_bytes())}),
                             report["snapshot_sha256"])
            self.assertFalse(report["restore_authorized"])
            self.assertFalse(report["physical_vehicle_verified"])
            matched=inspect_bundle(journal,"android",android_identity=IDENTITY,expected_snapshot=report["snapshot_sha256"])
            self.assertTrue(matched["snapshot_matches"])
            wrong=inspect_bundle(journal,"android",android_identity=IDENTITY,expected_snapshot="f"*64)
            self.assertFalse(wrong["verified"])
            self.assertFalse(wrong["snapshot_matches"])
    def test_modified_utf_identity(self):
        identity=AndroidIdentity("E39","ECM-été-🚗","OS-A",VIN)
        with tempfile.TemporaryDirectory() as folder:
            journal,_=android_fixture(Path(folder),identity)
            report=inspect_bundle(journal,"android",android_identity=identity)
            self.assertTrue(report["verified"],report)
            self.assertEqual(identity.controller,report["identity"]["controller"])
    def test_android_wrong_identity_hash_chain_trailing_data(self):
        for change in ("identity","chain","envelope"):
            with tempfile.TemporaryDirectory() as folder:
                journal,envelope=android_fixture(Path(folder))
                identity=IDENTITY
                if change=="identity":identity=AndroidIdentity("E39","OTHER","OS-A",VIN)
                if change=="chain":journal.write_bytes(journal.read_bytes().replace(b"BASELINE_CHECKED",b"BEFORE_ERASE"))
                if change=="envelope":envelope.write_bytes(envelope.read_bytes()+b"x")
                report=inspect_bundle(journal,"android",android_identity=identity)
                self.assertFalse(report["verified"])
                self.assertFalse(report["restore_authorized"])
    def test_windows_original_image_comparison(self):
        with tempfile.TemporaryDirectory() as folder:
            journal,envelope,image=windows_fixture(Path(folder))
            report=inspect_bundle(journal,"windows",expected_vin=VIN,expected_osid=123,original_bin=image)
            self.assertTrue(report["verified"],report)
            self.assertTrue(report["original_bin"]["verified"])
            self.assertEqual(8,report["original_bin"]["verified_bytes"])
            self.assertTrue(report["identity"]["declared_identity_matches"])
            self.assertEqual(snapshot_digest(sha(journal.read_bytes()),{envelope.name:sha(envelope.read_bytes())}),
                             report["snapshot_sha256"])
            self.assertFalse(report["authenticated"])
    def test_windows_complete_or_recovery_marker_never_authorizes_restore(self):
        phases=(("TestWriteAccepted","ok"),("EraseStarted",""),("EraseAcknowledged",""),("WriteStarted",""),
                ("WrittenCrcVerified","ABCD1234"),("WrittenBytesVerified","x"),("FinalBytesVerified","x"),("Completed",""))
        with tempfile.TemporaryDirectory() as folder:
            journal,_,_=windows_fixture(Path(folder),phases)
            report=inspect_bundle(journal,"windows")
            self.assertTrue(report["verified"],report)
            self.assertTrue(report["journal_audit"]["completion_recorded"])
            self.assertFalse(report["restore_authorized"])
        with tempfile.TemporaryDirectory() as folder:
            journal,_,_=windows_fixture(Path(folder),(("TestWriteAccepted","ok"),("EraseStarted",""),("RecoveryRequired","fault")))
            report=inspect_bundle(journal,"windows")
            self.assertTrue(report["verified"],report)
            self.assertEqual("RECOVERY_REQUIRED",report["journal_audit"]["recovery_disposition"])
            self.assertFalse(report["journal_audit"]["sequence_valid"])
            self.assertFalse(report["restore_authorized"])
    def test_windows_missing_original_receipt_and_wrong_bin_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            journal,_,image=windows_fixture(Path(folder))
            good=journal.read_bytes()
            journal.write_bytes(b"\n".join(good.splitlines()[:2])+b"\n")
            self.assertFalse(inspect_bundle(journal,"windows")["verified"])
            journal.write_bytes(good)
            data=bytearray(image.read_bytes());data[0x1000]^=1;image.write_bytes(data)
            report=inspect_bundle(journal,"windows",original_bin=image)
            self.assertFalse(report["verified"])
            self.assertFalse(report["original_bin"]["verified"])
    def test_duplicate_json_and_unexpected_files_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            journal,envelope,_=windows_fixture(Path(folder))
            good=journal.read_bytes()
            journal.write_bytes(good.replace(b'"Schema": 3',b'"Schema": 3, "Schema": 3',1))
            self.assertFalse(inspect_bundle(journal,"windows")["verified"])
            journal.write_bytes(good)
            (envelope.parent/"extra").write_bytes(b"extra")
            self.assertFalse(inspect_bundle(journal,"windows")["verified"])
    def test_changed_input_invalidates_final_snapshot(self):
        with tempfile.TemporaryDirectory() as folder:
            journal,envelope=android_fixture(Path(folder))
            calls=0
            def active():
                nonlocal calls
                calls+=1
                if calls==9:
                    data=envelope.read_bytes()
                    envelope.write_bytes(data[:-1]+bytes((data[-1]^1,)))
            report=inspect_bundle(journal,"android",android_identity=IDENTITY,require_current=active)
            self.assertFalse(report["verified"])
            self.assertFalse(report["restore_authorized"])
    def test_cli_export_exclusive_and_inputs_unchanged(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            journal,envelope=android_fixture(root)
            initial=journal.read_bytes(),envelope.read_bytes()
            output=root/"inspection.json"
            args=["verify-recovery","--platform","android","--journal",str(journal),"--family","E39",
                "--controller","ECM","--firmware","OS-A","--vin",VIN,"--output",str(output)]
            report=run(args)
            self.assertEqual(report,json.loads(output.read_text()))
            self.assertTrue(report["verified"])
            with self.assertRaises(FileExistsError):run(args)
            self.assertEqual(initial,(journal.read_bytes(),envelope.read_bytes()))
