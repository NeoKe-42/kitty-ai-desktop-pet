"""
TTS service: text-to-speech via edge-tts + local audio playback.
"""

import asyncio
import os
import platform
import re
import sys

import edge_tts

# Default voice: Xiaoxiao (child-friendly Chinese female voice)
DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
DEFAULT_RATE = "-15%"
DEFAULT_PITCH = "-2Hz"
DEFAULT_VOLUME = "-5%"

# Bedtime-specific defaults (slower, softer)
BEDTIME_DEFAULTS = {
    "voice": "zh-CN-XiaoxiaoNeural",
    "rate": "-18%",
    "pitch": "-2Hz",
    "volume": "-8%",
}


def clean_text_for_tts(text: str) -> str:
    """
    Clean story text for TTS朗读, removing markup and noise.

    Rules:
    1. Strip Markdown markers (#, *, >, backticks, ~~).
    2. Collapse multiple blank lines.
    3. Remove parenthetical stage directions like （轻声） or (action).
    4. Preserve normal Chinese punctuation.
    """
    if not text:
        return ""

    # 1. Remove Markdown formatting characters
    text = re.sub(r"#{1,6}\s*", "", text)       # headings
    text = re.sub(r"\*{1,3}(.+?)\*{1,3}", r"\1", text)  # bold/italic
    text = re.sub(r"~~(.+?)~~", r"\1", text)    # strikethrough
    text = re.sub(r"`{1,3}[^`]*`{1,3}", "", text)  # inline code / code blocks
    text = re.sub(r"^>\s?", "", text, flags=re.MULTILINE)  # blockquotes

    # 2. Remove parenthetical stage directions like （轻声） (whisper) etc.
    text = re.sub(r"[（(][^）)]{1,20}[）)]", "", text)

    # 3. Collapse multiple blank lines into one
    text = re.sub(r"\n{3,}", "\n\n", text)

    # 4. Normalize whitespace on each line (strip trailing spaces)
    text = "\n".join(line.rstrip() for line in text.splitlines())

    return text.strip()


async def _generate_tts_async(
    text: str,
    output_path: str,
    voice: str = DEFAULT_VOICE,
    rate: str = DEFAULT_RATE,
    pitch: str = DEFAULT_PITCH,
    volume: str = DEFAULT_VOLUME,
) -> None:
    """Async core: stream text to edge-tts and save as mp3."""
    communicate = edge_tts.Communicate(
        text=text,
        voice=voice,
        rate=rate,
        pitch=pitch,
        volume=volume,
    )
    await communicate.save(output_path)


def generate_tts(
    text: str,
    output_path: str,
    voice: str = DEFAULT_VOICE,
    rate: str = DEFAULT_RATE,
    pitch: str = DEFAULT_PITCH,
    volume: str = DEFAULT_VOLUME,
) -> None:
    """
    Generate an mp3 file from text using edge-tts.

    Handles event-loop conflicts by creating a fresh loop each call.
    """
    loop = asyncio.new_event_loop()
    try:
        loop.run_until_complete(
            _generate_tts_async(text, output_path, voice, rate, pitch, volume)
        )
    finally:
        loop.close()


def play_audio(audio_path: str) -> bool:
    """
    Play an audio file using the system default player.

    On Windows, uses os.startfile which opens the file with the default
    media player.  Returns True on success, False on failure.
    """
    if not os.path.exists(audio_path):
        print(f"[Audio] File not found: {audio_path}")
        return False

    try:
        if platform.system() == "Windows":
            os.startfile(audio_path)
        elif platform.system() == "Darwin":
            os.system(f'afplay "{audio_path}" &')
        else:
            os.system(f'xdg-open "{audio_path}" &')
        return True
    except Exception as e:
        print(f"[Audio] Playback failed: {e}")
        return False
