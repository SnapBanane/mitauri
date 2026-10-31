"""Speech recognition and voices, running locally so they stay free and private."""

import io
import logging
import os
import threading
import urllib.request
import wave
from pathlib import Path

import numpy as np

VOICE_ROOT = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
VOICES = {
    "de": ("de/de_DE/thorsten/high", "de_DE-thorsten-high"),
    "en": ("en/en_US/ryan/high", "en_US-ryan-high"),
}
SAMPLE_RATE = 16_000
CONFIDENT_LANGUAGE = 0.8
WHISPER_THREADS = int(os.environ.get("MITAURI_WHISPER_THREADS", min(8, os.cpu_count() or 4)))

log = logging.getLogger(__name__)


def choose_language(language: str, confidence: float, scores: dict[str, float]) -> str:
    """Short clips can fool detection, so unless it's sure, pick between the languages with voices."""
    if confidence >= CONFIDENT_LANGUAGE:
        return language
    return max(VOICES, key=lambda known: scores.get(known, 0))


class Speech:
    def __init__(self, data_dir: Path):
        self._voice_dir = data_dir / "voices"
        self._lock = threading.Lock()
        self._whisper = None
        self._detector = None
        self._voices = {}

    def preload(self) -> None:
        """Loads in the background, so the first request doesn't wait for it."""
        threading.Thread(target=self._preload, daemon=True).start()

    def _preload(self) -> None:
        try:
            with self._lock:
                self._load_whisper()
                if os.environ.get("MITAURI_SPEECH_LANGUAGE") == "auto":
                    self._load_detector()
                for language in VOICES:
                    self._load_voice(language)
            log.info("Speech models ready")
        except Exception:
            log.exception("Couldn't load the speech models; will retry on first use")

    def transcribe(self, pcm: bytes) -> tuple[str, str]:
        """Takes 16 kHz mono 16-bit audio and returns the text and its language."""
        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768
        with self._lock:
            language = os.environ.get("MITAURI_SPEECH_LANGUAGE", "de")
            if language == "auto":
                language = self._detect_language(audio)
            segments, _ = self._load_whisper().transcribe(
                audio, language=language, beam_size=1, vad_filter=True,
                condition_on_previous_text=False, without_timestamps=True,
            )
            return " ".join(s.text.strip() for s in segments).strip(), language

    def _detect_language(self, audio: np.ndarray) -> str:
        """Uses the tiny model, because detecting with the main one adds over a second."""
        language, confidence, ranked = self._load_detector().detect_language(audio)
        return choose_language(language, confidence, dict(ranked))

    def synthesize(self, text: str, language: str) -> bytes:
        """Returns WAV audio, using the English voice for languages without their own."""
        buffer = io.BytesIO()
        with self._lock:
            voice = self._load_voice(language if language in VOICES else "en")
            with wave.open(buffer, "wb") as output:
                voice.synthesize_wav(text, output)
        return buffer.getvalue()

    def _load_whisper(self):
        if self._whisper is None:
            from faster_whisper import WhisperModel
            size = os.environ.get("MITAURI_WHISPER_MODEL", "small")
            self._whisper = WhisperModel(size, device="cpu", compute_type="int8", cpu_threads=WHISPER_THREADS)
        return self._whisper

    def _load_detector(self):
        if self._detector is None:
            from faster_whisper import WhisperModel
            self._detector = WhisperModel("tiny", device="cpu", compute_type="int8")
        return self._detector

    def _load_voice(self, language: str):
        if language not in self._voices:
            from piper import PiperVoice
            folder, name = VOICES[language]
            self._voice_dir.mkdir(exist_ok=True)
            for suffix in (".onnx", ".onnx.json"):
                path = self._voice_dir / f"{name}{suffix}"
                if not path.exists():
                    log.info("Downloading %s", path.name)
                    temporary = path.with_suffix(path.suffix + ".part")
                    urllib.request.urlretrieve(f"{VOICE_ROOT}/{folder}/{name}{suffix}", temporary)
                    temporary.rename(path)
            self._voices[language] = PiperVoice.load(str(self._voice_dir / f"{name}.onnx"))
        return self._voices[language]
