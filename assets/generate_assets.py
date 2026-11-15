"""Reproduce original sounds and bitmap fonts. Usage: python assets/generate_assets.py --font-dir PATH.
Requires Pillow. Font inputs are the openly licensed DejaVu Sans family.
Generated sounds use only mathematical oscillators, not samples.
"""
from pathlib import Path
import argparse
import math
import shutil
import struct
import wave
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent

def font_atlas(font_path, name, size=48):
    scale = size // 48
    atlas_size = 1024 * scale
    font = ImageFont.truetype(str(font_path), size)
    atlas = Image.new("RGBA", (atlas_size, atlas_size), (255, 255, 255, 0))
    draw = ImageDraw.Draw(atlas)
    lines = [f'info face="{name}" size={size} bold=0 italic=0 charset="" unicode=0 stretchH=100 smooth=1 aa=1 padding=0,0,0,0 spacing=1,1',
             f'common lineHeight={64*scale} base={48*scale} scaleW={atlas_size} scaleH={atlas_size} pages=1 packed=0',
             f'page id=0 file="{name}.png"', 'chars count=95']
    for index, code in enumerate(range(32, 127)):
        char = chr(code)
        x, y = (index % 12) * 80 * scale, (index // 12) * 100 * scale
        left, top, right, bottom = font.getbbox(char)
        width, height = max(1, right-left), max(1, bottom-top)
        draw.text((x-left, y-top), char, font=font, fill="white")
        lines.append(f'char id={code} x={x} y={y} width={width} height={height} xoffset={left} yoffset={top} xadvance={round(font.getlength(char))} page=0 chnl=15')
    atlas.save(ROOT / "fonts" / f"{name}.png")
    (ROOT / "fonts" / f"{name}.fnt").write_text("\n".join(lines)+"\n", encoding="ascii")

def sound(name, seconds, sample):
    rate = 22050
    with wave.open(str(ROOT / "audio" / f"{name}.wav"), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        data = bytearray()
        for i in range(round(seconds*rate)):
            t = i/rate
            value = max(-1.0, min(1.0, sample(t)))
            data.extend(struct.pack("<h", round(value*32767)))
        out.writeframes(data)

def envelope(t, length):
    return min(1.0, t/0.008) * max(0.0, 1-t/length)**2

def generate_audio():
    sound("hit", .12, lambda t: .35*math.sin(2*math.pi*(600*t-900*t*t))*envelope(t,.12))
    sound("pulse", .32, lambda t: .28*(math.sin(2*math.pi*(220*t+1400*t*t))+.2*math.sin(2*math.pi*880*t))*envelope(t,.32))
    sound("goal", .7, lambda t: .32*math.sin(2*math.pi*(120*t+30*t*t))*envelope(t,.7))
    sound("victory", 1.4, lambda t: .23*sum(math.sin(2*math.pi*f*t) for f in (261.63,329.63,392.0))/3*envelope(t,1.4))
    # Integer-period fundamentals and periodic amplitude modulation make a seamless 16s loop.
    notes = (65.375, 98.0, 130.8125, 196.0)
    sound("ambient", 16, lambda t: sum(math.sin(2*math.pi*f*t)*(0.07+0.025*math.sin(2*math.pi*t/16+i)) for i,f in enumerate(notes)))

if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--font-dir", type=Path, required=True)
    args=parser.parse_args()
    (ROOT/"fonts").mkdir(parents=True, exist_ok=True)
    (ROOT/"audio").mkdir(parents=True, exist_ok=True)
    font_atlas(args.font_dir/"DejaVuSans.ttf", "body")
    font_atlas(args.font_dir/"DejaVuSans-Bold.ttf", "display", size=96)
    shutil.copyfile(args.font_dir/"LICENSE_DEJAVU", ROOT/"fonts"/"LICENSE_DEJAVU")
    generate_audio()
