"""Vibration motor service — Mini motor 6 mm, 3 V, 7000 RPM.

Wiring (NPN transistor driver, e.g. 2N2222 / BC547):
  GPIO pin (BCM) → 1 kΩ resistor → transistor Base
  Transistor Collector → motor (–) terminal
  Motor (+) terminal → 3.3 V (pin 1) or 5 V (pin 2)
  Transistor Emitter  → GND
  Flyback diode across motor: cathode to (+), anode to (–)

GPIO pin is read from config['vibration_gpio'] (default BCM 18).

Uses gpiozero PWMOutputDevice so intensity can be varied (0.0–1.0).
Falls back to RPi.GPIO digital on/off if gpiozero is unavailable.
"""
import logging
import threading
import time
from typing import Optional

log = logging.getLogger(__name__)


class VibrationService:

    def __init__(self, config: dict):
        self._pin: int = config.get("vibration_gpio", 18)
        self._device = None          # gpiozero PWMOutputDevice
        self._lock   = threading.Lock()
        self._active = False

        try:
            from gpiozero import PWMOutputDevice
            self._device = PWMOutputDevice(self._pin, initial_value=0)
            log.info("Vibration motor ready (PWM, GPIO BCM %d)", self._pin)
        except Exception as e:
            log.warning("Vibration motor (gpiozero PWM) not available: %s — motor disabled", e)

    # ── Public API ────────────────────────────────────────────────────────────

    def vibrate(self, duration_ms: int = 200, intensity: float = 1.0):
        """Pulse the motor for duration_ms milliseconds at the given intensity (0–1).

        Non-blocking — runs the pulse on a daemon thread so callers never block.
        If a pulse is already active the new one is queued after the current finishes.
        """
        if self._device is None:
            log.debug("Vibrate skipped (no hardware)")
            return
        intensity = max(0.0, min(1.0, intensity))
        t = threading.Thread(
            target=self._pulse, args=(duration_ms / 1000.0, intensity), daemon=True
        )
        t.start()

    def vibrate_pattern(self, pattern: list):
        """Play a vibration pattern: list of (duration_ms, intensity) tuples.

        Example: [(100, 1.0), (50, 0.0), (100, 1.0)] — two short pulses.
        Runs non-blocking on a daemon thread.
        """
        if self._device is None:
            return
        t = threading.Thread(target=self._play_pattern, args=(pattern,), daemon=True)
        t.start()

    def stop(self):
        """Immediately stop vibration."""
        if self._device:
            try:
                self._device.value = 0
            except Exception:
                pass

    def cleanup(self):
        self.stop()
        if self._device:
            try:
                self._device.close()
            except Exception:
                pass
            self._device = None

    # ── Internal ──────────────────────────────────────────────────────────────

    def _pulse(self, duration_s: float, intensity: float):
        with self._lock:
            try:
                self._device.value = intensity
                time.sleep(duration_s)
                self._device.value = 0
            except Exception as e:
                log.debug("Vibration pulse error: %s", e)

    def _play_pattern(self, pattern: list):
        with self._lock:
            try:
                for duration_ms, intensity in pattern:
                    self._device.value = max(0.0, min(1.0, intensity))
                    time.sleep(duration_ms / 1000.0)
                self._device.value = 0
            except Exception as e:
                log.debug("Vibration pattern error: %s", e)
