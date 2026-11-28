"""Replay Windows desktop recovery history reports using independent evidence.

Only the original bundles establish the comparison. The JSON is an unsigned
declaration; even a complete match cannot authorize a recovery operation.
"""
from datetime import datetime

from .recovery_history import _compare_history_value, _require
from .recovery_bundle import _digest


def silicon_geometry_matches(sectors):
    """MPC5566 main-flash map, not a qualification of the E39 ECU board.

    Mirrors NativeMpc5566FlashMap, based on NXP MPC5566RM rev.2 table
    13-4 and AN3903 table 2. Missing sectors are allowed because the
    transaction can cover a subset of the flash.
    """
    ranges = ((0, 0x4000), (0x4000, 0x10000),
              (0x10000, 0x1c000), (0x1c000, 0x20000),
              (0x20000, 0x30000), (0x30000, 0x40000))
    expected = {start: end - start for start, end in ranges}
    expected.update({start: 0x20000 for start in range(0x40000, 0x300000, 0x20000)})
    addresses = [row["address"] for row in sectors]
    return bool(sectors) and len(set(addresses)) == len(addresses) and all(
        expected.get(row["address"]) == row["length"] for row in sectors)


_STATES = {
    "INTERRUPTED": "Interrupted",
    "RECOVERY_REQUIRED": "RecoveryRequired",
    "COMPLETED": "Completed",
}


def _state(disposition):
    _require(disposition in _STATES, "Unknown Windows journal disposition")
    return _STATES[disposition]


def _hashes(value):
    """Normalize case only for actual SHA-256 properties, not other strings."""
    keys = {"sha256", "snapshotSha256", "originalSha256", "envelopeSha256",
            "originalImageSha256", "metadataSnapshotSha256"}
    if isinstance(value, dict):
        return {key: _digest(item) if key in keys else _hashes(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [_hashes(item) for item in value]
    return value


def _snapshot(report, size, ordinal):
    audit = report["journal_audit"]
    identity = report["identity"]
    # The native reader intentionally discards counters when a terminal
    # RecoveryRequired marker is present. Do not mistake these zeros for
    # absence of original-sector evidence.
    counts_valid = audit["sequence_valid"]
    return {
        "ordinal": ordinal,
        "journalName": report["journal_name"],
        "journalBytes": size,
        "records": audit["records"],
        "journal": {
            "state": _state(audit["recovery_disposition"]),
            "sequenceValid": counts_valid,
            "preparedSectors": audit["baseline_sectors"] if counts_valid else 0,
            "writtenSectors": audit["verified_sectors"] if counts_valid else 0,
            "finalReadbackSectors": audit["final_readback_sectors"] if counts_valid else 0,
            "possiblyIncompleteAddress": audit["uncertain_address"],
            "lastPhase": audit["last_stage"],
            "sha256": report["journal_sha256"],
        },
        "target": {
            "verified": True,
            "family": identity["family"],
            "vin": identity["vin"],
            "osId": identity["osid"],
            "problem": "",
            "originalImageSha256": identity["original_image_sha256"],
            "metadataSnapshotSha256": identity["metadata_sha256"],
        },
        "backup": {
            "verified": True,
            "expectedSectors": report["expected_sectors"],
            "verifiedBytes": report["verified_bytes"],
            "snapshotSha256": report["snapshot_sha256"],
            "problem": "",
            "sectors": [
                {"address": row["address"], "length": row["length"],
                 "originalSha256": row["original_sha256"],
                 "envelopeSha256": row["envelope_sha256"]}
                for row in report["sectors"]
            ],
            "siliconGeometryMatches": silicon_geometry_matches(report["sectors"]),
        },
    }


def native_projection(history, details):
    """Canonical native shape derived solely from pinned Python evidence."""
    _require(history["verified"] is True and history["platform"] == "WINDOWS",
             "Native projection requires verified Windows history")
    _require(len(details) == len(history["snapshots"]), "Missing pinned bundle details")
    return {
        "schema": "camarodiag-windows-recovery-history-v1",
        "verified": True,
        "orderedPrefixVerified": True,
        "snapshots": [_snapshot(bundle, size, ordinal)
                      for ordinal, (bundle, size) in enumerate(details, 1)],
        "transitions": [
            {"fromOrdinal": row["from_ordinal"], "toOrdinal": row["to_ordinal"],
             "addedBytes": row["added_bytes"], "addedRecords": row["added_records"],
             "originalEnvelopesUnchanged": True,
             "previousState": _state(row["previous_disposition"]),
             "recordedState": _state(row["recorded_disposition"])}
            for row in history["transitions"]
        ],
        "latestRecordedState": _state(history["latest_recorded_disposition"]),
        "latestRecordedUncertainAddress": history["latest_recorded_uncertain_address"],
        "problemCode": None,
        "failedOrdinal": None,
        "authenticated": False,
        "physicalVehicleVerified": False,
        "currentEcuStateVerified": False,
        "physicalRestoreQualified": False,
        "restoreAuthorized": False,
    }


def _portable_native(report, *, imported):
    """File names and inspection timestamps are local presentation metadata."""
    from copy import deepcopy

    result = deepcopy(report)
    if imported:
        stamp = result.pop("inspectedAtUtc", None)
        _require(isinstance(stamp, str) and len(stamp) <= 64,
                 "Missing native history inspection timestamp")
        try:
            parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
        except ValueError as error:
            raise ValueError("Invalid native history inspection timestamp") from error
        _require(parsed.tzinfo is not None and parsed.utcoffset() is not None,
                 "Native history timestamp must have an offset")
    snapshots = result.get("snapshots")
    _require(isinstance(snapshots, list) and 2 <= len(snapshots) <= 8,
             "Invalid native history snapshot list")
    for row in snapshots:
        _require(isinstance(row, dict), "Invalid native snapshot")
        name = row.pop("journalName", None)
        _require(isinstance(name, str) and bool(name) and len(name) <= 4096,
                 "Missing native journal name")
        backup = row.get("backup")
        _require(isinstance(backup, dict), "Missing native backup evidence")
        sectors = backup.get("sectors")
        _require(isinstance(sectors, list) and 0 < len(sectors) <= 4096,
                 "Invalid native sector inventory")
        _require(all(isinstance(sector, dict) and type(sector.get("address")) is int
                     for sector in sectors), "Invalid native sector address")
        backup["sectors"] = sorted(sectors, key=lambda sector: sector["address"])
    return _hashes(result)


def compare_native_history(declared, fresh, details):
    """Verify every native field other than local name and timestamp metadata.

    Unknown properties, wrong numeric types, missing fields and positive
    authorization flags all fail the strict structural comparison.
    """
    _require(declared.get("verified") is True and
             declared.get("orderedPrefixVerified") is True,
             "Only verified native history reports can be reproduced")
    expected = native_projection(fresh, details)
    _compare_history_value(_portable_native(declared, imported=True),
                           _portable_native(expected, imported=False),
                           "windows_history")
