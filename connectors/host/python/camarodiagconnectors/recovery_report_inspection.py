"""Independently match a recovery assessment export to its original evidence.

Only positive, independently reproducible backup assessments can match.
An export is a declaration, never proof of ECU identity or write permission.
"""
from pathlib import Path

from .recovery_bundle import AndroidIdentity, inspect_bundle, _regular, _json

_MAX_REPORT = 8 * 1024 * 1024


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _object(value, label):
    _require(isinstance(value, dict), f"Expected object: {label}")
    return value


def _equal(actual, expected, label):
    _require(type(actual) is type(expected) and actual == expected,
             f"Recovery report differs: {label}")


def _digest(value, label):
    _require(isinstance(value, str) and len(value) == 64
             and all(c in "0123456789abcdefABCDEF" for c in value),
             f"Invalid SHA-256: {label}")
    return value.lower()


def _hash_equal(actual, expected, label):
    _equal(_digest(actual, label), _digest(expected, label), label)


def _flags(value):
    for name in ("physicalVehicleVerified", "restoreAuthorized",
                 "physicalEcuConnected", "authenticated", "physicalRestoreQualified"):
        if name in value:
            _equal(value[name], False, name)
    _equal(value.get("physicalVehicleVerified"), False, "physicalVehicleVerified")
    _equal(value.get("restoreAuthorized"), False, "restoreAuthorized")


def _sectors(rows, fresh, android):
    _require(isinstance(rows, list), "Missing recovery sectors")
    _equal(len(rows), len(fresh), "sector count")
    by_address = {(x["region"], x["address"]): x for x in fresh}
    for entry in rows:
        row = _object(entry, "sector")
        address = row.get("address")
        _require(type(address) is int and address >= 0, "Invalid sector address")
        region = row.get("region") if android else "E39_MAIN_FLASH"
        key = (region, address)
        _require(isinstance(region, str) and key in by_address,
                 "Unexpected or repeated recovery sector")
        actual = by_address.pop(key)
        _equal(row.get("length"), actual["length"], "sector length")
        _hash_equal(row.get("sha256" if android else "originalSha256"),
                    actual["original_sha256"], "sector original bytes")
        _hash_equal(row.get("envelopeSha256"), actual["envelope_sha256"],
                    "sector envelope")
    _require(not by_address, "Missing recovery sectors")


def _android(report, journal, original_bin, expected_vin):
    _require(original_bin is None, "Android recovery has no generic original BIN mapping")
    _equal(report.get("verified"), True, "verified")
    _equal(report.get("snapshotAlgorithm"), "SHA256_JOURNAL_AND_ENVELOPES_V1",
           "snapshot algorithm")
    _equal(report.get("sourceRevalidatedBeforePublication"), True,
           "source revalidated before publication")
    declared = _object(report.get("identity"), "identity")
    identity = AndroidIdentity(*(declared.get(k) for k in
                                 ("family", "controller", "firmware", "vin"))).validate()
    if expected_vin is not None:
        _equal(identity.vin, expected_vin, "expected VIN")
    result = inspect_bundle(journal, "android", android_identity=identity,
                            expected_snapshot=_digest(report.get("snapshotSha256"), "snapshot"))
    _require(result["verified"], "Independent Android inspection failed: " + str(result["problem"]))
    _hash_equal(report.get("journalSha256"), result["journal_sha256"], "journal")
    _equal(report.get("expectedSectors"), result["expected_sectors"], "expected sectors")
    _equal(report.get("verifiedBytes"), result["verified_bytes"], "verified bytes")
    audit = _object(report.get("journalAudit"), "journal audit")
    actual = result["journal_audit"]
    for exported, source in (
        ("valid", "sequence_valid"), ("records", "records"),
        ("lastStage", "last_stage"), ("baselineSectors", "baseline_sectors"),
        ("verifiedSectors", "verified_sectors"), ("finalReadbackSectors", "final_readback_sectors"),
        ("completionRecorded", "completion_recorded"), ("recoveryDisposition", "recovery_disposition")
    ):
        _equal(audit.get(exported), actual[source], exported)
    _equal(report.get("uncertainAddress"), actual["uncertain_address"], "uncertain address")
    _equal(report.get("uncertainRegion"), actual["uncertain_region"], "uncertain region")
    _equal(report.get("journalDisposition"), actual["recovery_disposition"], "journal disposition")
    _sectors(report.get("sectors"), result["sectors"], True)
    return result


def _windows(report, journal, original_bin, expected_vin):
    target = _object(report.get("target"), "target")
    backup = _object(report.get("backup"), "backup")
    audit = _object(report.get("journal"), "journal")
    _equal(target.get("verified"), True, "target verified")
    _equal(backup.get("verified"), True, "backup verified")
    _require(isinstance(target.get("vin"), str), "Missing declared VIN")
    _require(type(target.get("osId")) is int, "Missing declared OSID")
    if expected_vin is not None:
        _equal(target["vin"], expected_vin, "expected VIN")
    image = report.get("originalBin")
    if image is not None:
        image = _object(image, "original BIN comparison")
        _equal(image.get("verified"), True, "original BIN verified")
        _require(original_bin is not None, "Original BIN required to reproduce comparison")
    else:
        _require(original_bin is None and report.get("originalBinName") is None,
                 "Original BIN was not assessed in the imported report")
    result = inspect_bundle(journal, "windows", expected_vin=target["vin"],
                            expected_osid=target["osId"], original_bin=original_bin,
                            expected_snapshot=_digest(backup.get("snapshotSha256"), "snapshot"))
    _require(result["verified"], "Independent Windows inspection failed: " + str(result["problem"]))
    actual = result["identity"]
    _equal(target.get("family"), actual["family"], "ECU family")
    _hash_equal(target.get("metadataSnapshotSha256"), actual["metadata_sha256"], "target metadata")
    _hash_equal(target.get("originalImageSha256"), actual["original_image_sha256"], "original image")
    _hash_equal(audit.get("sha256"), result["journal_sha256"], "journal")
    _equal(backup.get("expectedSectors"), result["expected_sectors"], "expected sectors")
    _equal(backup.get("verifiedBytes"), result["verified_bytes"], "verified bytes")
    state = result["journal_audit"]
    names = {"RECOVERY_REQUIRED": "RecoveryRequired", "COMPLETED": "Completed",
             "INTERRUPTED": "Interrupted"}
    _equal(audit.get("state"), names[state["recovery_disposition"]], "journal state")
    _equal(audit.get("sequenceValid"), state["sequence_valid"], "journal sequence")
    _equal(audit.get("possiblyIncompleteAddress"), state["uncertain_address"], "uncertain address")
    _equal(audit.get("lastPhase"), state["last_stage"], "last phase")
    # Native inspection returns zero counts when a terminal recovery marker
    # is present; host inspection still preserves the pre-marker inventory.
    for field, source in (("preparedSectors", "baseline_sectors"),
                          ("writtenSectors", "verified_sectors"),
                          ("finalReadbackSectors", "final_readback_sectors")):
        expected = state[source] if state["sequence_valid"] else 0
        _equal(audit.get(field), expected, field)
    _sectors(backup.get("sectors"), result["sectors"], False)
    if image is not None:
        compared = result["original_bin"]
        _equal(compared["verified"], True, "independent BIN comparison")
        _equal(image.get("verifiedSectors"), compared["verified_sectors"], "BIN sectors")
        _equal(image.get("verifiedBytes"), compared["verified_bytes"], "BIN bytes")
        _hash_equal(image.get("snapshotSha256"), result["snapshot_sha256"], "BIN snapshot")
        _hash_equal(image.get("metadataSnapshotSha256"), actual["metadata_sha256"], "BIN metadata")
        _hash_equal(image.get("originalImageSha256"), compared["sha256"], "BIN SHA-256")
        _equal(image.get("failedSectorAddress"), None, "failed BIN sector")
    return result


def verify_recovery_report(report_path: Path, journal: Path, *,
                           original_bin: Path | None = None,
                           expected_vin: str | None = None):
    """Read the exported declaration, re-inspect the bundle and compare fields.

    A matching declaration identifies only the supplied local files. File
    names may change when exporting evidence; content identity is mandatory.
    Unsupported/negative declarations do not become verified positive reports.
    """
    raw, stamp = _regular(Path(report_path), _MAX_REPORT)
    report = _object(_json(raw), "recovery report")
    schema = report.get("schema")
    _flags(report)
    if schema == "camarodiag-ecu-recovery-inspection-v1":
        result = _android(report, journal, original_bin, expected_vin)
    elif schema == "camarodiag-offline-e39-recovery-assessment-v1":
        result = _windows(report, journal, original_bin, expected_vin)
    else:
        raise ValueError("Unsupported recovery assessment schema")
    # Check that the imported declaration remained stable during inspection.
    again, current = _regular(Path(report_path), _MAX_REPORT)
    _require(current == stamp and raw == again, "Recovery report changed during inspection")
    return {**result, "schema": "camarodiag-offline-recovery-report-verification-v1",
            "source_schema": schema, "report_sha256": stamp.digest, "report_matches": True,
            "expected_vin_independently_supplied": expected_vin is not None,
            "physical_vehicle_verified": False, "restore_authorized": False,
            "authenticated": False}
