#!/usr/bin/env python3
"""Lumi Hardware Diagnostics — standalone interactive test runner.

Checks every piece of hardware attached to the Lumi device and reports
PASS / WARN / FAIL with clear explanations.  Run before the first setup
or any time something stops working.

Usage:
  python3 diagnostics.py            # full suite
  python3 diagnostics.py --quick    # skip interactive tests (speaker, button)
  python3 diagnostics.py --fix      # attempt to auto-fix common issues
"""
import argparse
import os
import subprocess
import sys
import time

# ── ANSI colour helpers ───────────────────────────────────────────────────────

_RESET  = "\033[0m"
_BOLD   = "\033[1m"
_RED    = "\033[91m"
_GREEN  = "\033[92m"
_YELLOW = "\033[93m"
_CYAN   = "\033[96m"
_WHITE  = "\033[97m"
_DIM    = "\033[2m"

def _c(colour, text):  return f"{colour}{text}{_RESET}"
def ok(s="PASS"):      return _c(_GREEN,  f"  ✓  {s}")
def warn(s="WARN"):    return _c(_YELLOW, f"  ⚠  {s}")
def fail(s="FAIL"):    return _c(_RED,    f"  ✗  {s}")
def info(s):           return _c(_DIM,    f"     {s}")
def head(s):           return _c(_BOLD + _CYAN, s)

_results: list = []

def _record(label: str, status: str, detail: str = ""):
    _results.append((label, status, detail))
    tag = ok() if status == "PASS" else (warn() if status == "WARN" else fail())
    print(f"{tag}  {_c(_WHITE, label)}")
    if detail:
        print(info(detail))

# ── Config loading ────────────────────────────────────────────────────────────

def _load_config() -> dict:
    try:
        sys.path.insert(0, os.path.dirname(__file__))
        from lumi_device.config import load
        return load()
    except Exception:
        return {}

# ── Individual tests ──────────────────────────────────────────────────────────

def test_python():
    v = sys.version_info
    if v < (3, 10):
        _record("Python version", "WARN",
                f"Python {v.major}.{v.minor} detected — 3.10+ recommended")
    else:
        _record("Python version", "PASS", f"Python {v.major}.{v.minor}.{v.micro}")


def test_dependencies():
    pkgs = {
        "sounddevice":    "sounddevice",
        "numpy":          "numpy",
        "cv2":            "opencv-python-headless",
        "speech_recognition": "SpeechRecognition",
        "bless":          "bless",
        "gpiozero":       "gpiozero",
        "pyfingerprint.pyfingerprint": "pyfingerprint",
        "smbus2":         "smbus2",
    }
    missing = []
    for mod, pkg in pkgs.items():
        try:
            __import__(mod)
        except ImportError:
            missing.append(pkg)
    if missing:
        _record("Python packages", "FAIL",
                f"Missing: {', '.join(missing)} — run: pip install {' '.join(missing)}")
    else:
        _record("Python packages", "PASS", f"All {len(pkgs)} packages found")


def test_config():
    cfg = _load_config()
    if not cfg:
        _record("Config file", "WARN",
                "~/.lumi/config.json not found — run setup.py first")
        return cfg
    device_id = cfg.get("device_id", "")
    if not device_id or device_id == "my-lumi-device":
        _record("Config file", "WARN",
                "Config exists but device_id is still default — run setup.py")
    else:
        _record("Config file", "PASS",
                f"device_id='{device_id}'  theme='{cfg.get('color_theme')}'")
    return cfg


def test_system_packages():
    missing = []
    for pkg in ("espeak-ng", "bluetoothd"):
        try:
            subprocess.run(["which", pkg.split("d")[0] if pkg.endswith("d") else pkg],
                           check=True, capture_output=True)
        except subprocess.CalledProcessError:
            missing.append(pkg)
    # espeak-ng
    try:
        subprocess.run(["espeak-ng", "--version"], check=True, capture_output=True)
    except Exception:
        missing.append("espeak-ng")
    # bluetoothd via systemctl
    r = subprocess.run(["systemctl", "is-active", "bluetooth"],
                       capture_output=True, text=True)
    if r.stdout.strip() != "active":
        missing.append("bluetooth service")

    if missing:
        _record("System packages", "WARN",
                f"Not ready: {', '.join(missing)}")
    else:
        _record("System packages", "PASS", "espeak-ng + bluetooth service active")


def test_bluetooth():
    try:
        r = subprocess.run(["hciconfig", "hci0"], capture_output=True, text=True, timeout=5)
        if "UP RUNNING" in r.stdout:
            _record("Bluetooth adapter", "PASS", "hci0 UP RUNNING")
        elif "DOWN" in r.stdout:
            _record("Bluetooth adapter", "WARN",
                    "hci0 is DOWN — run: sudo hciconfig hci0 up")
        else:
            _record("Bluetooth adapter", "FAIL",
                    "hci0 not found — check USB BT dongle or built-in chip")
    except FileNotFoundError:
        _record("Bluetooth adapter", "WARN",
                "hciconfig not found — install: sudo apt install bluez")
    except Exception as e:
        _record("Bluetooth adapter", "FAIL", str(e))


def test_camera(config: dict):
    idx = config.get("camera_index", 0)
    try:
        import cv2
        cap = cv2.VideoCapture(idx)
        if not cap.isOpened():
            _record("Camera", "FAIL",
                    f"Cannot open camera index {idx} — check USB or CSI connection")
            return
        ret, frame = cap.read()
        cap.release()
        if not ret or frame is None:
            _record("Camera", "FAIL", "Camera opened but failed to read a frame")
            return
        h, w = frame.shape[:2]
        _record("Camera", "PASS", f"Captured {w}×{h} frame (index {idx})")
    except ImportError:
        _record("Camera", "FAIL", "opencv-python-headless not installed")
    except Exception as e:
        _record("Camera", "FAIL", str(e))


def test_microphone(config: dict):
    mic_idx = config.get("mic_index", None)
    try:
        import sounddevice as sd
        import numpy as np
        duration = 2.0
        sr = 16000
        devices = sd.query_devices()
        input_devs = [d for d in devices if d["max_input_channels"] > 0]
        if not input_devs:
            _record("Microphone", "FAIL", "No input audio devices found")
            return

        audio = sd.rec(int(duration * sr), samplerate=sr, channels=1,
                       dtype="int16", device=mic_idx)
        sd.wait()
        rms = int(np.sqrt(np.mean(audio.astype(np.float32) ** 2)))
        threshold = config.get("vad_energy_threshold", 300)

        if rms < 10:
            _record("Microphone", "FAIL",
                    f"RMS={rms} — no signal (check mic connection or mute)")
        elif rms < threshold // 2:
            _record("Microphone", "WARN",
                    f"RMS={rms} — very low signal (VAD threshold={threshold})")
        else:
            dev_name = input_devs[0]["name"] if mic_idx is None else devices[mic_idx]["name"]
            _record("Microphone", "PASS",
                    f"RMS={rms}  device: {dev_name[:50]}")
    except Exception as e:
        _record("Microphone", "FAIL", str(e))


def test_speaker_interactive(config: dict, quick: bool):
    try:
        test_phrase = "Lumi hardware test. Can you hear me?"
        subprocess.Popen(
            ["espeak-ng", "-s", "150", "--", test_phrase],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        if quick:
            time.sleep(2)
            _record("Speaker", "WARN", "Quick mode — speaker output not confirmed by user")
            return
        time.sleep(2.5)
        ans = input(_c(_YELLOW, "     Did you hear the speaker test? [y/n]: ")).strip().lower()
        if ans == "y":
            _record("Speaker", "PASS", "User confirmed audio output")
        else:
            _record("Speaker", "FAIL",
                    "No audio heard — check speaker wiring / ALSA output device")
    except Exception as e:
        _record("Speaker", "FAIL", str(e))


def test_vibration(config: dict):
    gpio_pin = config.get("vibration_gpio", 18)
    try:
        from gpiozero import PWMOutputDevice
        motor = PWMOutputDevice(gpio_pin, initial_value=0)
        motor.value = 1.0
        time.sleep(0.3)
        motor.value = 0.0
        motor.close()
        _record("Vibration motor", "PASS",
                f"GPIO BCM {gpio_pin} — 300 ms pulse sent")
    except Exception as e:
        _record("Vibration motor", "WARN",
                f"GPIO BCM {gpio_pin} — {e} (check transistor wiring)")


def test_fingerprint(config: dict):
    if not config.get("fingerprint_enabled", False):
        _record("Fingerprint scanner", "WARN",
                "Disabled in config (fingerprint_enabled=false) — skipping")
        return

    uart = config.get("fingerprint_uart", "/dev/serial0")
    baud = config.get("fingerprint_baud", 57600)

    if not os.path.exists(uart):
        _record("Fingerprint scanner", "FAIL",
                f"{uart} not found — enable UART in raspi-config and reboot")
        return

    try:
        from pyfingerprint.pyfingerprint import PyFingerprint
        sensor = PyFingerprint(uart, baud, 0xFFFFFFFF, 0x00000000)
        if not sensor.verifyPassword():
            _record("Fingerprint scanner", "FAIL",
                    f"Sensor on {uart} responded but password verification failed")
            return
        n = sensor.countTemplates()
        _record("Fingerprint scanner", "PASS",
                f"SFM-V1.7 connected on {uart} @ {baud} baud — {n} template(s) enrolled")
    except ImportError:
        _record("Fingerprint scanner", "FAIL",
                "pyfingerprint not installed — run: pip install pyfingerprint")
    except Exception as e:
        _record("Fingerprint scanner", "FAIL",
                f"Could not communicate with sensor on {uart}: {e}")


def test_battery(config: dict):
    try:
        sys.path.insert(0, os.path.dirname(__file__))
        from lumi_device.battery_service import BatteryService
        bat = BatteryService(config)
        level = bat.get_level()
        charging = bat.is_charging()

        if level < 0:
            _record("Battery module", "WARN",
                    "No battery module detected (IP5306/MAX17040 not found on I2C, no sysfs entry). "
                    "Check I2C wiring and that i2c-tools is installed (sudo apt install i2c-tools).")
        else:
            charge_str = ""
            if charging is True:
                charge_str = " — charging"
            elif charging is False:
                charge_str = " — on battery"
            backend = bat._backend or "unknown"
            _record("Battery module", "PASS" if level >= 20 else "WARN",
                    f"{level}%{charge_str}  (driver: {backend})")
    except Exception as e:
        _record("Battery module", "WARN", f"Battery check error: {e}")


def test_i2c_bus(config: dict):
    """Run i2cdetect and report what addresses respond."""
    bus = config.get("battery_i2c_bus", 1)
    try:
        r = subprocess.run(
            ["i2cdetect", "-y", str(bus)],
            capture_output=True, text=True, timeout=10
        )
        # Find non-'--' addresses
        found = []
        for line in r.stdout.splitlines()[1:]:
            parts = line.split()[1:]
            for p in parts:
                if p not in ("--", "UU") and len(p) == 2:
                    try:
                        found.append(f"0x{p}")
                    except ValueError:
                        pass
        if found:
            _record("I2C bus scan", "PASS",
                    f"Bus {bus}: devices at {', '.join(found)}")
        else:
            _record("I2C bus scan", "WARN",
                    f"Bus {bus}: no I2C devices found (expected battery module at 0x75 or 0x36)")
    except FileNotFoundError:
        _record("I2C bus scan", "WARN",
                "i2cdetect not found — install: sudo apt install i2c-tools")
    except Exception as e:
        _record("I2C bus scan", "WARN", str(e))


def test_uart(config: dict):
    """Check that the UART device for the fingerprint sensor is accessible."""
    uart = config.get("fingerprint_uart", "/dev/serial0")
    if os.path.exists(uart):
        # Check permissions
        if os.access(uart, os.R_OK | os.W_OK):
            _record("UART port", "PASS", f"{uart} exists and is accessible")
        else:
            _record("UART port", "WARN",
                    f"{uart} exists but not readable/writable — "
                    f"run: sudo usermod -aG dialout $USER")
    else:
        _record("UART port", "WARN",
                f"{uart} not found — enable UART in raspi-config "
                f"(Interface Options → Serial Port)")


def test_button_interactive(config: dict, quick: bool):
    gpio_pin = config.get("task_button_gpio")
    if gpio_pin is None:
        _record("Task button", "WARN",
                "task_button_gpio not set in config — run setup.py to configure")
        return
    if quick:
        _record("Task button", "WARN",
                f"Quick mode — GPIO BCM {gpio_pin} configured but not tested interactively")
        return
    try:
        from gpiozero import Button
        btn = Button(gpio_pin, pull_up=True, bounce_time=0.05)
        print(_c(_YELLOW, f"\n     Press the task button on GPIO BCM {gpio_pin} (5 s)…"))
        deadline = time.monotonic() + 5.0
        pressed = False
        while time.monotonic() < deadline:
            if btn.is_pressed:
                pressed = True
                break
            time.sleep(0.05)
        btn.close()
        if pressed:
            _record("Task button", "PASS", f"GPIO BCM {gpio_pin} — button press detected")
        else:
            _record("Task button", "WARN",
                    f"GPIO BCM {gpio_pin} — no press detected in 5 s")
    except Exception as e:
        _record("Task button", "FAIL", str(e))


def test_network():
    try:
        r = subprocess.run(
            ["ping", "-c", "1", "-W", "3", "8.8.8.8"],
            capture_output=True, timeout=6
        )
        if r.returncode == 0:
            _record("Network / internet", "PASS", "ping 8.8.8.8 OK")
        else:
            _record("Network / internet", "WARN",
                    "ping failed — STT (Google) will not work without internet")
    except Exception as e:
        _record("Network / internet", "WARN", str(e))


def test_alsa():
    try:
        r = subprocess.run(["aplay", "-l"], capture_output=True, text=True, timeout=5)
        if "card" in r.stdout:
            lines = [l.strip() for l in r.stdout.splitlines() if l.strip().startswith("card")]
            _record("ALSA audio devices", "PASS",
                    f"{len(lines)} playback device(s): {lines[0][:60] if lines else ''}")
        else:
            _record("ALSA audio devices", "WARN", "No ALSA playback cards found")
    except Exception as e:
        _record("ALSA audio devices", "WARN", str(e))


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Lumi hardware diagnostics")
    parser.add_argument("--quick", action="store_true",
                        help="Skip interactive tests (speaker, button press)")
    parser.add_argument("--fix", action="store_true",
                        help="Attempt auto-fix for common issues")
    args = parser.parse_args()

    print()
    print(_c(_BOLD + _CYAN,
             "╔══════════════════════════════════════════════╗"))
    print(_c(_BOLD + _CYAN,
             "║        Lumi Hardware Diagnostics             ║"))
    print(_c(_BOLD + _CYAN,
             "╚══════════════════════════════════════════════╝"))
    print()

    config = _load_config()

    sections = [
        ("Software",   [
            test_python,
            test_dependencies,
            lambda: test_config(),
        ]),
        ("System",     [
            test_system_packages,
            test_bluetooth,
            test_alsa,
            test_network,
        ]),
        ("Peripherals (GPIO / UART / I2C)", [
            lambda: test_uart(config),
            lambda: test_i2c_bus(config),
            lambda: test_vibration(config),
            lambda: test_fingerprint(config),
            lambda: test_battery(config),
        ]),
        ("Camera & Audio", [
            lambda: test_camera(config),
            lambda: test_microphone(config),
            lambda: test_speaker_interactive(config, args.quick),
        ]),
        ("User Input", [
            lambda: test_button_interactive(config, args.quick),
        ]),
    ]

    for section_name, tests in sections:
        print(head(f"\n── {section_name} "))
        for t in tests:
            try:
                t()
            except Exception as e:
                _record(getattr(t, "__name__", "test"), "FAIL", f"Unhandled error: {e}")

    # ── Summary ───────────────────────────────────────────────────────────────
    print()
    print(_c(_BOLD + _CYAN, "── Summary "))
    total = len(_results)
    passed = sum(1 for _, s, _ in _results if s == "PASS")
    warned = sum(1 for _, s, _ in _results if s == "WARN")
    failed = sum(1 for _, s, _ in _results if s == "FAIL")

    print(f"  Total: {total}  "
          + _c(_GREEN, f"{passed} PASS") + "  "
          + _c(_YELLOW, f"{warned} WARN") + "  "
          + _c(_RED, f"{failed} FAIL"))
    print()

    if failed > 0:
        print(_c(_RED + _BOLD, "  Action required:"))
        for label, status, detail in _results:
            if status == "FAIL":
                print(f"  • {label}: {detail}")
        print()

    if warned > 0:
        print(_c(_YELLOW + _BOLD, "  Warnings:"))
        for label, status, detail in _results:
            if status == "WARN":
                print(f"  • {label}: {detail}")
        print()

    if args.fix and failed > 0:
        print(_c(_CYAN, "  Auto-fix: re-running sudo apt install for missing system packages…"))
        subprocess.run([
            "sudo", "apt", "install", "-y",
            "espeak-ng", "bluetooth", "bluez", "i2c-tools",
            "alsa-utils", "python3-gpiozero",
        ])
        print(_c(_CYAN, "  Done. Re-run diagnostics to verify."))

    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
