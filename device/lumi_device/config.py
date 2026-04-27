import json
import os

CONFIG_PATH = os.path.expanduser("~/.lumi/config.json")

DEFAULTS = {
    "device_id": "my-lumi-device",
    "color_theme": "grey",
    "stt_language": "en-US",
    "camera_index": 0,
    "mic_index": None,
    "speaker_index": None,
    "vad_silence_ms": 900,
    "vad_energy_threshold": 300,
    "fingerprint_enabled": False,
}


def load() -> dict:
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH) as f:
                return {**DEFAULTS, **json.load(f)}
        except Exception:
            pass
    return DEFAULTS.copy()


def save(cfg: dict):
    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    with open(CONFIG_PATH, "w") as f:
        json.dump(cfg, f, indent=2)
