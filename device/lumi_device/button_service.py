"""Physical task-button service — single, double, triple click and hold detection.

Button wiring: GPIO pin (BCM) → button → GND.
gpiozero applies an internal pull-up; pressing the button drives the pin LOW.

Interaction timings:
  hold_time        = 0.7 s  — button must be held this long to trigger HOLD
  double_click_win = 0.35 s — releases within this window accumulate clicks
  bounce_time      = 0.05 s — debounce filter

Callbacks set by main.py:
  on_single_click()   — one short press-and-release
  on_double_click()   — two quick presses within the window
  on_triple_click()   — three quick presses — triggers soft shutdown
  on_hold_start()     — button held ≥ hold_time (still pressed)
  on_hold_release()   — button released AFTER a hold was triggered
"""
import logging
import threading
from typing import Callable, Optional

log = logging.getLogger(__name__)

_HOLD_TIME_S        = 0.7
_DOUBLE_CLICK_WIN_S = 0.35
_BOUNCE_TIME_S      = 0.05


class ButtonService:

    def __init__(self, config: dict):
        self._pin: Optional[int] = config.get("task_button_gpio")
        self._button = None

        self._pending_clicks = 0
        self._click_timer: Optional[threading.Timer] = None
        self._was_held       = False
        self._lock           = threading.Lock()

        self.on_single_click:  Optional[Callable[[], None]] = None
        self.on_double_click:  Optional[Callable[[], None]] = None
        self.on_triple_click:  Optional[Callable[[], None]] = None
        self.on_hold_start:    Optional[Callable[[], None]] = None
        self.on_hold_release:  Optional[Callable[[], None]] = None

    # ── Lifecycle ────────────────────────────────────────────────────────────

    def start(self):
        if self._pin is None:
            log.info("ButtonService: no GPIO pin configured — skipping")
            return
        try:
            from gpiozero import Button
            self._button = Button(
                self._pin,
                pull_up     = True,
                bounce_time = _BOUNCE_TIME_S,
                hold_time   = _HOLD_TIME_S,
            )
            self._button.when_pressed  = self._on_press
            self._button.when_released = self._on_release
            self._button.when_held     = self._on_held
            log.info("ButtonService active on GPIO BCM %d", self._pin)
        except ImportError:
            log.warning("gpiozero not installed — button disabled")
        except Exception as e:
            log.warning("ButtonService setup failed (GPIO %d): %s", self._pin, e)

    def stop(self):
        if self._button is not None:
            try:
                self._button.close()
            except Exception:
                pass
            self._button = None
        with self._lock:
            if self._click_timer:
                self._click_timer.cancel()
                self._click_timer = None

    # ── gpiozero callbacks (run on gpiozero's internal thread) ───────────────

    def _on_press(self):
        with self._lock:
            if self._click_timer:
                self._click_timer.cancel()
                self._click_timer = None
            self._was_held = False
            self._pending_clicks += 1

    def _on_held(self):
        """Fires while button is still pressed after hold_time has elapsed."""
        with self._lock:
            if self._click_timer:
                self._click_timer.cancel()
                self._click_timer = None
            self._pending_clicks = 0
            self._was_held = True
        cb = self.on_hold_start
        if cb:
            cb()

    def _on_release(self):
        with self._lock:
            was_held = self._was_held
            if was_held:
                self._was_held = False
                self._pending_clicks = 0

        if was_held:
            cb = self.on_hold_release
            if cb:
                threading.Thread(target=cb, daemon=True).start()
            return

        with self._lock:
            t = threading.Timer(_DOUBLE_CLICK_WIN_S, self._resolve_clicks)
            t.daemon = True
            self._click_timer = t
        t.start()

    def _resolve_clicks(self):
        with self._lock:
            count = self._pending_clicks
            self._pending_clicks = 0
            self._click_timer = None
        if count == 1:
            cb = self.on_single_click
        elif count == 2:
            cb = self.on_double_click
        elif count >= 3:
            cb = self.on_triple_click
        else:
            return
        if cb:
            cb()
