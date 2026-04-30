"""Lumi device main — orchestrates BLE, audio, camera, and peripheral services."""
import asyncio
import logging
import signal

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
    audio       = AudioService(config)        # no loop dependency
    ble         = LumiBleServer(config, loop)
    button      = ButtonService(config)

    # ── BLE → audio lifecycle ─────────────────────────────────────────────────

    def on_cmd_ready():
        """Phone sent CMD_READY — start (or resume) listening."""
        audio.enable_listening()
        audio.speak("Lumi connected")

    def on_client_disconnected():
        """Phone disconnected — pause listening until reconnect."""
        audio.disable_listening()
        log.info("Phone disconnected — listening paused")

    def on_tts_text(text: str):
        audio.stop_speaking()   # stop any ongoing speech first
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

    # ── Speech recognized → send to phone ────────────────────────────────────

    async def on_speech_async(text: str):
        if not ble.connected:
            log.debug("Speech ignored — phone not connected")
            return

        # Fingerprint gate: must have scanned within last 10 s
        if config.get("fingerprint_enabled") and not fingerprint.is_authenticated_for_trigger():
            log.info("Speech blocked — fingerprint required")
            audio.speak("Please scan your fingerprint first")
            return

        log.info("Sending to phone: %s", text)
        vibration.vibrate(100)

        # Capture image with the voice command for visual AI context
        jpeg = await loop.run_in_executor(None, camera.capture_jpeg)
        if jpeg:
            await ble.send_image(jpeg)

        await ble.send_speech_text(text)

    # Bridge: STT thread → asyncio coroutine
    def _speech_cb(text: str):
        asyncio.run_coroutine_threadsafe(on_speech_async(text), loop)

    audio.on_speech_recognized = _speech_cb

    # ── Task button ───────────────────────────────────────────────────────────

    def on_task_button():
        """Physical task button pressed — stop current speech and signal ready."""
        if not ble.connected:
            audio.speak("Not connected to phone")
            return
        audio.stop_speaking()
        vibration.vibrate(60)
        audio.speak("Go ahead")   # brief cue: user can now speak

    button.on_button_pressed = on_task_button
    button.start()

    # ── Fall detection ────────────────────────────────────────────────────────

    def on_fall():
        vibration.vibrate(500)
        audio.speak("Fall detected. Are you okay?")

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

    audio.start()   # opens mic stream; listening gated by enable_listening()
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
