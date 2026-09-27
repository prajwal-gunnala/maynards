#!/usr/bin/env python3
"""
Build the pitch deck from the Canva template: its colours, its type, its layout.
Only the words and the pictures are ours.

    deck-venv/bin/python deck.py            # writes MeshAI_Deck_v4.pptx

Every number here comes from docs/measurements.md or results/REPORT.md in the code
repository. If a number is not in one of those files, it does not belong on a slide.
"""
import copy
import json
import pathlib
import re
from pptx import Presentation
from pptx.util import Inches

HERE = pathlib.Path(__file__).resolve().parent
TEMPLATE = HERE.parent / "maynards.pptx"
OUT = HERE.parent / "MeshAI_Deck_v4.pptx"
SHOTS = HERE / "img"

# ---------------------------------------------------------------- the running heads
HEADS = {
    "CREATIVE BRIEF": "MESH AI",
    "SUMMER 2030 ADVERTISING CAMPAIGN": "IQOO HACKATHON 2026 · DEVELOPER TOOLS",
    "RIMBERIO ACTIVEWEAR": "TEAM MAYNARDS",
    "PRESENTED BY: HARPER RUSSO": "PRAJWAL GUNNALA · YUVA RAJ AMBATI",
}

KEEP = [1, 2, 3, 4, 5, 6, 7, 8, 10, 16, 11, 12, 13, 14, 15, 17, 18]

# (slide number in the template) -> {shape id: new text}
TEXT = {
 1: {8: "IQOO Hackathon 2026"},

 2: {2: "WHAT IF YOU COULD ACTUALLY RUN IT?",
     5: "NO API BILL", 8: "ON YOUR PHONES", 11: "30B CODER",
     14: "REVIEWS YOUR CODE", 17: "ON A BUDGET LAPTOP", 20: "OFFLINE"},

 3: {2: "PROBLEM",
     3: "The model decides the answer, and memory decides the model. A 30B coding model needs "
        "about 18.6 GB of memory to run. A budget laptop has around 4 GB free once a browser and "
        "an editor are open. A phone has 2 to 10 GB. Neither can hold it, so you run a small model "
        "instead and you get answers you cannot use.\n\n"
        "We measured exactly what that costs. The same 15 coding problems, asked of each setup, "
        "scored by running every answer against its own tests. {LADDER}\n\n"
        "Buying a machine that can hold the big model costs more than the laptop. The devices that "
        "could hold it together are already on the table."},

 4: {26: "THE MESH",
     27: "MeshAI pools the free memory of the devices you already own. Phones and laptop join over "
         "a USB cable, one device plans which layers sit where, and the model runs across all of "
         "them behind a single OpenAI compatible endpoint, so the tools you already use do not "
         "change.\n\n"
         "Only a few kilobytes cross the link for each word, because the embeddings and the output "
         "head stay on the host.\n\n"
         "On top of it sits the developer tool: mesh review for a file, mesh tests to write tests for "
         "one, mesh diff for what you are about to commit, and a git hook that reviews every commit "
         "before it lands. All of it against your own devices.",
     7: "ONE ENDPOINT", 10: "PLANS THE SPLIT", 13: "REFUSES BAD DEVICES",
     16: "MESH REVIEW · TESTS · DIFF", 19: "GIT PRE-COMMIT HOOK", 22: "NOTHING LEAVES"},

 5: {2: "WHY NOT BEFORE?",
     "body": "Splitting a model across machines is not new. Doing it across phones is, and the reasons "
        "are physical rather than clever.\n\n"
        "Generating a word reads the whole model out of memory, so a device that holds part of the "
        "model is asked for it once per word. That makes the link, not the chip, the thing that "
        "decides whether a split is usable. Venue Wi-Fi measured 266 to 483 ms round trip and the "
        "planner refuses it. A USB cable measures 2 to 3 ms and it works.\n\n"
        "Loading was the second wall. The first 30B split took 517 seconds, because the engine read "
        "the file in scattered pieces while pushing layers out. Reading it once, front to back, "
        "brought that to 70.\n\n"
        "The research agrees on where the line falls. Petals, the only peer reviewed system to do this at "
        "scale, measured 0.83 tokens per second across two continents. A split is worth it on a link you "
        "own and worthless on one you do not, which is exactly where we drew it."},

 6: {2: "WHAT YOU DO WITH IT",
     "body": "You are about to commit. You tap one button on your phone.\n\n"
        "The phone asks your laptop what you have changed and not committed. The laptop answers over the "
        "cable with the diff. A 30B coding model running across the two phones and the laptop reviews it, "
        "and the review appears on the phone. No internet, no account, no API bill, and the code never "
        "left the desk.\n\n"
        "The same review runs as a git pre-commit hook, so every commit is read before it lands, and as "
        "mesh review on a file, or mesh tests to write the tests for one. One command to install, and it "
        "costs nothing on the ten thousandth run."},

 7: {2: "HOW IT WORKS",
     "body": "One device is the host. It reads every device's free memory, heat, battery and round trip "
        "time, then gives each one an unbroken run of layers it can actually hold, largest first. "
        "The embeddings and the output head stay on the host.\n\n"
        "Measured on our own hardware: laptop layers 0 to 22, one phone 23 to 42, the other 43 to 47. "
        "The phones hold 9.6 GB of the 18.6. What crosses the cable is one hidden state per word, about "
        "4 KB, read from the model's own header. The 18.6 GB of weights never move, and the app draws "
        "that traffic live while it answers."},

 8: {2: "WHAT MADE IT WORK",
     "body": "Read the file once, front to back: load 517 s to 70 s.\n\n"
        "Every phone keeps its own layers on its own storage, so a split it has never used before is "
        "ready in 70 s instead of 437.\n\n"
        "One conversation slot, so a long chat stops falling out of the cache.\n\n"
        "The median of ten round trips, not the average, so one Wi-Fi spike does not evict a healthy "
        "phone.\n\n"
        "And it refuses. Too slow to reach, too hot, nearly flat, or the model already fits one "
        "device: it says which, in a sentence you can read."},

 10: {3: "TECH STACK",
      4: "Any GGUF model",
      5: "Kotlin, Compose",
      6: "Rust agent",
      7: "llama.cpp, ggml RPC",
      8: "USB tethering",
      9: "OpenAI-style API",
      10: "Android NDK arm64",
      11: "CLI and git hook",
      12: "No cloud at all"},

 11: {3: "FEASIBILITY",
      6: "IT RUNS TODAY: 30B ACROSS A LAPTOP AND TWO PHONES",
      9: "READY IN 70 SECONDS, 6.6 TOKENS PER SECOND",
      12: "BUILT AND MEASURED INSIDE THE EVENT WINDOW"},

 12: {2: "LIMITS",
      6: "NOT FASTER. IT BUYS MEMORY, NOT SPEED",
      3: "Every word crosses the link once, so a split is slower than one device that can already "
         "hold the model. Our own planner will tell you so and refuse to involve your phone. We "
         "split only when the model fits nowhere else.",
      10: "PRIVATE ACROSS YOUR OWN DEVICES ONLY",
      7: "Nothing leaves the devices you own, and the demo runs with the internet off. We do not "
         "claim that activations sent to someone else's phone are private, because published work "
         "turns them back into text."},

 13: {2: "IMPACT",
      6: "THE CEILING MOVES",
      3: "The quality of AI you can run on your own hardware is set by memory. Pool the memory and "
         "the ceiling moves: the same devices that could only hold a small model now hold a 30B "
         "coding model, and the answers change accordingly.",
      10: "PHONE FIRST IS NOT A GIMMICK HERE",
      7: "In rural India 94.2% of households have a phone and 4.2% have a computer (MoSPI, 2022-23). "
         "That is not our first user and we will not pretend it is. It is why the phone has to be the "
         "device that works rather than the accessory: for most people it is the only computer in the "
         "house, and as open models improve at a given size, what it can hold matters more every year."},

 14: {2: "WHO IT IS FOR",
      8: "DEVELOPERS ON A WEAK LAPTOP", 11: "TWO PERSON TEAMS", 14: "NO-CLOUD WORKPLACES",
      17: "STUDENTS WITH A GOOD PHONE", 20: "FIELD WORK WITH NO SIGNAL", 23: "ANY CAPABLE ANDROID"},

 15: {7: "THE EVIDENCE"},

 # the timeline layout, reused: six systems, and what each one leaves undone
 16: {2: "WHAT ALREADY EXISTS"},

 17: {2: "THE NUMBERS",
      5: "30B RUNNING ACROSS THREE DEVICES, 18.6 GB POOLED",
      8: "PHONES HOLD 9.6 GB OF IT, THE LAPTOP HOLDS THE HEAD",
      11: "READY IN 70 SECONDS, 6.6 TOKENS PER SECOND",
      14: "4 KB CROSSES PER WORD, 18.6 GB NEVER MOVES"},

 18: {5: "TEAM MAYNARDS",
      9: "Prajwal Gunnala", 10: "github.com/prajwal-gunnala/maynards",
      13: "Yuva Raj Ambati", 14: "every number in this deck is in that repository"},
}

# slides whose template layout is a title only: we add the body box ourselves
# slides whose title the template centres down the page: ours sits where every other title sits
TITLE_TOP = {5, 6, 7, 8, 11, 15, 17}

BODY_BOX = {5: (0.52, 4.40, 18.95, 6.00), 6: (0.52, 4.40, 18.95, 6.00),
            7: (0.52, 4.40, 18.95, 2.60), 8: (0.52, 4.40, 18.95, 6.00)}

# template shapes to remove: display type left over from a layout we are reusing for body copy
DROP = {6: [9], 15: [8]}

# slides where we keep the title and the running heads and nothing else, because the picture is the slide
ONLY = {16: [22, 23, 24, 25]}   # 16 carries its own heading inside the picture
DROP_MORE = {18: [17, 18]}      # the template's third person; this team is two

# Slides whose text was white because a photograph sat behind it. The photograph is gone, so the
# words have to come back to ink or they vanish into the paper.
INK_TEXT = {11}

# the title box is not always shape 2
TITLE_ID = {11: 3, 15: 7}

# a long title in the template's display size runs off the slide; these get their own size, in points
TITLE_PT = {2: 88, 12: 150, 14: 120, 17: 88}   # 15: a decorative bar that sat behind the template's photography

# pictures: (slide, image file, left, top, width) in inches. The stock photos are removed first.
PICTURES = {
 3: [("fits.png", 0.44, 5.35, 10.20)],
 4: [("app-host.png", 15.90, 4.30, 3.11)],
 6: [("flow.png", 0.52, 7.15, 18.95)],
 7: [("split.png", 0.52, 7.55, 18.95)],
 15: [("accuracy.png", 3.25, 4.60, 13.50)],
 16: [("compare.png", 0.90, 1.00, 18.20)],
}


def ladder_sentence():
    """One sentence of real benchmark numbers, or nothing if they are not measured yet."""
    rows = []
    d = HERE.parent.parent / "maynards" / "results"
    for f in sorted(d.glob("*.json")) if d.exists() else []:
        r = json.loads(f.read_text())
        if r.get("tasks") == 15:
            rows.append(r)
    if not rows:
        return "The results table is in the repository."
    rows.sort(key=lambda r: r["accuracy"])
    bits = [f"{r['label']} passed {r['passed']} of {r['tasks']}" for r in rows]
    return "; ".join(bits) + "."


def set_text(shape, text):
    """Replace the words, keep every bit of formatting the template gave the first run."""
    tf = shape.text_frame
    first = tf.paragraphs[0]
    keep = first.runs[0] if first.runs else None
    for p in list(tf.paragraphs)[1:]:
        p._p.getparent().remove(p._p)
    for r in list(first.runs)[1:]:
        r._r.getparent().remove(r._r)
    blocks = text.split("\n\n")
    if keep is None:
        tf.text = blocks[0]
    else:
        keep.text = blocks[0]
    for b in blocks[1:]:
        p = copy.deepcopy(first._p)
        first._p.getparent().append(p)
        from pptx.text.text import _Paragraph
        para = _Paragraph(p, tf)
        if para.runs:
            para.runs[0].text = b
            for r in list(para.runs)[1:]:
                r._r.getparent().remove(r._r)


def add_body(slide, template_body, text, box):
    """Slides the template left as a title only get a body in the template's own body style."""
    el = copy.deepcopy(template_body._element)
    slide.shapes._spTree.append(el)
    shape = slide.shapes[-1]
    l, t, w, h = box
    shape.left, shape.top, shape.width, shape.height = Inches(l), Inches(t), Inches(w), Inches(h)
    set_text(shape, text)
    return shape


def by_id(slide):
    out = {}
    def walk(shapes):
        for sh in shapes:
            out[sh.shape_id] = sh
            if sh.shape_type == 6:
                walk(sh.shapes)
    walk(slide.shapes)
    return out


def is_photo(shape):
    """Any template photography: a picture, or a freeform or group filled with one."""
    return "a:blipFill" in shape._element.xml or shape.shape_type == 13   # 13 = PICTURE


def morph(slide, ms=900):
    """A morph transition, so text that appears on two slides slides into place instead of blinking."""
    ns = ('xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" '
          'xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main" '
          'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
          'xmlns:p159="http://schemas.microsoft.com/office/powerpoint/2015/09/main"')
    xml = (f'<mc:AlternateContent {ns}>'
           f'<mc:Choice Requires="p14"><p:transition spd="slow" p14:dur="{ms}">'
           f'<p159:morph option="byObject"/></p:transition></mc:Choice>'
           f'<mc:Fallback><p:transition spd="slow"><p:fade/></p:transition></mc:Fallback>'
           f'</mc:AlternateContent>')
    from pptx.oxml import parse_xml
    el = parse_xml(xml)
    sld = slide._element
    # the transition belongs after cSld and clrMapOvr
    after = sld.find('{http://schemas.openxmlformats.org/presentationml/2006/main}clrMapOvr')
    if after is not None:
        after.addnext(el)
    else:
        sld.append(el)


def main():
    prs = Presentation(str(TEMPLATE))
    ladder = ladder_sentence()
    template_body = by_id(prs.slides[2])[3]   # slide 3's paragraph, the body style of this deck

    for n in KEEP:
        slide = prs.slides[n - 1]
        ids = by_id(slide)
        for sid in DROP.get(n, []) + DROP_MORE.get(n, []):
            if sid in ids:
                ids[sid]._element.getparent().remove(ids[sid]._element)
        if n in ONLY:
            for sid, sh in list(ids.items()):
                if sid not in ONLY[n]:
                    parent = sh._element.getparent()
                    if parent is not None:
                        parent.remove(sh._element)
        body = TEXT.get(n, {}).get("body")
        if body and n in BODY_BOX:
            add_body(slide, template_body, body.replace("{LADDER}", ladder), BODY_BOX[n])
        for sid, text in TEXT.get(n, {}).items():
            if sid == "body":
                continue
            if sid in ids and ids[sid].has_text_frame:
                set_text(ids[sid], text.replace("{LADDER}", ladder))
        for sh in list(slide.shapes):
            if sh.has_text_frame and sh.text_frame.text.strip() in HEADS:
                set_text(sh, HEADS[sh.text_frame.text.strip()])
        # after the words are in, because replacing the text rebuilds the runs and would undo this
        title_id = TITLE_ID.get(n, 2)
        if title_id in ids and (n in TITLE_TOP or n in TITLE_PT):
            from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
            from pptx.util import Pt
            t = ids[title_id]
            if n in TITLE_TOP:
                t.left, t.top, t.width, t.height = Inches(0.52), Inches(1.16), Inches(18.43), Inches(2.92)
                t.text_frame.vertical_anchor = MSO_ANCHOR.TOP
            t.text_frame.word_wrap = True
            for para in t.text_frame.paragraphs:
                if n in TITLE_TOP:
                    para.alignment = PP_ALIGN.LEFT   # some template titles are centred in their own box
                if n in TITLE_PT:
                    # both the paragraph default and every run: renderers disagree about which one wins
                    para.font.size = Pt(TITLE_PT[n])
                    for run in para.runs:
                        run.font.size = Pt(TITLE_PT[n])
        # The stock photography goes and ours takes its place. Some of it is nested inside groups, so
        # this walks the tree, but it never touches anything holding text: on some layouts the group is
        # what positions the words, and removing it piles them on top of each other.
        def holds_text(sh):
            if sh.shape_type == 6:
                return any(holds_text(x) for x in sh.shapes)
            return sh.has_text_frame and sh.text_frame.text.strip() != ""

        def strip(shapes):
            for sh in list(shapes):
                if holds_text(sh):
                    continue
                if is_photo(sh):
                    sh._element.getparent().remove(sh._element)
                elif sh.shape_type == 6:
                    strip(sh.shapes)
        strip(slide.shapes)
        if n in INK_TEXT:
            from pptx.dml.color import RGBColor
            def darken(shapes):
                for sh in shapes:
                    if sh.shape_type == 6:
                        darken(sh.shapes)
                    elif sh.has_text_frame:
                        for para in sh.text_frame.paragraphs:
                            for run in para.runs:
                                run.font.color.rgb = RGBColor(0x11, 0x11, 0x11)
            darken(slide.shapes)
        for name, l, t, w in PICTURES.get(n, []):
            f = SHOTS / name
            if f.exists():
                slide.shapes.add_picture(str(f), Inches(l), Inches(t), width=Inches(w))
            else:
                print(f"  missing picture: {name}")

    # drop the slides we are not using, last first so the indices hold
    lst = prs.slides._sldIdLst
    ids = list(lst)
    for i in sorted(set(range(1, len(ids) + 1)) - set(KEEP), reverse=True):
        lst.remove(ids[i - 1])

    for slide in prs.slides:
        morph(slide)

    prs.save(str(OUT))
    print(f"wrote {OUT} ({len(prs.slides.__iter__.__self__._sldIdLst)} slides)")


# ================================================================ v5: two slides on top of v4
#
#     deck-venv/bin/python deck.py --v5     # draws img/milestones.png and img/standing.png,
#                                           # writes MeshAI_Deck_v5.pptx next to this file
#
# v5 opens the finished v4 deck and adds two slides before the closing one; nothing in v4 changes.
# Our numbers: docs/measurements.md, results/REPORT.md, and the Aider runs recorded in commits
# ccb7003 (8B, laptop alone) and 5255cfe (30B, laptop + 2 phones). Cloud numbers: the sources
# printed on the slide, read 27 Sep 2026. Anything not measured says "est." where it stands.

V4 = HERE / "MeshAI_Deck_v4.pptx"
V5 = HERE / "MeshAI_Deck_v5.pptx"

PAPER, INK, GREY, RULE, ORANGE = (239, 239, 236), (17, 17, 17), (95, 95, 95), (200, 200, 195), (242, 107, 29)
DPI = 200   # pixels per inch of slide: the pictures are drawn at twice the size they are shown


def _font(size, bold=False):
    from PIL import ImageFont
    name = "LiberationSans-Bold.ttf" if bold else "LiberationSans-Regular.ttf"
    for d in ("/usr/share/fonts/truetype/liberation", "/usr/share/fonts/truetype/liberation2"):
        p = pathlib.Path(d) / name
        if p.exists():
            return ImageFont.truetype(str(p), size)
    return ImageFont.load_default(size)


def _wrap(d, text, font, width):
    lines, line = [], ""
    for w in text.split():
        t = (line + " " + w).strip()
        if d.textlength(t, font=font) <= width or not line:
            line = t
        else:
            lines.append(line)
            line = w
    return lines + ([line] if line else [])


def _dashed_rect(d, box, color, width=5, dash=26, gap=16, radius=0):
    x0, y0, x1, y1 = box
    for (ax, ay, bx, by) in ((x0, y0, x1, y0), (x1, y0, x1, y1), (x1, y1, x0, y1), (x0, y1, x0, y0)):
        length = max(abs(bx - ax), abs(by - ay))
        s = 0
        while s < length:
            e = min(s + dash, length)
            fx, fy = (bx - ax) / length, (by - ay) / length
            d.line((ax + fx * s, ay + fy * s, ax + fx * e, ay + fy * e), fill=color, width=width)
            s = e + gap


def _phone(d, x, y, h, color, fill=None):
    w = int(h * 0.52)
    d.rounded_rectangle((x, y, x + w, y + h), radius=int(h * 0.12), outline=color, width=5, fill=fill)
    d.line((x + w * 0.35, y + h * 0.1, x + w * 0.65, y + h * 0.1), fill=color, width=5)
    return w


def _laptop(d, x, y, h, color):
    w = int(h * 1.45)
    d.rounded_rectangle((x + w * 0.1, y, x + w * 0.9, y + h * 0.78), radius=8, outline=color, width=5)
    d.polygon([(x, y + h), (x + w, y + h), (x + w * 0.92, y + h * 0.84), (x + w * 0.08, y + h * 0.84)],
              outline=color, fill=color)
    return w


def draw_milestones(path):
    """Left to right: what is done (solid) and what is next (outlined)."""
    from PIL import Image, ImageDraw
    W, H = int(18.95 * DPI), int(7.9 * DPI)
    im = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(im)
    steps = [
        ("26 SEP", True, "Two models, two phones", "12.3 · 19.1",
         ["tok/s, text · vision", "Qwen3-8B on one phone", "Qwen2.5-VL-3B on the other",
          "the laptop routes each question"]),
        ("26 SEP", True, "Layers split across devices", "2–3 ms",
         ["llama.cpp RPC over a USB cable", "venue Wi-Fi 266–483 ms: refused",
          "first phone split: 9.2 tok/s"]),
        ("26–27 SEP", True, "30B coder on laptop + 2 phones", "6.6–7.1 tok/s",
         ["Qwen3-Coder-30B, 18.6 GB", "ready in 70 s, was 437–517 s",
          "layers stored and pinned per phone"]),
        ("27 SEP", True, "Agentic coding on the mesh", "5/5 tests",
         ["Aider + the 30B fixed a real bug", "tests pass in 603 s (~10 min)", "5.4 tok/s"]),
        ("NEXT", False, "Mesh clusters", "more devices",
         ["more phones and laptops", "bigger, better models"]),
        ("NEXT", False, "Token subscription", "cheaper tokens",
         ["spare phones supply compute", "agentic coding within reach",
          "cost: time, first word ~1–2 min on long prompts (est.)"]),
    ]
    n, gap, left = len(steps), 44, 0
    col = (W - gap * (n - 1)) / n
    f_date, f_title, f_big, f_body = _font(44, True), _font(66, True), _font(108, True), _font(58)
    f_key = _font(38)
    line_y = 150
    # the line: solid through what is done, dashed after today
    done_end = left + 4 * (col + gap) - gap / 2
    d.line((0, line_y, done_end, line_y), fill=INK, width=8)
    x = done_end
    while x < W:
        d.line((x, line_y, min(x + 34, W), line_y), fill=INK, width=8)
        x += 56
    # today
    d.line((done_end, line_y - 70, done_end, line_y + 70), fill=ORANGE, width=8)
    t = "TODAY"
    d.text((done_end - d.textlength(t, font=f_date) / 2, line_y - 122), t, font=f_date, fill=ORANGE)

    top, bottom = line_y + 110, H - 90
    for i, (when, done, title, big, lines) in enumerate(steps):
        x0 = left + i * (col + gap)
        cx = x0 + 40
        r = 30
        if done:
            d.ellipse((cx - r, line_y - r, cx + r, line_y + r), fill=INK)
        else:
            d.ellipse((cx - r, line_y - r, cx + r, line_y + r), fill=PAPER, outline=INK, width=7)
        d.text((cx + r + 18, line_y - 64), f"{i + 1} · {when}", font=f_date, fill=INK if done else GREY)
        box = (x0, top, x0 + col, bottom)
        fg = PAPER if done else INK
        if done:
            d.rectangle(box, fill=INK)
        else:
            _dashed_rect(d, box, INK, width=6)
        pad = 34
        y = top + pad
        for ln in _wrap(d, title.upper(), f_title, col - 2 * pad):
            d.text((x0 + pad, y), ln, font=f_title, fill=fg)
            y += 78
        y += 34
        fb = f_big
        while d.textlength(big, font=fb) > col - 2 * pad:
            fb = _font(fb.size - 4, True)
        d.text((x0 + pad, y), big, font=fb, fill=ORANGE if (done and i == 2) else fg)
        y += fb.size + 44
        d.line((x0 + pad, y, x0 + col - pad, y), fill=(90, 90, 90) if done else RULE, width=3)
        y += 40
        for ln in lines:
            for w in _wrap(d, ln, f_body, col - 2 * pad):
                d.text((x0 + pad, y), w, font=f_body, fill=fg)
                y += 70
            y += 30
    # the key
    ky = H - 58
    d.rectangle((0, ky, 44, ky + 40), fill=INK)
    d.text((62, ky - 2), "done, measured", font=f_key, fill=INK)
    kx = 62 + d.textlength("done, measured", font=f_key) + 60
    _dashed_rect(d, (kx, ky, kx + 44, ky + 40), INK, width=4, dash=10, gap=6)
    d.text((kx + 62, ky - 2), "next, planned", font=f_key, fill=INK)
    src = "sources: docs/measurements.md · Aider runs in commits ccb7003, 5255cfe"
    d.text((W - d.textlength(src, font=f_key), ky - 2), src, font=f_key, fill=GREY)
    im.save(path)


# Cloud rows, read 27 Sep 2026. Price: the vendors' pricing pages. Index and speed: Artificial Analysis,
# Intelligence Index v4.3.2, each model at max effort. Their first-token time includes the thinking.
STANDING = [
    # name, sub, speed, first token, price, index, private/offline, kind
    ("Claude Fable 5.1", "Anthropic · top tier", "69 tok/s", "1st token 246 s*", "$10 / $50", 53, "no · no", "cloud"),
    ("Claude Sonnet 5", "Anthropic · cheaper tier", "80 tok/s", "1st token 170 s*", "$2 / $10", 38, "no · no", "cloud"),
    ("GPT-6 Astra", "OpenAI · top tier", "63 tok/s", "1st token 324 s*", "$10 / $50", 53, "no · no", "cloud"),
    ("GPT-6 Sol", "OpenAI · cheaper tier", "86 tok/s", "1st token 138 s*", "$2 / $10", 48, "no · no", "cloud"),
    ("Local, one device", "Qwen3-8B on our laptop · Ollama, LM Studio",
     "2.5 tok/s", "1st token 287 s (Aider)", "$0 †", 6, "yes · yes", "local"),
    ("MeshAI", "Qwen3-Coder-30B · laptop + 2 phones",
     "6.6–7.1 tok/s", "1st token ~1–2 min (est.)", "$0 †", 10, "yes · yes", "ours"),
    ("Bigger local models", "more devices in the mesh",
     "est.", "", "$0 †", None, "yes · yes", "next"),
]


def draw_standing(path):
    """Cloud against local against us, one scale for intelligence, and the device setups under it."""
    from PIL import Image, ImageDraw
    W, H = int(18.95 * DPI), int(8.3 * DPI)
    im = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(im)
    f_head, f_name, f_sub, f_cell, f_small = _font(34), _font(50, True), _font(34), _font(48), _font(34)
    cols = [0, 1030, 1750, 2330, 3280]          # setup, speed, price, index, private
    heads = ["SETUP", "SPEED", "PRICE / 1M TOKENS  IN / OUT", "INTELLIGENCE · AA INDEX v4.3.2",
             "PRIVATE · OFFLINE"]
    for x, h in zip(cols, heads):
        d.text((x, 0), h, font=f_head, fill=INK)
    y = 58
    d.line((0, y, W, y), fill=INK, width=4)
    row_h = 138
    bar_x, bar_w = cols[3], 640
    for name, sub, speed, first, price, idx, priv, kind in STANDING:
        ours = kind == "ours"
        fg, sub_fg = (PAPER, (200, 200, 195)) if ours else (INK, GREY)
        if ours:
            d.rectangle((0, y + 4, W, y + row_h), fill=INK)
        pad = 24 if ours else 0
        ty = y + 20
        d.text((pad, ty), name, font=f_name, fill=fg)
        d.text((pad, ty + 60), sub, font=f_sub, fill=sub_fg)
        d.text((cols[1], ty), speed, font=f_cell, fill=fg)
        if first:
            d.text((cols[1], ty + 60), first, font=f_sub, fill=sub_fg)
        d.text((cols[2], ty), price, font=f_cell, fill=fg)
        d.text((cols[2], ty + 60), "per token, local" if kind != "cloud" else "per 1M tokens",
               font=f_sub, fill=sub_fg)
        by = ty + 8
        if idx is None:
            _dashed_rect(d, (bar_x, by, bar_x + bar_w, by + 40), INK, width=4, dash=14, gap=10)
            d.text((bar_x + bar_w + 24, ty), "more", font=f_cell, fill=fg)
            d.text((bar_x, ty + 60), "est.", font=f_sub, fill=sub_fg)
        else:
            d.rectangle((bar_x, by, bar_x + bar_w, by + 40), outline=(90, 90, 90) if ours else RULE, width=2)
            d.rectangle((bar_x, by, bar_x + bar_w * idx / 60, by + 40), fill=ORANGE if ours else fg)
            d.text((bar_x + bar_w + 24, ty), str(idx), font=f_cell, fill=fg)
        d.text((cols[4], ty), priv, font=f_cell, fill=fg)
        y += row_h
        if not ours:
            d.line((0, y, W, y), fill=RULE, width=2)

    # the device setups, measured
    y += 40
    d.text((0, y), "WHAT EACH SETUP RUNS, MEASURED", font=f_head, fill=INK)
    y += 56
    cards = [
        ("phone", "ONE PHONE", ["Qwen3-0.6B  78.1 tok/s", "Qwen3-8B  11.8 tok/s,", "5.3 after 10 min (heat)"]),
        ("laptop", "ONE LAPTOP", ["Qwen3-1.7B  8.6 tok/s, 7/15 tests", "Qwen3-8B  2.5 tok/s writing,",
                                  "9.6 tok/s reading (Aider)"]),
        ("mesh", "LAPTOP + 2 PHONES", ["Qwen3-Coder-30B  6.6–7.1 tok/s", "18.6 GB: no single device",
                                       "here can hold it"]),
        ("next", "NEXT (EST.)", ["more phones and laptops", "→ bigger, better models"]),
    ]
    gap = 40
    cw = (W - gap * 3) / 4
    ch = 400
    f_ct, f_cb = _font(44, True), _font(42)
    for i, (kind, title, lines) in enumerate(cards):
        x0 = i * (cw + gap)
        box = (x0, y, x0 + cw, y + ch)
        dark = kind == "mesh"
        fg = PAPER if dark else INK
        if dark:
            d.rectangle(box, fill=INK)
        elif kind == "next":
            _dashed_rect(d, box, INK, width=5)
        else:
            d.rectangle(box, outline=INK, width=4)
        ix, iy, ih = x0 + 30, y + 30, 96
        if kind == "phone":
            _phone(d, ix, iy, ih, fg)
        elif kind == "laptop":
            _laptop(d, ix, iy + 14, ih - 14, fg)
        else:
            w = _laptop(d, ix, iy + 14, ih - 14, fg)
            _phone(d, ix + w + 22, iy, ih, fg)
            w2 = _phone(d, ix + w + 22 + int(ih * 0.52) + 18, iy, ih, fg)
            if kind == "next":
                px = ix + w + 22 + 2 * (int(ih * 0.52) + 18)
                d.text((px, iy + 20), "+ …", font=f_ct, fill=fg)
        d.text((x0 + 30, y + 152), title, font=f_ct, fill=ORANGE if dark else fg)
        ly = y + 214
        for ln in lines:
            d.text((x0 + 30, ly), ln, font=f_cb, fill=fg)
            ly += 54

    # sources, short
    y += ch + 28
    src = ("* first token at max effort, includes thinking.   † electricity and hardware you already own not counted.   "
           "Sources, 27 Sep 2026: claude.com/pricing · developers.openai.com/api/docs/pricing · "
           "artificialanalysis.ai (Intelligence Index v4.3.2) · ours: docs/measurements.md, results/REPORT.md, "
           "commits ccb7003, 5255cfe")
    for ln in _wrap(d, src, f_small, W):
        d.text((0, y), ln, font=f_small, fill=GREY)
        y += 42
    im.save(path)


def build_v5():
    from pptx.util import Pt
    draw_milestones(SHOTS / "milestones.png")
    draw_standing(SHOTS / "standing.png")
    prs = Presentation(str(V4))
    # v4 still carries the part of a template slide it dropped from the list. On save python-pptx
    # renumbers the listed slides, and one of them lands on that orphan's name: drop the orphan.
    listed = {sid.rId for sid in prs.slides._sldIdLst}
    for rId, rel in list(prs.part.rels.items()):
        if rel.reltype.endswith("/slide") and rId not in listed:
            prs.part.drop_rel(rId)
    slides = list(prs.slides)
    # the base: the "what already exists" slide (running heads and paper, a picture as the body) ...
    base = next(s for s in slides if any(sh.shape_type == 13 for sh in s.shapes)
                and any(sh.has_text_frame and sh.text_frame.text.strip() == "MESH AI" for sh in s.shapes)
                and sum(1 for sh in s.shapes) == 5)
    # ... and a title in the deck's display type, taken from "what made it work"
    title_src = next(sh for s in slides for sh in s.shapes
                     if sh.has_text_frame and sh.text_frame.text.strip() == "WHAT MADE IT WORK")
    for title, pic, top, width in (("MILESTONES", "milestones.png", 2.45, 18.95),
                                   ("WHERE WE STAND", "standing.png", 2.3, 18.95)):
        s = prs.slides.add_slide(base.slide_layout)
        for ph in list(s.placeholders):
            ph._element.getparent().remove(ph._element)
        bg = base._element.cSld.bg
        if bg is not None:
            s._element.cSld.insert(0, copy.deepcopy(bg))
        for sh in base.shapes:
            if sh.shape_type != 13:
                s.shapes._spTree.append(copy.deepcopy(sh._element))
        el = copy.deepcopy(title_src._element)
        s.shapes._spTree.append(el)
        t = s.shapes[-1]
        t.left, t.top, t.width, t.height = Inches(0.52), Inches(0.95), Inches(18.43), Inches(1.3)
        set_text(t, title)
        for para in t.text_frame.paragraphs:
            para.font.size = Pt(88)
            para.line_spacing = Pt(88)   # the source's fixed spacing was for 68 pt
            for run in para.runs:
                run.font.size = Pt(88)
        s.shapes.add_picture(str(SHOTS / pic), Inches(0.52), Inches(top), width=Inches(width))
        morph(s)
        # before the closing slide
        lst = prs.slides._sldIdLst
        new = list(lst)[-1]
        lst.remove(new)
        lst.insert(len(lst) - 1, new)
    prs.save(str(V5))
    print(f"wrote {V5} ({len(prs.slides._sldIdLst)} slides)")


if __name__ == "__main__":
    import sys
    if "--v5" in sys.argv:
        build_v5()
    else:
        main()
