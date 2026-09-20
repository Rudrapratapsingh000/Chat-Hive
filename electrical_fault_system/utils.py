from __future__ import annotations

import json
import math
import urllib.request
from dataclasses import dataclass
from typing import Any, Iterable, Mapping

import numpy as np
import pandas as pd

FEATURE_COLUMNS = [
    "current",
    "voltage",
    "power",
    "dI_dt",
    "dV_dt",
    "current_ma",
]


@dataclass(slots=True)
class Reading:
    """Single telemetry point received from the ESP8266."""

    current: float
    voltage: float
    timestamp: int

    def to_dict(self) -> dict[str, float | int]:
        return {
            "current": self.current,
            "voltage": self.voltage,
            "timestamp": self.timestamp,
        }


def parse_bool(value: Any, default: bool = False) -> bool:
    """Parse common truthy and falsy values from environment variables."""

    if value is None:
        return default
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "y", "on"}:
        return True
    if text in {"0", "false", "no", "n", "off"}:
        return False
    return default


def validate_reading(payload: Mapping[str, Any]) -> Reading:
    """Validate an incoming JSON payload and convert it into a Reading."""

    required_fields = ("current", "voltage", "timestamp")
    missing = [field for field in required_fields if field not in payload]
    if missing:
        raise ValueError(f"Missing required fields: {', '.join(missing)}")

    try:
        current = float(payload["current"])
        voltage = float(payload["voltage"])
        timestamp = int(payload["timestamp"])
    except (TypeError, ValueError) as exc:
        raise ValueError("Telemetry payload must contain numeric values.") from exc

    if not math.isfinite(current) or not math.isfinite(voltage):
        raise ValueError("Current and voltage must be finite numeric values.")

    return Reading(current=current, voltage=voltage, timestamp=timestamp)


def readings_to_frame(readings: Iterable[Mapping[str, Any] | Reading]) -> pd.DataFrame:
    """Convert a bounded in-memory buffer of readings into a DataFrame."""

    rows: list[dict[str, Any]] = []
    for item in readings:
        if isinstance(item, Reading):
            rows.append(item.to_dict())
        else:
            rows.append(dict(item))

    if not rows:
        return pd.DataFrame(columns=["timestamp", *FEATURE_COLUMNS])

    frame = pd.DataFrame(rows)
    frame = frame.sort_values("timestamp").reset_index(drop=True)
    return engineer_features(frame)


def engineer_features(frame: pd.DataFrame, ma_window: int = 3) -> pd.DataFrame:
    """Create derived features required by the hybrid model."""

    if frame.empty:
        return pd.DataFrame(columns=["timestamp", *FEATURE_COLUMNS])

    features = frame.copy()
    features["current"] = pd.to_numeric(features["current"], errors="coerce")
    features["voltage"] = pd.to_numeric(features["voltage"], errors="coerce")
    features["timestamp"] = pd.to_numeric(features["timestamp"], errors="coerce")
    features = features.dropna(subset=["current", "voltage", "timestamp"]).reset_index(drop=True)

    if features.empty:
        return pd.DataFrame(columns=["timestamp", *FEATURE_COLUMNS])

    features["power"] = features["current"] * features["voltage"]

    delta_t = features["timestamp"].diff().replace(0, np.nan).fillna(1.0)
    delta_t = delta_t.clip(lower=1.0)
    features["dI_dt"] = features["current"].diff().fillna(0.0) / delta_t
    features["dV_dt"] = features["voltage"].diff().fillna(0.0) / delta_t
    features["current_ma"] = (
        features["current"]
        .rolling(window=ma_window, min_periods=1)
        .mean()
        .bfill()
        .fillna(0.0)
    )

    ordered_columns = ["timestamp", *FEATURE_COLUMNS]
    return features[ordered_columns].replace([np.inf, -np.inf], 0.0).fillna(0.0)


def build_training_sequences(
    feature_frame: pd.DataFrame,
    window_size: int,
) -> tuple[np.ndarray, np.ndarray]:
    """Turn the feature table into supervised LSTM sequences."""

    if len(feature_frame) <= window_size:
        return np.empty((0, window_size, len(FEATURE_COLUMNS))), np.empty((0,))

    sequences: list[np.ndarray] = []
    targets: list[float] = []

    for start_index in range(len(feature_frame) - window_size):
        end_index = start_index + window_size
        window = feature_frame.iloc[start_index:end_index][FEATURE_COLUMNS].to_numpy(dtype=np.float32)
        target = float(feature_frame.iloc[end_index]["current"])
        sequences.append(window)
        targets.append(target)

    return np.asarray(sequences, dtype=np.float32), np.asarray(targets, dtype=np.float32)


def derive_risk_level(
    prediction_error: float,
    anomaly_flag: int,
    baseline_threshold: float,
) -> tuple[str, float]:
    """Map the anomaly model output to a user-friendly risk label and score."""

    safe_threshold = max(float(baseline_threshold), 1e-6)
    error_ratio = prediction_error / safe_threshold
    risk_score = min(1.0, error_ratio / 2.0)

    if anomaly_flag == -1:
        risk_score = max(risk_score, 0.85)
        return "High", risk_score

    if error_ratio >= 1.0:
        risk_score = max(risk_score, 0.55)
        return "Medium", risk_score

    return "Low", risk_score


def risk_color(risk_label: str) -> str:
    """Return a dashboard color that matches the current risk bucket."""

    palette = {
        "Low": "#1d8348",
        "Medium": "#d68910",
        "High": "#c0392b",
    }
    return palette.get(risk_label, "#566573")


def format_alert_message(
    timestamp: int,
    risk_label: str,
    current: float,
    voltage: float,
    prediction_error: float,
) -> str:
    """Create a concise message for console and webhook notifications."""

    return (
        f"[ALERT] Fault risk={risk_label} at {timestamp} | "
        f"current={current:.3f} A, voltage={voltage:.3f} V, error={prediction_error:.4f}"
    )


def post_webhook(url: str, payload: Mapping[str, Any], timeout: int = 5) -> None:
    """Send an anomaly notification to an external webhook endpoint."""

    request = urllib.request.Request(
        url=url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout):
        return
