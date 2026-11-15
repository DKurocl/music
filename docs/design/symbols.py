"""Subsets Material Symbols Rounded to the icons the app uses: res/font/material_symbols.ttf.

    pip install fonttools brotli
    curl -L -o /tmp/rounded.ttf "https://raw.githubusercontent.com/google/material-design-icons/master/variablefont/MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.ttf"
    python3 docs/design/symbols.py /tmp/rounded.ttf

The FILL and wght axes stay variable, so an icon can fill in and change weight smoothly (Symbol in
ui/Symbols.kt animates both). GRAD is pinned to 0 and opsz to 24. The `rclt` feature stays: at
FILL 1 it swaps in the drawn filled forms. The codepoints are read from Symbols.kt (the Glyph
enum), so adding an icon there and running this script is all it takes.
"""
import re
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/ua/olex/music/ui/Symbols.kt"
OUT = ROOT / "app/src/main/res/font/material_symbols.ttf"


def main(full: str):
    codepoints = sorted({int(h, 16) for h in re.findall(r"\(0x([0-9A-Fa-f]{4,5})\)", SOURCE.read_text())})
    # Subset first (the full font is 15 MB), then pin the axes the app does not animate.
    font = TTFont(full)
    options = subset.Options()
    options.layout_features = ["rclt", "liga"]
    options.notdef_outline = True
    options.name_IDs = ["*"]
    sub = subset.Subsetter(options)
    sub.populate(unicodes=codepoints)
    sub.subset(font)
    font = instantiateVariableFont(font, {"GRAD": 0, "opsz": 24})
    font.save(OUT)
    print(f"{len(codepoints)} icons, {OUT.stat().st_size // 1024} KB -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main(sys.argv[1])
