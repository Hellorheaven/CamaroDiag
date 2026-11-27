"""Independent cross-host verification of a saved CF/1 file and its JSON.

A CRC-valid, consistent capture is not authenticated evidence of vehicle
state. Live credential status in a host report is an unverified declaration.
"""
from __future__ import annotations

import io
from pathlib import Path
import re

from .j1979_freeze_native import decode, MAX_CAPTURE
from .j1979_freeze_inspection import (
    MAX_REPORT, replay_result, _require, _timestamp, _int,
)
from .recovery_bundle import _regular, _json
from .recovery_history import _compare_history_value

SCHEMA = "camarodiag-functional-j1979-native-freeze-v1"
_CORE = {
    "schema", "protocol", "source", "profile", "capture_integrity_verified",
    "device_credential_authenticated", "hardware_provenance_verified",
    "evidence_authenticated", "physical_vehicle_verified", "permits_programming",
    "observations_received", "summary", "result", "error",
}
_EXTRA = {
    "client_proof_sent", "cancelled", "host_elapsed_ms", "capture_sha256",
    "raw_capture_name", "version", "declared_connector", "declared_ecu",
    "connection_generation", "started_at_utc", "finished_at_utc",
    "frame_integrity_verified", "execution_completed",
}


def _digest(value):
    _require(isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{64}", value),
             "Invalid declared CF/1 digest")
    return value.lower()


def verify(capture, report, *, expected_vin=None, capture_sha256):
    _require(isinstance(report, dict) and _CORE <= set(report) <= _CORE | _EXTRA,
             "Unsupported native freeze report fields")
    _require(report["schema"] == SCHEMA and report["protocol"] == "SAE-J1979" and
             report["source"] in ("OFFLINE_CF1", "LIVE_USB_CU2"),
             "Unsupported native freeze report")
    for flag in ("hardware_provenance_verified", "evidence_authenticated",
                 "physical_vehicle_verified", "permits_programming"):
        _require(report[flag] is False, "Unsupported provenance assertion: " + flag)
    for flag in ("capture_integrity_verified", "device_credential_authenticated"):
        _require(type(report[flag]) is bool, "Invalid flag: " + flag)
    for flag in ("client_proof_sent", "cancelled", "frame_integrity_verified",
                 "execution_completed"):
        if flag in report:
            _require(type(report[flag]) is bool, "Invalid flag: " + flag)
    for field in ("started_at_utc", "finished_at_utc"):
        if field in report:
            _timestamp(report[field])
    for field in ("version", "declared_connector", "declared_ecu", "raw_capture_name"):
        if field in report and report[field] is not None:
            _require(isinstance(report[field], str) and 0 < len(report[field]) <= 1024,
                     "Invalid native metadata: " + field)
    for field in ("host_elapsed_ms", "connection_generation"):
        if field in report:
            _int(report[field], 0, 2**63-1, field)
    _require(report["error"] is None or isinstance(report["error"], str) and
             0 < len(report["error"]) <= 8192, "Invalid report error")
    live = report["source"] == "LIVE_USB_CU2"
    authenticated = report["device_credential_authenticated"]
    sent = report.get("client_proof_sent", False)
    cancelled = report.get("cancelled", False)
    if live:
        _require("client_proof_sent" in report and "cancelled" in report,
                 "Missing live session declaration")
        _require(not sent or authenticated, "Client proof without server credential")
    else:
        _require(not authenticated and not sent and not cancelled,
                 "Offline report cannot claim live authentication or cancellation")

    decoded = decode(io.BytesIO(capture))
    _require(decoded["profile"] is not None, "CF/1 has no complete plan")
    _compare_history_value(report["profile"], decoded["profile"], "native.profile")
    if expected_vin is not None:
        _require(decoded["profile"]["plan"]["expected_vin"] == expected_vin,
                 "Independently supplied VIN differs from CF/1 plan")
    _int(report["observations_received"], 0, 10000, "observation count")
    _require(report["observations_received"] == decoded["observations_received"],
             "Native observation count differs")
    valid = decoded["capture_integrity_verified"]
    claimed = report["capture_integrity_verified"]
    _require(not claimed or valid, "Host claims integrity of malformed CF/1")
    _require(not claimed or report["error"] is None and not cancelled,
             "Successful capture carries cancellation or error")
    if live:
        _require(not claimed or authenticated and sent,
                 "Successful live capture lacks declared credential exchange")
    else:
        _require(claimed == valid, "Offline integrity differs from independent replay")
    if "frame_integrity_verified" in report:
        _require(not report["frame_integrity_verified"] or valid,
                 "Host claims verified frames despite malformed capture")
    digest_present = report.get("capture_sha256") is not None
    if digest_present:
        _require(_digest(report["capture_sha256"]) == _digest(capture_sha256),
                 "CF/1 digest differs from report")
    if report["summary"] is not None:
        _compare_history_value(report["summary"], decoded["summary"], "native.summary")
    if report["result"] is not None:
        rebuilt = replay_result(report["result"], expected_vin=expected_vin)
        _require(valid, "Host presents a complete result for malformed CF/1")
        _compare_history_value(rebuilt, decoded["result"], "native.result")
    if claimed:
        _require(report["summary"] is not None and report["result"] is not None,
                 "Verified native capture lacks replay result")
    complete = claimed and valid and not cancelled and bool(decoded["summary"]["completed"])
    if "execution_completed" in report:
        _require(report["execution_completed"] == complete, "Execution completion differs")
    return {
        "schema": "camarodiag-j1979-native-freeze-inspection-v1",
        "consistent": True,
        "verified": claimed and valid,
        "execution_completed": complete,
        "all_frames_stable": complete and decoded["summary"]["all_frames_stable"],
        "capture_sha256": capture_sha256,
        "host_digest_matched": digest_present,
        "declared_live_credential_authenticated": live and authenticated,
        "credential_independently_verified": False,
        "hardware_provenance_verified": False,
        "physical_vehicle_verified": False,
        "permits_programming": False,
        "expected_vin_independently_supplied": expected_vin is not None,
        "replayed": decoded,
    }


def verify_files(capture, report, *, expected_vin=None):
    capture, report = Path(capture), Path(report)
    _require(capture.resolve() != report.resolve(), "Evidence files must differ")
    raw, capture_stamp = _regular(capture, MAX_CAPTURE)
    report_raw, report_stamp = _regular(report, MAX_REPORT)
    result = verify(raw, _json(report_raw), expected_vin=expected_vin,
                    capture_sha256=capture_stamp.digest)
    raw_after, capture_after = _regular(capture, MAX_CAPTURE)
    report_after, report_stamp_after = _regular(report, MAX_REPORT)
    _require(raw_after == raw and capture_after == capture_stamp and
             report_after == report_raw and report_stamp_after == report_stamp,
             "Native evidence changed during independent replay")
    return {**result, "report_sha256": report_stamp.digest}
