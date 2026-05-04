#!/usr/bin/env python3
"""Lumi device one-time setup.

Usage:
  sudo python3 setup.py
  sudo python3 setup.py --device-id "orange-lumi-1" --color-theme orange --skip-tests

Steps:
  1. Wait for task-button press (detects GPIO pin automatically)
  2. Collect device settings (id, theme, language)
  3. Install system packages
  4. Hardware checks + interactive tests
  5. Configure BlueZ
  6. Create Python venv + install pip dependencies
  7. Save config to ~/.lumi/config.json
  8. Install and enable the systemd service
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import tempfile
from typing import Optional

CONFIG_PATH = os.path.expanduser("~/.lumi/config.json")
INSTALL_DIR = "/opt/lumi"
SERVICE_SRC = os.path.join(os.path.dirname(__file__), "lumi.service")
SERVICE_DST = "/etc/systemd/system/lumi.service"

THEMES = ("grey", "orange", "blue", "purple")

SYSTEM_PACKAGES = [
    "python3-venv",
    "python3-dev",
    "bluetooth",
    "bluez",
    "libdbus-1-dev",
    "python3-dbus",
    "python3-gi",
    "espeak-ng",
    "portaudio19-dev",
    "libatlas-base-dev",
    "libopencv-dev",
    "ffmpeg",
    "python3-gpiozero",
    "alsa-utils",
    "i2c-tools",
    "python3-smbus2",
]

# BCM pins safe to scan (excluding power, GND, I2C=2/3, SPI=9/10/11, UART=14/15)
_SAFE_BCM_PINS = [4, 5, 6, 12, 13, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27]


# ── Helpers ───────────────────────────────────────────────────────────────────

def run(cmd: list, check=True, **kw):
    print(f"  $ {' '.join(cmd)}")
    subprocess.run(cmd, check=check, **kw)


def ask(prompt: str, default: str) -> str:
    val = input(f"{prompt} [{default}]: ").strip()
    return val if val else default


def ask_choice(prompt: str, choices: tuple, default: str) -> str:
    while True:
        val = ask(f"{prompt} ({'/'.join(choices)})", default)
        if val in choices:
            return val
        print(f"  Please choose one of: {', '.join(choices)}")


def ask_words(prompt: str, default: str, min_words=3, max_words=10) -> str:
    while True:
        val = ask(prompt, default)
        words = val.split()
        if min_words <= len(words) <= max_words:
            return val
        print(f"  Please enter between {min_words} and {max_words} words.")


# ── GPIO button detection ─────────────────────────────────────────────────────

def _ask_pin_manual() -> Optional[int]:
    while True:
        val = input("  Task button GPIO BCM pin [17, or Enter to skip]: ").strip()
        if not val:
            print("  Task button not configured — re-run setup later to add it.")
            return None
        try:
            n = int(val)
            if 1 <= n <= 27:
                return n
        except ValueError:
            pass
        print("  Please enter a BCM pin number (1-27) or press Enter to skip.")


def detect_task_button_gpio(prompt_start: bool = False) -> Optional[int]:
    """Scan all safe GPIO pins and return the BCM number of the pressed button.

    When prompt_start=True the function also prints the setup banner and waits
    up to 60 s; otherwise it waits up to 30 s.
    """
    if prompt_start:
        print("\n╔══════════════════════════════════╗")
        print("║   Lumi Device Setup               ║")
        print("╚══════════════════════════════════╝\n")
        print("  Wire your task button between any GPIO pin and GND.")
        print("  The button must be DIFFERENT from the power button.\n")
        print("  >>> Press the task button to begin setup <<<\n")
    else:
        print("  >>> Press the task button now <<<\n")

    try:
        from gpiozero import Button as _GPIOButton
    except Exception:
        print("  (gpiozero not available — enter pin manually)")
        return _ask_pin_manual()

    detected: list = [None]
    buttons: dict  = {}

    def _make_cb(pin: int):
        def _cb():
            if detected[0] is None:
                detected[0] = pin
        return _cb

    for pin in _SAFE_BCM_PINS:
        try:
            b = _GPIOButton(pin, pull_up=True, bounce_time=0.05)
            b.when_pressed = _make_cb(pin)
            buttons[pin] = b
        except Exception:
            pass

    timeout  = 60.0 if prompt_start else 30.0
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if detected[0] is not None:
            break
        time.sleep(0.1)

    for b in buttons.values():
        try:
            b.close()
        except Exception:
            pass

    if detected[0] is None:
        print(f"  No button detected within {int(timeout)} s.")
        return _ask_pin_manual()

    pin = detected[0]
    print(f"\n  Detected button press on GPIO BCM {pin}.")
    if input(f"  Use GPIO BCM {pin} as the task button? [Y/n]: ").strip().lower() not in ("", "y", "yes"):
        return _ask_pin_manual()
    return pin


# ── Hardware checks ───────────────────────────────────────────────────────────

def check_vibration(gpio_pin: int) -> bool:
    """Pulse the vibration motor briefly as a hardware check."""
    try:
        from gpiozero import PWMOutputDevice
        motor = PWMOutputDevice(gpio_pin, initial_value=0)
        motor.value = 1.0
        time.sleep(0.25)
        motor.value = 0.0
        motor.close()
        print(f"  ✓ Vibration motor pulsed on GPIO BCM {gpio_pin}")
        return True
    except Exception as e:
        print(f"  ✗ Vibration motor GPIO BCM {gpio_pin}: {e}")
        return False


def check_fingerprint_uart(uart: str = "/dev/serial0") -> bool:
    if not os.path.exists(uart):
        print(f"  ✗ Fingerprint UART {uart} not found — enable UART and reboot")
        return False
    try:
        from pyfingerprint.pyfingerprint import PyFingerprint
        sensor = PyFingerprint(uart, 57600, 0xFFFFFFFF, 0x00000000)
        if sensor.verifyPassword():
            n = sensor.countTemplates()
            print(f"  ✓ Fingerprint sensor ready on {uart} — {n} template(s) stored")
            return True
        print(f"  ✗ Fingerprint sensor on {uart}: password verification failed")
        return False
    except ImportError:
        print("  ⚠ pyfingerprint not yet installed (will install in step 7)")
        return True   # not a hard failure at this stage
    except Exception as e:
        print(f"  ✗ Fingerprint sensor on {uart}: {e}")
        return False


def check_battery_i2c() -> bool:
    try:
        import smbus2
        bus = smbus2.SMBus(1)
        # Try IP5306 first (0x75), then MAX17040 (0x36)
        for addr, name in [(0x75, "IP5306"), (0x36, "MAX17040")]:
            try:
                bus.read_byte(addr)
                print(f"  ✓ Battery module detected on I2C bus 1 address 0x{addr:02X} ({name})")
                return True
            except Exception:
                pass
        print("  ✗ Battery module not found on I2C (tried 0x75 and 0x36)")
        print("    Check SDA→GPIO2, SCL→GPIO3 wiring and that I2C is enabled")
        return False
    except ImportError:
        print("  ⚠ smbus2 not yet installed (will install in step 7)")
        return True
    except Exception as e:
        print(f"  ✗ I2C bus 1 error: {e}")
        return False


def check_camera() -> bool:
    for i in range(4):
        if os.path.exists(f"/dev/video{i}"):
            print(f"  ✓ Camera detected (/dev/video{i})")
            return True
    print("  ✗ Camera not found — check USB/CSI connection")
    return False


def check_microphone() -> bool:
    r = subprocess.run(["arecord", "--list-devices"], capture_output=True, text=True)
    if "card" in r.stdout.lower():
        print("  ✓ Microphone/audio-in device detected")
        return True
    print("  ✗ No audio capture device found")
    return False


def check_speaker() -> bool:
    r = subprocess.run(["aplay", "--list-devices"], capture_output=True, text=True)
    if "card" in r.stdout.lower():
        print("  ✓ Speaker/audio-out device detected")
        return True
    print("  ✗ No audio playback device found")
    return False


def check_bluetooth() -> bool:
    try:
        r = subprocess.run(["hciconfig"], capture_output=True, text=True, timeout=5)
        if "hci0" in r.stdout:
            print("  ✓ Bluetooth adapter detected (hci0)")
            return True
        r2 = subprocess.run(["bluetoothctl", "show"], capture_output=True, text=True, timeout=5)
        if "Controller" in r2.stdout:
            print("  ✓ Bluetooth controller detected")
            return True
    except Exception:
        pass
    print("  ✗ Bluetooth adapter not found — check hardware")
    return False


# ── Hardware tests ────────────────────────────────────────────────────────────

def test_microphone() -> bool:
    print("  Recording 2 seconds — make some noise!")
    tmp = tempfile.mktemp(suffix=".wav")
    try:
        r = subprocess.run(
            ["arecord", "-d", "2", "-f", "S16_LE", "-r", "16000", "-c", "1", tmp],
            capture_output=True, timeout=8
        )
        if r.returncode != 0 or not os.path.exists(tmp):
            print("  ✗ arecord failed — check microphone connection")
            return False
        size = os.path.getsize(tmp)
        if size > 200:
            print(f"  ✓ Microphone OK ({size} bytes recorded)")
            return True
        print(f"  ✗ Microphone appears silent ({size} bytes) — check connection")
        return False
    except subprocess.TimeoutExpired:
        print("  ✗ Recording timed out")
        return False
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def test_speaker() -> bool:
    print("  Playing test phrase…")
    subprocess.run(
        ["espeak-ng", "-s", "155", "Lumi setup test. Speaker is working."],
        capture_output=True, timeout=10
    )
    heard = input("  Did you hear the test phrase? [Y/n]: ").strip().lower()
    if heard in ("", "y", "yes"):
        print("  ✓ Speaker OK")
        return True
    print("  ✗ Speaker test failed — check audio output and volume")
    return False


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    if os.geteuid() != 0:
        print("Error: run as root:  sudo python3 setup.py")
        sys.exit(1)

    parser = argparse.ArgumentParser(description="Lumi device setup")
    parser.add_argument("--device-id",     help="Device name (3-10 words)")
    parser.add_argument("--color-theme",   choices=THEMES)
    parser.add_argument("--stt-language",  default="en-US")
    parser.add_argument("--skip-packages", action="store_true")
    parser.add_argument("--skip-service",  action="store_true")
    parser.add_argument("--skip-tests",    action="store_true")
    args = parser.parse_args()

    # ── 1. Task button (doubles as setup trigger) ─────────────────────────────
    task_button_gpio = detect_task_button_gpio(prompt_start=True)
    if task_button_gpio is not None:
        print(f"  Task button → GPIO BCM {task_button_gpio}\n")

    # ── 2. Device settings ────────────────────────────────────────────────────
    print("─── Device Settings ─────────────────────────────────────────────")

    device_id    = args.device_id    or ask_words("Device ID (3-10 words, e.g. 'orange lumi one')", "my lumi device")
    color_theme  = args.color_theme  or ask_choice("Color theme", THEMES, "grey")
    stt_language = args.stt_language or ask("STT language (e.g. en-US, ro-RO, fr-FR)", "en-US")

    # ── 2b. Peripheral hardware ───────────────────────────────────────────────
    print("\n─── Peripheral Hardware ──────────────────────────────────────────────")

    # Vibration motor GPIO
    print("\n  Vibration motor (Mini 6 mm, 3 V, 7000 RPM)")
    print("  Connect via NPN transistor (2N2222 / BC547):")
    print("    GPIO pin → 1 kΩ → Base  |  Collector → motor → 3.3 V  |  Emitter → GND")
    print("    Add flyback diode across motor (cathode to 3.3 V side)")
    vib_pin_s = input("  GPIO BCM pin for vibration motor [18, or Enter to skip]: ").strip()
    vibration_gpio: Optional[int] = None
    if vib_pin_s:
        try:
            vibration_gpio = int(vib_pin_s)
        except ValueError:
            pass
    if vibration_gpio is None:
        vibration_gpio = 18
        print(f"  Using default GPIO BCM {vibration_gpio}")
    else:
        print(f"  Vibration motor → GPIO BCM {vibration_gpio}")

    # Fingerprint sensor UART
    print("\n  Fingerprint sensor (SFM-V1.7 — UART)")
    print("  Wiring: SFM TXD→Pi GPIO15(RXD)  SFM RXD→Pi GPIO14(TXD)  VCC→3.3V  GND→GND")
    print("  UART must be enabled: raspi-config → Interface Options → Serial Port")
    print("    → Disable login shell over serial  → Enable serial hardware  → Reboot")
    fp_enabled = input("  Is the fingerprint sensor connected? [y/N]: ").strip().lower() in ("y", "yes")

    # Battery module I2C
    print("\n  Battery module (LiPo 3.7 V for Pi Zero — I2C)")
    print("  Wiring: I2C SDA → Pi GPIO2 (pin 3)  SCL → Pi GPIO3 (pin 5)  VCC/GND as labelled")
    print("  I2C must be enabled: raspi-config → Interface Options → I2C → Enable")
    bat_enabled = input("  Is the battery module connected via I2C? [y/N]: ").strip().lower() in ("y", "yes")

    print(f"\n  Device ID    : {device_id}")
    print(f"  Color theme  : {color_theme}")
    print(f"  Language     : {stt_language}")
    print(f"  Task button  : {'GPIO BCM ' + str(task_button_gpio) if task_button_gpio else 'not configured'}")
    print(f"  Vibration GPIO: {vibration_gpio}")
    print(f"  Fingerprint  : {'enabled (SFM-V1.7 UART)' if fp_enabled else 'disabled'}")
    print(f"  Battery I2C  : {'enabled' if bat_enabled else 'disabled/not connected'}")
    if input("\nProceed? [Y/n]: ").strip().lower() not in ("", "y", "yes"):
        print("Setup cancelled.")
        sys.exit(0)

    # ── 3. System packages ────────────────────────────────────────────────────
    if not args.skip_packages:
        print("\n─── Installing system packages ───────────────────────────────────")
        run(["apt-get", "update", "-qq"])
        run(["apt-get", "install", "-y", "-qq"] + SYSTEM_PACKAGES)

    # ── 4. Hardware checks ────────────────────────────────────────────────────
    print("\n─── Hardware Check ───────────────────────────────────────────────")
    cam_ok = check_camera()
    mic_ok = check_microphone()
    spk_ok = check_speaker()
    bt_ok  = check_bluetooth()
    vib_ok = check_vibration(vibration_gpio)
    fp_ok  = check_fingerprint_uart() if fp_enabled else True
    bat_ok = check_battery_i2c() if bat_enabled else True

    checks = [(cam_ok, "camera"), (mic_ok, "microphone"), (spk_ok, "speaker"),
              (bt_ok, "bluetooth"), (vib_ok, "vibration motor")]
    if fp_enabled:
        checks.append((fp_ok, "fingerprint sensor"))
    if bat_enabled:
        checks.append((bat_ok, "battery module"))
    missing = [n for ok_flag, n in checks if not ok_flag]
    if missing:
        print(f"\n  WARNING: {', '.join(missing)} not detected.")
        if input("  Continue anyway? [y/N]: ").strip().lower() not in ("y", "yes"):
            print("Setup cancelled — fix hardware and re-run.")
            sys.exit(1)

    # ── 5. Hardware tests ─────────────────────────────────────────────────────
    if not args.skip_tests:
        print("\n─── Hardware Tests ───────────────────────────────────────────────")
        if mic_ok:
            test_microphone()
        else:
            print("  Skipping microphone test (device not detected)")
        if spk_ok:
            test_speaker()
        else:
            print("  Skipping speaker test (device not detected)")

    # ── 6. Configure BlueZ ────────────────────────────────────────────────────
    print("\n─── Configuring BlueZ ────────────────────────────────────────────")
    bluez_conf = "/etc/bluetooth/main.conf"
    if os.path.exists(bluez_conf):
        with open(bluez_conf) as f:
            content = f.read()
        if "Experimental" not in content:
            with open(bluez_conf, "a") as f:
                f.write("\n[Policy]\nAutoEnable=true\n\n[General]\nExperimental=true\n")
            print("  BlueZ experimental features enabled")
        else:
            print("  BlueZ already configured")
    run(["systemctl", "restart", "bluetooth"], check=False)

    # ── 7. Python venv + dependencies ─────────────────────────────────────────
    print("\n─── Setting up Python environment ────────────────────────────────")
    script_dir = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(INSTALL_DIR, exist_ok=True)

    dest_pkg = os.path.join(INSTALL_DIR, "lumi_device")
    if os.path.exists(dest_pkg):
        shutil.rmtree(dest_pkg)
    shutil.copytree(os.path.join(script_dir, "lumi_device"), dest_pkg)
    print(f"  Copied lumi_device → {dest_pkg}")

    venv_dir = os.path.join(INSTALL_DIR, "venv")
    if not os.path.exists(venv_dir):
        run([sys.executable, "-m", "venv", venv_dir])
    pip = os.path.join(venv_dir, "bin", "pip")
    req = os.path.join(script_dir, "requirements.txt")
    run([pip, "install", "--upgrade", "pip", "-q"])
    run([pip, "install", "-r", req, "-q"])

    # ── 8. Save config ────────────────────────────────────────────────────────
    print("\n─── Saving config ────────────────────────────────────────────────")
    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    existing = {}
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH) as f:
                existing = json.load(f)
        except Exception:
            pass
    existing.update({
        "device_id":           device_id,
        "color_theme":         color_theme,
        "stt_language":        stt_language,
        "task_button_gpio":    task_button_gpio,
        "vibration_gpio":      vibration_gpio,
        "fingerprint_enabled": fp_enabled,
        "fingerprint_uart":    "/dev/serial0",
        "fingerprint_baud":    57600,
        "battery_i2c_bus":     1,
        "fall_detect_enabled": True,
    })
    with open(CONFIG_PATH, "w") as f:
        json.dump(existing, f, indent=2)
    print(f"  Config saved to {CONFIG_PATH}")

    # ── 9. Systemd service ────────────────────────────────────────────────────
    if not args.skip_service:
        print("\n─── Installing systemd service ───────────────────────────────────")
        if not os.path.exists(SERVICE_SRC):
            print("  Warning: lumi.service not found, skipping")
        else:
            shutil.copy(SERVICE_SRC, SERVICE_DST)
            run(["systemctl", "daemon-reload"])
            run(["systemctl", "enable", "lumi.service"])
            print("  Service enabled — auto-starts on boot")
            if input("  Start Lumi now? [Y/n]: ").strip().lower() in ("", "y", "yes"):
                run(["systemctl", "start", "lumi.service"])
                print("  Running!  Logs: journalctl -u lumi-device -f")

    print("\n✓ Setup complete!")
    print(f"  Device ID    : {device_id}")
    print(f"  Color theme  : {color_theme}")
    print(f"  Task button  : {'GPIO BCM ' + str(task_button_gpio) if task_button_gpio else 'not configured'}")
    print(f"  Vibration    : GPIO BCM {vibration_gpio}")
    print(f"  Fingerprint  : {'enabled' if fp_enabled else 'disabled'}")
    print()
    print("Next steps:")
    print("  0. Run diagnostics anytime: python3 diagnostics.py")
    print("  1. Open the Lumi app on your phone")
    print("  2. Settings → Bluetooth → select this device")
    print("  3. The device auto-connects when the app opens")
    if args.skip_service:
        print("\n  To start manually: sudo /opt/lumi/venv/bin/python -m lumi_device.main")


if __name__ == "__main__":
    main()
