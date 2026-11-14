"""Regenerate player vectors from the official Material Symbols Rounded variable font.

Usage: python3 scripts/player_symbols.py /path/to/MaterialSymbolsRounded.ttf
Requires fonttools. No network or hand-authored substitute paths are used.
"""
from pathlib import Path
import sys
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ICONS = dict(Play=0xE037, Pause=0xE034, Next=0xE044, Previous=0xE045, Note=0xE405,
             Down=0xE5CF, Queue=0xE03D, Shuffle=0xE043, Repeat=0xE040, RepeatOne=0xE041,
             Favourite=0xE87D, FavouriteOutline=0xE87E, Lyrics=0xEC0B, Output=0xE1B1,
             Timer=0xE425, More=0xE5D4)
root = Path(__file__).resolve().parents[1]
font = TTFont(sys.argv[1])
missing = set(ICONS.values()) - set(font.getBestCmap())
assert not missing, missing
options = subset.Options()
options.layout_features = ['*']
subsetter = subset.Subsetter(options=options)
subsetter.populate(unicodes=ICONS.values())
subsetter.subset(font)
asset = root / 'scripts/assets/material_symbols.ttf'
asset.parent.mkdir(parents=True, exist_ok=True)
font.save(asset)
filled = instantiateVariableFont(TTFont(asset), {'FILL': 1, 'wght': 400, 'GRAD': 0, 'opsz': 24})
font = instantiateVariableFont(font, {'FILL': 0, 'wght': 400, 'GRAD': 0, 'opsz': 24})
glyphs = font.getGlyphSet()
scale = 24 / font['head'].unitsPerEm
lines = []
for name, codepoint in ICONS.items():
    pen = SVGPathPen(glyphs, lambda v: f'{v:.3f}'.rstrip('0').rstrip('.') if v else '0')
    if name == 'Favourite':
        filled.getGlyphSet()[filled.getBestCmap()[codepoint]].draw(TransformPen(pen, (scale, 0, 0, -scale, 0, 24)))
    else:
        glyphs[font.getBestCmap()[codepoint]].draw(TransformPen(pen, (scale, 0, 0, -scale, 0, 24)))
    lines.append(f'    val {name} = icon("{pen.getCommands()}")')
(root / 'app/src/main/java/ua/olex/music/ui/Icons.kt').write_text('''package ua.olex.music.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Generated from official Material Symbols Rounded by scripts/player_symbols.py. */
object AppIcons {
''' + '\n'.join(lines) + '''
    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
''')
