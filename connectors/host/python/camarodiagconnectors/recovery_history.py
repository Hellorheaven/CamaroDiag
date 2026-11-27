"""Offline comparison of explicitly ordered recovery-bundle snapshots.

A prefix match proves a relationship between imported bytes only. It never
establishes that the journal was produced by a real transaction, that the
latest snapshot is current on an ECU, or that restoration is safe.
"""
from pathlib import Path

from .recovery_bundle import (
    AndroidIdentity, MAX_JOURNAL, _regular, inspect_bundle,
)

MAX_SNAPSHOTS = 8
MAX_HISTORY_JOURNAL_BYTES = 64 * 1024 * 1024


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _sector_signature(result):
    return tuple((row["region"], row["address"], row["length"],
                  row["original_sha256"], row["envelope_sha256"])
                 for row in result["sectors"])


def _same_target(first, later):
    # Windows metadata contains the original/target/profile/driver identity;
    # its SHA must also remain stable, not just the VIN and OSID.
    return first["identity"] == later["identity"]


def _summary(report, ordinal):
    audit = report.get("journal_audit") or {}
    return {
        "ordinal": ordinal,
        "journal_name": report["journal_name"],
        "journal_sha256": report["journal_sha256"],
        "snapshot_sha256": report["snapshot_sha256"],
        "verified": report["verified"],
        "problem": report["problem"],
        "identity": report["identity"],
        "expected_sectors": report["expected_sectors"],
        "verified_bytes": report["verified_bytes"],
        "records": audit.get("records"),
        "last_stage": audit.get("last_stage"),
        "sequence_valid": audit.get("sequence_valid"),
        "completion_recorded": audit.get("completion_recorded"),
        "recovery_disposition": audit.get("recovery_disposition"),
        "uncertain_address": audit.get("uncertain_address"),
        "uncertain_region": audit.get("uncertain_region"),
        "written_sectors": audit.get("verified_sectors"),
        "final_readback_sectors": audit.get("final_readback_sectors"),
    }


def inspect_history(journals, platform, *, android_identity: AndroidIdentity | None = None,
                    expected_vin=None, expected_osid=None, require_current=lambda: None,
                    _details=None):
    """Inspect 2–8 local snapshots in explicit oldest-to-newest order.

    Require each complete original-sector inventory to be verified separately.
    Exact journal prefix, metadata and original envelope identity must agree.
    Different transactions, rollbacks and forks are not merged or repaired.
    """
    paths = [Path(p) for p in journals]
    _require(2 <= len(paths) <= MAX_SNAPSHOTS, "Select two to eight recovery snapshots")
    _require(len({p.resolve() for p in paths}) == len(paths),
             "Recovery snapshots must use distinct journal paths")
    _require(platform in ("android", "windows"), "Select explicit recovery platform")
    if platform == "android":
        _require(android_identity is not None and expected_osid is None,
                 "Android requires its declared target identity")
        android_identity.validate()
        if expected_vin is not None:
            _require(android_identity.vin == expected_vin, "Android expected VIN differs")
    else:
        _require(android_identity is None, "Windows identity is read from target metadata")

    result = {
        "schema": "camaradiag-offline-recovery-history-v1",
        "platform": platform.upper(),
        "verified": False,
        "ordered_prefix_verified": False,
        "identity": None,
        "snapshots": [],
        "transitions": [],
        "latest_recorded_disposition": None,
        "latest_recorded_uncertain_address": None,
        "latest_recorded_uncertain_region": None,
        "problem": None,
        "authenticated": False,
        "physical_vehicle_verified": False,
        "physical_restore_qualified": False,
        "restore_authorized": False,
        "current_ecu_state_verified": False,
    }
    reports = []
    raw_snapshots = []
    stamps = []
    seen = set()
    consumed = 0
    try:
        for ordinal, journal in enumerate(paths, 1):
            require_current()
            raw, stamp = _regular(journal, MAX_JOURNAL, require_current)
            consumed += len(raw)
            _require(consumed <= MAX_HISTORY_JOURNAL_BYTES, "Recovery history exceeds journal size bound")
            _require(stamp.identity[:2] not in seen, "Aliased recovery journal input")
            seen.add(stamp.identity[:2])
            inspected = inspect_bundle(journal, platform,
                android_identity=android_identity, expected_vin=expected_vin,
                expected_osid=expected_osid, require_current=require_current)
            result["snapshots"].append(_summary(inspected, ordinal))
            _require(inspected["verified"],
                     f"Snapshot {ordinal} rejected: {inspected['problem']}")
            _require(inspected["journal_sha256"] == stamp.digest,
                     f"Snapshot {ordinal} journal changed during inspection")
            _require(_regular(journal, MAX_JOURNAL, require_current)[1] == stamp,
                     f"Snapshot {ordinal} journal changed after inspection")
            if reports:
                previous = reports[-1]
                _require(_same_target(reports[0], inspected),
                         f"Snapshot {ordinal} declares a different target or metadata")
                _require(_sector_signature(reports[0]) == _sector_signature(inspected),
                         f"Snapshot {ordinal} original sectors or envelopes differ")
                old = raw_snapshots[-1]
                _require(len(raw) > len(old) and raw.startswith(old),
                         f"Snapshot {ordinal} is a duplicate, rollback or fork, not a strict journal extension")
                before = previous["journal_audit"]
                after = inspected["journal_audit"]
                _require(after["records"] > before["records"],
                         f"Snapshot {ordinal} does not add complete journal records")
                _require(not before["completion_recorded"] and before["sequence_valid"],
                         f"Snapshot {ordinal} extends a terminal journal")
                result["transitions"].append({
                    "from_ordinal": ordinal - 1,
                    "to_ordinal": ordinal,
                    "strict_prefix": True,
                    "added_records": after["records"] - before["records"],
                    "added_bytes": len(raw) - len(old),
                    "previous_disposition": before["recovery_disposition"],
                    "recorded_disposition": after["recovery_disposition"],
                    "original_envelopes_unchanged": True,
                })
            reports.append(inspected)
            raw_snapshots.append(raw)
            stamps.append(stamp)
        # Revalidate all bundles before publishing a positive relationship;
        # a matching journal prefix alone cannot detect changed original files.
        for index, (journal, prior, stamp) in enumerate(zip(paths, reports, stamps), 1):
            require_current()
            rechecked = inspect_bundle(journal, platform,
                android_identity=android_identity, expected_vin=expected_vin,
                expected_osid=expected_osid, expected_snapshot=prior["snapshot_sha256"],
                require_current=require_current)
            _require(rechecked["verified"] and _same_target(prior, rechecked) and
                     _sector_signature(prior) == _sector_signature(rechecked),
                     f"Snapshot {index} changed before history publication")
            _require(_regular(journal, MAX_JOURNAL, require_current)[1] == stamp,
                     f"Snapshot {index} journal changed before history publication")
        require_current()
        if _details is not None:
            _details.extend((report, len(raw)) for report, raw in zip(reports, raw_snapshots))
        latest = reports[-1]["journal_audit"]
        result.update(verified=True, ordered_prefix_verified=True, identity=reports[0]["identity"],
                      latest_recorded_disposition=latest["recovery_disposition"],
                      latest_recorded_uncertain_address=latest["uncertain_address"],
                      latest_recorded_uncertain_region=latest.get("uncertain_region"))
    except KeyboardInterrupt:
        result["problem"] = "CANCELLED"
    except (OSError, ValueError, RuntimeError, TypeError, KeyError) as exc:
        result["problem"] = str(exc)
    return result


MAX_HISTORY_REPORT = 16 * 1024 * 1024


def _compare_history_value(declared, actual, field):
    """Compare JSON evidence recursively without bool/int coercion."""
    _require(type(declared) is type(actual), f"History report type differs: {field}")
    if isinstance(actual, dict):
        _require(declared.keys() == actual.keys(), f"History report fields differ: {field}")
        for key in actual:
            _compare_history_value(declared[key], actual[key], f"{field}.{key}")
    elif isinstance(actual, list):
        _require(len(declared) == len(actual), f"History report length differs: {field}")
        for index, (left, right) in enumerate(zip(declared, actual)):
            _compare_history_value(left, right, f"{field}[{index}]")
    else:
        _require(declared == actual, f"History report value differs: {field}")


def _portable_history(report):
    """Local filenames and independently supplied target checks are contextual.

    All recorded target identifiers, journal and envelope fingerprints, state
    transitions and safety flags remain mandatory. A comparison made using an
    independently supplied VIN/OSID is recorded separately in the verification.
    """
    from copy import deepcopy
    projected = deepcopy(report)
    identities = [projected.get("identity")]
    snapshots = projected.get("snapshots")
    _require(isinstance(snapshots, list), "Missing history snapshots")
    for snapshot in snapshots:
        _require(isinstance(snapshot, dict), "Invalid history snapshot")
        name = snapshot.pop("journal_name", None)
        _require(isinstance(name, str) and bool(name), "Missing snapshot journal name")
        identities.append(snapshot.get("identity"))
    for identity in identities:
        _require(isinstance(identity, dict), "Missing snapshot target identity")
        if "declared_identity_matches" in identity:
            state = identity.pop("declared_identity_matches")
            _require(state is None or state is True, "Invalid declared target comparison")
    return projected


def verify_history_report(report_path, journals, platform, *,
                          android_identity=None, expected_vin=None,
                          expected_osid=None, require_current=lambda: None):
    """Replay a previously exported history against all original bundles.

    A positive report must be fully reproducible from the explicit ordered
    inputs. Do not reconstruct missing evidence or infer ECU state from any
    declared historical transition. Failed input is never upgraded to success.
    """
    from .recovery_bundle import _json

    path = Path(report_path)
    journals = [Path(journal) for journal in journals]
    _require(path.resolve() not in {p.resolve() for p in journals},
             "History report cannot also be a journal")
    raw, stamp = _regular(path, MAX_HISTORY_REPORT, require_current)
    declared = _json(raw)
    _require(isinstance(declared, dict), "Expected recovery history object")
    schema = declared.get("schema")
    native = schema == "camarodiag-windows-recovery-history-v1"
    _require(native or schema == "camarodiag-offline-recovery-history-v1",
             "Unsupported recovery history report schema")
    if native:
        _require(platform == "windows", "Windows history requires Windows original journals")
    else:
        _require(declared.get("verified") is True and
                 declared.get("ordered_prefix_verified") is True,
                 "Only verified history reports can be reproduced")
        _require(declared.get("platform") == platform.upper(),
                 "History report platform differs from supplied platform")
    details = []
    fresh = inspect_history(journals, platform, android_identity=android_identity,
                            expected_vin=expected_vin, expected_osid=expected_osid,
                            require_current=require_current, _details=details if native else None)
    _require(fresh["verified"] and fresh["ordered_prefix_verified"],
             "Independent history replay failed: " + str(fresh["problem"]))
    if native:
        from .recovery_windows_history import compare_native_history
        compare_native_history(declared, fresh, details)
    else:
        _compare_history_value(_portable_history(declared), _portable_history(fresh), "history")
    again, final_stamp = _regular(path, MAX_HISTORY_REPORT, require_current)
    _require(stamp == final_stamp and raw == again, "History report changed during replay")
    require_current()
    return {
        **fresh,
        "schema": "camarodiag-offline-recovery-history-report-verification-v1",
        "source_schema": declared["schema"],
        "report_matches": True,
        "report_sha256": stamp.digest,
        "expected_vin_independently_supplied": expected_vin is not None,
        "expected_osid_independently_supplied": expected_osid is not None,
    }
