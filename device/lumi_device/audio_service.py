"""Audio service — mic input with energy-based VAD, STT via Google, speaker output.

Uses:
  sounddevice  — cross-platform audio I/O (wraps PortAudio, works with USB mic/speaker)
  SpeechRecognition — Google Web STT (requires internet on the Pi)
  espeak-ng    — system TTS for device-side speech output (apt package, no pip)

VAD: simple RMS energy threshold — no C extensions required on ARM.
"""
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

STT_TIMEOUT_SECS = 10


class AudioService:
    def __init__(self, config: dict):
        self._mic_index: Optional[int] = config.get("mic_index")
        self._spk_index: Optional[int] = config.get("speaker_index")
        self._energy_threshold: int = config.get("vad_energy_threshold", 300)
        silence_ms: int = config.get("vad_silence_ms", 900)
        self._silence_frames = max(1, silence_ms // FRAME_MS)
        self._language: str = config.get("stt_language", "en-US")

        self._recognizer = sr.Recognizer()
        self._running = False
        self._listening_enabled = False
        self._thread: Optional[threading.Thread] = None
        # Bounded queue — drop oldest frames if Pi is too slow to process (prevents RAM growth)
        self._frame_queue: queue.Queue = queue.Queue(maxsize=200)

        # Set by main.py — called with recognized text string (sync, thread-safe)
        self.on_speech_recognized: Optional[Callable[[str], None]] = None

    # ── Playback ─────────────────────────────────────────────────────────────

    def speak(self, text: str):
        """Speak text via espeak-ng (system TTS, non-blocking)."""
        if not text.strip():
            return
        subprocess.Popen(
            ["espeak-ng", "-s", "155", "-p", "50", "--", text],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        log.info("Speaking: %s", text[:60])

    def stop_speaking(self):
        subprocess.run(["pkill", "-f", "espeak-ng"], capture_output=True)

    # ── Listening control ────────────────────────────────────────────────────

    def start(self):
        """Start the mic thread (opens the audio stream)."""
        if self._thread and self._thread.is_alive():
            return
        self._running = True
        self._listening_enabled = False  # wait for enable_listening()
        self._thread = threading.Thread(target=self._record_loop, daemon=True)
        self._thread.start()
        log.info("Audio service started (mic=%s, threshold=%d, lang=%s)",
                 self._mic_index, self._energy_threshold, self._language)

    def enable_listening(self):
        """Allow VAD to fire after phone sends CMD_READY."""
        self._listening_enabled = True
        log.info("Listening enabled")

    def disable_listening(self):
        """Pause VAD (e.g. while phone is disconnected)."""
        self._listening_enabled = False
        log.info("Listening disabled")

    def stop(self):
        self._running = False
        self._listening_enabled = False

    # ── Recording ────────────────────────────────────────────────────────────

    def _record_loop(self):
        speech_buf = []
        silent_count = 0
        in_speech = False

        def audio_callback(indata, frames, time_info, status):
            if not self._listening_enabled:
                return
            if status:
                log.debug("Audio status: %s", status)
            try:
                self._frame_queue.put_nowait(indata.copy())
            except queue.Full:
                # Drop oldest frame to make room
                try:
                    self._frame_queue.get_nowait()
                    self._frame_queue.put_nowait(indata.copy())
                except queue.Empty:
                    pass

        stream_kwargs = dict(
            samplerate=SAMPLE_RATE,
            channels=CHANNELS,
            dtype="int16",
            blocksize=FRAME_SAMPLES,
            callback=audio_callback,
        )
        if self._mic_index is not None:
            stream_kwargs["device"] = self._mic_index

        try:
            with sd.InputStream(**stream_kwargs):
                log.info("Mic stream open")
                while self._running:
                    try:
                        frame = self._frame_queue.get(timeout=0.5)
                    except queue.Empty:
                        continue

                    if not self._listening_enabled:
                        speech_buf.clear()
                        in_speech = False
                        silent_count = 0
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
                            audio_data = np.concatenate(speech_buf, axis=0)
                            speech_buf.clear()
                            silent_count = 0
                            in_speech = False
                            # Run STT in a separate thread so we don't block the mic
                            threading.Thread(
                                target=self._run_stt, args=(audio_data,), daemon=True
                            ).start()
        except Exception as e:
            log.error("Record loop error: %s", e)

    def _run_stt(self, pcm: np.ndarray):
        """Run STT synchronously in a thread and fire callback directly."""
        raw = pcm.tobytes()
        audio = sr.AudioData(raw, SAMPLE_RATE, 2)  # 2 bytes = int16
        try:
            text = self._recognizer.recognize_google(
                audio, language=self._language, show_all=False
            )
            if text.strip():
                log.info("STT: %s", text)
                cb = self.on_speech_recognized
                if cb:
                    cb(text)  # caller (_sync_speech_cb in main.py) handles thread-safe dispatch
        except sr.UnknownValueError:
            log.debug("STT: speech not understood")
        except sr.RequestError as e:
            log.warning("STT request failed: %s", e)
        except Exception as e:
            log.warning("STT error: %s", e)
