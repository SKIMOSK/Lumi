"""Battery monitoring service — LiPo module for Raspberry Pi Zero.

Supports (tried in order):
  1. IP5306   — I2C address 0x75, register 0x78 (2-bit SOC field)
     Common in cheap Chinese Pi Zero UPS/LiPo shield modules.
  2. MAX17040 — I2C address 0x36, register 0x04 (16-bit SOC, 1/256 % resolution)
     Used on Adafruit LiPo Fuel Gauge and some PiSugar variants.
  3. /sys/class/power_supply/ — kernel-reported capacity (works if the module
     registers itself as a Linux power supply, e.g. PiSugar2/3 with driver).
  4. None — returns -1 (unknown).

Config key: battery_i2c_bus (default 1 — the standard Pi I2C bus on GPIO 2/3).
"""
import logging
import os
from typing import Optional

log = logging.getLogger(__name__)


class BatteryService:

    def __init__(self, config: dict):
        self._bus_num: int = config.get("battery_i2c_bus", 1)
        self._backend: Optional[str] = None
        self._bus = None

        self._backend = self._detect_backend()
        if self._backend:
            log.info("Battery monitor: using backend '%s'", self._backend)
        else:
            log.info("Battery monitor: no supported module detected — level will read as unknown")

    # ── Public API ────────────────────────────────────────────────────────────

    def get_level(self) -> int:
        """Return battery charge level 0–100, or -1 if unknown."""
        if self._backend == "ip5306":
            return self._read_ip5306()
        if self._backend == "max17040":
            return self._read_max17040()
        if self._backend == "sysfs":
            return self._read_sysfs()
        return -1

    def get_status(self) -> str:
        """Human-readable status string, e.g. '75% (IP5306)'."""
        level = self.get_level()
        if level < 0:
            return "unknown"
        tag = f" ({self._backend})" if self._backend else ""
        return f"{level}%{tag}"

    def is_charging(self) -> Optional[bool]:
        """Return True/False/None (unknown) for charging state."""
        if self._backend == "ip5306":
            return self._ip5306_charging()
        if self._backend == "sysfs":
            return self._sysfs_charging()
        return None

    # ── Backend detection ─────────────────────────────────────────────────────

    def _detect_backend(self) -> Optional[str]:
        try:
            import smbus2
            bus = smbus2.SMBus(self._bus_num)
            self._bus = bus

            # IP5306 — write/read register 0x00 to confirm presence
            try:
                bus.read_byte_data(0x75, 0x00)
                return "ip5306"
            except Exception:
                pass

            # MAX17040 — read version register 0x08 (should be non-zero)
            try:
                v = bus.read_word_data(0x36, 0x08)
                if v:
                    return "max17040"
            except Exception:
                pass

        except ImportError:
            log.debug("smbus2 not installed — I2C battery detection skipped")
        except Exception as e:
            log.debug("I2C bus %d open failed: %s", self._bus_num, e)

        if self._read_sysfs() >= 0:
            return "sysfs"

        return None

    # ── IP5306 (0x75) ─────────────────────────────────────────────────────────

    def _read_ip5306(self) -> int:
        try:
            data = self._bus.read_byte_data(0x75, 0x78)
            # bits [4:3] = 2-bit battery level indicator
            level_bits = (data >> 3) & 0x03
            return [12, 37, 62, 87][level_bits]   # mid-point of each 25% band
        except Exception as e:
            log.debug("IP5306 read error: %s", e)
            return -1

    def _ip5306_charging(self) -> Optional[bool]:
        try:
            data = self._bus.read_byte_data(0x75, 0x71)
            return bool(data & 0x08)   # bit 3 = charging
        except Exception:
            return None

    # ── MAX17040 (0x36) ───────────────────────────────────────────────────────

    def _read_max17040(self) -> int:
        try:
            # Register 0x04 — SOC MSB = integer %, LSB = 1/256 fraction
            raw = self._bus.read_word_data(0x36, 0x04)
            # word is big-endian on the wire; smbus2 byte-swaps on read
            msb = raw & 0xFF
            return min(100, max(0, msb))
        except Exception as e:
            log.debug("MAX17040 read error: %s", e)
            return -1

    # ── /sys fallback ─────────────────────────────────────────────────────────

    def _read_sysfs(self) -> int:
        try:
            base = "/sys/class/power_supply"
            for name in os.listdir(base):
                cap_file = os.path.join(base, name, "capacity")
                if os.path.exists(cap_file):
                    with open(cap_file) as f:
                        return int(f.read().strip())
        except Exception:
            pass
        return -1

    def _sysfs_charging(self) -> Optional[bool]:
        try:
            base = "/sys/class/power_supply"
            for name in os.listdir(base):
                status_file = os.path.join(base, name, "status")
                if os.path.exists(status_file):
                    with open(status_file) as f:
                        return f.read().strip().lower() == "charging"
        except Exception:
            pass
        return None
