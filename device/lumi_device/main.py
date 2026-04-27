"""Lumi device main — orchestrates BLE, audio, camera, and peripheral services."""
import asyncio
import logging
import signal
import sys

from . import config as cfg_module
from .audio_service import AudioService
from .ble_server import LumiBleServer
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


async def run():
    config = cfg_module.load()
    log.info("Starting Lumi device '%s' (theme: %s)",
             config["device_id"], config["color_theme"])

    loop = asyncio.get_running_loop()

    # ── Services ─────────────────────────────────────────────────────────────
    camera      = CameraService(config)
    vibration   = VibrationService(config)
    fall_det    = FallDetector(config)
    fingerprint = FingerprintService(config)
    audio       = AudioService(config, loop)
    ble         = LumiBleServer(config, loop)

    # ── BLE callbacks ────────────────────────────────────────────────────────

    def on_tts_text(text: str):
        audio.speak(text)
        vibration.vibrate(80)

    def on_cmd_ready():
        log.info("Phone connected — starting audio listener")
        audio.start()
        audio.speak("Lumi connected")

    def on_setup_fingerprint():
        fingerprint.enroll_fingerprint()

    def on_fingerprint_setting(enabled: bool):
        fingerprint.set_enabled(enabled)
        config["fingerprint_enabled"] = enabled
        cfg_module.save(config)
        log.info("Fingerprint %s", "enabled" if enabled else "disabled")

    ble.on_tts_text           = on_tts_text
    ble.on_cmd_ready          = on_cmd_ready
    ble.on_setup_fingerprint  = on_setup_fingerprint
    ble.on_fingerprint_setting = on_fingerprint_setting

    # ── Audio callback ───────────────────────────────────────────────────────

    async def on_speech_recognized(text: str):
        # Fingerprint gate: if enabled, user must have scanned within last 10s
        if config.get("fingerprint_enabled") and not fingerprint.is_authenticated_for_trigger():
            log.info("Speech ignored — fingerprint not authenticated")
            audio.speak("Please scan your fingerprint first")
            return

        log.info("Processing speech: %s", text)
        vibration.vibrate(100)

        # Capture image alongside the voice command for visual context
        jpeg = await loop.run_in_executor(None, camera.capture_jpeg)
        if jpeg:
            await ble.send_image(jpeg)
            log.debug("Image sent with voice command")

        # Send recognized text to phone
        await ble.send_speech_text(text)

    # Wire audio callback (bridge thread → coroutine)
    def _sync_speech_cb(text: str):
        asyncio.run_coroutine_threadsafe(on_speech_recognized(text), loop)

    audio.on_speech_recognized = _sync_speech_cb

    # ── Fall detection callback ───────────────────────────────────────────────

    def on_fall():
        vibration.vibrate(500)
        audio.speak("Fall detected. Are you okay?")
        # Could also send an alert to the phone here via BLE status

    fall_det.on_fall = on_fall
    fall_det.start()

    # ── Shutdown ─────────────────────────────────────────────────────────────

    stop_event = asyncio.Event()

    def _signal_handler():
        log.info("Shutdown signal received")
        stop_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, _signal_handler)

    # ── Start BLE server ─────────────────────────────────────────────────────
    await ble.start()
    audio.speak("Lumi ready")

    log.info("Waiting for phone connection...")
    await stop_event.wait()

    # ── Cleanup ───────────────────────────────────────────────────────────────
    audio.stop()
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
