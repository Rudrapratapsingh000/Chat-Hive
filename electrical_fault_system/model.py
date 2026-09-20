from __future__ import annotations

import threading
from dataclasses import asdict, dataclass
from typing import Any

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler

from electrical_fault_system.utils import (
    FEATURE_COLUMNS,
    build_training_sequences,
    derive_risk_level,
)

try:
    import tensorflow as tf
    from tensorflow.keras import Sequential
    from tensorflow.keras.callbacks import EarlyStopping
    from tensorflow.keras.layers import Dense, Dropout, LSTM

    TENSORFLOW_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # pragma: no cover - environment dependent
    tf = None
    Sequential = None
    EarlyStopping = None
    Dense = None
    Dropout = None
    LSTM = None
    TENSORFLOW_IMPORT_ERROR = exc


@dataclass(slots=True)
class PredictionResult:
    """Single prediction + anomaly classification output."""

    predicted_current: float
    actual_current: float
    prediction_error: float
    anomaly_flag: int
    anomaly_score: float
    risk_label: str
    risk_score: float
    baseline_error_threshold: float

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class HybridFaultPredictor:
    """LSTM current predictor backed by Isolation Forest anomaly detection."""

    def __init__(
        self,
        window_size: int = 15,
        contamination: float = 0.1,
        lstm_epochs: int = 20,
        batch_size: int = 16,
        random_state: int = 42,
    ) -> None:
        self.window_size = window_size
        self.contamination = contamination
        self.lstm_epochs = lstm_epochs
        self.batch_size = batch_size
        self.random_state = random_state

        self.feature_scaler = StandardScaler()
        self.target_scaler = StandardScaler()
        self.isolation_forest = IsolationForest(
            contamination=self.contamination,
            n_estimators=150,
            random_state=self.random_state,
        )

        self._lock = threading.RLock()
        self.lstm_model: Sequential | None = None
        self.is_trained = False
        self.error_threshold = 0.0
        self.training_sequence_count = 0

    @property
    def dependency_error(self) -> Exception | None:
        return TENSORFLOW_IMPORT_ERROR

    def _ensure_tensorflow(self) -> None:
        if TENSORFLOW_IMPORT_ERROR is not None:
            raise RuntimeError(
                "TensorFlow/Keras could not be imported. "
                "Install the dependencies from requirements.txt in a supported Python environment."
            ) from TENSORFLOW_IMPORT_ERROR

    def _build_lstm_model(self, feature_count: int) -> Sequential:
        self._ensure_tensorflow()
        assert tf is not None
        assert Sequential is not None
        assert Dense is not None
        assert Dropout is not None
        assert LSTM is not None

        tf.random.set_seed(self.random_state)
        np.random.seed(self.random_state)

        model = Sequential(
            [
                LSTM(32, input_shape=(self.window_size, feature_count)),
                Dropout(0.2),
                Dense(16, activation="relu"),
                Dense(1),
            ]
        )
        model.compile(optimizer="adam", loss="mse")
        return model

    def fit(self, feature_frame: pd.DataFrame) -> dict[str, Any]:
        """Train or refresh the LSTM and Isolation Forest on recent history."""

        with self._lock:
            self._ensure_tensorflow()

            sequences, targets = build_training_sequences(feature_frame, self.window_size)
            minimum_sequences = max(12, self.window_size)
            if len(sequences) < minimum_sequences:
                raise ValueError(
                    f"Need at least {minimum_sequences} supervised sequences before training. "
                    f"Currently available: {len(sequences)}."
                )

            flattened_sequences = sequences.reshape(-1, len(FEATURE_COLUMNS))
            self.feature_scaler = StandardScaler()
            self.feature_scaler.fit(flattened_sequences)
            scaled_sequences = self.feature_scaler.transform(flattened_sequences).reshape(
                sequences.shape
            )

            self.target_scaler = StandardScaler()
            scaled_targets = self.target_scaler.fit_transform(targets.reshape(-1, 1))

            self.lstm_model = self._build_lstm_model(feature_count=len(FEATURE_COLUMNS))
            callbacks = [EarlyStopping(monitor="loss", patience=3, restore_best_weights=True)]
            self.lstm_model.fit(
                scaled_sequences,
                scaled_targets,
                epochs=self.lstm_epochs,
                batch_size=self.batch_size,
                verbose=0,
                callbacks=callbacks,
            )

            train_predictions_scaled = self.lstm_model.predict(scaled_sequences, verbose=0)
            train_predictions = self.target_scaler.inverse_transform(train_predictions_scaled).ravel()
            errors = np.abs(train_predictions - targets)

            self.isolation_forest = IsolationForest(
                contamination=self.contamination,
                n_estimators=150,
                random_state=self.random_state,
            )
            self.isolation_forest.fit(errors.reshape(-1, 1))

            self.error_threshold = float(np.quantile(errors, 0.95))
            self.training_sequence_count = len(sequences)
            self.is_trained = True

            return {
                "status": "trained",
                "sequence_count": len(sequences),
                "mean_error": float(errors.mean()),
                "max_error": float(errors.max()),
                "error_threshold": self.error_threshold,
            }

    def predict(self, feature_frame: pd.DataFrame) -> PredictionResult:
        """Predict the newest current value and classify its anomaly risk."""

        with self._lock:
            if not self.is_trained or self.lstm_model is None:
                raise RuntimeError("The hybrid fault model has not been trained yet.")

            if len(feature_frame) < self.window_size + 1:
                raise ValueError(
                    f"Need at least {self.window_size + 1} readings for a live prediction."
                )

            recent_window = feature_frame.iloc[-(self.window_size + 1) : -1]
            model_input = recent_window[FEATURE_COLUMNS].to_numpy(dtype=np.float32)
            model_input_scaled = self.feature_scaler.transform(model_input).reshape(
                1, self.window_size, len(FEATURE_COLUMNS)
            )

            actual_current = float(feature_frame.iloc[-1]["current"])
            predicted_scaled = self.lstm_model.predict(model_input_scaled, verbose=0)
            predicted_current = float(
                self.target_scaler.inverse_transform(predicted_scaled)[0, 0]
            )
            prediction_error = abs(predicted_current - actual_current)

            error_vector = np.asarray([[prediction_error]], dtype=np.float32)
            anomaly_flag = int(self.isolation_forest.predict(error_vector)[0])
            anomaly_score = float(-self.isolation_forest.score_samples(error_vector)[0])
            risk_label, risk_score = derive_risk_level(
                prediction_error=prediction_error,
                anomaly_flag=anomaly_flag,
                baseline_threshold=self.error_threshold,
            )

            return PredictionResult(
                predicted_current=predicted_current,
                actual_current=actual_current,
                prediction_error=prediction_error,
                anomaly_flag=anomaly_flag,
                anomaly_score=anomaly_score,
                risk_label=risk_label,
                risk_score=risk_score,
                baseline_error_threshold=self.error_threshold,
            )
