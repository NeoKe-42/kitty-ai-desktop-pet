from collections import deque
from pathlib import Path

from PIL import Image, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
WINDOWS_DIR = ROOT / "windows"
SOURCE = Path(
    r"C:\Users\m1586\AppData\Local\Temp"
    r"\codex-clipboard-972c0a72-a9de-413b-a96f-0d902ce5f678.png"
)
OUTPUT = WINDOWS_DIR / "assets" / "animations"

CELL_WIDTH = 96
ROW_HEIGHT = 126
HEADER_HEIGHT = 22
CONTENT_HEIGHT = 104

STATES = {
    "idle": 6,
    "running-right": 8,
    "running-left": 8,
    "waving": 4,
    "jumping": 5,
    "failed": 8,
    "waiting": 6,
    "running": 6,
    "review": 6,
}


def is_preview_background(rgb: tuple[int, int, int]) -> bool:
    r, g, b = rgb
    near_white = r > 245 and g > 245 and b > 245
    checker_gray = 218 <= r <= 242 and 218 <= g <= 242 and 218 <= b <= 242
    border_tint = g > 120 and r < 80 and b < 160
    return near_white or checker_gray or border_tint


def remove_connected_background(image: Image.Image) -> Image.Image:
    image = image.convert("RGB")
    width, height = image.size
    pixels = image.load()
    seen = bytearray(width * height)
    mask = Image.new("L", image.size, 255)
    mask_pixels = mask.load()
    todo: deque[tuple[int, int]] = deque()

    for x in range(width):
        todo.append((x, 0))
        todo.append((x, height - 1))
    for y in range(height):
        todo.append((0, y))
        todo.append((width - 1, y))

    while todo:
        x, y = todo.popleft()
        index = y * width + x
        if seen[index]:
            continue
        seen[index] = 1
        if not is_preview_background(pixels[x, y]):
            continue
        mask_pixels[x, y] = 0
        if x:
            todo.append((x - 1, y))
        if x + 1 < width:
            todo.append((x + 1, y))
        if y:
            todo.append((x, y - 1))
        if y + 1 < height:
            todo.append((x, y + 1))

    mask = mask.filter(ImageFilter.GaussianBlur(0.35))
    rgba = image.convert("RGBA")
    rgba.putalpha(mask)
    return rgba


def normalize_frame(frame: Image.Image) -> Image.Image:
    alpha = frame.getchannel("A")
    width, height = alpha.size
    pixels = alpha.load()
    seen = bytearray(width * height)
    components: list[list[tuple[int, int]]] = []

    for start_y in range(height):
        for start_x in range(width):
            start_index = start_y * width + start_x
            if seen[start_index] or pixels[start_x, start_y] < 32:
                continue
            component: list[tuple[int, int]] = []
            todo = deque([(start_x, start_y)])
            while todo:
                x, y = todo.popleft()
                index = y * width + x
                if seen[index]:
                    continue
                seen[index] = 1
                if pixels[x, y] < 32:
                    continue
                component.append((x, y))
                if x:
                    todo.append((x - 1, y))
                if x + 1 < width:
                    todo.append((x + 1, y))
                if y:
                    todo.append((x, y - 1))
                if y + 1 < height:
                    todo.append((x, y + 1))
            if component:
                components.append(component)

    if not components:
        raise RuntimeError("Empty animation frame")
    main_component = max(components, key=len)
    keep = Image.new("L", alpha.size, 0)
    keep_pixels = keep.load()
    for x, y in main_component:
        keep_pixels[x, y] = pixels[x, y]
    frame.putalpha(keep)
    scale = min(250 / frame.width, 250 / frame.height)
    frame = frame.resize(
        (max(1, round(frame.width * scale)), max(1, round(frame.height * scale))),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGBA", (280, 280), (0, 0, 0, 0))
    x = (canvas.width - frame.width) // 2
    y = canvas.height - frame.height
    canvas.alpha_composite(frame, (x, y))
    return canvas


def main() -> None:
    sheet = Image.open(SOURCE).convert("RGB")
    expected = (CELL_WIDTH * 8, ROW_HEIGHT * len(STATES))
    if sheet.size != expected:
        raise RuntimeError(f"Unexpected contact sheet size: {sheet.size}, expected {expected}")

    OUTPUT.mkdir(parents=True, exist_ok=True)
    for row, (state, count) in enumerate(STATES.items()):
        state_dir = OUTPUT / state
        state_dir.mkdir(parents=True, exist_ok=True)
        for index in range(count):
            left = index * CELL_WIDTH + 2
            top = row * ROW_HEIGHT + HEADER_HEIGHT + 1
            right = (index + 1) * CELL_WIDTH - 1
            bottom = row * ROW_HEIGHT + HEADER_HEIGHT + CONTENT_HEIGHT - 1
            cell = sheet.crop((left, top, right, bottom))
            frame = normalize_frame(remove_connected_background(cell))
            frame.save(state_dir / f"{index}.png", optimize=True)
        print(f"{state}={count}")


if __name__ == "__main__":
    main()
