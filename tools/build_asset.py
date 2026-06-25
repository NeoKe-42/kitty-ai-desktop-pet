from collections import deque
from pathlib import Path

from PIL import Image, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "kitty 照片.jpg"
OUTPUT = ROOT / "assets" / "kitty.png"


def is_background(rgb: tuple[int, int, int]) -> bool:
    r, g, b = rgb
    return (
        r > 175
        and g > 170
        and b > 145
        and max(rgb) - min(rgb) < 70
        and r >= b
    )


def main() -> None:
    image = Image.open(SOURCE).convert("RGB")
    width, height = image.size
    pixels = image.load()
    seen = bytearray(width * height)
    background = Image.new("L", image.size, 0)
    bg_pixels = background.load()
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
        if not is_background(pixels[x, y]):
            continue
        bg_pixels[x, y] = 255
        if x:
            todo.append((x - 1, y))
        if x + 1 < width:
            todo.append((x + 1, y))
        if y:
            todo.append((x, y - 1))
        if y + 1 < height:
            todo.append((x, y + 1))

    alpha = background.filter(ImageFilter.GaussianBlur(0.7))
    alpha = alpha.point(lambda value: 255 - value)
    rgba = image.convert("RGBA")
    rgba.putalpha(alpha)

    box = alpha.getbbox()
    if not box:
        raise RuntimeError("没有检测到角色主体")
    left, top, right, bottom = box
    padding = 16
    box = (
        max(0, left - padding),
        max(0, top - padding),
        min(width, right + padding),
        min(height, bottom + padding),
    )
    rgba = rgba.crop(box)
    rgba.thumbnail((250, 250), Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", (280, 280), (0, 0, 0, 0))
    x = (canvas.width - rgba.width) // 2
    y = canvas.height - rgba.height
    canvas.alpha_composite(rgba, (x, y))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUTPUT, optimize=True)
    print(f"saved={OUTPUT}")
    print(f"subject_size={rgba.size}")


if __name__ == "__main__":
    main()
