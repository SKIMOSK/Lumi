"""BLE GATT peripheral — mirrors the protocol expected by LumiBluetoothManager.kt.

Service/characteristic UUIDs must match exactly what the Android app expects.
"""
import asyncio
import json
import logging
import time
from typing import Callable, Optional

log = logging.getLogger(__name__)

SERVICE_UUID            = "12345678-1234-1234-1234-123456789abc"
AUDIO_CHAR_UUID         = "12345678-1234-1234-1234-123456789ab1"
IMAGE_CHAR_UUID         = "12345678-1234-1234-1234-123456789ab2"
CMD_CHAR_UUID           = "12345678-1234-1234-1234-123456789ab3"
STATUS_CHAR_UUID        = "12345678-1234-1234-1234-123456789ab4"
TTS_TEXT_CHAR_UUID      = "12345678-1234-1234-1234-123456789ab5"
DEVICE_INFO_CHAR_UUID   = "12345678-1234-1234-1234-123456789ab6"
SPEECH_TEXT_CHAR_UUID   = "12345678-1234-1234-1234-123456789ab7"
AUDIO_RECORD_CHAR_UUID  = "12345678-1234-1234-1234-123456789ab8"  # recorded WAV (Pi→Phone)

CMD_SPEAK       = 0x01
CMD_STOP        = 0x02
CMD_READY       = 0x03
CMD_SETUP_FP    = 0x04
CMD_FP_ON       = 0x05
CMD_FP_OFF      = 0x06

# Safe BLE write size — Pi Zero 2W CYW43438 negotiates ~244-byte PDU;
# 3 bytes ATT header + 1 byte chunk prefix = 240 bytes payload.
BLE_CHUNK = 240

# TTS buffer cleared if no new chunk arrives within this many seconds.
TTS_BUF_STALE_SECS = 10.0


class LumiBleServer:
    def __init__(self, config: dict, loop: asyncio.AbstractEventLoop):
        self._config = config
        self._loop = loop
        self._server = None
        self._connected = False

        self._tts_buf = bytearray()
        self._tts_last_chunk_time: float = 0.0

        # Callbacks wired by main.py
        self.on_tts_text: Optional[Callable[[str], None]] = None
        self.on_cmd_ready: Optional[Callable[[], None]] = None
        self.on_setup_fingerprint: Optional[Callable[[], None]] = None
        self.on_fingerprint_setting: Optional[Callable[[bool], None]] = None
        self.on_client_connected: Optional[Callable[[], None]] = None
        self.on_client_disconnected: Optional[Callable[[], None]] = None

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

        # Audio (Pi → Phone) — notify only
        await self._server.add_new_characteristic(
            SERVICE_UUID, AUDIO_CHAR_UUID,
            Props.notify, None, Perms.readable)

        # Image (Pi → Phone) — notify only
        await self._server.add_new_characteristic(
            SERVICE_UUID, IMAGE_CHAR_UUID,
            Props.notify, None, Perms.readable)

        # CMD (Phone → Pi) — write + write-no-response
        await self._server.add_new_characteristic(
            SERVICE_UUID, CMD_CHAR_UUID,
            Props.write | Props.write_without_response,
            None, Perms.writeable)

        # Status (read by Phone)
        await self._server.add_new_characteristic(
            SERVICE_UUID, STATUS_CHAR_UUID,
            Props.read, bytearray(b'\x00'), Perms.readable)

        # TTS Text (Phone → Pi) — write + write-no-response
        await self._server.add_new_characteristic(
            SERVICE_UUID, TTS_TEXT_CHAR_UUID,
            Props.write | Props.write_without_response,
            None, Perms.writeable)

        # Device Info (read by Phone) — JSON metadata
        info_bytes = json.dumps({
            "device_id": self._config.get("device_id", "lumi-device"),
            "color_theme": self._config.get("color_theme", "grey"),
            "fingerprint_enabled": self._config.get("fingerprint_enabled", False),
        }).encode()
        await self._server.add_new_characteristic(
            SERVICE_UUID, DEVICE_INFO_CHAR_UUID,
            Props.read, bytearray(info_bytes), Perms.readable)

        # Speech Text (Pi → Phone) — notify only
        await self._server.add_new_characteristic(
            SERVICE_UUID, SPEECH_TEXT_CHAR_UUID,
            Props.notify, None, Perms.readable)

        # Audio Recording (Pi → Phone) — WAV chunks, same protocol as image
        await self._server.add_new_characteristic(
            SERVICE_UUID, AUDIO_RECORD_CHAR_UUID,
            Props.notify, None, Perms.readable)

        await self._server.start()
        log.info("BLE server started — advertising as 'Lumi'")

    async def stop(self):
        if self._server:
            await self._server.stop()
            log.info("BLE server stopped")

    # ── State ───────────────────────────────────────────────────────────────────

    @property
    def connected(self) -> bool:
        return self._connected

    def _mark_connected(self):
        if not self._connected:
            self._connected = True
            log.info("Client connected")
            if self.on_client_connected:
                self._loop.call_soon_threadsafe(self.on_client_connected)

    def _mark_disconnected(self):
        if self._connected:
            self._connected = False
            self._reset_tts_buf()
            log.info("Client disconnected")
            if self.on_client_disconnected:
                self._loop.call_soon_threadsafe(self.on_client_disconnected)

    def _reset_tts_buf(self):
        self._tts_buf.clear()
        self._tts_last_chunk_time = 0.0

    # ── GATT callbacks ──────────────────────────────────────────────────────────

    def _on_read(self, characteristic, **kwargs):
        return characteristic.value

    def _on_write(self, characteristic, value):
        uuid = str(characteristic.uuid).lower()
        data = bytes(value) if value is not None else b""
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
            self._mark_connected()
            if self.on_cmd_ready:
                self._loop.call_soon_threadsafe(self.on_cmd_ready)
        elif cmd == CMD_STOP:
            log.info("Stop command")
        elif cmd == CMD_SETUP_FP:
            log.info("Fingerprint setup requested")
            if self.on_setup_fingerprint:
                self._loop.call_soon_threadsafe(self.on_setup_fingerprint)
        elif cmd == CMD_FP_ON:
            log.info("Fingerprint enabled")
            if self.on_fingerprint_setting:
                self._loop.call_soon_threadsafe(self.on_fingerprint_setting, True)
        elif cmd == CMD_FP_OFF:
            log.info("Fingerprint disabled")
            if self.on_fingerprint_setting:
                self._loop.call_soon_threadsafe(self.on_fingerprint_setting, False)

    def _handle_tts_chunk(self, data: bytes):
        if not data:
            return
        now = time.monotonic()
        # Clear stale buffer — phone may have disconnected mid-send
        if self._tts_last_chunk_time and (now - self._tts_last_chunk_time) > TTS_BUF_STALE_SECS:
            log.warning("TTS buf stale (%.1fs) — clearing", now - self._tts_last_chunk_time)
            self._reset_tts_buf()

        flag, payload = data[0], data[1:]
        self._tts_buf.extend(payload)
        self._tts_last_chunk_time = now

        if flag == 0x01:  # last (or only) chunk — speak now
            text = self._tts_buf.decode("utf-8", errors="replace").strip()
            self._reset_tts_buf()
            if text:
                log.info("TTS text received: %s", text[:80])
                if self.on_tts_text:
                    self._loop.call_soon_threadsafe(self.on_tts_text, text)

    # ── Outgoing notifications ──────────────────────────────────────────────────

    async def send_speech_text(self, text: str):
        """Notify the phone with recognized speech text."""
        if not self._server:
            return
        char = self._server.get_characteristic(SPEECH_TEXT_CHAR_UUID)
        if not char:
            log.warning("SPEECH_TEXT_CHAR not found on server")
            return
        payload = text.encode("utf-8")[:BLE_CHUNK]
        char.value = bytearray(payload)
        try:
            self._server.update_value(SERVICE_UUID, SPEECH_TEXT_CHAR_UUID)
            log.debug("Sent speech text (%d bytes)", len(payload))
        except Exception as e:
            log.debug("Speech text notify failed (no client?): %s", e)

    async def send_image(self, jpeg_data: bytes):
        """Send a JPEG image to the phone using the chunk protocol."""
        if not self._server or not self._connected:
            return
        char = self._server.get_characteristic(IMAGE_CHAR_UUID)
        if not char:
            return

        chunks = [jpeg_data[i:i + BLE_CHUNK] for i in range(0, len(jpeg_data), BLE_CHUNK)]
        if len(chunks) >= 0xFF:
            log.warning("Image too large (%d chunks) — resizing would help", len(chunks))
            chunks = chunks[:0xFE]

        for idx, chunk in enumerate(chunks):
            char.value = bytearray([idx & 0xFF]) + bytearray(chunk)
            try:
                self._server.update_value(SERVICE_UUID, IMAGE_CHAR_UUID)
            except Exception as e:
                log.debug("Image chunk notify failed: %s", e)
                return
            await asyncio.sleep(0.012)

        # End-of-image sentinel: 0xFF + total size (4 bytes LE)
        total = len(jpeg_data).to_bytes(4, "little")
        char.value = bytearray(b"\xFF") + bytearray(total)
        try:
            self._server.update_value(SERVICE_UUID, IMAGE_CHAR_UUID)
        except Exception as e:
            log.debug("Image sentinel notify failed: %s", e)
        log.debug("Image sent (%d bytes, %d chunks)", len(jpeg_data), len(chunks))

    async def send_recorded_audio(self, wav_data: bytes):
        """Send a WAV recording to the phone using the same chunk protocol as images."""
        if not self._server or not self._connected:
            return
        char = self._server.get_characteristic(AUDIO_RECORD_CHAR_UUID)
        if not char:
            return

        chunks = [wav_data[i:i + BLE_CHUNK] for i in range(0, len(wav_data), BLE_CHUNK)]
        if len(chunks) >= 0xFF:
            chunks = chunks[:0xFE]

        for idx, chunk in enumerate(chunks):
            char.value = bytearray([idx & 0xFF]) + bytearray(chunk)
            try:
                self._server.update_value(SERVICE_UUID, AUDIO_RECORD_CHAR_UUID)
            except Exception as e:
                log.debug("Audio record chunk notify failed: %s", e)
                return
            await asyncio.sleep(0.012)

        total = len(wav_data).to_bytes(4, "little")
        char.value = bytearray(b"\xFF") + bytearray(total)
        try:
            self._server.update_value(SERVICE_UUID, AUDIO_RECORD_CHAR_UUID)
        except Exception as e:
            log.debug("Audio record sentinel notify failed: %s", e)
        log.debug("Recorded audio sent (%d bytes, %d chunks)", len(wav_data), len(chunks))

    def set_status(self, status_byte: int):
        if self._server:
            char = self._server.get_characteristic(STATUS_CHAR_UUID)
            if char:
                char.value = bytearray([status_byte & 0xFF])
