"""
Test script for the bedtime-story + TTS feature (v2).

Usage (run from the windows/ directory):
    python test_bedtime_story.py

Interactive commands:
    /bedtime                  – default story
    /bedtime 三分钟 小猫      – 3-min cat story
    /bedtime 五分钟 月亮      – 5-min moon story
    /replay                   – replay last audio
    /stop                     – show stop notice
    (natural language)        – e.g. 给妹妹讲一个三分钟的小猫故事
    quit / exit               – exit

The script will show the full story text, audio path, and generation metadata.
"""

import os
import sys
from pathlib import Path

# Ensure the windows/ directory is on sys.path
_PROJECT_DIR = Path(__file__).resolve().parent
if str(_PROJECT_DIR) not in sys.path:
    sys.path.insert(0, str(_PROJECT_DIR))

from app import DeepSeekClient  # noqa: E402
from services.bedtime_story_service import (  # noqa: E402
    is_bedtime_story_request,
    is_replay_request,
    is_stop_request,
    generate_bedtime_story,
    replay_last_story,
    stop_playback,
)


def print_bar(label: str = "") -> None:
    width = 60
    if label:
        side = (width - len(label) - 2) // 2
        print("=" * side + f" {label} " + "=" * (width - side - len(label) - 2))
    else:
        print("=" * width)


def run_interactive() -> None:
    print_bar("Kitty 睡前故事测试 v2")
    print("  输入 /bedtime                 → 默认故事")
    print("  输入 /bedtime 三分钟 小猫     → 3 分钟小猫主题")
    print("  输入 /replay                  → 重播上次音频")
    print("  输入 /stop                    → 查看停止提示")
    print("  输入 quit / exit              → 退出")
    print()

    client = DeepSeekClient()

    while True:
        try:
            user_input = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n再见~")
            break

        if not user_input:
            continue
        if user_input.lower() in ("quit", "exit"):
            print("再见~")
            break

        # ---- /stop -------------------------------------------------
        if is_stop_request(user_input):
            print(f"[Stop] {stop_playback()}")
            continue

        # ---- /replay ------------------------------------------------
        if is_replay_request(user_input):
            print("[Replay] 查找上次音频...")
            msg = replay_last_story()
            print(f"[Replay] {msg}")
            continue

        # ---- bedtime story (keyword or /bedtime) --------------------
        if is_bedtime_story_request(user_input):
            print()
            result = generate_bedtime_story(user_input, client)

            print_bar("Kitty bedtime story")
            print(result["text"])
            print_bar()

            print(f"\n  duration   : {result['duration']}")
            print(f"  theme      : {result['theme']}")
            print(f"  tts_success: {result['tts_success']}")
            print(f"  play_success: {result['play_success']}")
            if result["audio_path"]:
                print(f"  audio_path : {result['audio_path']}")
            if result.get("error"):
                print(f"  error      : {result['error']}")
            print()
            continue

        # ---- not a bedtime request ----------------------------------
        print("[提示] 这个请求没有命中睡前故事关键词。")
        print("  试试：讲个睡前故事、/bedtime 三分钟 小猫、晚安故事")


if __name__ == "__main__":
    run_interactive()
