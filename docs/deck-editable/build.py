#!/usr/bin/env python3
"""
Build the deck as a real PowerPoint file: every word is a text box you can edit,
every panel is a shape you can move. No pictures of text.

    deck-venv/bin/python build.py      ->  MeshAI_Deck.pptx

Plain words, short lines. A slide is read in a few seconds from the back of a room.
"""
import pathlib
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.oxml import parse_xml
from pptx.util import Inches, Pt, Emu

OUT = pathlib.Path.home() / "IQOO" / "MeshAI_Deck.pptx"

BG      = RGBColor(0x0B, 0x0C, 0x0B)
PANEL   = RGBColor(0x14, 0x16, 0x12)
PANEL_HI= RGBColor(0x14, 0x2E, 0x20)
LINE    = RGBColor(0x2A, 0x2F, 0x27)
FG      = RGBColor(0xF0, 0xEF, 0xE9)
FG2     = RGBColor(0xA8, 0xAC, 0xA1)
FG3     = RGBColor(0x71, 0x76, 0x6C)
GREEN   = RGBColor(0x5F, 0xD6, 0x8A)
VIOLET  = RGBColor(0xA7, 0x8B, 0xFA)
AMBER   = RGBColor(0xE8, 0xB8, 0x4B)
SANS    = "Segoe UI"
MONO    = "Consolas"

W, H = Inches(13.333), Inches(7.5)
M = Inches(0.62)                      # page margin


def deck():
    p = Presentation()
    p.slide_width, p.slide_height = W, H
    return p


def slide(prs):
    s = prs.slides.add_slide(prs.slide_layouts[6])
    bg = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, W, H)
    bg.fill.solid(); bg.fill.fore_color.rgb = BG; bg.line.fill.background()
    bg.shadow.inherit = False
    morph(s)
    return s


def morph(s, ms=800):
    ns = ('xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" '
          'xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main" '
          'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
          'xmlns:p159="http://schemas.microsoft.com/office/powerpoint/2015/09/main"')
    el = parse_xml(f'<mc:AlternateContent {ns}><mc:Choice Requires="p14">'
                   f'<p:transition spd="slow" p14:dur="{ms}"><p159:morph option="byObject"/></p:transition>'
                   f'</mc:Choice><mc:Fallback><p:transition spd="slow"><p:fade/></p:transition>'
                   f'</mc:Fallback></mc:AlternateContent>')
    sld = s._element
    after = sld.find('{http://schemas.openxmlformats.org/presentationml/2006/main}clrMapOvr')
    (after.addnext(el) if after is not None else sld.append(el))


def text(s, x, y, w, h, runs, size=16, color=FG, font=SANS, bold=False,
         align=PP_ALIGN.LEFT, spacing=6, anchor=MSO_ANCHOR.TOP, line=None):
    """runs: a string, or a list of (text, size, color, bold, font) tuples, one per line."""
    box = s.shapes.add_textbox(x, y, w, h)
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    lines = [runs] if isinstance(runs, str) else runs
    for i, ln in enumerate(lines):
        if isinstance(ln, str):
            ln = (ln, size, color, bold, font)
        t, sz, col, bd, fnt = (list(ln) + [None] * 5)[:5]
        para = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        para.alignment = align
        para.space_after = Pt(spacing)
        if line:
            para.line_spacing = line
        r = para.add_run(); r.text = t
        r.font.size = Pt(sz or size); r.font.bold = bool(bd)
        r.font.name = fnt or font
        r.font.color.rgb = col or color
    return box


def card(s, x, y, w, h, fill=PANEL, border=LINE):
    sh = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x, y, w, h)
    sh.fill.solid(); sh.fill.fore_color.rgb = fill
    sh.line.color.rgb = border; sh.line.width = Pt(0.75)
    sh.shadow.inherit = False
    sh.adjustments[0] = 0.04
    return sh


def bar(s, x, y, w, h, fill):
    sh = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x, y, w, h)
    sh.fill.solid(); sh.fill.fore_color.rgb = fill
    sh.line.fill.background(); sh.shadow.inherit = False
    sh.adjustments[0] = 0.18
    return sh


def lines_of(t, size, width_in):
    """Roughly how many lines this text will take. Enough to stop a wrap landing on the next thing."""
    per = max(8, int(width_in / (size * 0.0078)))
    return max(1, -(-len(t) // per))


def head(s, eyebrow, title, sub=None):
    """Title, optional one-line summary, then a rule. Everything below moves if the title wraps."""
    text(s, M, Inches(0.46), W - 2 * M, Inches(0.3), eyebrow.upper(), 12, FG3, MONO)
    tl = lines_of(title, 40, 12.09)
    th = 0.62 * tl
    text(s, M, Inches(0.78), W - 2 * M, Inches(th), title, 40, FG, SANS, bold=True, line=1.05)
    y = Inches(0.78 + th + 0.12)
    if sub:
        sl = lines_of(sub, 16, 9.6)
        text(s, M, y, Inches(9.6), Inches(0.3 * sl), sub, 16, FG2, SANS, line=1.25)
        y += Inches(0.3 * sl + 0.18)
    ln = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, M, y, W - 2 * M, Pt(0.75))
    ln.fill.solid(); ln.fill.fore_color.rgb = LINE; ln.line.fill.background(); ln.shadow.inherit = False
    return y + Inches(0.3)


def foot(s, left, right):
    text(s, M, H - Inches(0.62), Inches(5.4), Inches(0.3), left, 11, FG3, MONO)
    text(s, W - M - Inches(6.4), H - Inches(0.62), Inches(6.4), Inches(0.3), right, 11, FG3, MONO,
         align=PP_ALIGN.RIGHT)


def bullets(s, x, y, w, items, size=15, gap=0.44):
    """items: a line, or (line, note). Rows advance by what each one actually needs."""
    w_in = w / 914400
    yy = y
    for it in items:
        t, note = (it, None) if isinstance(it, str) else it
        n = lines_of(t, size, w_in - 0.26)
        d = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, yy + Inches(0.10), Inches(0.13), Pt(1.5))
        d.fill.solid(); d.fill.fore_color.rgb = GREEN; d.line.fill.background(); d.shadow.inherit = False
        text(s, x + Inches(0.26), yy, w - Inches(0.26), Inches(0.3 * n), t, size, FG, SANS, line=1.15)
        h = 0.245 * size / 14 * n
        if note:
            nn = lines_of(note, 12, w_in - 0.26)
            text(s, x + Inches(0.26), yy + Inches(h), w - Inches(0.26), Inches(0.24 * nn), note, 12, FG3, SANS, line=1.15)
            h += 0.21 * nn
        yy += Inches(h + max(gap - 0.30, 0.12))
    return yy


# ----------------------------------------------------------------- the slides
def build():
    prs = deck()

    # 1 · title
    s = slide(prs)
    text(s, M, Inches(0.46), W - 2 * M, Inches(0.3),
         "TEAM MAYNARDS · IQOO HACKATHON 2026 · DEVELOPER TOOLS", 12, FG3, MONO)
    text(s, M, Inches(2.5), Inches(9), Inches(1.2), "MeshAI", 66, FG, SANS, bold=True)
    text(s, M, Inches(3.75), Inches(8.6), Inches(1.0),
         "Run big models on the devices you already own.", 24, FG, SANS, line=1.25)
    text(s, M, Inches(4.75), Inches(10), Inches(0.4),
         "A 30B model across one laptop and two phones. No cloud. No internet.", 16, FG2, SANS)
    for i, t in enumerate(["ready in 70 s", "6.6 tokens per second", "4 KB per word", "internet off"]):
        x = M + Inches(3.06 * i)
        c = card(s, x, Inches(5.45), Inches(2.86), Inches(0.5))
        text(s, x, Inches(5.58), Inches(2.86), Inches(0.3), t, 13, GREEN if i == 0 else FG2, MONO,
             align=PP_ALIGN.CENTER)
    foot(s, "Prajwal Gunnala · Yuva Raj Ambati", "27 September 2026")

    # 2 · the problem
    s = slide(prs)
    y = head(s, "01 · The problem", "Your model has to fit in memory.",
             "If it does not fit, you cannot run it. So people run small models, and small models get it wrong.")
    cw = (W - 2 * M - Inches(0.3)) / 2
    card(s, M, y, cw, Inches(3.9))
    text(s, M + Inches(0.3), y + Inches(0.26), cw - Inches(0.6), Inches(0.3), "WHAT A MODEL NEEDS", 11, FG3, MONO)
    rows = [("Qwen3 0.6B", "0.64 GB", False), ("Qwen3 8B", "4.7 GB", False),
            ("gpt-oss 20B", "12.1 GB", False), ("Qwen3-Coder 30B", "18.6 GB", True)]
    for i, (n, v, hi) in enumerate(rows):
        yy = y + Inches(0.66 + 0.42 * i)
        text(s, M + Inches(0.3), yy, cw - Inches(1.8), Inches(0.3), n, 15, GREEN if hi else FG, SANS, bold=hi)
        text(s, M + cw - Inches(1.7), yy, Inches(1.4), Inches(0.3), v, 15, GREEN if hi else FG2, MONO,
             align=PP_ALIGN.RIGHT)
    text(s, M + Inches(0.3), y + Inches(2.5), cw - Inches(0.6), Inches(0.3), "WHAT A DEVICE HAS FREE", 11, FG3, MONO)
    for i, (n, v, hi) in enumerate([("A budget laptop", "about 4 GB", False),
                                    ("One 16 GB phone", "about 8.7 GB", False),
                                    ("The three together", "about 20 GB", True)]):
        yy = y + Inches(2.9 + 0.36 * i)
        text(s, M + Inches(0.3), yy, cw - Inches(1.8), Inches(0.3), n, 14, GREEN if hi else FG, SANS, bold=hi)
        text(s, M + cw - Inches(2.3), yy, Inches(2.0), Inches(0.3), v, 14, GREEN if hi else FG2, MONO,
             align=PP_ALIGN.RIGHT)

    x2 = M + cw + Inches(0.3)
    card(s, x2, y, cw, Inches(3.9))
    text(s, x2 + Inches(0.3), y + Inches(0.26), cw - Inches(0.6), Inches(0.3),
         "SMALL MODELS GET IT WRONG", 11, FG3, MONO)
    text(s, x2 + Inches(0.3), y + Inches(0.6), cw - Inches(0.6), Inches(0.5),
         "We asked three setups the same 15 coding problems and ran their answers against the tests.",
         14, FG2, SANS, line=1.3)
    base = y + Inches(3.1)
    for i, (lab, pct, hgt, col) in enumerate([("0.6B  2 of 15", "13%", 0.30, LINE),
                                              ("1.7B  7 of 15", "47%", 1.05, GREEN),
                                              ("30B  18.6 GB", "?", 1.40, None)]):
        bx = x2 + Inches(0.5 + 1.55 * i)
        if col:
            bar(s, bx, base - Inches(hgt), Inches(1.15), Inches(hgt), col)
        else:
            d = card(s, bx, base - Inches(hgt), Inches(1.15), Inches(hgt), BG, LINE)
        text(s, bx, base - Inches(hgt) - Inches(0.34), Inches(1.15), Inches(0.3), pct, 17,
             GREEN if i == 1 else FG3, MONO, align=PP_ALIGN.CENTER)
        text(s, bx - Inches(0.3), base + Inches(0.1), Inches(1.75), Inches(0.5), lab, 11, FG3, MONO,
             align=PP_ALIGN.CENTER)
    foot(s, "MeshAI", "measured 27 September · results/REPORT.md")

    # 3 · what we built
    s = slide(prs)
    y = head(s, "02 · What we built", "Put the devices together and run the big model.",
             "One device plans and answers. The others hold part of the model. Your tools do not change.")
    bullets(s, M, y + Inches(0.1), Inches(7.2), [
        ("Phones and laptops in one cluster", "any Android phone, any laptop, any GGUF model"),
        ("One device decides who holds what", "it reads free memory, heat, battery and link speed"),
        ("Only about 4 KB crosses the cable per word", "the 18.6 GB of weights never move"),
        ("It says no when a device would slow you down", "in a sentence you can read"),
        ("Nothing leaves your devices", "the demo runs with the internet off"),
    ], size=16, gap=0.62)
    cx = M + Inches(7.6)
    card(s, cx, y + Inches(0.1), W - M - cx, Inches(3.2), PANEL_HI, GREEN)
    text(s, cx + Inches(0.32), y + Inches(0.4), Inches(4), Inches(0.3), "THE PHONE IS NOT AN ACCESSORY", 11, GREEN, MONO)
    text(s, cx + Inches(0.32), y + Inches(0.78), W - M - cx - Inches(0.64), Inches(2.2),
         "A phone can be the host: it plans the split, runs the model and answers.\n\n"
         "Every other system treats a phone as a client that asks a server.", 16, FG, SANS, line=1.35)
    foot(s, "MeshAI", "one app · host role or helper role")

    # 5 · how it works
    s = slide(prs)
    y = head(s, "03 · How it works", "One word goes through all 48 layers, then comes back.")
    lap_w, ph_w = Inches(4.5), Inches(1.9)
    card(s, M, y + Inches(0.15), lap_w, Inches(2.9))
    text(s, M + Inches(0.26), y + Inches(0.36), lap_w - Inches(0.5), Inches(0.3), "THIS LAPTOP · THE HOST", 11, GREEN, MONO)
    text(s, M + Inches(0.26), y + Inches(0.72), lap_w - Inches(0.5), Inches(0.3),
         "Embeddings and output head stay here", 12, FG2, SANS)
    bar(s, M + Inches(0.26), y + Inches(1.0), lap_w - Inches(0.52), Inches(0.2), RGBColor(0x2C, 0x5E, 0x41))
    text(s, M + Inches(0.26), y + Inches(1.35), lap_w - Inches(0.5), Inches(0.3), "Layers 0–22 · 9.19 GB", 12, FG2, SANS)
    for i in range(23):
        bar(s, M + Inches(0.26) + Inches(0.172) * i, y + Inches(1.63), Inches(0.14), Inches(0.42), GREEN)
    text(s, M + Inches(0.26), y + Inches(2.25), lap_w - Inches(0.5), Inches(0.5),
         "Plans the split · serves one endpoint · reads the model file once", 12, FG3, SANS, line=1.3)

    for j, (px, n, rng, gb) in enumerate([(M + lap_w + Inches(0.45), 20, "Layers 23–42", "7.53 GB"),
                                          (M + lap_w + Inches(2.75), 5, "Layers 43–47", "2.04 GB")]):
        card(s, px, y + Inches(0.15), ph_w, Inches(2.9))
        text(s, px + Inches(0.2), y + Inches(0.36), ph_w - Inches(0.4), Inches(0.3), "A PHONE", 11, VIOLET, MONO)
        text(s, px + Inches(0.2), y + Inches(0.72), ph_w - Inches(0.4), Inches(0.3), rng, 12, FG2, SANS)
        text(s, px + Inches(0.2), y + Inches(0.98), ph_w - Inches(0.4), Inches(0.3), gb, 12, FG3, MONO)
        for i in range(n):
            bar(s, px + Inches(0.2), y + Inches(1.3) + Inches(0.062) * i, ph_w - Inches(0.4), Inches(0.058), VIOLET)
        text(s, px + Inches(0.2), y + Inches(2.62), ph_w - Inches(0.4), Inches(0.3),
             "keeps its layers" if j == 0 else "ready in 70 s", 11, FG3, SANS)

    rx = M + lap_w + Inches(5.0)
    card(s, rx, y + Inches(0.15), W - M - rx, Inches(2.9))
    for i, (v, lab, col) in enumerate([("48 layers", "one pass, in order", FG),
                                       ("4 KB", "crosses each cable, per word", GREEN),
                                       ("18.6 GB", "of weights never move", FG),
                                       ("2–3 ms", "over the USB cable", FG)]):
        yy = y + Inches(0.4 + 0.63 * i)
        text(s, rx + Inches(0.28), yy, Inches(2.6), Inches(0.3), v, 19, col, MONO, bold=True)
        text(s, rx + Inches(0.28), yy + Inches(0.3), Inches(2.6), Inches(0.3), lab, 11, FG3, SANS)

    y3 = y + Inches(3.25)
    for i, (t, d) in enumerate([("The host decides", "from live memory, heat, battery and link speed"),
                                ("The phone never sees your prompt", "it gets numbers, computes, sends numbers back"),
                                ("The head stays home", "which is why only kilobytes cross")]):
        cw3 = (W - 2 * M - Inches(0.6)) / 3
        x = M + (cw3 + Inches(0.3)) * i
        card(s, x, y3, cw3, Inches(0.95))
        text(s, x + Inches(0.24), y3 + Inches(0.18), cw3 - Inches(0.48), Inches(0.3), t, 14, FG, SANS, bold=True)
        text(s, x + Inches(0.24), y3 + Inches(0.5), cw3 - Inches(0.48), Inches(0.4), d, 12, FG3, SANS, line=1.25)
    foot(s, "MeshAI", "the configuration we measured on 27 September")

    # 6 · what we fixed
    s = slide(prs)
    y = head(s, "04 · What we fixed", "Three things made it slow. We fixed all three.")
    cw = (W - 2 * M - Inches(0.6)) / 3
    fixes = [("Loading the model", "517 s", "70 s",
              "It was reading the file in scattered pieces. Now it reads it once, start to finish."),
             ("A split it has not used", "437 s", "70 s",
              "Each device now keeps its own layers, checked against the original file."),
             ("First word in a long chat", "slow", "fast",
              "One conversation slot, so the chat stays in cache instead of being read again.")]
    for i, (t, a, b, d) in enumerate(fixes):
        x = M + (cw + Inches(0.3)) * i
        card(s, x, y + Inches(0.1), cw, Inches(2.5))
        text(s, x + Inches(0.28), y + Inches(0.32), cw - Inches(0.56), Inches(0.3), t, 14, FG3, SANS)
        text(s, x + Inches(0.28), y + Inches(0.68), cw - Inches(0.56), Inches(0.5),
             f"{a}  \u2192  {b}", 23, GREEN, MONO, bold=True)
        text(s, x + Inches(0.28), y + Inches(1.35), cw - Inches(0.56), Inches(1.0), d, 13, FG2, SANS, line=1.35)
    y2 = y + Inches(2.85)
    card(s, M, y2, W - 2 * M, Inches(1.85))
    text(s, M + Inches(0.3), y2 + Inches(0.24), Inches(6), Inches(0.3), "AND THE REST", 11, FG3, MONO)
    left = [("The head stays on the host", "so only a few KB cross per word"),
            ("We take the middle ping, not the average", "one bad spike cannot throw out a good device")]
    right = [("We tested it hot, not cold", "11.4 to 5.3 tokens/s as the phone goes 34.6 to 47.5 °C"),
             ("Photos and text on two phones at once", "each answer says which device produced it")]
    bullets(s, M + Inches(0.3), y2 + Inches(0.62), Inches(5.6), left, size=14, gap=0.52)
    bullets(s, M + Inches(6.4), y2 + Inches(0.62), Inches(5.6), right, size=14, gap=0.52)
    foot(s, "MeshAI", "every figure in docs/measurements.md, with its conditions")

    # 12 · what we do not claim
    s = slide(prs)
    y = head(s, "05 · When to use it", "Use it when the model will not fit. Not otherwise.",
             "It is worth knowing where this helps, because it is the same thing as knowing where it does not.")
    cw = (W - 2 * M - Inches(0.3)) / 2
    card(s, M, y, cw, Inches(3.55), PANEL_HI, GREEN)
    text(s, M + Inches(0.3), y + Inches(0.26), cw - Inches(0.6), Inches(0.3), "USE IT WHEN", 11, GREEN, MONO)
    bullets(s, M + Inches(0.3), y + Inches(0.72), cw - Inches(0.6), [
        ("The model will not fit on one device", "this is the whole reason it exists"),
        ("The work cannot leave your devices", "nothing is uploaded, and the demo runs offline"),
        ("You have devices sitting idle", "phones, laptops, anything with memory to spare"),
        ("You run it often", "there is no bill, however many times"),
    ], size=14, gap=0.56)
    x2 = M + cw + Inches(0.3)
    card(s, x2, y, cw, Inches(3.55))
    text(s, x2 + Inches(0.3), y + Inches(0.26), cw - Inches(0.6), Inches(0.3), "DO NOT USE IT WHEN", 11, FG3, MONO)
    for i, (t, d) in enumerate([("The model already fits one device",
                                 "splitting would only make it slower, and the app refuses"),
                                ("You want the fastest possible answer",
                                 "every word crosses the cable once, so a cloud is quicker"),
                                ("Your data is free to leave",
                                 "then a cloud API is cheaper per word, and we say so"),
                                ("You need a stranger's phone to be private",
                                 "we only claim privacy across devices one owner controls")]):
        yy = y + Inches(0.72 + 0.6 * i)
        d2 = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, x2 + Inches(0.3), yy + Inches(0.1), Inches(0.13), Pt(1.5))
        d2.fill.solid(); d2.fill.fore_color.rgb = FG3; d2.line.fill.background(); d2.shadow.inherit = False
        text(s, x2 + Inches(0.56), yy, cw - Inches(0.9), Inches(0.3), t, 15, FG, SANS)
        text(s, x2 + Inches(0.56), yy + Inches(0.23), cw - Inches(0.9), Inches(0.3), d, 12, FG3, SANS)
    foot(s, "MeshAI", "the app itself refuses the cases on the right, in words")

    # 4 · what you do with it
    s = slide(prs)
    y = head(s, "06 · What you do with it", "Review your code before you commit.",
             "One tap on the phone. The answer comes from a 30B model running on your own devices.")
    steps = [("Your laptop", "You change a file", PANEL, FG),
             ("Your phone", "Tap: review my changes", PANEL, FG),
             ("The mesh", "30B across three devices", PANEL, FG),
             ("The review", "On your phone, in seconds", PANEL_HI, GREEN)]
    sw = (W - 2 * M - Inches(0.9)) / 4
    for i, (t, d, fill, col) in enumerate(steps):
        x = M + (sw + Inches(0.3)) * i
        card(s, x, y + Inches(0.1), sw, Inches(1.25), fill, GREEN if fill == PANEL_HI else LINE)
        text(s, x + Inches(0.24), y + Inches(0.3), sw - Inches(0.48), Inches(0.3), t, 14, col, SANS, bold=True)
        text(s, x + Inches(0.24), y + Inches(0.66), sw - Inches(0.48), Inches(0.5), d, 13, FG2, SANS, line=1.25)
        if i < 3:
            text(s, x + sw + Inches(0.02), y + Inches(0.55), Inches(0.26), Inches(0.3), "→", 16, GREEN, SANS,
                 align=PP_ALIGN.CENTER)
    y2 = y + Inches(1.7)
    cw = (W - 2 * M - Inches(0.3)) / 2
    card(s, M, y2, cw, Inches(2.2))
    text(s, M + Inches(0.3), y2 + Inches(0.26), cw - Inches(0.6), Inches(0.3), "WHY PEOPLE KEEP IT", 11, FG3, MONO)
    bullets(s, M + Inches(0.3), y2 + Inches(0.66), cw - Inches(0.6), [
        "It installs as a git hook, in one command",
        "Every commit gets read before it lands",
        "No bill, however many times you run it",
    ], size=14, gap=0.42)
    card(s, M + cw + Inches(0.3), y2, cw, Inches(2.2))
    text(s, M + cw + Inches(0.6), y2 + Inches(0.26), cw - Inches(0.6), Inches(0.3), "AND IT NEVER LEAVES", 11, FG3, MONO)
    bullets(s, M + cw + Inches(0.6), y2 + Inches(0.66), cw - Inches(0.6), [
        "Internet off. No account. No upload",
        "The code stays on the desk it was written on",
        "Also: review a file, or write its tests",
    ], size=14, gap=0.42)
    foot(s, "MeshAI", "mesh review · mesh tests · mesh diff · git pre-commit hook")

    # 8 · proof
    s = slide(prs)
    y = head(s, "07 · Proof", "We measured it. We did not guess.",
             "Same 15 coding problems for every setup. We ran each answer against its own tests. Right or wrong, nothing in between.")
    cw = (W - 2 * M - Inches(0.3)) * 0.56
    card(s, M, y, cw, Inches(3.3))
    base = y + Inches(2.3)
    for i, (lab, pct, h, col, sp) in enumerate([("laptop alone, 0.6B\n2 of 15", "13%", 0.4, LINE, "20.9 tok/s"),
                                                ("laptop alone, 1.7B\n7 of 15", "47%", 1.45, GREEN, "8.6 tok/s"),
                                                ("split over two, 1.7B\n7 of 15", "47%", 1.45, VIOLET, "7.4 tok/s")]):
        bx = M + Inches(0.45 + 2.1 * i)
        bar(s, bx, base - Inches(h), Inches(1.5), Inches(h), col)
        text(s, bx, base - Inches(h) - Inches(0.36), Inches(1.5), Inches(0.3), pct, 20,
             col if col != LINE else FG3, MONO, bold=True, align=PP_ALIGN.CENTER)
        text(s, bx, base + Inches(0.1), Inches(1.5), Inches(0.6), lab.replace("\n", "\n"), 11, FG3, MONO,
             align=PP_ALIGN.CENTER, line=1.3)
        text(s, bx, base + Inches(0.62), Inches(1.5), Inches(0.3), sp, 11, FG2, MONO, align=PP_ALIGN.CENTER)
    x2 = M + cw + Inches(0.3)
    card(s, x2, y, W - M - x2, Inches(3.3))
    text(s, x2 + Inches(0.3), y + Inches(0.26), Inches(4.5), Inches(0.3), "WHAT THIS SHOWS", 11, FG3, MONO)
    bullets(s, x2 + Inches(0.3), y + Inches(0.7), W - M - x2 - Inches(0.6), [
        ("A bigger model is three times better", "13% becomes 47% on the same problems"),
        ("Splitting costs a little speed", "8.6 becomes 7.4 words a second"),
        ("Splitting does not change the answers", "the split got the same 8 wrong as the single device"),
    ], size=15, gap=0.62)
    text(s, x2 + Inches(0.3), y + Inches(2.6), W - M - x2 - Inches(0.6), Inches(0.6),
         "Speed drops a little. Accuracy jumps a lot. That is the trade.", 16, GREEN, SANS, line=1.3)
    foot(s, "MeshAI", "one button in the app runs this · results/REPORT.md")

    # 9 · what exists today
    s = slide(prs)
    y = head(s, "08 · What exists today", "Nobody else pools phone memory.")
    rows = [("Darkbloom", "a16z backed", "Rents out idle Macs and pays their owners",
             "One whole model per Mac. Never a phone.", False),
            ("exo", "47k stars", "Splits a model across your Macs and Linux boxes",
             "The phone is not a real device in it.", False),
            ("Petals", "peer reviewed", "A public swarm of volunteer servers",
             "0.83 words a second. Dead since 2024.", False),
            ("llama.cpp RPC", "we build on it", "Runs layers on another machine over a socket",
             "You type the addresses by hand. Nothing decides.", False),
            ("Ollama, LM Studio", "and the rest", "Great on one machine, one model",
             "If it does not fit, you do not run it.", False),
            ("MeshAI", "what we built", "Phones and laptops together. It decides who holds what, and says no when it should",
             "Nothing. No product and no paper does this.", True)]
    text(s, M, y, Inches(2.6), Inches(0.3), "SYSTEM", 11, FG3, MONO)
    text(s, M + Inches(2.9), y, Inches(5), Inches(0.3), "WHAT IT DOES", 11, FG3, MONO)
    text(s, M + Inches(8.2), y, Inches(4), Inches(0.3), "WHAT IT LEAVES TO YOU", 11, FG3, MONO)
    for i, (n, tag, does, gap_, us) in enumerate(rows):
        yy = y + Inches(0.42 + 0.63 * i)
        if us:
            card(s, M - Inches(0.14), yy - Inches(0.1), W - 2 * M + Inches(0.28), Inches(0.72), PANEL_HI, GREEN)
        text(s, M, yy, Inches(2.8), Inches(0.3), n, 15, GREEN if us else FG, SANS, bold=True)
        text(s, M, yy + Inches(0.26), Inches(2.8), Inches(0.25), tag, 10, FG3, MONO)
        text(s, M + Inches(2.9), yy, Inches(5.1), Inches(0.5), does, 13, FG if us else FG2, SANS, line=1.25)
        text(s, M + Inches(8.2), yy, Inches(4.2), Inches(0.5), gap_, 13, FG if us else FG2, SANS, line=1.25)
    foot(s, "MeshAI", "each one checked against its own source, September 2026")

    # 10 · the business
    s = slide(prs)
    y = head(s, "09 · The business", "We rent memory, not speed.",
             "The models are open and free. What people do not have is the memory to hold them. A phone in a drawer is cheap memory.")
    cw = (W - 2 * M - Inches(0.6)) / 3
    card(s, M, y, cw, Inches(3.4), PANEL_HI, GREEN)
    text(s, M + Inches(0.28), y + Inches(0.26), cw - Inches(0.56), Inches(0.3), "THE FLEET", 11, GREEN, MONO)
    text(s, M + Inches(0.28), y + Inches(0.6), cw - Inches(0.56), Inches(0.8),
         "People give us a phone they no longer use. We keep it, and pay them.", 15, FG, SANS, line=1.3)
    bullets(s, M + Inches(0.28), y + Inches(1.5), cw - Inches(0.56), [
        "Racked and plugged in, in our hands",
        "No battery to ruin, no pocket to leave",
        "We buy no hardware. They earn from a drawer",
    ], size=13, gap=0.42)
    text(s, M + Inches(0.28), y + Inches(2.95), cw - Inches(0.56), Inches(0.5),
         "100 old phones is about 800 GB of memory.", 13, GREEN, SANS, bold=True)

    x2 = M + cw + Inches(0.3)
    card(s, x2, y, cw, Inches(3.4))
    text(s, x2 + Inches(0.28), y + Inches(0.26), cw - Inches(0.56), Inches(0.3), "WHAT A CUSTOMER BUYS", 11, FG3, MONO)
    text(s, x2 + Inches(0.28), y + Inches(0.6), cw - Inches(0.56), Inches(0.8),
         "Enough memory to hold the model they want to run.", 15, FG, SANS, line=1.3)
    for i, (a, b) in enumerate([("A 8B model", "about 6 GB"), ("A 20B model", "about 13 GB"),
                                ("A 30B model", "about 20 GB"), ("A 70B model", "about 45 GB")]):
        yy = y + Inches(1.5 + 0.42 * i)
        text(s, x2 + Inches(0.28), yy, Inches(2.2), Inches(0.3), a, 14, FG, SANS)
        text(s, x2 + Inches(1.7), yy, cw - Inches(2.0), Inches(0.3), b, 14, GREEN, MONO, align=PP_ALIGN.RIGHT)
    text(s, x2 + Inches(0.28), y + Inches(3.15), cw - Inches(0.56), Inches(0.3),
         "Bigger model, more memory, higher tier.", 13, FG2, SANS)

    x3 = M + 2 * (cw + Inches(0.3))
    card(s, x3, y, cw, Inches(3.4))
    text(s, x3 + Inches(0.28), y + Inches(0.26), cw - Inches(0.56), Inches(0.3), "AND TWO MORE WAYS", 11, FG3, MONO)
    for i, (t, d) in enumerate([("Their own devices",
                                 "a lab or a campus turns its idle phones into a private cloud. Sold per site."),
                                ("Licensed to a phone maker",
                                 "two phones are worth more together than apart, which is a reason to own the second.")]):
        yy = y + Inches(0.7 + 1.15 * i)
        text(s, x3 + Inches(0.28), yy, cw - Inches(0.56), Inches(0.3), t, 16, FG, SANS, bold=True)
        text(s, x3 + Inches(0.28), yy + Inches(0.32), cw - Inches(0.56), Inches(0.7), d, 12, FG2, SANS, line=1.3)
    text(s, x3 + Inches(0.28), y + Inches(3.05), cw - Inches(0.56), Inches(0.35),
         "That last buyer is in this room.", 13, GREEN, SANS, bold=True)
    foot(s, "MeshAI", "open models \u00b7 the memory is the scarce part, and that is what we sell")

    # 11 · can we build it
    s = slide(prs)
    y = head(s, "10 · Feasibility", "It already runs. This is not a plan.")
    cw = (W - 2 * M - Inches(0.6)) / 3
    card(s, M, y, cw, Inches(3.5), PANEL_HI, GREEN)
    text(s, M + Inches(0.28), y + Inches(0.26), cw - Inches(0.56), Inches(0.3), "WHAT RAN", 11, GREEN, MONO)
    text(s, M + Inches(0.28), y + Inches(0.6), cw - Inches(0.56), Inches(0.4), "Qwen3-Coder 30B", 19, FG, SANS, bold=True)
    bullets(s, M + Inches(0.28), y + Inches(1.1), cw - Inches(0.56), [
        "18.6 GB across a laptop and two phones", "The phones held 9.6 GB of it",
        "Ready in 70 seconds", "6.6 words a second", "Three coding questions, all correct",
        "Internet off the whole time",
    ], size=13, gap=0.36)
    text(s, M + Inches(0.28), y + Inches(3.1), cw - Inches(0.56), Inches(0.4),
         "Cable at 2–3 ms · context 4096 · laptop capped at 8 GB · 27 September", 10, FG3, MONO, line=1.3)
    x2 = M + cw + Inches(0.3)
    card(s, x2, y, cw, Inches(3.5))
    text(s, x2 + Inches(0.28), y + Inches(0.26), cw - Inches(0.56), Inches(0.3), "BUILT IN THE WINDOW", 11, FG3, MONO)
    bullets(s, x2 + Inches(0.28), y + Inches(0.66), cw - Inches(0.56), [
        "llama.cpp built for phones", "The app: host and helper", "The laptop agent and its panel",
        "Our own model reader and planner", "The layer store", "The test harness", "The CLI and the git hook",
    ], size=13, gap=0.38)
    text(s, x2 + Inches(0.28), y + Inches(3.15), cw - Inches(0.56), Inches(0.3),
         "Every commit timestamped. Nothing pre-built.", 11, FG3, SANS)
    x3 = M + 2 * (cw + Inches(0.3))
    card(s, x3, y, cw, Inches(3.5))
    text(s, x3 + Inches(0.28), y + Inches(0.26), cw - Inches(0.56), Inches(0.3), "IF SOMETHING GOES WRONG", 11, FG3, MONO)
    pairs = [("Slow link", "refused above 60 ms"), ("Phone short of memory", "we reserve, and you can cap it"),
             ("A device drops", "it comes back by itself"), ("Engine missing a flag", "we ask it what it has"),
             ("Bad model file", "every read is checked"), ("A strange machine", "one button checks everything")]
    for i, (a, b) in enumerate(pairs):
        yy = y + Inches(0.68 + 0.45 * i)
        text(s, x3 + Inches(0.28), yy, Inches(1.9), Inches(0.3), a, 13, FG, SANS)
        text(s, x3 + Inches(2.2), yy, cw - Inches(2.5), Inches(0.3), b, 12, FG3, SANS)
    text(s, x3 + Inches(0.28), y + Inches(3.15), cw - Inches(0.56), Inches(0.3),
         "It says no before it disappoints you.", 13, GREEN, SANS, bold=True)
    foot(s, "MeshAI", "two 16 GB phones and one budget laptop · nothing else was bought")

    # 15 · close
    s = slide(prs)
    text(s, M, Inches(2.2), Inches(10), Inches(1.0), "Thank you", 52, FG, SANS, bold=True)
    text(s, M, Inches(3.4), Inches(9.6), Inches(1.0),
         "A big model, on the devices you already own, with the internet switched off.", 21, FG2, SANS, line=1.3)
    for i, (n, d) in enumerate([("Prajwal Gunnala", "github.com/prajwal-gunnala/maynards"),
                                ("Yuva Raj Ambati", "every number here is in that repository")]):
        x = M + Inches(5.6) * i
        text(s, x, Inches(4.7), Inches(5.2), Inches(0.4), n, 20, FG, SANS, bold=True)
        text(s, x, Inches(5.1), Inches(5.2), Inches(0.3), d, 12, FG3, MONO)
    for i, t in enumerate(["30B across three devices", "ready in 70 s", "6.6 words a second", "internet off"]):
        x = M + Inches(3.075 * i)
        c = card(s, x, Inches(5.9), Inches(2.87), Inches(0.5), PANEL_HI, GREEN)
        text(s, x, Inches(6.03), Inches(2.87), Inches(0.3), t, 12, GREEN, MONO, align=PP_ALIGN.CENTER)
    foot(s, "Team Maynards", "iQOO Hackathon 2026 · Developer Tools")

    prs.save(str(OUT))
    print(f"wrote {OUT} ({len(prs.slides._sldIdLst)} slides, every word editable)")


if __name__ == "__main__":
    build()
