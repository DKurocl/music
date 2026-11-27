import sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.basePen import BasePen
from fontTools.pens.recordingPen import RecordingPen
src,dst=sys.argv[1],sys.argv[2]
names=dict(HOME=0xE88A,QUEUE=0xE03D,SEARCH=0xEF7A,SMART=0xE65F,ENERGY=0xEA0B,HAPPY=0xE813,RELAX=0xEB4C,PARTY=0xEA65,WORKOUT=0xEB43,COMMUTE=0xE940,FAVOURITE=0xE87E,RAIN=0xF176,FOCUS=0xE719,SLEEP=0xF159,NOTE=0xE405)
class P(BasePen):
    def __init__(s): super().__init__(None); s.cs=[]; s.c=None
    def _moveTo(s,p): s.c=['M',[p]]; 
    def _lineTo(s,p): s.c.append(('L',[p]))
    def _qCurveToOne(s,a,b): s.c.append(('Q',[a,b]))
    def _curveToOne(s,a,b,c): s.c.append(('C',[a,b,c]))
    def _closePath(s): s.cs.append(s.c); s.c=None
    def _endPath(s): s.cs.append(s.c); s.c=None
inst={}
for fill in (0,1):
    f=instantiateVariableFont(TTFont(src),{'FILL':fill,'wght':400})
    gs=f.getGlyphSet(); cmap=f.getBestCmap()
    for n,cp in names.items():
        p=P(); gs[cmap[cp]].draw(p); inst[(n,fill)]=p.cs
lines=['package ua.olex.music.ui','','// Generated from res/font/material_symbols.ttf (Material Symbols Rounded, wght 400) by','// docs/design/glyph_paths.py: each contour at FILL 0 and FILL 1, in the font\'s 960-unit em','// with y down (SVG viewBox 0 -960 960 960). Do not edit by hand.','',
'internal class GlyphContour(val ops: String, val empty: FloatArray, val solid: FloatArray)','','internal object MotionGlyphData {']
for n in names:
    a=inst[(n,0)]; b=inst[(n,1)]
    assert len(a)==len(b),n
    cs=[]
    for ca,cb in zip(a,b):
        ops='M'; xa=list(ca[1][0]); xb=list(cb[1][0])
        assert len(ca)==len(cb),n
        for sa,sb in zip(ca[2:],cb[2:]):
            assert sa[0]==sb[0],n
            ops+=sa[0]
            for pt in sa[1]: xa+=pt
            for pt in sb[1]: xb+=pt
        # y down
        fa=[v if i%2==0 else -v for i,v in enumerate(xa)]
        fb=[v if i%2==0 else -v for i,v in enumerate(xb)]
        fmt=lambda arr: ', '.join(('%g'%round(v,1))+'f' for v in arr)
        cs.append(f'        GlyphContour("{ops}", floatArrayOf({fmt(fa)}), floatArrayOf({fmt(fb)})),')
    lines.append(f'    val {n} = listOf(')
    lines+=cs
    lines.append('    )')
lines.append('}')
open(dst,'w').write('\n'.join(lines)+'\n')
