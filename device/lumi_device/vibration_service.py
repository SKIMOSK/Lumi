"""Vibration motor service — placeholder until hardware is wired.

Hardware: connect DC motor via NPN transistor (e.g. 2N2222) to any GPIO pin.
Use GPIO pin defined in config['vibration_gpio'] (default 18).
"""
import logging

log = logging.getLogger(__name__)


class VibrationService:
    def __init__(self, config: dict):
        self._gpio_pin = config.get("vibration_gpio", 18)
        self._gpio = None
        try:
            import RPi.GPIO as GPIO
            GPIO.setmode(GPIO.BCM)
            GPIO.setup(self._gpio_pin, GPIO.OUT, initial=GPIO.LOW)
            self._gpio = GPIO
            log.info("Vibration motor ready on GPIO %d", self._gpio_pin)
        except Exception as e:
            log.warning("Vibration motor not available: %s", e)

    def vibrate(self, duration_ms: int = 200):
        if self._gpio is None:
            log.debug("Vibrate skipped (no hardware)")
            return
        import time
        try:
            self._gpio.output(self._gpio_pin, self._gpio.HIGH)
            time.sleep(duration_ms / 1000.0)
            self._gpio.output(self._gpio_pin, self._gpio.LOW)
        except Exception as e:
            log.warning("Vibration error: %s", e)

    def cleanup(self):
        if self._gpio:
            try:
                self._gpio.cleanup(self._gpio_pin)
            except Exception:
                pass
