"""Builds the game's drawables from the original Firefly images.

Run once from a throwaway venv:
    python3 -m venv /tmp/octopie-venv
    /tmp/octopie-venv/bin/pip install "rembg[cpu]" pillow numpy
    /tmp/octopie-venv/bin/python tools/prepare_assets.py <octopus.png> <pie.png>
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter
from rembg import remove

RES = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable-nodpi"


def cutout(img: Image.Image) -> Image.Image:
    rgba = remove(img, post_process_mask=True)
    return rgba.crop(rgba.getbbox())


def scale_to_width(img: Image.Image, width: int) -> Image.Image:
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def reef_without_octopus(scene: Image.Image, octopus_alpha: Image.Image) -> Image.Image:
    # Fill the octopus hole from its surroundings (normalized convolution), then blur it all.
    # Work at quarter size: the result is blurred heavily anyway, and full-size float blurs are slow.
    small = (scene.width // 4, scene.height // 4)
    scene = scene.resize(small, Image.LANCZOS)
    hole = octopus_alpha.resize(small).filter(ImageFilter.MaxFilter(5)).point(lambda a: 255 if a > 8 else 0)
    known = np.asarray(hole) == 0
    rgb = np.asarray(scene, dtype=np.float32) * known[..., None]

    def blur(a: np.ndarray, radius: int) -> np.ndarray:
        x = np.arange(-3 * radius, 3 * radius + 1)
        k = np.exp(-(x**2) / (2 * radius**2))
        k /= k.sum()
        conv = lambda v: np.convolve(v, k)[3 * radius : 3 * radius + len(v)]
        return np.apply_along_axis(conv, 1, np.apply_along_axis(conv, 0, a))

    # Grow inward from the edges of the hole, finest radius first.
    for radius in (2, 5, 12, 30, 75):
        weight = blur(known.astype(np.float32), radius)
        estimate = np.stack([blur(rgb[..., c], radius) for c in range(3)], axis=-1)
        estimate /= np.maximum(weight, 1e-6)[..., None]
        fill = ~known & (weight > (0.2 if radius < 75 else 0))
        rgb[fill] = estimate[fill]
        known |= fill
    out = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))
    out = out.filter(ImageFilter.GaussianBlur(3))
    return ImageEnhance.Brightness(out).enhance(0.8)


def surprised(octopus: Image.Image) -> Image.Image:
    """Repaints the face of the 800px-wide octopus sprite: wide eyes, tiny pupils, "O" mouth, raised brows."""
    ss = 4  # supersample the drawing for smooth edges
    face_box = (290, 60, 520, 250)
    face = octopus.crop(face_box)
    rgb = np.asarray(face.convert("RGB"), dtype=np.float32)

    # Paint out the smile by filling it from the surrounding skin.
    hole = Image.new("L", face.size)
    ImageDraw.Draw(hole).ellipse((42, 120, 162, 174), fill=255)
    known = np.asarray(hole) == 0
    rgb *= known[..., None]
    for radius in (2, 4, 8, 16):
        x = np.arange(-3 * radius, 3 * radius + 1)
        k = np.exp(-(x**2) / (2 * radius**2))
        k /= k.sum()
        conv = lambda v: np.convolve(v, k)[3 * radius : 3 * radius + len(v)]
        blur = lambda a: np.apply_along_axis(conv, 1, np.apply_along_axis(conv, 0, a))
        weight = blur(known.astype(np.float32))
        estimate = np.stack([blur(rgb[..., c]) for c in range(3)], axis=-1) / np.maximum(weight, 1e-6)[..., None]
        fill = ~known & (weight > 0.2)
        rgb[fill] = estimate[fill]
        known |= fill
    # The fill is airbrush-smooth; add some grain so it matches the bumpy skin around it.
    grain = np.asarray(
        Image.fromarray((np.random.default_rng(1).normal(128, 40, known.shape)).clip(0, 255).astype(np.uint8))
        .filter(ImageFilter.GaussianBlur(0.8)),
        dtype=np.float32,
    ) - 128
    patch = np.asarray(hole.filter(ImageFilter.GaussianBlur(3)), dtype=np.float32)[..., None] / 255
    rgb += grain[..., None] * 0.35 * patch
    patched = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).convert("RGBA")
    patched.putalpha(face.getchannel("A"))

    big = patched.resize((face.width * ss, face.height * ss), Image.LANCZOS)
    d = ImageDraw.Draw(big)
    s = lambda *v: [c * ss for c in v]

    def eye(cx, cy, rx, ry, iris):
        d.ellipse(s(cx - rx, cy - ry, cx + rx, cy + ry), fill=(246, 244, 240))
        d.ellipse(s(cx - iris, cy - iris, cx + iris, cy + iris), fill=(196, 104, 30))
        d.ellipse(s(cx - iris * 0.55, cy - iris * 0.55, cx + iris * 0.55, cy + iris * 0.55), fill=(20, 12, 8))
        d.ellipse(s(cx - iris * 0.5, cy - iris * 0.7, cx - iris * 0.1, cy - iris * 0.3), fill=(255, 255, 255))

    # Eye centres/radii in face-crop coordinates.
    eye(51, 75, 19, 26, 8)
    eye(167, 75, 31, 25, 10)
    # Raised brows.
    brow = (140, 52, 22)
    d.arc(s(28, 22, 76, 50), 200, 320, fill=brow, width=5 * ss)
    d.arc(s(135, 18, 205, 48), 220, 340, fill=brow, width=5 * ss)
    # "O" mouth.
    mx, my = 104, 150
    d.ellipse(s(mx - 16, my - 20, mx + 16, my + 20), fill=(160, 64, 30))
    d.ellipse(s(mx - 12, my - 16, mx + 12, my + 16), fill=(70, 18, 16))
    d.ellipse(s(mx - 8, my + 4, mx + 8, my + 14), fill=(190, 80, 90))

    out = octopus.copy()
    out.paste(big.resize(face.size, Image.LANCZOS), face_box[:2], face.getchannel("A"))
    return out


def launcher_foreground(octopus: Image.Image) -> Image.Image:
    # Adaptive icon: 108dp canvas, keep the art inside the central ~66dp.
    size = 432
    art = scale_to_width(octopus, round(size * 0.78))
    canvas = Image.new("RGBA", (size, size))
    canvas.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return canvas


def main(octopus_path: str, pie_path: str) -> None:
    scene = Image.open(octopus_path).convert("RGB")
    octopus_full = remove(scene, post_process_mask=True)
    octopus = octopus_full.crop(octopus_full.getbbox())
    octopus_800 = scale_to_width(octopus, 800)
    octopus_800.save(RES / "octopus.webp", lossless=True)
    surprised(octopus_800).save(RES / "octopus_surprised.webp", lossless=True)
    reef_without_octopus(scene, octopus_full.getchannel("A")).save(RES / "reef_bg.webp", quality=85)
    launcher_foreground(octopus).save(RES / "ic_launcher_foreground.webp", lossless=True)

    pie = cutout(Image.open(pie_path).convert("RGB"))
    scale_to_width(pie, 400).save(RES / "pie.webp", lossless=True)


if __name__ == "__main__":
    main(*sys.argv[1:3])
