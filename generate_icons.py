#!/usr/bin/env python3
"""Generate Android launcher icons from the uploaded source image.

Strategy:
- White square background + red circle centered = square icon for phone
- Wear OS round screen crops to circle, white edges get cropped away
- Content fits within the safe zone (center 66% for adaptive, but we use legacy PNG)
"""
from PIL import Image
import os

SRC = "/workspace/app/src/main/res/drawable/icon_original.png"

# Android legacy launcher icon sizes
SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# The icon content (red circle) should occupy ~80% of the canvas
# so that round cropping on Wear OS doesn't cut into the circle.
CONTENT_RATIO = 0.80


def find_content_bounds(img: Image.Image):
    """Find the bounding box of non-transparent pixels."""
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    alpha = img.getchannel("A")
    bbox = alpha.getbbox()
    return bbox


def generate_icon(size: int) -> Image.Image:
    """Generate a white-square icon with the red circle centered."""
    src = Image.open(SRC).convert("RGBA")
    bbox = find_content_bounds(src)
    if bbox:
        src = src.crop(bbox)

    # Create white square canvas
    canvas = Image.new("RGBA", (size, size), (255, 255, 255, 255))

    # Scale the circular content to fit within CONTENT_RATIO of canvas
    content_size = int(size * CONTENT_RATIO)
    scaled = src.resize((content_size, content_size), Image.LANCZOS)

    # Center on canvas
    offset = ((size - content_size) // 2, (size - content_size) // 2)
    canvas.paste(scaled, offset, scaled)

    return canvas


def main():
    modules = ["app", "mobile"]
    for module in modules:
        for density, px in SIZES.items():
            out_dir = f"/workspace/{module}/src/main/res/mipmap-{density}"
            os.makedirs(out_dir, exist_ok=True)
            icon = generate_icon(px)
            out_path = os.path.join(out_dir, "ic_launcher.png")
            icon.save(out_path, "PNG")
            print(f"  {out_path}  ({px}x{px})")

    # Also delete mipmap-anydpi-v26 so legacy PNG takes precedence
    for module in modules:
        anydpi_dir = f"/workspace/{module}/src/main/res/mipmap-anydpi-v26"
        if os.path.isdir(anydpi_dir):
            for f in os.listdir(anydpi_dir):
                os.remove(os.path.join(anydpi_dir, f))
            os.rmdir(anydpi_dir)
            print(f"  Removed {anydpi_dir}")

    print("Done.")


if __name__ == "__main__":
    main()
