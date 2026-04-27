"""Fingerprint sensor service — placeholder.

Supported hardware (to be added): R307 / FPM10A / GT521F52 via UART.
Enable UART: sudo raspi-config → Interface Options → Serial Port
  (disable serial console, enable serial hardware).

Until hardware is configured, fingerprint auth always succeeds so the
rest of the device works normally.
"""
import logging
import time
from typing import Optional

log = logging.getLogger(__name__)

AUTH_WINDOW_SECONDS = 10.0


class FingerprintService:
    def __init__(self, config: dict):
        self._enabled = config.get("fingerprint_enabled", False)
        self._sensor = None
        self._last_auth: float = 0.0

        if self._enabled:
            log.warning(
                "Fingerprint enabled in config but no sensor module is wired. "
                "Auth will always pass until hardware is added."
            )

    # ── Public API ─────────────────────────────────────────────────────────────

    def is_authenticated_for_trigger(self) -> bool:
        """Returns True if a fingerprint was verified within the last 10 seconds."""
        if not self._enabled:
            return True
        return (time.monotonic() - self._last_auth) < AUTH_WINDOW_SECONDS

    def scan_and_verify(self) -> bool:
        """Block until a valid fingerprint is presented (or sensor unavailable).

        Returns True on success.  Always True when no hardware is present.
        """
        if self._sensor is None:
            log.debug("Fingerprint scan skipped (no hardware) — passing")
            self._last_auth = time.monotonic()
            return True
        # TODO: add sensor-specific scan call here
        return False

    def enroll_fingerprint(self):
        """Interactive enrollment.  Placeholder — run manually on device."""
        log.info("Fingerprint enrollment: hardware not yet configured.")
        log.info("Add your sensor module to fingerprint_service.py and re-run.")

    def set_enabled(self, enabled: bool):
        self._enabled = enabled
