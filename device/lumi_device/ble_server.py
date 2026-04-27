"""BLE GATT peripheral — mirrors the protocol expected by LumiBluetoothManager.kt.

Service/characteristic UUIDs must match exactly what the Android app expects.
"""
import asyncio
import json
import logging
from typing import Callable, Optional

log = logging.getLogger(__name__)

SERVICE_UUID          = "12345678-1234-1234-1234-123456789abc"
AUDIO_CHAR_UUID       = "12345678-1234-1234-1234-123456789ab1"
IMAGE_CHAR_UUID       = "12345678-1234-1234-1234-123456789ab2"
CMD_CHAR_UUID         = "12345678-1234-1234-1234-123456789ab3"
STATUS_CHAR_UUID      = "12345678-1234-1234-1234-123456789ab4"
TTS_TEXT_CHAR_UUID    = "12345678-1234-1234-1234-123456789ab5"
DEVICE_INFO_CHAR_UUID = "12345678-1234-1234-1234-123456789ab6"
SPEECH_TEXT_CHAR_UUID = "12345678-1234-1234-1234-123456789ab7"

CMD_SPEAK       = 0x01
CMD_STOP        = 0x02
CMD_READY       = 0x03
CMD_SETUP_FP    = 0x04
CMD_FP_ON       = 0x05
CMD_FP_OFF      = 0x06

# Safe BLE write size — Pi Zero 2W CYW43438 negotiates ~244 byte PDU,
# 3 bytes ATT header, 1 byte chunk prefix = 240 bytes of payload.
BLE_CHUNK = 240


class LumiBleServer:
    def __init__(self, config: dict, loop: asyncio.AbstractEventLoop):
        self._config = config
        self._loop = loop
        self._server = None
        self._tts_buf = bytearray()

        # Callbacks wired by main.py
        self.on_tts_text: Optional[Callable[[str], None]] = None
        self.on_cmd_ready: Optional[Callable[[], None]] = None
        self.on_setup_fingerprint: Optional[Callable[[], None]] = None
        self.on_fingerprint_setting: Optional[Callable[[bool], None]] = None

    # ── Lifecycle ───────────────────────────────────────────────────────────────

    async def start(self):
        from bless import (
            BlessServer,
            BlessGATTCharacteristicProperties as Props,
            BlessGATTCharacteristicPermissions as Perms,
        )

        self._server = BlessServer(name="Lumi", loop=self._loop)
        self._server.read_request_func = self._on_read
        self._server.write_request_func = self._on_write

        await self._server.add_new_service(SERVICE_UUID)

        # Audio (Pi → Phone) — notify
        await self._server.add_new_characteristic(
            SERVICE_UUID, AUDIO_CHAR_UUID,
            Props.notify, None, Perms.readable)

        # Image (Pi → Phone) — notify
        await self._server.add_new_characteristic(
            SERVICE_UUID, IMAGE_CHAR_UUID,
            Props.notify, None, Perms.readable)

        # CMD (Phone → Pi) — write
        await self._server.add_new_characteristic(
            SERVICE_UUID, CMD_CHAR_UUID,
            Props.write | Props.write_without_response,
            None, Perms.writeable)

        # Status (read by Phone)
        await self._server.add_new_characteristic(
            SERVICE_UUID, STATUS_CHAR_UUID,
            Props.read, bytearray(b'\x00'), Perms.readable)

        # TTS Text (Phone → Pi) — write
        await self._server.add_new_characteristic(
            SERVICE_UUID, TTS_TEXT_CHAR_UUID,
            Props.write | Props.write_without_response,
            None, Perms.writeable)

        # Device Info (read by Phone)
        info_bytes = json.dumps({
            "device_id": self._config.get("device_id", "lumi-device"),
            "color_theme": self._config.get("color_theme", "grey"),
            "fingerprint_enabled": self._config.get("fingerprint_enabled", False),
        }).encode()
        await self._server.add_new_characteristic(
            SERVICE_UUID, DEVICE_INFO_CHAR_UUID,
            Props.read, bytearray(info_bytes), Perms.readable)

        # Speech Text (Pi → Phone) — notify
        await self._server.add_new_characteristic(
            SERVICE_UUID, SPEECH_TEXT_CHAR_UUID,
            Props.notify, None, Perms.readable)

        await self._server.start()
        log.info("BLE server started — advertising as 'Lumi'")

    async def stop(self):
        if self._server:
            await self._server.stop()
            log.info("BLE server stopped")

    # ── GATT callbacks ──────────────────────────────────────────────────────────

    def _on_read(self, characteristic, **kwargs):
        return characteristic.value

    def _on_write(self, characteristic, value):
        uuid = str(characteristic.uuid).lower()
        data = bytes(value)
        if uuid == CMD_CHAR_UUID.lower():
            self._handle_cmd(data)
        elif uuid == TTS_TEXT_CHAR_UUID.lower():
            self._handle_tts_chunk(data)

    def _handle_cmd(self, data: bytes):
        if not data:
            return
        cmd = data[0]
        log.debug("CMD received: 0x%02x", cmd)
        if cmd == CMD_READY:
            log.info("Phone is ready")
            if self.on_cmd_ready:
                self._loop.call_soon_threadsafe(self.on_cmd_ready)
        elif cmd == CMD_SETUP_FP:
            if self.on_setup_fingerprint:
                self._loop.call_soon_threadsafe(self.on_setup_fingerprint)
        elif cmd == CMD_FP_ON:
            if self.on_fingerprint_setting:
                self._loop.call_soon_threadsafe(self.on_fingerprint_setting, True)
        elif cmd == CMD_FP_OFF:
            if self.on_fingerprint_setting:
                self._loop.call_soon_threadsafe(self.on_fingerprint_setting, False)

    def _handle_tts_chunk(self, data: bytes):
        if not data:
            return
        flag, payload = data[0], data[1:]
        self._tts_buf.extend(payload)
        if flag == 0x01:  # last (or only) chunk — speak now
            text = self._tts_buf.decode("utf-8", errors="replace").strip()
            self._tts_buf.clear()
            if text:
                log.info("TTS text: %s", text[:80])
                if self.on_tts_text:
                    self._loop.call_soon_threadsafe(self.on_tts_text, text)

    # ── Outgoing notifications ──────────────────────────────────────────────────

    async def send_speech_text(self, text: str):
        """Notify the phone with recognized speech text."""
        if not self._server:
            return
        char = self._server.get_characteristic(SPEECH_TEXT_CHAR_UUID)
        if not char:
            return
        # Truncate to safe MTU; phone only needs the text, not streaming
        payload = text.encode("utf-8")[:BLE_CHUNK]
        char.value = bytearray(payload)
        self._server.update_value(SERVICE_UUID, SPEECH_TEXT_CHAR_UUID)
        log.debug("Sent speech text (%d bytes)", len(payload))

    async def send_image(self, jpeg_data: bytes):
        """Send a JPEG image to the phone using the chunk protocol."""
        if not self._server:
            return
        char = self._server.get_characteristic(IMAGE_CHAR_UUID)
        if not char:
            return

        chunks = [jpeg_data[i:i + BLE_CHUNK] for i in range(0, len(jpeg_data), BLE_CHUNK)]
        if len(chunks) >= 0xFF:
            log.warning("Image too large (%d chunks) — truncating", len(chunks))
            chunks = chunks[:0xFE]

        for idx, chunk in enumerate(chunks):
            char.value = bytearray([idx & 0xFF]) + bytearray(chunk)
            self._server.update_value(SERVICE_UUID, IMAGE_CHAR_UUID)
            await asyncio.sleep(0.012)  # avoid flooding BLE stack

        # End-of-image sentinel: 0xFF + total size (4 bytes LE)
        total = len(jpeg_data).to_bytes(4, "little")
        char.value = bytearray(b"\xFF") + bytearray(total)
        self._server.update_value(SERVICE_UUID, IMAGE_CHAR_UUID)
        log.debug("Image sent (%d bytes, %d chunks)", len(jpeg_data), len(chunks))

    def set_status(self, status_byte: int):
        if self._server:
            char = self._server.get_characteristic(STATUS_CHAR_UUID)
            if char:
                char.value = bytearray([status_byte & 0xFF])
