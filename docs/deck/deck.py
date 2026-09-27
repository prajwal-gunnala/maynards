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
      8: "DEVELOPERS ON A WEAK LAPTOP", 11: "TWO PERSON TEAMS", 14: "WORKPLACES THAT FORBID CLOUD AI",
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
TITLE_PT = {17: 88}   # 15: a decorative bar that sat behind the template's photography

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
        if n in TITLE_TOP and title_id in ids:
            from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
            from pptx.util import Pt
            t = ids[title_id]
            t.left, t.top, t.width, t.height = Inches(0.52), Inches(1.16), Inches(18.43), Inches(2.92)
            t.text_frame.word_wrap = True
            t.text_frame.vertical_anchor = MSO_ANCHOR.TOP
            for para in t.text_frame.paragraphs:
                para.alignment = PP_ALIGN.LEFT      # some template titles are centred in their own box
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


if __name__ == "__main__":
    main()
