"""Shared Android sprite conversion; preserve alpha and the authored frame/anchor."""
from pathlib import Path
from PIL import Image


def save_webp(source, destination: Path, size=None, quality=90, method=4):
    with Image.open(source) as image:
        image = image.convert("RGBA")
        if size is not None and image.size != size:
            image = image.resize(size, Image.Resampling.LANCZOS)
        destination.parent.mkdir(parents=True, exist_ok=True)
        image.save(destination, quality=quality, method=method)
