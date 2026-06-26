"""
Bedtime story service: intent detection, story generation, TTS, and playback.

Second-round optimisations:
- YAML/JSON config file with defaults
- /bedtime /replay /stop commands
- Theme extraction from user input
- TTS text cleaning
- Story history tracking
- Improved length control
"""

import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path

# ---------------------------------------------------------------------------
# paths — relative to this file
# ---------------------------------------------------------------------------

_SERVICE_DIR = Path(__file__).resolve().parent   # windows/services/
_PROJECT_DIR = _SERVICE_DIR.parent                # windows/
_CONFIG_FILE = _PROJECT_DIR / "config" / "bedtime_story.json"
_PROMPT_FILE = _PROJECT_DIR / "prompts" / "bedtime_story_prompt.txt"
_HISTORY_FILE = _PROJECT_DIR / "data" / "bedtime_story_history.json"
_DEFAULT_AUDIO_DIR = _PROJECT_DIR / "audio" / "bedtime"

# ---------------------------------------------------------------------------
# built-in defaults (used when config file is missing / broken)
# ---------------------------------------------------------------------------

_DEFAULT_CONFIG = {
    "bedtime_story": {
        "enabled": True,
        "listener_name": "妹妹",
        "default_duration": "3min",
        "favorite_themes": ["小猫", "月亮", "小兔子", "星星", "云朵"],
        "avoid_themes": ["怪物", "黑暗森林", "打斗", "追赶", "迷路", "哭泣", "死亡", "恐怖", "生病"],
        "opening_line": "妹妹躺好了吗？Kitty 要开始讲故事啦。",
        "ending_style": "自然引导妹妹放松、闭上眼睛、慢慢睡觉",
        "default_tone": "温柔、安静、慢节奏、低刺激、可爱但不过度兴奋",
        "save_story_history": True,
    },
    "tts": {
        "bedtime_voice": "zh-CN-XiaoxiaoNeural",
        "bedtime_rate": "-18%",
        "bedtime_pitch": "-2Hz",
        "bedtime_volume": "-8%",
        "output_dir": "audio/bedtime",
        "auto_play": True,
        "max_audio_files": 20,
    },
}

# keyword + slash-command detection ---------------------------------------

_BEDTIME_KEYWORDS = [
    "睡前故事", "晚安故事", "哄睡", "哄妹妹睡觉",
    "讲故事睡觉", "给妹妹讲故事", "讲个故事", "讲一个故事",
    "小猫故事", "月亮故事", "星星故事",
]

# theme detection keywords
_THEME_KEYWORDS = [
    "小猫", "月亮", "小兔子", "星星", "云朵", "太阳",
    "小花", "小鱼", "小鸟", "小熊", "小鹿", "小狐狸",
    "森林", "花园", "海边", "星空", "彩虹", "蒲公英",
    "小船", "摇篮", "风铃", "糖果",
]


# ---------------------------------------------------------------------------
# config helpers
# ---------------------------------------------------------------------------

def _load_config() -> dict:
    """Load bedtime-story config, falling back to defaults on any failure."""
    try:
        raw = json.loads(_CONFIG_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeError):
        return _DEFAULT_CONFIG

    # deep-merge: overlay whatever keys exist, keep defaults for missing
    result = json.loads(json.dumps(_DEFAULT_CONFIG))  # deep copy
    for section in ("bedtime_story", "tts"):
        if section in raw and isinstance(raw[section], dict):
            result[section].update(
                {k: v for k, v in raw[section].items() if k in result[section]}
            )
    return result


def _ensure_project_on_path() -> None:
    """Make sure the project root is importable."""
    if str(_PROJECT_DIR) not in sys.path:
        sys.path.insert(0, str(_PROJECT_DIR))


# ---------------------------------------------------------------------------
# intent detection
# ---------------------------------------------------------------------------

def is_bedtime_story_request(user_input: str) -> bool:
    """Return True if *user_input* triggers bedtime story mode."""
    stripped = user_input.strip()
    # slash command
    if stripped.startswith("/bedtime"):
        return True
    # keyword match
    return any(keyword in stripped for keyword in _BEDTIME_KEYWORDS)


def is_replay_request(user_input: str) -> bool:
    """Return True if user wants to replay the last story audio."""
    return user_input.strip() == "/replay"


def is_stop_request(user_input: str) -> bool:
    """Return True if user wants to stop playback."""
    return user_input.strip() == "/stop"


# ---------------------------------------------------------------------------
# command / input parsing
# ---------------------------------------------------------------------------

_DURATION_ALIASES = {
    "三分钟": "3min", "3分钟": "3min", "三分鐘": "3min",
    "五分钟": "5min", "5分钟": "5min", "五分鐘": "5min",
    "十分钟": "10min", "10分钟": "10min", "十分鐘": "10min",
    "短一点": "short", "短一點": "short", "短点": "short",
    "长一点": "long",  "長一點": "long",  "长点": "long",
}


def _parse_duration(user_input: str, config: dict) -> str:
    """Extract duration from user input; fall back to config default then '3min'."""
    # check explicit mentions
    for alias, canonical in _DURATION_ALIASES.items():
        if alias in user_input:
            return canonical
    # config default
    cfg_dur = config["bedtime_story"].get("default_duration", "3min")
    if cfg_dur in ("3min", "5min", "10min", "short", "long"):
        return cfg_dur
    return "3min"


def _extract_theme(user_input: str) -> str | None:
    """Try to extract a theme keyword from user input."""
    for theme in _THEME_KEYWORDS:
        if theme in user_input:
            return theme
    return None


def _get_length_instruction(duration: str) -> str:
    """Map a canonical duration to a prompt instruction."""
    mapping = {
        "3min":   "请生成约 500–700 中文字，适合 3 分钟左右的睡前朗读。",
        "5min":   "请生成约 900–1200 中文字，适合 5 分钟左右的睡前朗读。",
        "10min":  "请生成约 1800–2500 中文字，适合 10 分钟左右的睡前朗读。",
        "short":  "请生成约 400–600 中文字，适合较短的睡前朗读。",
        "long":   "请生成约 1200–1600 中文字，适合较长的睡前朗读。",
    }
    return mapping.get(duration, mapping["3min"])


# ---------------------------------------------------------------------------
# story history (lightweight — only metadata, not full text)
# ---------------------------------------------------------------------------

def _load_history() -> list[dict]:
    """Load history list, return [] on any failure."""
    try:
        data = json.loads(_HISTORY_FILE.read_text(encoding="utf-8"))
        if isinstance(data, list):
            return data[-20:]  # keep last 20
    except (OSError, json.JSONDecodeError, UnicodeError):
        pass
    return []


def _save_history(entry: dict) -> None:
    """Append an entry and prune to the most recent 20."""
    history = _load_history()
    history.append(entry)
    history = history[-20:]
    try:
        _HISTORY_FILE.parent.mkdir(parents=True, exist_ok=True)
        _HISTORY_FILE.write_text(
            json.dumps(history, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except OSError:
        pass  # never crash on history write failure


# ---------------------------------------------------------------------------
# audio file cleanup
# ---------------------------------------------------------------------------

def _cleanup_old_audio(audio_dir: Path, max_files: int) -> None:
    """Delete oldest bedtime mp3s when count exceeds *max_files*."""
    if max_files <= 0:
        return
    mp3s = sorted(audio_dir.glob("bedtime_*.mp3"), key=lambda p: p.stat().st_mtime)
    if len(mp3s) <= max_files:
        return
    to_delete = mp3s[: len(mp3s) - max_files]
    for p in to_delete:
        try:
            p.unlink()
            print(f"[Cleanup] Deleted old audio: {p.name}")
        except OSError:
            pass


# ---------------------------------------------------------------------------
# main: generate bedtime story
# ---------------------------------------------------------------------------

def generate_bedtime_story(user_input: str, client) -> dict:
    """
    Generate a bedtime story, TTS it, play it, save history.

    Parameters
    ----------
    user_input : str   – original user message (or /bedtime command)
    client     : DeepSeekClient

    Returns
    -------
    dict with keys: mode, text, audio_path, tts_success, play_success,
                    theme, duration, error (if any).
    """
    _ensure_project_on_path()
    config = _load_config()
    bs_cfg = config["bedtime_story"]
    tts_cfg = config["tts"]

    # parse intent
    duration = _parse_duration(user_input, config)
    theme = _extract_theme(user_input)
    length_instruction = _get_length_instruction(duration)

    # build theme strings
    favorite_themes = "、".join(bs_cfg.get("favorite_themes", []))
    avoid_themes = "、".join(bs_cfg.get("avoid_themes", []))
    if theme:
        favorite_themes = f"{theme}、" + favorite_themes

    print(f"[BedtimeStory] intent detected")
    print(f"[BedtimeStory] duration: {duration}")
    print(f"[BedtimeStory] theme: {theme or 'auto'}")
    print("[BedtimeStory] Generating story...")

    # build system prompt from template
    prompt_template = _PROMPT_FILE.read_text(encoding="utf-8")
    system_prompt = prompt_template.format(
        listener_name=bs_cfg.get("listener_name", "妹妹"),
        user_input=user_input,
        favorite_themes=favorite_themes,
        avoid_themes=avoid_themes,
        opening_line=bs_cfg.get("opening_line", ""),
        length_instruction=length_instruction,
        ending_style=bs_cfg.get("ending_style", ""),
    )

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_input},
    ]

    # call LLM
    model = client.config_manager.get_pro_model()
    story_text = client.call_model(
        messages, model=model, temperature=0.9, max_tokens=3000
    )

    # TTS
    output_dir_name = tts_cfg.get("output_dir", "audio/bedtime")
    audio_dir = _PROJECT_DIR / output_dir_name
    audio_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    audio_path = audio_dir / f"bedtime_{timestamp}.mp3"

    tts_success = False
    play_success = False
    error_msg = None

    try:
        from services.tts_service import (  # noqa: E402
            generate_tts,
            play_audio,
            clean_text_for_tts,
        )

        # clean text before sending to TTS
        cleaned = clean_text_for_tts(story_text)

        voice = tts_cfg.get("bedtime_voice", "zh-CN-XiaoxiaoNeural")
        rate = tts_cfg.get("bedtime_rate", "-18%")
        pitch = tts_cfg.get("bedtime_pitch", "-2Hz")
        volume = tts_cfg.get("bedtime_volume", "-8%")

        print(f"[TTS] voice={voice} rate={rate} generating mp3...")
        generate_tts(cleaned, str(audio_path), voice=voice, rate=rate, pitch=pitch, volume=volume)
        tts_success = True
        print(f"[TTS] Audio saved: {audio_path}")

        # prune old audio files if exceeding limit
        max_files = tts_cfg.get("max_audio_files", 20)
        if max_files > 0:
            _cleanup_old_audio(audio_dir, max_files)

        if tts_cfg.get("auto_play", True):
            print("[Audio] Playing...")
            play_success = play_audio(str(audio_path))
    except Exception as e:
        error_msg = f"TTS failed: {e}"
        print(f"[Warning] {error_msg}")

    # save history (metadata only)
    if bs_cfg.get("save_story_history", True) and tts_success:
        _save_history({
            "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "theme": theme or "通用",
            "duration": duration,
            "audio_path": str(audio_path),
        })

    return {
        "mode": "bedtime_story",
        "text": story_text,
        "audio_path": str(audio_path) if tts_success else None,
        "tts_success": tts_success,
        "play_success": play_success,
        "theme": theme or "通用",
        "duration": duration,
        "error": error_msg,
    }


# ---------------------------------------------------------------------------
# replay
# ---------------------------------------------------------------------------

def replay_last_story() -> str:
    """
    Replay the most recent bedtime story audio.

    Returns
    -------
    str — a user-facing message describing the result.
    """
    _ensure_project_on_path()
    history = _load_history()

    # find the most recent entry whose audio file still exists
    for entry in reversed(history):
        path = entry.get("audio_path", "")
        if path and os.path.exists(path):
            print(f"[Replay] Found: {path}")
            print("[Audio] Playing...")
            from services.tts_service import play_audio  # noqa: E402

            ok = play_audio(path)
            if ok:
                return f"正在重播上次的睡前故事（{entry.get('theme', '未知主题')}，{entry.get('duration', '未知时长')}）。"
            else:
                return "重播失败，无法打开音频文件。"
        elif path:
            print(f"[Replay] File gone: {path}")

    return "还没有可以重播的睡前故事音频。"


# ---------------------------------------------------------------------------
# stop
# ---------------------------------------------------------------------------

def stop_playback() -> str:
    """
    Attempt to stop current playback.

    Because we use os.startfile() on Windows we cannot control the external
    player.  Return a helpful message.
    """
    return "当前播放方式不支持自动停止，请手动关闭播放器窗口。"
