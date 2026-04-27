#!/usr/bin/env python3
"""Lumi device one-time setup.

Usage:
  sudo python3 setup.py
  sudo python3 setup.py --device-id "orange-lumi-1" --color-theme orange

This script:
  1. Installs system packages (Bluetooth, audio, espeak-ng)
  2. Creates a Python venv and installs Python dependencies
  3. Prompts for (or accepts via flags) device settings
  4. Saves config to ~/.lumi/config.json
  5. Installs and enables the systemd service for auto-start on boot
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

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
]


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
