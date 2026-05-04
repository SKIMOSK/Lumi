"""Lumi device main — orchestrates BLE, audio, camera, and peripheral services.

Button interaction modes (set by physical task button):
  IDLE           — connected, waiting for a button press
  SINGLE_LISTEN  — single click: VAD active, next recognised utterance is sent
  PHOTO_LISTEN   — double click: photo(s) queued, next utterance sent with photos
  HOLD_RECORDING — hold: raw audio buffered; on release STT+WAV sent to phone
"""
import asyncio
import logging
import signal
from enum import Enum, auto

import numpy as np

from . import config as cfg_module
from .audio_service import AudioService
from .ble_server import LumiBleServer
from .button_service import ButtonService
from .camera_service import CameraService
from .fall_detector import FallDetector
from .fingerprint_service import FingerprintService
from .vibration_service import VibrationService

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("lumi.main")


class DeviceMode(Enum):
    IDLE           = auto()
    SINGLE_LISTEN  = auto()
    PHOTO_LISTEN   = auto()
    HOLD_RECORDING = auto()


# ── Image helper (defined at module level to stay out of run()) ───────────────

def _stitch_images(jpegs: list) -> bytes | None:
    """Return a side-by-side JPEG of 1-2 images, or the first on failure."""
    if not jpegs:
        return None
    try:
        import cv2
        imgs = [cv2.imdecode(np.frombuffer(j, np.uint8), cv2.IMREAD_COLOR) for j in jpegs]
        imgs = [i for i in imgs if i is not None]
        if not imgs:
            return None
        combined = np.hstack(imgs) if len(imgs) > 1 else imgs[0]
        _, enc = cv2.imencode(".jpg", combined, [cv2.IMWRITE_JPEG_QUALITY, 60])
        return enc.tobytes()
    except Exception as e:
        log.warning("Image stitch failed: %s", e)
        return jpegs[0] if jpegs else None


async def run():
    config = cfg_module.load()
    log.info("Starting Lumi device '%s' (theme: %s)",
             config["device_id"], config["color_theme"])

    loop = asyncio.get_running_loop()

    # ── Services ──────────────────────────────────────────────────────────────
    camera      = CameraService(config)
    vibration   = VibrationService(config)
    fall_det    = FallDetector(config)
    fingerprint = FingerprintService(config)
    audio       = AudioService(config)
    ble         = LumiBleServer(config, loop)
    button      = ButtonService(config)

    # ── Shared state ──────────────────────────────────────────────────────────
    _mode:         DeviceMode = DeviceMode.IDLE
    _queued_jpegs: list       = []

    def _set_mode(new_mode: DeviceMode):
        nonlocal _mode
        _mode = new_mode
        fall_det.set_paused(new_mode != DeviceMode.IDLE)
        log.info("DeviceMode → %s", new_mode.name)

    async def _capture_jpeg():
        return await loop.run_in_executor(None, camera.capture_jpeg)

    # ── BLE → audio lifecycle ─────────────────────────────────────────────────

    def on_cmd_ready():
        audio.enable_listening()
        audio.speak("Lumi connected")

    def on_client_disconnected():
        audio.disable_listening()
        _set_mode(DeviceMode.IDLE)
        _queued_jpegs.clear()
        log.info("Phone disconnected — listening paused")

    def on_tts_text(text: str):
        audio.stop_speaking()
        audio.speak(text)
        vibration.vibrate(80)

    def on_setup_fingerprint():
        fingerprint.enroll_fingerprint()

    def on_fingerprint_setting(enabled: bool):
        fingerprint.set_enabled(enabled)
        config["fingerprint_enabled"] = enabled
        cfg_module.save(config)
        log.info("Fingerprint %s", "enabled" if enabled else "disabled")

    ble.on_cmd_ready           = on_cmd_ready
    ble.on_client_disconnected = on_client_disconnected
    ble.on_tts_text            = on_tts_text
    ble.on_setup_fingerprint   = on_setup_fingerprint
    ble.on_fingerprint_setting = on_fingerprint_setting

    # ── Speech recognised → forward to phone ─────────────────────────────────

    async def on_speech_async(text: str):
        nonlocal _mode, _queued_jpegs

        if not ble.connected:
            return
        if _mode not in (DeviceMode.SINGLE_LISTEN, DeviceMode.PHOTO_LISTEN):
            log.debug("Speech ignored in mode %s", _mode.name)
            return
        if config.get("fingerprint_enabled") and not fingerprint.is_authenticated_for_trigger():
            audio.speak("Please scan your fingerprint first")
            return

        log.info("Sending speech to phone: %s", text)
        vibration.vibrate(100)

        images = list(_queued_jpegs)
        _queued_jpegs.clear()
        _set_mode(DeviceMode.IDLE)

        for jpeg in images:
            await ble.send_image(jpeg)
        await ble.send_speech_text(text)

    def _speech_cb(text: str):
        asyncio.run_coroutine_threadsafe(on_speech_async(text), loop)

    audio.on_speech_recognized = _speech_cb

    # ── Button interactions ───────────────────────────────────────────────────

    def on_single_click():
        if not ble.connected:
            audio.speak("Not connected")
            return
        if _mode == DeviceMode.IDLE:
            audio.stop_speaking()
            vibration.vibrate(50)
            _set_mode(DeviceMode.SINGLE_LISTEN)
            audio.speak("Listening")
        elif _mode in (DeviceMode.SINGLE_LISTEN, DeviceMode.PHOTO_LISTEN):
            _queued_jpegs.clear()
            _set_mode(DeviceMode.IDLE)
            audio.speak("Cancelled")

    def on_double_click():
        if not ble.connected:
            audio.speak("Not connected")
            return

        async def _double_click_async():
            nonlocal _mode, _queued_jpegs
            audio.stop_speaking()
            vibration.vibrate(60)
            jpeg = await _capture_jpeg()

            if _mode == DeviceMode.PHOTO_LISTEN:
                # Second double-click: add photo and send everything immediately
                if jpeg:
                    _queued_jpegs.append(jpeg)
                images = list(_queued_jpegs)
                _queued_jpegs.clear()
                _set_mode(DeviceMode.IDLE)
                combined = _stitch_images(images)
                if combined:
                    await ble.send_image(combined)
                await ble.send_speech_text("[PHOTO_ANALYSIS]")
                return

            if jpeg:
                _queued_jpegs.clear()
                _queued_jpegs.append(jpeg)
            _set_mode(DeviceMode.PHOTO_LISTEN)
            audio.speak("Photo taken. Listening")

        asyncio.run_coroutine_threadsafe(_double_click_async(), loop)

    def on_hold_start():
        if not ble.connected:
            return
        audio.stop_speaking()
        vibration.vibrate(30)
        _set_mode(DeviceMode.HOLD_RECORDING)
        audio.start_raw_recording()

    def on_hold_release():
        if _mode != DeviceMode.HOLD_RECORDING:
            return

        async def _send_recording_async():
            nonlocal _mode
            wav_bytes = audio.stop_raw_recording()
            _set_mode(DeviceMode.IDLE)
            if not wav_bytes or not ble.connected:
                return
            vibration.vibrate(100)
            pcm_bytes = wav_bytes[44:]  # skip standard 44-byte WAV header
            pcm_array = np.frombuffer(pcm_bytes, dtype=np.int16).reshape(-1, 1)
            stt_text = await loop.run_in_executor(None, audio.recognize_pcm, pcm_array)
            log.info("Hold STT result: %s", stt_text or "(none)")
            await ble.send_recorded_audio(wav_bytes)
            await ble.send_speech_text(stt_text if stt_text else "[SOUND_IDENTIFY]")

        asyncio.run_coroutine_threadsafe(_send_recording_async(), loop)

    button.on_single_click  = on_single_click
    button.on_double_click  = on_double_click
    button.on_hold_start    = on_hold_start
    button.on_hold_release  = on_hold_release
    button.start()

    # ── Fall detection ────────────────────────────────────────────────────────

    def on_fall():
        vibration.vibrate(500)
        audio.speak("Fall detected. Are you okay?")
        if ble.connected:
            asyncio.run_coroutine_threadsafe(
                ble.send_speech_text("[FALL_DETECTED]"), loop
            )

    fall_det.on_fall = on_fall
    fall_det.start()

    # ── Shutdown handling ─────────────────────────────────────────────────────

    stop_event = asyncio.Event()

    def _signal_handler():
        log.info("Shutdown signal received")
        stop_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, _signal_handler)

    # ── Start services ────────────────────────────────────────────────────────

    audio.start()
    await ble.start()
    audio.speak("Lumi ready. Waiting for connection.")
    log.info("Advertising as 'Lumi' — waiting for phone…")

    await stop_event.wait()

    # ── Cleanup ───────────────────────────────────────────────────────────────
    audio.stop()
    button.stop()
    fall_det.stop()
    camera.release()
    vibration.cleanup()
    await ble.stop()
    log.info("Lumi device stopped")


def main():
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
