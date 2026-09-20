from __future__ import annotations

import time
from typing import Any

import pandas as pd
import streamlit as st

from electrical_fault_system.mqtt_receiver import ServiceConfig, get_shared_service
from electrical_fault_system.utils import risk_color

st.set_page_config(
    page_title="Electrical Fault Prediction Dashboard",
    layout="wide",
    initial_sidebar_state="expanded",
)


def apply_styles() -> None:
    st.markdown(
        """
        <style>
            :root {
                --bg-start: #f7f3e9;
                --bg-end: #edf5f2;
                --card-bg: rgba(255, 255, 255, 0.82);
                --card-border: rgba(28, 36, 48, 0.08);
                --ink: #18202c;
                --muted: #5d6d7e;
                --shadow: 0 18px 40px rgba(24, 32, 44, 0.08);
                --accent: #0d6f61;
            }

            .stApp {
                background:
                    radial-gradient(circle at top left, rgba(13, 111, 97, 0.10), transparent 34%),
                    linear-gradient(145deg, var(--bg-start), var(--bg-end));
                color: var(--ink);
            }

            .dashboard-shell {
                padding: 0.4rem 0 1rem 0;
            }

            .hero-card, .metric-card, .alert-card {
                background: var(--card-bg);
                border: 1px solid var(--card-border);
                border-radius: 18px;
                box-shadow: var(--shadow);
                padding: 1rem 1.2rem;
            }

            .hero-title {
                font-size: 2rem;
                font-weight: 700;
                margin: 0;
                color: var(--ink);
            }

            .hero-subtitle {
                color: var(--muted);
                margin-top: 0.35rem;
                font-size: 0.98rem;
            }

            .metric-label {
                color: var(--muted);
                font-size: 0.85rem;
                text-transform: uppercase;
                letter-spacing: 0.08em;
                margin-bottom: 0.55rem;
            }

            .metric-value {
                color: var(--ink);
                font-size: 2rem;
                font-weight: 700;
                margin: 0;
            }

            .metric-footnote {
                color: var(--muted);
                font-size: 0.88rem;
                margin-top: 0.35rem;
            }

            .alert-card {
                border-left: 8px solid var(--accent);
            }
        </style>
        """,
        unsafe_allow_html=True,
    )


@st.cache_resource(show_spinner=False)
def boot_service() -> Any:
    service = get_shared_service(ServiceConfig.from_env())
    service.start()
    return service


def render_metric_card(title: str, value: str, footnote: str) -> None:
    st.markdown(
        f"""
        <div class="metric-card">
            <div class="metric-label">{title}</div>
            <div class="metric-value">{value}</div>
            <div class="metric-footnote">{footnote}</div>
        </div>
        """,
        unsafe_allow_html=True,
    )


apply_styles()

st.markdown('<div class="dashboard-shell">', unsafe_allow_html=True)

try:
    service = boot_service()
except Exception as exc:
    st.error(f"Unable to start the electrical fault service: {exc}")
    st.stop()

snapshot = service.get_snapshot()
records = pd.DataFrame(snapshot["records"])
latest = snapshot["latest"] or {}
latest_risk = str(latest.get("risk_label", "Low"))
latest_score = float(latest.get("risk_score", 0.0))

st.markdown(
    """
    <div class="hero-card">
        <p class="hero-title">Real-Time Electrical Fault Prediction</p>
        <p class="hero-subtitle">
            MQTT ingestion, LSTM forecasting, and Isolation Forest anomaly detection for
            live ESP8266 electrical telemetry.
        </p>
    </div>
    """,
    unsafe_allow_html=True,
)

with st.sidebar:
    st.header("Runtime")
    st.write(
        "Mode:",
        "Simulation" if snapshot["simulate"] else f"MQTT ({snapshot['broker_host']}:{snapshot['broker_port']})",
    )
    st.write("Topic:", f"`{snapshot['topic']}`")
    st.write("Window size:", snapshot["window_size"])
    st.write("Warm-up target:", snapshot["bootstrap_samples"])
    auto_refresh = st.toggle("Auto refresh every second", value=True)

    if snapshot["tensorflow_error"]:
        st.error(
            "TensorFlow is not available in the current Python environment. "
            "Install the project requirements in a supported interpreter to enable the LSTM model."
        )

    if not snapshot["simulate"] and snapshot["mqtt_error"]:
        st.warning(
            "paho-mqtt is not available. Install requirements.txt or enable simulation mode."
        )

col1, col2, col3 = st.columns(3)
with col1:
    render_metric_card(
        title="Risk Level",
        value=latest_risk,
        footnote=f"Risk score: {latest_score * 100:.1f}%",
    )
with col2:
    render_metric_card(
        title="Current",
        value=f"{float(latest.get('current', 0.0)):.3f} A",
        footnote=f"Predicted: {float(latest.get('predicted_current', 0.0)):.3f} A",
    )
with col3:
    render_metric_card(
        title="Voltage",
        value=f"{float(latest.get('voltage', 0.0)):.3f} V",
        footnote=f"Prediction error: {float(latest.get('prediction_error', 0.0)):.4f}",
    )

st.markdown(
    f"""
    <div class="alert-card" style="--accent: {risk_color(latest_risk)};">
        <strong>System status:</strong> {snapshot['status_message']}
    </div>
    """,
    unsafe_allow_html=True,
)

if latest.get("anomaly_flag") == -1:
    st.error(
        "Fault anomaly detected. Alert dispatched to the console and configured webhook endpoint."
    )
elif not snapshot["model_ready"]:
    progress = min(1.0, snapshot["samples_received"] / max(snapshot["bootstrap_samples"], 1))
    st.info("The model is still warming up on live data before anomaly detection begins.")
    st.progress(progress)

if records.empty:
    st.warning("No telemetry has been received yet. Publish MQTT messages or enable simulation mode.")
else:
    records["timestamp"] = pd.to_datetime(records["timestamp"], unit="s")
    records = records.sort_values("timestamp")

    current_chart, voltage_chart = st.columns(2)
    with current_chart:
        st.subheader("Live Current")
        current_series = records.set_index("timestamp")[["current"]].copy()
        if "predicted_current" in records.columns:
            current_series["predicted_current"] = records.set_index("timestamp")["predicted_current"]
        st.line_chart(current_series)

    with voltage_chart:
        st.subheader("Live Voltage")
        st.line_chart(records.set_index("timestamp")[["voltage"]])

    st.subheader("Recent Telemetry")
    table_columns = [
        "timestamp",
        "current",
        "voltage",
        "power",
        "predicted_current",
        "prediction_error",
        "risk_label",
        "anomaly_flag",
    ]
    st.dataframe(
        records[table_columns]
        .tail(20)
        .sort_values("timestamp", ascending=False)
        .reset_index(drop=True),
        use_container_width=True,
    )

    if snapshot["alerts"]:
        st.subheader("Recent Alerts")
        alerts_frame = pd.DataFrame(snapshot["alerts"])
        st.dataframe(alerts_frame[["timestamp", "message"]], use_container_width=True)

st.markdown("</div>", unsafe_allow_html=True)

if auto_refresh:
    time.sleep(1)
    st.rerun()
