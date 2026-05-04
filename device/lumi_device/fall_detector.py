"""Camera-only fall detection using OpenCV background subtraction.

Algorithm:
  • MOG2 background model at 5 FPS, 320×240.
  • Tracks the bounding-box aspect ratio (width/height) of the largest
    foreground contour.  A standing person is tall (ratio < 1); a fallen
    person is wide (ratio > 1).
  • FALL = aspect ratio rises rapidly AND the bounding-box centre moves
    downward by ≥15 % of the frame height within a 2-second window.

The detector pauses itself while the device is in an active interaction
(PTT / photo / hold-recording) so the background model isn't corrupted
by the user moving intentionally.  Call set_paused(True/False) from main.py.
"""
import collections
import logging
import threading
import time
from typing import Callable, Optional

log = logging.getLogger(__name__)

_WIDTH  = 320
_HEIGHT = 240
_FPS    = 5

_MIN_AREA         = 500   # pixels² — ignore tiny blobs
_HISTORY_FRAMES   = 10    # 2 s worth of frames at 5 FPS
_FALL_RATIO_THR   = 1.0   # current ratio must exceed this (horizontal)
_FALL_RATIO_DELTA = 0.45  # ratio must have risen by at least this much
_FALL_Y_DELTA     = 0.15  # y-centre must have dropped ≥15 % of frame height


class FallDetector:
    def __init__(self, config: dict):
        self._camera_index: int = config.get("camera_index", 0)
        self._running  = False
        self._paused   = False
        self._thread: Optional[threading.Thread] = None
        self.on_fall: Optional[Callable[[], None]] = None

        try:
            import cv2  # noqa: F401 — just verify it is importable
            self._cv2_available = True
        except ImportError:
            self._cv2_available = False
            log.warning("opencv not available — fall detection disabled")

    # ── Control ───────────────────────────────────────────────────────────────

    def set_paused(self, paused: bool):
        """Pause/resume detection (call when device is in active-use states)."""
        self._paused = paused

    def start(self):
        if not self._cv2_available:
            log.info("Fall detector disabled (no opencv)")
            return
        self._running = True
        self._thread = threading.Thread(target=self._detect_loop, daemon=True)
        self._thread.start()
        log.info("Camera fall detector started (camera_index=%d)", self._camera_index)

    def stop(self):
        self._running = False

    # ── Detection loop ────────────────────────────────────────────────────────

    def _detect_loop(self):
        import cv2
        import numpy as np

        cap = cv2.VideoCapture(self._camera_index)
        if not cap.isOpened():
            log.warning("Fall detector: camera %d not accessible — disabled", self._camera_index)
            return

        cap.set(cv2.CAP_PROP_FRAME_WIDTH,  _WIDTH)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, _HEIGHT)
        cap.set(cv2.CAP_PROP_FPS, _FPS)

        fgbg = cv2.createBackgroundSubtractorMOG2(
            history=100, varThreshold=40, detectShadows=False
        )

        ratio_hist   = collections.deque(maxlen=_HISTORY_FRAMES)
        y_hist       = collections.deque(maxlen=_HISTORY_FRAMES)
        last_t       = 0.0

        try:
            while self._running:
                now = time.monotonic()

                if self._paused:
                    time.sleep(0.5)
                    # Reset model so the first non-paused frames rebuild background cleanly
                    fgbg = cv2.createBackgroundSubtractorMOG2(
                        history=100, varThreshold=40, detectShadows=False
                    )
                    ratio_hist.clear()
                    y_hist.clear()
                    last_t = 0.0
                    continue

                # Throttle to ~5 FPS
                elapsed = now - last_t
                if elapsed < 0.18:
                    time.sleep(0.05)
                    continue

                ret, frame = cap.read()
                if not ret:
                    time.sleep(0.5)
                    continue

                last_t = now

                fg_mask = fgbg.apply(frame)

                # Morphological clean-up: remove noise, fill gaps
                kernel = np.ones((5, 5), np.uint8)
                fg_mask = cv2.morphologyEx(fg_mask, cv2.MORPH_OPEN,  kernel)
                fg_mask = cv2.morphologyEx(fg_mask, cv2.MORPH_CLOSE, kernel)

                contours, _ = cv2.findContours(
                    fg_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
                )
                if not contours:
                    continue

                largest = max(contours, key=cv2.contourArea)
                if cv2.contourArea(largest) < _MIN_AREA:
                    continue

                x, y, w, h = cv2.boundingRect(largest)
                aspect_ratio = w / max(h, 1)          # >1 = horizontal/fallen
                y_centre     = (y + h / 2) / _HEIGHT  # normalised 0-1

                ratio_hist.append(aspect_ratio)
                y_hist.append(y_centre)

                if len(ratio_hist) < _HISTORY_FRAMES // 2:
                    continue

                half = len(ratio_hist) // 2
                r_list = list(ratio_hist)
                y_list = list(y_hist)

                avg_ratio_early = sum(r_list[:half]) / half
                avg_ratio_late  = sum(r_list[half:]) / max(len(r_list) - half, 1)

                ratio_rose      = (avg_ratio_late - avg_ratio_early) > _FALL_RATIO_DELTA
                now_horizontal  = avg_ratio_late > _FALL_RATIO_THR
                y_dropped       = (y_list[-1] - y_list[0]) > _FALL_Y_DELTA

                if ratio_rose and now_horizontal and y_dropped:
                    log.warning("Fall detected (camera)")
                    ratio_hist.clear()
                    y_hist.clear()
                    cb = self.on_fall
                    if cb:
                        cb()

        except Exception as e:
            log.error("Fall detector loop error: %s", e)
        finally:
            cap.release()
            log.info("Camera fall detector stopped")
