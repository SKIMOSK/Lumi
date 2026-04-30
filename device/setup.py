#!/usr/bin/env python3
"""Lumi device one-time setup.

Usage:
  sudo python3 setup.py
  sudo python3 setup.py --device-id "orange-lumi-1" --color-theme orange

This script:
  1. Installs system packages (Bluetooth, audio, espeak-ng, gpiozero)
  2. Creates a Python venv and installs Python dependencies
  3. Prompts for (or accepts via flags) device settings
  4. Detects the physical task button GPIO pin
  5. Saves config to ~/.lumi/config.json
  6. Installs and enables the systemd service for auto-start on boot
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
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
    "libatlas-base-dev",   # numpy optimisation on ARM
    "libopencv-dev",
    "ffmpeg",
    "python3-gpiozero",    # GPIO button support
]

# GPIO pins safe to scan for button detection (BCM numbering).
# Excludes: power (1,2), GND pins, I2C (2,3), SPI (9,10,11), UART (14,15),
# and ID EEPROM (0,1). Pins used by common Pi peripherals are excluded too.
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
    """Fallback: ask the user to type the BCM pin number (or skip)."""
    while True:
        val = input("  Task button GPIO BCM pin [17, or Enter to skip]: ").strip()
        if not val:
            print("  Task button disabled — re-run setup any time to configure it.")
            return None
        try:
            n = int(val)
            if 1 <= n <= 27:
                return n
        except ValueError:
            pass
        print("  Please enter a BCM pin number (1-27) or press Enter to skip.")


def detect_task_button_gpio() -> Optional[int]:
    """Scan GPIO pins for a button press and return the detected BCM pin number.

    Requires gpiozero (installed in step 2).  Falls back to manual entry if
    gpiozero is not available or if running outside a Raspberry Pi environment.
    """
    print("\n  Wire your task button between a GPIO pin and GND.")
    print("  The button must be DIFFERENT from the power button.\n")

    try:
        from gpiozero import Button as _GPIOButton
    except Exception:
        print("  gpiozero not available — entering pin manually.")
        return _ask_pin_manual()

    buttons: dict = {}
    detected: list = [None]

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
            pass  # pin already in use or not accessible

    print("  >>> Press the task button now...  (30-second window)")
    deadline = time.monotonic() + 30
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
        print("  No button press detected within 30 seconds.")
        return _ask_pin_manual()

    pin = detected[0]
    print(f"\n  Detected button press on GPIO BCM {pin}.")
    confirm = input(f"  Use GPIO BCM {pin} as the task button? [Y/n]: ").strip().lower()
    if confirm not in ("", "y", "yes"):
        return _ask_pin_manual()
    return pin


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    if os.geteuid() != 0:
        print("Error: this script must be run as root (sudo python3 setup.py)")
        sys.exit(1)

    parser = argparse.ArgumentParser(description="Lumi device setup")
    parser.add_argument("--device-id", help="Device name (3-10 words)")
    parser.add_argument("--color-theme", choices=THEMES, help="Device color theme")
    parser.add_argument("--stt-language", default="en-US",
                        help="Speech recognition language (default: en-US)")
    parser.add_argument("--skip-packages", action="store_true",
                        help="Skip apt package installation")
    parser.add_argument("--skip-service", action="store_true",
                        help="Skip systemd service installation")
    args = parser.parse_args()

    print("\n╔══════════════════════════════╗")
    print("║   Lumi Device Setup          ║")
    print("╚══════════════════════════════╝\n")

    # ── 1. Collect settings ─────────────────────────────────────────────────
    print("─── Device Settings ───────────────────────────────────────────")

    device_id = args.device_id or ask_words(
        "Device ID (3-10 words, e.g. 'orange lumi one')",
        "my lumi device"
    )
    color_theme = args.color_theme or ask_choice(
        "Color theme (matches device body color)",
        THEMES, "grey"
    )
    stt_language = args.stt_language or ask(
        "STT language (e.g. en-US, ro-RO, fr-FR)", "en-US"
    )

    print(f"\n  Device ID   : {device_id}")
    print(f"  Color theme : {color_theme}")
    print(f"  Language    : {stt_language}")
    confirm = input("\nProceed with these settings? [Y/n]: ").strip().lower()
    if confirm not in ("", "y", "yes"):
        print("Setup cancelled.")
        sys.exit(0)

    # ── 2. System packages ──────────────────────────────────────────────────
    if not args.skip_packages:
        print("\n─── Installing system packages ────────────────────────────────")
        run(["apt-get", "update", "-qq"])
        run(["apt-get", "install", "-y", "-qq"] + SYSTEM_PACKAGES)

    # ── 2.5. Task button GPIO setup ─────────────────────────────────────────
    print("\n─── Task Button Setup ─────────────────────────────────────────")
    task_button_gpio = detect_task_button_gpio()
    if task_button_gpio is not None:
        print(f"  Task button will be on GPIO BCM {task_button_gpio}")
    else:
        print("  Task button skipped (voice-only mode)")

    # ── 3. Configure BlueZ for GATT peripheral ──────────────────────────────
    print("\n─── Configuring BlueZ ─────────────────────────────────────────")
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

    # ── 4. Python venv + dependencies ───────────────────────────────────────
    print("\n─── Setting up Python environment ─────────────────────────────")
    script_dir = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(INSTALL_DIR, exist_ok=True)

    # Copy device app to /opt/lumi
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

    # ── 5. Save config ───────────────────────────────────────────────────────
    print("\n─── Saving config ─────────────────────────────────────────────")
    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    existing = {}
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH) as f:
                existing = json.load(f)
        except Exception:
            pass
    existing.update({
        "device_id": device_id,
        "color_theme": color_theme,
        "stt_language": stt_language,
        "task_button_gpio": task_button_gpio,
    })
    with open(CONFIG_PATH, "w") as f:
        json.dump(existing, f, indent=2)
    print(f"  Config saved to {CONFIG_PATH}")

    # ── 6. Systemd service ───────────────────────────────────────────────────
    if not args.skip_service:
        print("\n─── Installing systemd service ────────────────────────────────")
        if not os.path.exists(SERVICE_SRC):
            print("  Warning: lumi.service not found, skipping")
        else:
            shutil.copy(SERVICE_SRC, SERVICE_DST)
            run(["systemctl", "daemon-reload"])
            run(["systemctl", "enable", "lumi.service"])
            print("  Service enabled — will auto-start on boot")
            start_now = input("  Start Lumi now? [Y/n]: ").strip().lower()
            if start_now in ("", "y", "yes"):
                run(["systemctl", "start", "lumi.service"])
                print("  Lumi is running. Check logs: journalctl -u lumi-device -f")

    print("\n✓ Setup complete!")
    print(f"  Device ID    : {device_id}")
    print(f"  Color theme  : {color_theme}")
    print(f"  Task button  : {'GPIO BCM ' + str(task_button_gpio) if task_button_gpio else 'not configured'}")
    print()
    print("Next steps:")
    print("  1. On your Android phone, open Bluetooth settings and pair with 'Lumi'")
    print("  2. In the Lumi app → Settings → Bluetooth, select this device")
    print("  3. The device will auto-connect when the app opens")
    print()
    if args.skip_service:
        print("  To start manually: sudo /opt/lumi/venv/bin/python -m lumi_device.main")


if __name__ == "__main__":
    main()
