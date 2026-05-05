"""Fingerprint sensor service — SFM-V1.7 (ZFM/FPM10A-compatible UART protocol).

Hardware setup:
  1. Enable UART hardware (disable serial console):
       sudo raspi-config → Interface Options → Serial Port
       → "Would you like a login shell accessible over serial?" → No
       → "Would you like the serial port hardware to be enabled?" → Yes
  2. Reboot — /dev/serial0 will then be available.

Wiring (3.3 V logic):
  SFM-V1.7 VCC  → 3.3 V (Pi pin 1) — or 5 V if your module requires it
  SFM-V1.7 GND  → GND (Pi pin 6)
  SFM-V1.7 TXD  → Pi GPIO 15 / RXD (pin 10)
  SFM-V1.7 RXD  → Pi GPIO 14 / TXD (pin 8)

Config keys:
  fingerprint_uart    — serial device  (default: /dev/serial0)
  fingerprint_baud    — baud rate      (default: 57600)
  fingerprint_enabled — bool           (default: False)

Auth window: once a fingerprint is verified it stays valid for 10 seconds.
If no sensor is present, all scans auto-pass so development works normally.
"""
import logging
import subprocess
import threading
import time
from typing import Callable, Optional

log = logging.getLogger(__name__)

_AUTH_WINDOW_S  = 10.0
_SCAN_TIMEOUT_S = 15.0   # give up waiting for a finger after this long


class FingerprintService:

    def __init__(self, config: dict):
        self._enabled: bool = config.get("fingerprint_enabled", False)
        self._uart: str  = config.get("fingerprint_uart", "/dev/serial0")
        self._baud: int  = config.get("fingerprint_baud", 57600)
        self._sensor     = None          # PyFingerprint instance
        self._last_auth: float = 0.0
        self._auth_lock  = threading.Lock()
        self._scan_thread: Optional[threading.Thread] = None

        # Optional callback — called with True/False after a background scan
        self.on_auth_result: Optional[Callable[[bool], None]] = None

        if self._enabled:
            self._connect()

    # ── Connection ────────────────────────────────────────────────────────────

    def _connect(self):
        try:
            from pyfingerprint.pyfingerprint import PyFingerprint
            sensor = PyFingerprint(self._uart, self._baud, 0xFFFFFFFF, 0x00000000)
            if not sensor.verifyPassword():
                log.error("Fingerprint sensor: password verification failed")
                return
            self._sensor = sensor
            n = sensor.countTemplates()
            log.info("Fingerprint sensor ready (%s @ %d baud) — %d template(s) stored",
                     self._uart, self._baud, n)
        except ImportError:
            log.warning("pyfingerprint not installed — run: pip install pyfingerprint")
        except Exception as e:
            log.warning("Fingerprint sensor unavailable (%s): %s", self._uart, e)

    # ── Public API ────────────────────────────────────────────────────────────

    def is_authenticated_for_trigger(self) -> bool:
        """True if a valid fingerprint was scanned within the last 10 seconds."""
        if not self._enabled:
            return True
        if self._sensor is None:
            return True   # no hardware → always allow
        with self._auth_lock:
            last = self._last_auth
        return (time.monotonic() - last) < _AUTH_WINDOW_S

    def start_auth_scan(self):
        """Non-blocking fingerprint scan.

        Fires on_auth_result(True/False) when done.  If fingerprint is not
        enabled or sensor is absent the result is immediately True.
        """
        if not self._enabled or self._sensor is None:
            with self._auth_lock:
                self._last_auth = time.monotonic()
            if self.on_auth_result:
                self.on_auth_result(True)
            return
        if self._scan_thread and self._scan_thread.is_alive():
            return   # scan already running
        self._scan_thread = threading.Thread(target=self._background_scan, daemon=True)
        self._scan_thread.start()

    def scan_and_verify(self) -> bool:
        """Blocking scan — returns True on a recognised match."""
        if not self._enabled or self._sensor is None:
            with self._auth_lock:
                self._last_auth = time.monotonic()
            return True
        return self._do_scan()

    def count_templates(self) -> int:
        if self._sensor is None:
            return 0
        try:
            return self._sensor.countTemplates()
        except Exception:
            return 0

    def enroll_fingerprint(self) -> bool:
        """Two-scan interactive enrollment. Returns True on success.

        Called when the phone sends CMD_SETUP_FP.  Speaks progress aloud.
        """
        if self._sensor is None:
            log.warning("Enroll skipped — no fingerprint sensor connected")
            return False

        def _speak(text: str):
            subprocess.Popen(["espeak-ng", "--", text],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        try:
            log.info("Enrollment: place finger on sensor (scan 1)…")
            _speak("Place your finger on the scanner")
            deadline = time.monotonic() + 30.0
            while not self._sensor.readImage():
                if time.monotonic() > deadline:
                    _speak("Enrollment timed out"); return False
                time.sleep(0.05)

            self._sensor.convertImage(0x01)
            _speak("Remove your finger")
            log.info("Scan 1 captured — waiting for finger removal")
            time.sleep(1.5)

            log.info("Enrollment: place the SAME finger again (scan 2)…")
            _speak("Place the same finger again")
            deadline = time.monotonic() + 30.0
            while not self._sensor.readImage():
                if time.monotonic() > deadline:
                    _speak("Enrollment timed out"); return False
                time.sleep(0.05)

            self._sensor.convertImage(0x02)
            self._sensor.createTemplate()
            position = self._sensor.storeTemplate()
            log.info("Fingerprint enrolled at slot %d (%d total)",
                     position, self.count_templates())
            _speak("Fingerprint saved successfully")
            return True

        except Exception as e:
            log.error("Enrollment error: %s", e)
            return False

    def set_enabled(self, enabled: bool):
        self._enabled = enabled
        if enabled and self._sensor is None:
            self._connect()

    # ── Internal ──────────────────────────────────────────────────────────────

    def _background_scan(self):
        ok = self._do_scan()
        if self.on_auth_result:
            self.on_auth_result(ok)

    def _do_scan(self) -> bool:
        try:
            deadline = time.monotonic() + _SCAN_TIMEOUT_S
            while not self._sensor.readImage():
                if time.monotonic() > deadline:
                    log.info("Fingerprint scan timed out (no finger presented)")
                    return False
                time.sleep(0.05)

            self._sensor.convertImage(0x01)
            position, score = self._sensor.searchTemplate()

            if position == -1:
                log.info("Fingerprint not recognised (score=%d)", score)
                return False

            log.info("Fingerprint verified — slot=%d score=%d", position, score)
            with self._auth_lock:
                self._last_auth = time.monotonic()
            return True

        except Exception as e:
            log.warning("Fingerprint scan error: %s", e)
            return False
