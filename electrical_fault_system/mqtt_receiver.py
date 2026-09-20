from __future__ import annotations

import argparse
import json
import math
import os
import random
import threading
import time
from collections import deque
from dataclasses import dataclass
from typing import Any

import pandas as pd

from electrical_fault_system.model import HybridFaultPredictor
from electrical_fault_system.utils import (
    format_alert_message,
    parse_bool,
    post_webhook,
    readings_to_frame,
    validate_reading,
)

try:
    import paho.mqtt.client as mqtt

    MQTT_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # pragma: no cover - environment dependent
    mqtt = None
    MQTT_IMPORT_ERROR = exc


@dataclass(slots=True)
class ServiceConfig:
    """Runtime options for MQTT ingestion, model training, and dashboard behavior."""

    broker_host: str = "localhost"
    broker_port: int = 1883
    topic: str = "esp8266/electrical"
    client_id: str = "electrical-fault-monitor"
    username: str | None = None
    password: str | None = None
    window_size: int = 15
    bootstrap_samples: int = 75
    history_limit: int = 240
    retrain_interval: int = 30
    webhook_url: str | None = None
    simulate: bool = False
    simulated_interval_seconds: float = 1.0

    @classmethod
    def from_env(cls) -> "ServiceConfig":
        return cls(
            broker_host=os.getenv("FAULT_MQTT_BROKER", "localhost"),
            broker_port=int(os.getenv("FAULT_MQTT_PORT", "1883")),
            topic=os.getenv("FAULT_MQTT_TOPIC", "esp8266/electrical"),
            client_id=os.getenv("FAULT_MQTT_CLIENT_ID", "electrical-fault-monitor"),
            username=os.getenv("FAULT_MQTT_USERNAME") or None,
            password=os.getenv("FAULT_MQTT_PASSWORD") or None,
            window_size=int(os.getenv("FAULT_WINDOW_SIZE", "15")),
            bootstrap_samples=int(os.getenv("FAULT_BOOTSTRAP_SAMPLES", "75")),
            history_limit=int(os.getenv("FAULT_HISTORY_LIMIT", "240")),
            retrain_interval=int(os.getenv("FAULT_RETRAIN_INTERVAL", "30")),
            webhook_url=os.getenv("FAULT_WEBHOOK_URL") or None,
            simulate=parse_bool(os.getenv("FAULT_SIMULATE"), default=False),
            simulated_interval_seconds=float(os.getenv("FAULT_SIMULATED_INTERVAL", "1.0")),
        )


class FaultPredictionService:
    """Owns the bounded telemetry buffer, the hybrid model, and alerting."""

    def __init__(self, config: ServiceConfig | None = None) -> None:
        self.config = config or ServiceConfig.from_env()
        self.predictor = HybridFaultPredictor(window_size=self.config.window_size)

        self._lock = threading.RLock()
        self._raw_history: deque[dict[str, Any]] = deque(maxlen=self.config.history_limit)
        self._telemetry_history: deque[dict[str, Any]] = deque(maxlen=self.config.history_limit)
        self._alerts: deque[dict[str, Any]] = deque(maxlen=20)

        self._mqtt_client: Any | None = None
        self._simulator_thread: threading.Thread | None = None
        self._training_thread: threading.Thread | None = None

        self._running = False
        self._training_in_progress = False
        self._last_training_sample_count = 0
        self._last_alert_timestamp = 0.0

        self.total_messages = 0
        self.latest_prediction: dict[str, Any] | None = None
        self.status_message = "Waiting to start."

    @property
    def training_in_progress(self) -> bool:
        with self._lock:
            return self._training_in_progress

    def start(self) -> "FaultPredictionService":
        """Start the ingestion service once and keep it alive for Streamlit reruns."""

        with self._lock:
            if self._running:
                return self

            self._running = True
            if self.config.simulate:
                self.status_message = "Simulation mode enabled. Generating sample ESP8266 telemetry."
                self._simulator_thread = threading.Thread(
                    target=self._run_simulator,
                    daemon=True,
                    name="fault-simulator",
                )
                self._simulator_thread.start()
                return self

            self._start_mqtt_listener()
            return self

    def stop(self) -> None:
        """Stop MQTT and simulation threads."""

        with self._lock:
            self._running = False
            if self._mqtt_client is not None:
                try:
                    self._mqtt_client.loop_stop()
                    self._mqtt_client.disconnect()
                except Exception:
                    pass
                self._mqtt_client = None

    def _start_mqtt_listener(self) -> None:
        if MQTT_IMPORT_ERROR is not None:
            raise RuntimeError(
                "paho-mqtt is not installed. Install requirements.txt or set FAULT_SIMULATE=true."
            ) from MQTT_IMPORT_ERROR

        assert mqtt is not None

        def build_client() -> Any:
            client_id = f"{self.config.client_id}-{int(time.time())}"
            callback_api = getattr(mqtt, "CallbackAPIVersion", None)
            if callback_api is not None:
                try:
                    return mqtt.Client(callback_api.VERSION2, client_id=client_id)
                except Exception:
                    pass
            return mqtt.Client(client_id=client_id)

        self._mqtt_client = build_client()
        if self.config.username:
            self._mqtt_client.username_pw_set(self.config.username, self.config.password)

        self._mqtt_client.on_connect = self._on_connect
        self._mqtt_client.on_message = self._on_message
        self._mqtt_client.on_disconnect = self._on_disconnect

        self.status_message = (
            f"Connecting to MQTT broker {self.config.broker_host}:{self.config.broker_port} "
            f"on topic '{self.config.topic}'."
        )
        self._mqtt_client.connect_async(
            host=self.config.broker_host,
            port=self.config.broker_port,
            keepalive=60,
        )
        self._mqtt_client.loop_start()

    def _on_connect(
        self,
        client: Any,
        userdata: Any,
        flags: Any,
        reason_code: Any,
        properties: Any = None,
    ) -> None:
        code = getattr(reason_code, "value", reason_code)
        if code == 0:
            client.subscribe(self.config.topic)
            with self._lock:
                self.status_message = f"Connected to MQTT topic '{self.config.topic}'."
        else:
            with self._lock:
                self.status_message = f"MQTT connection failed with code {code}."

    def _on_disconnect(
        self,
        client: Any,
        userdata: Any,
        reason_code: Any,
        properties: Any = None,
    ) -> None:
        with self._lock:
            if self._running:
                self.status_message = f"MQTT disconnected with code {reason_code}."

    def _on_message(
        self,
        client: Any,
        userdata: Any,
        message: Any,
    ) -> None:
        try:
            payload = json.loads(message.payload.decode("utf-8"))
            self.process_payload(payload)
        except Exception as exc:
            with self._lock:
                self.status_message = f"Failed to process MQTT payload: {exc}"

    def process_payload(self, payload: dict[str, Any]) -> None:
        """Validate telemetry, update the prediction window, and run anomaly checks."""

        reading = validate_reading(payload)
        history_frame: pd.DataFrame | None = None
        should_train = False
        should_retrain = False

        with self._lock:
            latest_record = {
                **reading.to_dict(),
                "power": reading.current * reading.voltage,
                "predicted_current": None,
                "prediction_error": None,
                "anomaly_flag": 1,
                "anomaly_score": 0.0,
                "risk_label": "Low",
                "risk_score": 0.0,
            }

            self._raw_history.append(reading.to_dict())
            self._telemetry_history.append(latest_record)
            self.total_messages += 1

            history_frame = readings_to_frame(self._raw_history)
            available_sequences = max(0, len(history_frame) - self.config.window_size)

            if not self.predictor.is_trained and not self._training_in_progress:
                should_train = len(history_frame) >= self.config.bootstrap_samples
            elif (
                self.predictor.is_trained
                and not self._training_in_progress
                and self.total_messages - self._last_training_sample_count >= self.config.retrain_interval
            ):
                should_retrain = True

            if not self.predictor.is_trained:
                self.status_message = (
                    f"Warming up model with {len(history_frame)}/{self.config.bootstrap_samples} "
                    f"samples. Available sequences: {available_sequences}."
                )

        if should_train or should_retrain:
            self._schedule_training(history_frame)

        if self.predictor.is_trained:
            self._run_live_prediction()

    def _schedule_training(self, history_frame: pd.DataFrame) -> None:
        with self._lock:
            if self._training_in_progress:
                return
            self._training_in_progress = True
            self.status_message = "Training the hybrid LSTM + Isolation Forest model."

        training_copy = history_frame.copy()
        self._training_thread = threading.Thread(
            target=self._train_model,
            args=(training_copy,),
            daemon=True,
            name="fault-model-trainer",
        )
        self._training_thread.start()

    def _train_model(self, history_frame: pd.DataFrame) -> None:
        try:
            metrics = self.predictor.fit(history_frame)
            with self._lock:
                self._last_training_sample_count = self.total_messages
                self.status_message = (
                    "Model ready. "
                    f"Sequences={metrics['sequence_count']}, "
                    f"mean error={metrics['mean_error']:.4f}, "
                    f"threshold={metrics['error_threshold']:.4f}."
                )
        except Exception as exc:
            with self._lock:
                self.status_message = f"Model training failed: {exc}"
        finally:
            with self._lock:
                self._training_in_progress = False

    def _run_live_prediction(self) -> None:
        history_frame = self.get_history_frame()
        if len(history_frame) < self.config.window_size + 1:
            return

        try:
            result = self.predictor.predict(history_frame)
        except Exception as exc:
            with self._lock:
                self.status_message = f"Prediction step failed: {exc}"
            return

        with self._lock:
            if not self._telemetry_history:
                return

            latest = self._telemetry_history[-1]
            latest.update(result.to_dict())
            self.latest_prediction = latest.copy()
            self.status_message = (
                f"Monitoring live data. Latest risk={result.risk_label}, "
                f"prediction error={result.prediction_error:.4f}."
            )

        if result.anomaly_flag == -1:
            self._dispatch_alert()

    def _dispatch_alert(self) -> None:
        now = time.time()
        if now - self._last_alert_timestamp < 5:
            return

        latest = self.latest_prediction or {}
        alert_message = format_alert_message(
            timestamp=int(latest.get("timestamp", int(now))),
            risk_label=str(latest.get("risk_label", "High")),
            current=float(latest.get("current", 0.0)),
            voltage=float(latest.get("voltage", 0.0)),
            prediction_error=float(latest.get("prediction_error", 0.0)),
        )

        alert_payload = {
            "message": alert_message,
            "timestamp": latest.get("timestamp"),
            "topic": self.config.topic,
            "reading": latest,
        }

        print(alert_message, flush=True)

        with self._lock:
            self._last_alert_timestamp = now
            self._alerts.appendleft(alert_payload)

        if self.config.webhook_url:
            try:
                post_webhook(self.config.webhook_url, alert_payload)
            except Exception as exc:
                with self._lock:
                    self.status_message = f"Alert webhook failed: {exc}"

    def _run_simulator(self) -> None:
        step = 0
        while self._running:
            angle = step / 6.0
            current = 9.0 + 0.45 * math.sin(angle) + random.gauss(0.0, 0.08)
            voltage = 230.0 + 2.2 * math.cos(angle / 2.0) + random.gauss(0.0, 0.7)

            # Inject a short disturbance every minute to demonstrate fault detection.
            if step % 60 in {48, 49, 50, 51}:
                current += random.uniform(2.8, 4.5)
                voltage -= random.uniform(18.0, 35.0)

            simulated_payload = {
                "current": round(max(current, 0.01), 3),
                "voltage": round(max(voltage, 0.01), 3),
                "timestamp": int(time.time()),
            }
            self.process_payload(simulated_payload)
            step += 1
            time.sleep(self.config.simulated_interval_seconds)

    def get_history_frame(self) -> pd.DataFrame:
        with self._lock:
            return readings_to_frame(list(self._raw_history))

    def get_snapshot(self) -> dict[str, Any]:
        """Expose a thread-safe dashboard snapshot."""

        with self._lock:
            records = list(self._telemetry_history)
            alerts = list(self._alerts)
            latest = self.latest_prediction or (records[-1] if records else None)

            return {
                "status_message": self.status_message,
                "model_ready": self.predictor.is_trained,
                "training_in_progress": self._training_in_progress,
                "simulate": self.config.simulate,
                "topic": self.config.topic,
                "broker_host": self.config.broker_host,
                "broker_port": self.config.broker_port,
                "window_size": self.config.window_size,
                "bootstrap_samples": self.config.bootstrap_samples,
                "samples_received": self.total_messages,
                "records": records,
                "alerts": alerts,
                "latest": latest,
                "tensorflow_error": (
                    str(self.predictor.dependency_error) if self.predictor.dependency_error else None
                ),
                "mqtt_error": str(MQTT_IMPORT_ERROR) if MQTT_IMPORT_ERROR else None,
            }


_SERVICE_SINGLETON: FaultPredictionService | None = None
_SERVICE_LOCK = threading.Lock()


def get_shared_service(config: ServiceConfig | None = None) -> FaultPredictionService:
    """Return a single service instance that survives Streamlit script reruns."""

    global _SERVICE_SINGLETON
    with _SERVICE_LOCK:
        if _SERVICE_SINGLETON is None:
            _SERVICE_SINGLETON = FaultPredictionService(config=config)
        return _SERVICE_SINGLETON


def main() -> None:
    parser = argparse.ArgumentParser(description="Real-time electrical fault receiver")
    parser.add_argument("--simulate", action="store_true", help="Generate simulated telemetry.")
    parser.add_argument("--broker", default=os.getenv("FAULT_MQTT_BROKER", "localhost"))
    parser.add_argument("--port", type=int, default=int(os.getenv("FAULT_MQTT_PORT", "1883")))
    parser.add_argument("--topic", default=os.getenv("FAULT_MQTT_TOPIC", "esp8266/electrical"))
    parser.add_argument(
        "--webhook-url",
        default=os.getenv("FAULT_WEBHOOK_URL"),
        help="Optional webhook URL for anomaly notifications.",
    )
    args = parser.parse_args()

    config = ServiceConfig.from_env()
    config.simulate = config.simulate or args.simulate
    config.broker_host = args.broker
    config.broker_port = args.port
    config.topic = args.topic
    config.webhook_url = args.webhook_url or config.webhook_url

    service = get_shared_service(config)
    service.start()

    print("Electrical fault prediction receiver started.", flush=True)
    try:
        while True:
            snapshot = service.get_snapshot()
            print(
                f"[STATUS] samples={snapshot['samples_received']} | "
                f"model_ready={snapshot['model_ready']} | "
                f"message={snapshot['status_message']}",
                flush=True,
            )
            time.sleep(5)
    except KeyboardInterrupt:
        print("Stopping receiver...", flush=True)
        service.stop()


if __name__ == "__main__":
    main()
