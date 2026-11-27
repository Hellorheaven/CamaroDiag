"""Offline independent replay of a selected SAE J1979 freeze-frame campaign.

The replay proves internal consistency of recorded application responses and
the plan; it does not authenticate a CAN capture or establish vehicle state.
"""
from __future__ import annotations

from datetime import datetime
from pathlib import Path
import re

from .j1979 import physical_pair
from .j1979_freeze import FreezePlan, Read, MAX_STEPS, MAX_RESPONSE, execute_plan
from .recovery_bundle import _regular, _json
from .recovery_history import _compare_history_value

SCHEMA = "camarodiag-functional-j1979-freeze-v1"
MAX_REPORT = 64 * 1024 * 1024


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _object(value, keys, name):
    _require(isinstance(value, dict) and set(value) == set(keys), f"Invalid {name} fields")
    return value


def _int(value, lower, upper, label):
    _require(type(value) is int and lower <= value <= upper, f"Invalid {label}")
    return value


def _hex(value, bound):
    _require(isinstance(value, str) and len(value) <= bound * 2 and
             re.fullmatch(r"(?:[0-9A-F]{2})*", value) is not None,
             "Noncanonical hexadecimal evidence")
    return bytes.fromhex(value)


def _can_id(value):
    _require(isinstance(value, str) and re.fullmatch(r"[0-9A-F]{1,8}", value) is not None,
             "Invalid CAN ID evidence")
    return int(value, 16)


def _timestamp(value):
    _require(isinstance(value, str) and len(value) <= 64, "Invalid timestamp")
    try:
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("Invalid timestamp") from error
    _require(timestamp.tzinfo is not None and timestamp.utcoffset() is not None,
             "Timestamp lacks a time-zone offset")
    return timestamp


def _plan(value):
    root = _object(value, ("expected_vin", "frames", "selected_pids", "budget_ms"), "freeze plan")
    _require(isinstance(root["frames"], list) and isinstance(root["selected_pids"], list),
             "Invalid explicit frame or PID selection")
    return FreezePlan(root["expected_vin"], tuple(root["frames"]),
                      tuple(root["selected_pids"]), root["budget_ms"]).validate()


def replay_result(result, *, expected_vin=None):
    _require(isinstance(result, dict), "Missing freeze-frame result")
    plan = _plan(result.get("plan"))
    if expected_vin is not None:
        _require(plan.vin == expected_vin, "Independently supplied VIN differs from plan")
    steps = result.get("steps")
    _require(isinstance(steps, list) and 1 <= len(steps) <= MAX_STEPS,
             "Missing or excessive read observations")
    position = 0

    def observe(request):
        nonlocal position
        _require(position < len(steps), "Missing planned freeze-frame exchange")
        saved = _object(steps[position], ("request_hex", "response_hex", "transport_outcome",
            "duration_ms", "transmission_attempted", "status", "detail", "evidence"), "read observation")
        position += 1
        _require(_hex(saved["request_hex"], 3) == request, "Unexpected freeze-frame request or order")
        response = None if saved["response_hex"] is None else _hex(saved["response_hex"], MAX_RESPONSE)
        return Read(saved["transport_outcome"], response, saved["duration_ms"],
                    saved["transmission_attempted"]).validate()

    rebuilt = execute_plan(plan, observe)
    _require(position == len(steps), "Unexpected extra freeze-frame exchanges")
    duration = _int(result.get("duration_ms"), 0, 3600000, "campaign duration")
    # The reported per-step times are not independently measured. Reject
    # impossible aggregates while allowing independent rounding of each read.
    elapsed = sum(row["duration_ms"] for row in rebuilt["steps"])
    _require(elapsed <= duration + len(steps), "Read durations exceed recorded campaign duration")
    rebuilt["duration_ms"] = duration
    _compare_history_value(result, rebuilt, "freeze")
    return rebuilt


def inspect(report, *, expected_vin=None):
    root = _object(report, ("schema", "protocol", "transport", "version", "interface",
        "tx_can_id", "rx_can_id", "physical_channel_confirmed_by_operator",
        "started_at_utc", "finished_at_utc", "transport_problem",
        "execution_completed", "result"), "freeze-frame report")
    _require(root["schema"] == SCHEMA and root["protocol"] == "SAE-J1979" and
             root["transport"] == "LINUX_SOCKETCAN_ISOTP", "Unsupported freeze-frame evidence schema or transport")
    _require(isinstance(root["version"], str) and 0 < len(root["version"]) <= 128,
             "Missing host version")
    _require(isinstance(root["interface"], str) and
             re.fullmatch(r"[A-Za-z0-9_.-]{1,15}", root["interface"]) is not None,
             "Invalid recorded SocketCAN interface")
    tx, rx = _can_id(root["tx_can_id"]), _can_id(root["rx_can_id"])
    _require(physical_pair(tx, rx), "Invalid recorded physical channel")
    _require(root["physical_channel_confirmed_by_operator"] is True,
             "Missing declared channel selection")
    # Wall clock timestamps can move due to clock adjustment; these are
    # validated as metadata, not interpreted as monotonic bus timing.
    _timestamp(root["started_at_utc"])
    _timestamp(root["finished_at_utc"])
    _require(root["transport_problem"] in (None, "TRANSPORT_ERROR", "CANCELLED"),
             "Unknown transport problem")
    result = replay_result(root["result"], expected_vin=expected_vin)
    complete = result["completed"] and root["transport_problem"] is None
    _require(type(root["execution_completed"]) is bool and root["execution_completed"] == complete,
             "Recorded execution completion differs")
    return {
        "schema": "camarodiag-j1979-freeze-inspection-v1",
        "consistent": True,
        "execution_completed": complete,
        "all_frames_stable": complete and result["all_frames_stable"],
        "source_transport": root["transport"],
        "tx_can_id": root["tx_can_id"], "rx_can_id": root["rx_can_id"],
        "expected_vin_independently_supplied": expected_vin is not None,
        "transport_problem": root["transport_problem"],
        "replayed": result,
        "authenticated": False,
        "physical_vehicle_verified": False,
        "whole_vehicle_coverage_verified": False,
        "timing_independently_verified": False,
        "permits_programming": False,
    }


def inspect_file(path, *, expected_vin=None):
    source = Path(path)
    raw, stamp = _regular(source, MAX_REPORT)
    report = _json(raw)
    result = inspect(report, expected_vin=expected_vin)
    again, final_stamp = _regular(source, MAX_REPORT)
    _require(raw == again and stamp == final_stamp, "Freeze-frame report changed during replay")
    return {**result, "source_sha256": stamp.digest}
