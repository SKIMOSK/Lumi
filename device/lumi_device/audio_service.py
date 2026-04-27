"""Audio service — mic input with energy-based VAD, STT via Google, speaker output.

Uses:
  sounddevice  — cross-platform audio I/O (wraps PortAudio, works with USB mic/speaker)
  SpeechRecognition — Google Web STT (requires internet on the Pi)
  espeak-ng    — system TTS for device-side speech output (apt package, no pip)

VAD: simple RMS energy threshold — no C extensions required on ARM.
"""
import asyncio
import logging
import queue
import subprocess
import threading
from typing import Callable, Optional

import numpy as np
import sounddevice as sd
import speech_recognition as sr

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000
CHANNELS = 1
FRAME_MS = 30
FRAME_SAMPLES = int(SAMPLE_RATE * FRAME_MS / 1000)  # 480 samples

# How many silent frames in a row mark end-of-speech (default ~900 ms)
_SILENCE_FRAMES_DEFAULT = 30  # × 30 ms = 900 ms


class AudioService:
    def __init__(self, config: dict, loop: asyncio.AbstractEventLoop):
        self._loop = loop
        self._mic_index: Optional[int] = config.get("mic_index")
        self._spk_index: Optional[int] = config.get("speaker_index")
        self._energy_threshold: int = config.get("vad_energy_threshold", 300)
        silence_ms: int = config.get("vad_silence_ms", 900)
        self._silence_frames = max(1, silence_ms // FRAME_MS)
        self._language: str = config.get("stt_language", "en-US")

        self._recognizer = sr.Recognizer()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._frame_queue: queue.Queue = queue.Queue()

        # Set by main.py — called with recognized text string
        self.on_speech_recognized: Optional[Callable[[str], None]] = None

    # ── Playback ────────────────────────────────────────────────────────────────

    def speak(self, text: str):
        """Speak text via espeak-ng (system TTS, non-blocking)."""
        if not text.strip():
            return
        try:
            subprocess.Popen(
                ["espeak-ng", "-s", "155", "-p", "50", text],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            )
            log.info("Speaking: %s", text[:60])
        except FileNotFoundError:
            log.warning("espeak-ng not found — install with: sudo apt-get install espeak-ng")

    def stop_speaking(self):
        subprocess.run(["pkill", "-f", "espeak-ng"], capture_output=True)

    # ── Recording ───────────────────────────────────────────────────────────────

    def start(self):
        self._running = True
        self._thread = threading.Thread(target=self._record_loop, daemon=True)
        self._thread.start()
        log.info("Audio service started (mic index=%s, threshold=%d, language=%s)",
                 self._mic_index, self._energy_threshold, self._language)

    def stop(self):
        self._running = False

    def _record_loop(self):
        """Continuously record from mic, detect speech, run STT, fire callback."""
        speech_buf = []
        silent_count = 0
        in_speech = False

        def audio_callback(indata, frames, time_info, status):
            if status:
                log.debug("Audio status: %s", status)
            self._frame_queue.put(indata.copy())

        try:
            stream_kwargs = dict(
                samplerate=SAMPLE_RATE,
                channels=CHANNELS,
                dtype="int16",
                blocksize=FRAME_SAMPLES,
                callback=audio_callback,
            )
            if self._mic_index is not None:
                stream_kwargs["device"] = self._mic_index

            with sd.InputStream(**stream_kwargs):
                log.info("Mic stream open")
                while self._running:
                    try:
                        frame = self._frame_queue.get(timeout=0.5)
                    except queue.Empty:
                        continue

                    rms = int(np.sqrt(np.mean(frame.astype(np.float32) ** 2)))
                    is_loud = rms > self._energy_threshold

                    if is_loud:
                        in_speech = True
                        silent_count = 0
                        speech_buf.append(frame)
                    elif in_speech:
                        speech_buf.append(frame)
                        silent_count += 1
                        if silent_count >= self._silence_frames:
                            # End of utterance — run STT
                            audio_data = np.concatenate(speech_buf, axis=0)
                            speech_buf.clear()
                            silent_count = 0
                            in_speech = False
                            self._run_stt(audio_data)
        except Exception as e:
            log.error("Record loop error: %s", e)

    def _run_stt(self, pcm: np.ndarray):
        """Run STT in a thread-pool thread and fire the callback on the asyncio loop."""
        raw = pcm.tobytes()
        audio = sr.AudioData(raw, SAMPLE_RATE, 2)  # 2 bytes = int16
        try:
            text = self._recognizer.recognize_google(audio, language=self._language)
            if text.strip():
                log.info("STT: %s", text)
                if self.on_speech_recognized:
                    asyncio.run_coroutine_threadsafe(
                        self._fire_speech(text), self._loop
                    )
        except sr.UnknownValueError:
            log.debug("STT: speech not understood")
        except sr.RequestError as e:
            log.warning("STT request error: %s", e)

    async def _fire_speech(self, text: str):
        if self.on_speech_recognized:
            await asyncio.get_event_loop().run_in_executor(
                None, self.on_speech_recognized, text
            )
