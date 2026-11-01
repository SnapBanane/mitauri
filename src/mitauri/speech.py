"""Speech recognition and voices. The voices always run locally.

With MITAURI_GROQ_API_KEY set, Groq hears instead, which means recordings leave this machine. Only the part of a
clip with speech in it is sent. Local recognition takes over whenever Groq fails, and stays unloaded until then to
free its memory for the voices.

Without a key, Parakeet hears by default: on this laptop it takes about 0.3 s where Whisper small takes 1.4 s, however
short the clip, because Whisper always processes a full 30 s window. Set MITAURI_SPEECH_ENGINE=whisper to
go back, and Whisper also takes over if Parakeet can't load. Whisper still hears very short clips: Parakeet
guesses the language by itself, so on one or two words it turned "Wecker aus" into "Becca aus" and "Ja" into
"Yeah", and those are the words that stop an alarm or answer a question.
"""

import io
import logging
import os
import threading
from collections import OrderedDict
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import time
import urllib.request
import wave
from pathlib import Path

import httpx
import numpy as np

VOICE_ROOT = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
VOICES = {
    "de": ("de/de_DE/thorsten/high", "de_DE-thorsten-high"),
    "en": ("en/en_US/ryan/high", "en_US-ryan-high"),
}
WAITING = {"de": "Moment.", "en": "One moment."}  # said when Claude goes to a tool without a word
SAMPLE_RATE = 16_000
VOICE_CACHE_SIZE = 64  # phrases like "Moment, ich denke kurz nach" come back often, and stay in memory only
VOICE_CACHE_MAX_CHARS = 60
CONFIDENT_LANGUAGE = 0.8
GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
GROQ_MODEL = "whisper-large-v3"
GROQ_TIMEOUT_SECONDS = 3.0
GROQ_SECOND_TRY_SECONDS = 0.8  # it normally answers in 0.3 s but can take 3, so a slow answer gets raced
GROQ_REST_SECONDS = 60  # after a failure, so an outage doesn't add the timeout to every request
PARAKEET_MODEL = "nemo-parakeet-tdt-0.6b-v3"
SHORT_CLIP_SECONDS = 2.0  # counts the bit of silence the filter keeps around the words
WHISPER_THREADS = int(os.environ.get("MITAURI_WHISPER_THREADS", min(8, os.cpu_count() or 4)))

log = logging.getLogger(__name__)


def speech_engine() -> str:
    """Which engine hears: the one named in the settings, or Groq if there's a key, or Parakeet."""
    chosen = os.environ.get("MITAURI_SPEECH_ENGINE")
    return chosen or ("groq" if os.environ.get("MITAURI_GROQ_API_KEY") else "parakeet")


def wav_bytes(audio: np.ndarray) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes((audio * 32767).astype(np.int16).tobytes())
    return buffer.getvalue()


def choose_language(language: str, confidence: float, scores: dict[str, float]) -> str:
    """Short clips can fool detection, so unless it's sure, pick between the languages with voices."""
    if confidence >= CONFIDENT_LANGUAGE:
        return language
    return max(VOICES, key=lambda known: scores.get(known, 0))


def only_speech(audio: np.ndarray) -> np.ndarray:
    """Cuts the clip down to its speech. Parakeet would otherwise hear words in silence, like "Thank you."."""
    from faster_whisper.vad import get_speech_timestamps
    spoken = get_speech_timestamps(audio) if audio.size else []
    return audio[spoken[0]["start"]:spoken[-1]["end"]] if spoken else audio[:0]


class Speech:
    def __init__(self, data_dir: Path):
        self._voice_dir = data_dir / "voices"
        # Separate, so a voice being generated never holds up hearing, or the other way round.
        self._hearing = threading.Lock()
        self._speaking = threading.Lock()
        self._parakeet = None
        engine = self._engine = speech_engine()
        self._parakeet_failed = engine != "parakeet"
        # Taken out of the environment, so Claude and the tools it starts never inherit the key.
        key = os.environ.pop("MITAURI_GROQ_API_KEY", "")
        self._groq_key = key if engine == "groq" else ""
        self._groq_client = httpx.Client(timeout=GROQ_TIMEOUT_SECONDS)
        self._groq_rests_until = 0.0
        self._groq_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="groq")
        self._voice_cache: OrderedDict[tuple[str, str], bytes] = OrderedDict()
        self._whisper = None
        self._detector = None
        self._voices = {}

    def preload(self) -> None:
        """Loads in the background, so the first request doesn't wait for it."""
        threading.Thread(target=self._preload, daemon=True).start()

    def _preload(self) -> None:
        try:
            # The first run through each model is much slower than the rest, so it's spent here.
            with self._hearing:
                silence = np.zeros(SAMPLE_RATE, dtype=np.float32)
                only_speech(silence)
                if not self._groq_key:
                    if parakeet := self._load_parakeet():
                        parakeet.recognize(silence, sample_rate=SAMPLE_RATE)
                    list(self._load_whisper().transcribe(silence, language="de", beam_size=1)[0])
                if os.environ.get("MITAURI_SPEECH_LANGUAGE") == "auto":
                    self._load_detector()
            for language in VOICES:
                self.synthesize(WAITING[language], language)  # also has it ready in the cache
            log.info("Speech models ready: %s and %s", self._engine.capitalize(), ", ".join(name for _, name in VOICES.values()))
        except Exception:
            log.exception("Couldn't load the speech models; will retry on first use")

    def transcribe(self, pcm: bytes) -> tuple[str, str]:
        """Takes 16 kHz mono 16-bit audio and returns the text and its language."""
        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768
        with self._hearing:
            language = os.environ.get("MITAURI_SPEECH_LANGUAGE", "de")
            if language == "auto":
                language = self._detect_language(audio)
            return self._recognise(audio, language), language

    def _groq_once(self, wav: bytes, language: str) -> str:
        response = self._groq_client.post(
            GROQ_URL,
            headers={"Authorization": f"Bearer {self._groq_key}"},
            files={"file": ("speech.wav", wav, "audio/wav")},
            data={"model": GROQ_MODEL, "language": language, "response_format": "json", "temperature": "0"},
        )
        response.raise_for_status()
        return response.json()["text"].strip()

    def _ask_groq(self, speech: np.ndarray, language: str) -> str | None:
        """Returns the text, or None if local recognition has to do it."""
        if time.monotonic() < self._groq_rests_until:
            return None
        wav = wav_bytes(speech)
        deadline = time.monotonic() + GROQ_TIMEOUT_SECONDS
        running = {self._groq_pool.submit(self._groq_once, wav, language)}
        second_try = False
        failure: Exception | None = None
        while running and failure is None:
            left = deadline - time.monotonic()
            done, running = wait(
                running, timeout=max(0, GROQ_SECOND_TRY_SECONDS if not second_try else left), return_when=FIRST_COMPLETED,
            )
            for future in done:
                try:
                    return future.result()
                except (httpx.HTTPError, ValueError, KeyError, TypeError, AttributeError) as error:
                    if not running:
                        failure = error
            if not done:
                if not second_try:
                    second_try = True
                    running.add(self._groq_pool.submit(self._groq_once, wav, language))
                else:
                    failure = TimeoutError()
        self._groq_rests_until = time.monotonic() + GROQ_REST_SECONDS
        # Only the kind of failure, as an error's text could carry what was said.
        status = failure.response.status_code if isinstance(failure, httpx.HTTPStatusError) else type(failure).__name__
        log.warning("Groq failed (%s), so this machine hears for the next %s s", status, GROQ_REST_SECONDS)
        return None

    def _recognise(self, audio: np.ndarray, language: str = "de") -> str:
        if self._groq_key:
            speech = only_speech(audio)
            if not speech.size:
                return ""
            text = self._ask_groq(speech, language)
            if text is not None:
                return text
        parakeet = self._load_parakeet()
        if parakeet is not None:
            speech = only_speech(audio)
            if not speech.size:
                return ""
            if speech.size >= SHORT_CLIP_SECONDS * SAMPLE_RATE:
                return parakeet.recognize(speech, sample_rate=SAMPLE_RATE).strip()
        segments, _ = self._load_whisper().transcribe(
            audio, language=language, beam_size=1, vad_filter=True,
            condition_on_previous_text=False, without_timestamps=True,
        )
        return " ".join(s.text.strip() for s in segments).strip()

    def _detect_language(self, audio: np.ndarray) -> str:
        """Uses the tiny model, because detecting with the main one adds over a second."""
        language, confidence, ranked = self._load_detector().detect_language(audio)
        return choose_language(language, confidence, dict(ranked))

    def synthesize(self, text: str, language: str) -> bytes:
        """Returns WAV audio, using the English voice for languages without their own."""
        buffer = io.BytesIO()
        key = (language, text)
        with self._speaking:
            if key in self._voice_cache:
                self._voice_cache.move_to_end(key)
                return self._voice_cache[key]
            voice = self._load_voice(language if language in VOICES else "en")
            with wave.open(buffer, "wb") as output:
                voice.synthesize_wav(text, output)
            audio = buffer.getvalue()
            if len(text) <= VOICE_CACHE_MAX_CHARS:
                self._voice_cache[key] = audio
                if len(self._voice_cache) > VOICE_CACHE_SIZE:
                    self._voice_cache.popitem(last=False)
        return audio

    def _load_parakeet(self):
        if self._parakeet is None and not self._parakeet_failed:
            try:
                import onnx_asr
                self._parakeet = onnx_asr.load_model(PARAKEET_MODEL, quantization="int8")
            except Exception:
                self._parakeet_failed = True
                log.exception("Couldn't load Parakeet, so Whisper takes over")
        return self._parakeet

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
