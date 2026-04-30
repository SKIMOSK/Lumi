"""Physical task-button listener using gpiozero.

Monitors the GPIO pin stored in config["task_button_gpio"] (BCM numbering).
When the button is pressed (pin pulled LOW via GND), on_button_pressed() fires.

The button should be wired: GPIO pin → button → GND.
gpiozero enables the internal pull-up resistor automatically.
"""
import logging
from typing import Callable, Optional

log = logging.getLogger(__name__)


class ButtonService:

    def __init__(self, config: dict):
        self._pin: Optional[int] = config.get("task_button_gpio")
        self._button = None
        self.on_button_pressed: Optional[Callable[[], None]] = None

    def start(self):
        if self._pin is None:
            log.info("Task button: no GPIO pin configured — skipping")
            return
        try:
            from gpiozero import Button
            self._button = Button(self._pin, pull_up=True, bounce_time=0.2)
            self._button.when_pressed = self._on_press
            log.info("Task button active on GPIO BCM %d", self._pin)
        except ImportError:
            log.warning("gpiozero not installed — task button disabled")
        except Exception as e:
            log.warning("Task button setup failed on GPIO %d: %s", self._pin, e)

    def stop(self):
        if self._button is not None:
            try:
                self._button.close()
            except Exception:
                pass
            self._button = None

    def _on_press(self):
        cb = self.on_button_pressed
        if cb:
            cb()
