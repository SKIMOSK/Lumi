"""Fall detection service — placeholder for MPU-6050 IMU via I2C.

Hardware: connect MPU-6050 to I2C pins (SDA=GPIO2, SCL=GPIO3).
Enable I2C: sudo raspi-config → Interface Options → I2C.
Install: pip install mpu6050-raspberrypi

Fall detection logic: monitor accelerometer total vector.
A sudden spike (>3g) followed by near-zero (<0.3g) indicates a fall.
"""
import logging
import threading
from typing import Callable, Optional

log = logging.getLogger(__name__)

FALL_SPIKE_G = 3.0
FALL_REST_G = 0.3


class FallDetector:
    def __init__(self, config: dict):
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self.on_fall: Optional[Callable[[], None]] = None
        self._mpu = None
        try:
            from mpu6050 import mpu6050
            self._mpu = mpu6050(0x68)
            log.info("MPU-6050 connected on I2C 0x68")
        except Exception as e:
            log.warning("Fall detector not available: %s", e)

    def start(self):
        if self._mpu is None:
            log.info("Fall detector disabled (no hardware)")
            return
        self._running = True
        self._thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False

    def _monitor_loop(self):
        import math
        import time
        spike_seen = False
        while self._running:
            try:
                acc = self._mpu.get_accel_data()
                g = math.sqrt(acc['x']**2 + acc['y']**2 + acc['z']**2) / 9.81
                if not spike_seen and g > FALL_SPIKE_G:
                    spike_seen = True
                elif spike_seen and g < FALL_REST_G:
                    spike_seen = False
                    log.warning("Fall detected!")
                    if self.on_fall:
                        self.on_fall()
                else:
                    spike_seen = False
                time.sleep(0.05)
            except Exception as e:
                log.debug("IMU read error: %s", e)
                time.sleep(1.0)
