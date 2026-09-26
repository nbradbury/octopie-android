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


def blur(a: np.ndarray, radius: int) -> np.ndarray:
    x = np.arange(-3 * radius, 3 * radius + 1)
    k = np.exp(-(x**2) / (2 * radius**2))
    k /= k.sum()
    conv = lambda v: np.convolve(v, k)[3 * radius : 3 * radius + len(v)]
    return np.apply_along_axis(conv, 1, np.apply_along_axis(conv, 0, a))


def fill_hole(rgb: np.ndarray, known: np.ndarray, hole: np.ndarray, radii: tuple[int, ...]) -> np.ndarray:
    """Fills `hole` pixels from nearby `known` ones (normalized convolution), growing inward finest radius first."""
    out = rgb * known[..., None]
    known = known.copy()
    for i, radius in enumerate(radii):
        weight = blur(known.astype(np.float32), radius)
        estimate = np.stack([blur(out[..., c], radius) for c in range(3)], axis=-1)
        estimate /= np.maximum(weight, 1e-6)[..., None]
        fill = hole & ~known & (weight > (0.2 if i < len(radii) - 1 else 0))
        out[fill] = estimate[fill]
        known |= fill
    return np.where(hole[..., None], out, rgb)


def reef_without_octopus(scene: Image.Image, octopus_alpha: Image.Image) -> Image.Image:
    # Fill the octopus hole from its surroundings (normalized convolution), then blur it all.
    # Work at quarter size: the result is blurred heavily anyway, and full-size float blurs are slow.
    small = (scene.width // 4, scene.height // 4)
    scene = scene.resize(small, Image.LANCZOS)
    hole = octopus_alpha.resize(small).filter(ImageFilter.MaxFilter(5)).point(lambda a: 255 if a > 8 else 0)
    hole = np.asarray(hole) > 0
    rgb = fill_hole(np.asarray(scene, dtype=np.float32), ~hole, hole, (2, 5, 12, 30, 75))
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
    mask = np.asarray(hole) > 0
    rgb = fill_hole(rgb, ~mask, mask, (2, 4, 8, 16))
    # The fill is airbrush-smooth; add some grain so it matches the bumpy skin around it.
    grain = np.asarray(
        Image.fromarray((np.random.default_rng(1).normal(128, 40, mask.shape)).clip(0, 255).astype(np.uint8))
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


# Eyes in the 800px-wide sprite: (white cx, cy, rx, ry), (iris cx, cy, rx, ry). Must match Eyes in OctopusDrawing.kt.
EYES = [
    ((341, 132, 20, 25.5), (345, 136, 16, 20.5)),
    ((456, 136, 33, 28), (454.5, 138.5, 21.5, 21.5)),
]


def eyeless(octopus: Image.Image) -> Image.Image:
    """Paints the irises out of the eye whites so the game can draw them looking around."""
    rgb = np.asarray(octopus.convert("RGB"), dtype=np.float32)
    white = Image.new("L", octopus.size)
    iris = Image.new("L", octopus.size)
    for (cx, cy, rx, ry), (ix, iy, irx, iry) in EYES:
        ImageDraw.Draw(white).ellipse((cx - rx - 1, cy - ry - 1, cx + rx + 1, cy + ry + 1), fill=255)
        ImageDraw.Draw(iris).ellipse((ix - irx - 1, iy - iry - 1, ix + irx + 1, iy + iry + 1), fill=255)
    white = np.asarray(white) > 0
    iris = np.asarray(iris) > 0
    # Only the bright, shaded eye-white pixels are sources, so the fill keeps its soft grey edges.
    known = white & ~iris & (rgb.mean(axis=2) > 170)
    out = Image.fromarray(np.clip(fill_hole(rgb, known, white & iris, (6, 12, 24)), 0, 255).astype(np.uint8))
    out.putalpha(octopus.getchannel("A"))
    return out


def iris_sprite(octopus: Image.Image) -> Image.Image:
    """The right eye's iris and pupil, cut out as a soft-edged disc."""
    ix, iy, r, _ = EYES[1][1]
    crop = octopus.crop((round(ix - r), round(iy - r), round(ix + r), round(iy + r)))
    ss = 4
    disc = Image.new("L", (crop.width * ss, crop.height * ss))
    # Inset so none of the surrounding eye-white comes along.
    ImageDraw.Draw(disc).ellipse((2 * ss, 2 * ss, (crop.width - 2) * ss, (crop.height - 2) * ss), fill=255)
    crop.putalpha(disc.resize(crop.size, Image.LANCZOS))
    return crop


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
    eyeless(octopus_800).save(RES / "octopus.webp", lossless=True)
    iris_sprite(octopus_800).save(RES / "octopus_iris.webp", lossless=True)
    surprised(octopus_800).save(RES / "octopus_surprised.webp", lossless=True)
    reef_without_octopus(scene, octopus_full.getchannel("A")).save(RES / "reef_bg.webp", quality=85)
    launcher_foreground(octopus).save(RES / "ic_launcher_foreground.webp", lossless=True)

    pie = cutout(Image.open(pie_path).convert("RGB"))
    scale_to_width(pie, 400).save(RES / "pie.webp", lossless=True)


if __name__ == "__main__":
    main(*sys.argv[1:3])
