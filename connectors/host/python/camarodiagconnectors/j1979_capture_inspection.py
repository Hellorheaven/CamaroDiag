"""Independent replay of CJ/1 captures against exported Python/Windows/Android reports.

The capture is unsigned. This checks consistency, never authenticates hardware,
a credential exchange, the vehicle or the author of the JSON.
"""
from __future__ import annotations

import hashlib
import io
import json
import math
from pathlib import Path
import re
import stat

from .j1979_campaign_evidence import decode, vin_match

MAX_CAPTURE = 2048 * (8 + 4110 + 4)
MAX_JSON = 32 * 1024 * 1024
PYTHON_SCHEMA = "camarodiag-functional-j1979-embedded-evidence-v1"
NATIVE_SCHEMA = "camarodiag-functional-j1979-native-usb-v1"
# Both Android and Windows use the same native export schema.
WINDOWS_SCHEMA = NATIVE_SCHEMA
PROFILE_FIELDS = ("request_can_id", "response_can_id", "extended", "budget_ms")
STEP_FIELDS = ("sequence", "request_hex", "response_hex", "status", "duration_ms",
               "dtc_codes", "negative_response_code", "measurement", "monitor_records",
               "vehicle_info", "readiness", "supported_page", "freeze_trigger")


def _snapshot(path, limit):
    """Bound input and reject nonregular files and unstable snapshots."""
    path = Path(path)
    if not stat.S_ISREG(path.stat().st_mode):
        raise ValueError("Evidence input must be a regular file")
    with path.open("rb") as stream:
        import os
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise ValueError("Evidence input exceeds bounds")
        data = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
        stream.seek(0)
        second = stream.read(limit + 1)
        final = os.fstat(stream.fileno())
    def identity(info):
        return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    if (len(data) > limit or len(data) != before.st_size or data != second or
            not identity(before) == identity(after) == identity(final) == identity(path.stat())):
        raise ValueError("Evidence input changed during acquisition")
    return data


def _pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError("Duplicate JSON key: " + key)
        result[key] = value
    return result


def _nonfinite(_value):
    raise ValueError("Nonfinite JSON number")


def _load_report(raw):
    try:
        value = json.loads(raw, object_pairs_hook=_pairs, parse_constant=_nonfinite)
    except (UnicodeError, RecursionError) as exc:
        raise ValueError("Invalid JSON report") from exc
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON report object")
    return value


def _same(actual, expected):
    """Do not accept bool as an integer or coerce textual data."""
    if type(expected) is float:
        try:
            return (type(actual) in (int, float) and math.isfinite(actual)
                    and math.isclose(actual, expected, rel_tol=1e-12, abs_tol=1e-12))
        except OverflowError:
            return False
    if type(actual) is not type(expected):
        return False
    if isinstance(expected, dict):
        return actual.keys() == expected.keys() and all(
            _same(actual[k], value) for k, value in expected.items())
    if isinstance(expected, list):
        return len(actual) == len(expected) and all(
            _same(a, b) for a, b in zip(actual, expected))
    return actual == expected


def _valid_vin(value):
    return isinstance(value, str) and re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", value) is not None


def verify(capture, report, *, expected_vin=None):
    """Recompute all application evidence from the captured bytes.

    A negative or cancelled host report cannot become a successful inspection,
    even if its captured bytes alone form a complete sequence.
    """
    if expected_vin is not None and not _valid_vin(expected_vin):
        raise ValueError("Expected VIN must contain 17 valid characters")
    if not isinstance(capture, bytes) or len(capture) > MAX_CAPTURE:
        raise ValueError("Invalid or oversized CJ/1 capture")
    if not isinstance(report, dict) or report.get("schema") not in (PYTHON_SCHEMA, WINDOWS_SCHEMA):
        raise ValueError("Unsupported CJ/1 JSON report schema")
    replay = decode(io.BytesIO(capture))
    differences = []

    def compare(actual, key, expected, label=None):
        if key not in actual or not _same(actual[key], expected):
            differences.append(label or key)

    compare(report, "protocol", "SAE-J1979")
    for field in PROFILE_FIELDS:
        if field in replay:
            compare(report, field, replay[field])
        elif report.get(field) is not None:
            differences.append(field)
    for flag in ("physical_vehicle_verified", "evidence_authenticated",
                 "hardware_provenance_verified"):
        if flag in report and report[flag] is not False:
            differences.append(flag)
    compare(report, "physical_vehicle_verified", False)
    compare(report, "observed_vin", replay["observed_vin"])
    if report["schema"] == NATIVE_SCHEMA and report.get("capture_integrity_verified") is True:
        # A complete native capture contains start + observations + summary.
        compare(report, "records", len(replay["steps"]) + 2)
    steps = report.get("steps")
    if not isinstance(steps, list) or len(steps) != len(replay["steps"]):
        differences.append("steps.length")
    else:
        for index, (actual, wanted) in enumerate(zip(steps, replay["steps"])):
            if not isinstance(actual, dict):
                differences.append(f"steps[{index}]")
                continue
            for field in STEP_FIELDS:
                compare(actual, field, wanted[field], f"steps[{index}].{field}")
    compare(report, "service_observations", replay["service_observations"])
    # Older v1 reports did not provide this derived view; if present it must
    # exactly match the captured response, not a producer-supplied claim.
    from .j1979_multi_signal import historical_projection
    catalog = "NOT_REPORTED"
    if "multi_signal_records" in report:
        if _same(report["multi_signal_records"], replay["multi_signal_records"]):
            catalog = "CURRENT"
        elif _same(report["multi_signal_records"], historical_projection(replay["multi_signal_records"])):
            catalog = "HISTORICAL_49"
        else:
            differences.append("multi_signal_records")
    if "status_records" in report:
        compare(report, "status_records", replay["status_records"])
    if "contextual_measurements" in report:
        compare(report, "contextual_measurements", replay["contextual_measurements"])
    if "scaled_monitor_records" in report:
        compare(report, "scaled_monitor_records", replay["scaled_monitor_records"])
    if "extended_vehicle_info" in report:
        compare(report, "extended_vehicle_info", replay["extended_vehicle_info"])
    wanted_summary = replay["summary"]
    actual_summary = report.get("summary")
    if wanted_summary is None:
        compare(report, "summary", None)
    elif not isinstance(actual_summary, dict):
        differences.append("summary")
    else:
        for field, wanted in wanted_summary.items():
            # Windows exports the same flags and step count, with counters
            # independently recoverable from service_observations.
            if field == "counts" and report["schema"] == WINDOWS_SCHEMA and field not in actual_summary:
                continue
            compare(actual_summary, field, wanted, "summary." + field)

    integrity = report.get("capture_integrity_verified")
    cancelled = report.get("cancelled", False)
    if type(integrity) is not bool or type(cancelled) is not bool:
        differences.append("host_status")
    if integrity is True and not replay["capture_integrity_verified"]:
        differences.append("capture_integrity_verified")
    if cancelled is True and integrity is True:
        differences.append("cancelled")
    if integrity is True and report.get("error") is not None:
        differences.append("error")
    source = report.get("source")
    if source not in ("OFFLINE_CAPTURE", "LIVE_USB_CU1"):
        differences.append("source")
    live = source == "LIVE_USB_CU1"
    credential = report.get("device_credential_authenticated")
    if live and type(credential) is not bool:
        differences.append("device_credential_authenticated")
    if live and integrity is True and credential is not True:
        differences.append("device_credential_authenticated")
    if report["schema"] == WINDOWS_SCHEMA:
        if not live:
            differences.append("source")
        if integrity is True and report.get("frame_integrity_verified") is not True:
            differences.append("frame_integrity_verified")
    declared = report.get("declared_vin")
    if declared is not None and not _valid_vin(declared):
        differences.append("declared_vin")
    else:
        # This reproduces the host's claimed status; authentication itself is
        # explicitly not independently established from unsigned CJ/1 bytes.
        host_view = dict(replay, capture_integrity_verified=integrity is True and
                         replay["capture_integrity_verified"], cancelled=cancelled,
                         device_credential_authenticated=credential)
        compare(report, "vin_match", vin_match(host_view, declared, require_device_auth=live))
    if expected_vin is not None and declared is not None and declared != expected_vin:
        differences.append("expected_vin")
    independent_vin = vin_match(replay, expected_vin if expected_vin is not None else
                                declared if _valid_vin(declared) else None)
    consistent = not differences
    complete = (consistent and integrity is True and not cancelled
                and replay["capture_integrity_verified"] and wanted_summary is not None
                and wanted_summary["end"] == "COMPLETE"
                and independent_vin in ("MATCH", "NOT_REQUESTED"))
    return {
        "schema": "camarodiag-j1979-capture-inspection-v1",
        "verified": complete,
        "report_consistent": consistent,
        "differences": differences,
        "source_schema": report["schema"],
        "host_platform": ("ANDROID" if "app_version" in report else "WINDOWS")
                         if report["schema"] == NATIVE_SCHEMA else "PYTHON",
        "capture_sha256": hashlib.sha256(capture).hexdigest(),
        "capture_bytes": len(capture),
        "capture_integrity_verified": replay["capture_integrity_verified"],
        "host_capture_integrity_claim": integrity,
        "host_cancelled": cancelled,
        "declared_vin": declared,
        "expected_vin": expected_vin,
        "observed_vin": replay["observed_vin"],
        "vin_match": independent_vin,
        "profile": {key: replay.get(key) for key in PROFILE_FIELDS},
        "summary": wanted_summary,
        "verified_prefix_steps": len(replay["steps"]),
        "service_observations": replay["service_observations"],
        "extended_vehicle_info": replay["extended_vehicle_info"],
        "multi_signal_records": replay["multi_signal_records"],
        "multi_signal_report_catalog": catalog,
        "status_records": replay["status_records"],
        "contextual_measurements": replay["contextual_measurements"],
        "scaled_monitor_records": replay["scaled_monitor_records"],
        "capture_error": replay["error"],
        "host_error": report.get("error"),
        "device_credential_independently_verified": False,
        "evidence_authenticated": False,
        "physical_vehicle_verified": False,
        "write_authorized": False,
    }


def verify_files(capture_path, report_path, *, expected_vin=None):
    if Path(capture_path).resolve() == Path(report_path).resolve():
        raise ValueError("Capture and report must be separate files")
    capture = _snapshot(capture_path, MAX_CAPTURE)
    raw = _snapshot(report_path, MAX_JSON)
    result = verify(capture, _load_report(raw), expected_vin=expected_vin)
    if (_snapshot(capture_path, MAX_CAPTURE) != capture or
            _snapshot(report_path, MAX_JSON) != raw):
        raise ValueError("Evidence changed during verification")
    result["report_sha256"] = hashlib.sha256(raw).hexdigest()
    return result
