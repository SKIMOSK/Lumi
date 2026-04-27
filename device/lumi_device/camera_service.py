"""Camera service — uses the first available V4L2 camera (USB or CSI).

Works with any USB webcam out-of-the-box.
For Pi Camera Module (CSI), ensure camera is enabled:
  sudo raspi-config → Interface Options → Camera  (Bullseye)
  or camera_auto_detect=1 in /boot/config.txt      (Bookworm, default)
"""
import io
import logging
from typing import Optional

log = logging.getLogger(__name__)

CAPTURE_WIDTH = 640
CAPTURE_HEIGHT = 480
JPEG_QUALITY = 70  # lower quality = smaller transfer over BLE


class CameraService:
    def __init__(self, config: dict):
        self._index = config.get("camera_index", 0)
        self._cap = None
        self._cv2 = None
        self._init_camera()

    def _init_camera(self):
        try:
            import cv2
            self._cv2 = cv2
            cap = cv2.VideoCapture(self._index)
            if cap.isOpened():
                cap.set(cv2.CAP_PROP_FRAME_WIDTH, CAPTURE_WIDTH)
                cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAPTURE_HEIGHT)
                self._cap = cap
                log.info("Camera ready (index %d, %dx%d)", self._index, CAPTURE_WIDTH, CAPTURE_HEIGHT)
            else:
                log.warning("Camera index %d not available", self._index)
        except ImportError:
            log.warning("opencv-python-headless not installed — camera disabled")
        except Exception as e:
            log.warning("Camera init error: %s", e)

    def capture_jpeg(self) -> Optional[bytes]:
        """Capture one frame and return it as JPEG bytes, or None on failure."""
        if self._cap is None or self._cv2 is None:
            return None
        ret, frame = self._cap.read()
        if not ret:
            log.warning("Camera read failed, reinitialising")
            self._init_camera()
            return None
        ok, buf = self._cv2.imencode(".jpg", frame, [self._cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
        if not ok:
            return None
        data = buf.tobytes()
        log.debug("Captured JPEG %d bytes", len(data))
        return data

    def release(self):
        if self._cap:
            self._cap.release()
            self._cap = None
