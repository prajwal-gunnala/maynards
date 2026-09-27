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

KEEP = [1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 18]

# The order a pitch wants, which is not the order the template shipped in: hook, problem, what it is,
# what you do with it, how it is built, what it took, proof, prior art, the business, the honest limits,
# who it is for, what it is made of, and the ask.
ORDER = [1, 2, 3, 4, 6, 7, 8, 5, 15, 16, 17, 11, 12, 13, 14, 10, 18]

# (slide number in the template) -> {shape id: new text}
TEXT = {
 1: {8: "IQOO Hackathon 2026"},

 2: {2: "WHAT IF YOU COULD ACTUALLY RUN IT?",
     5: "NO API BILL", 8: "ON YOUR PHONES", 11: "30B CODER",
     14: "REVIEWS YOUR CODE", 17: "ON A BUDGET LAPTOP", 20: "OFFLINE"},

 3: {2: "PROBLEM",
     3: "A 30B coding model needs 18.6 GB to run\n"
        "A budget laptop has about 4 GB free\n"
        "A phone has 2 to 10 GB\n"
        "Neither can hold it, so you run something small\n"
        "\n"
        "Small models are not almost as good. They are unusable:\n"
        "{LADDER}\n"
        "\n"
        "A machine that can hold the big model costs more than the laptop"},

 4: {26: "THE MESH",
     27: "Pool the free memory of devices you already own\n"
         "One device plans, the others hold layers\n"
         "One OpenAI-compatible endpoint, so your tools do not change\n"
         "About 4 KB crosses the cable per word\n"
         "The 18.6 GB of weights never move\n"
         "Internet off throughout",
     7: "ONE ENDPOINT", 10: "PLANS THE SPLIT", 13: "REFUSES BAD DEVICES",
     16: "MESH REVIEW · TESTS · DIFF", 19: "GIT PRE-COMMIT HOOK", 22: "NOTHING LEAVES"},

 5: {2: "WHY NOT BEFORE?",
     "body": "Every word waits for the link, so the link decides everything\n"
        "Venue Wi-Fi: 266 to 483 ms. The planner refuses it\n"
        "A USB cable: 2 to 3 ms. It works\n"
        "\n"
        "Loading was the second wall\n"
        "First 30B split: 517 seconds\n"
        "Read the file once, front to back: 70 seconds\n"
        "\n"
        "Petals measured 0.83 tokens per second across two continents\n"
        "A split is worth it on a link you own, and worthless on one you do not"},

 6: {2: "WHAT YOU DO WITH IT",
     "body": "You are about to commit. You tap one button on your phone\n"
        "The phone asks your laptop what you changed\n"
        "A 30B across two phones and the laptop reviews it\n"
        "The review appears on the phone\n"
        "\n"
        "No internet · no account · no API bill · the code never left the desk\n"
        "The same review installs as a git pre-commit hook, in one command"},

 7: {2: "ARCHITECTURE"},

 8: {2: "WHAT MADE IT WORK",
     "body": "Read the file once, front to back: load 517 s to 70 s\n"
        "Each device keeps its own layers: an unseen split 437 s to 70 s\n"
        "One conversation slot, so a long chat stays in the cache\n"
        "Embeddings and output head pinned to the host\n"
        "Median of ten round trips, so one spike does not evict a good device\n"
        "It refuses, in words: too slow, too hot, nearly flat, or it already fits"},

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
      9: "READY IN 70 SECONDS · 6.6 TOKENS PER SECOND",
      12: "BUILT AND MEASURED INSIDE THE EVENT WINDOW"},

 12: {2: "LIMITS",
      6: "NOT FASTER. IT BUYS MEMORY",
      3: "Every word crosses the link once\n"
         "A split is slower than one device that already fits\n"
         "Our planner says so and refuses your phone\n"
         "We split only when the model fits nowhere else",
      10: "PRIVATE ACROSS YOUR OWN DEVICES",
      7: "Nothing leaves the devices you own\n"
         "The demo runs with the internet off\n"
         "We do not claim activations sent to a stranger are private\n"
         "Published work turns them back into text"},

 13: {2: "IMPACT",
      6: "THE CEILING MOVES",
      3: "Memory decides which model you can run\n"
         "Pool the memory and the ceiling moves\n"
         "The same devices now hold a 30B\n"
         "The answers change accordingly",
      10: "PHONE FIRST IS NOT A GIMMICK",
      7: "Rural India: 94.2% have a phone, 4.2% a computer\n"
         "Not our first user, and we will not pretend it is\n"
         "It is why the phone has to be the device that works\n"
         "As open models improve, what it holds matters more"},

 14: {2: "WHO IT IS FOR",
      8: "DEVELOPERS ON A WEAK LAPTOP", 11: "TWO PERSON TEAMS", 14: "NO-CLOUD WORKPLACES",
      17: "STUDENTS WITH A GOOD PHONE", 20: "FIELD WORK WITH NO SIGNAL", 23: "ANY CAPABLE ANDROID"},

 15: {7: "THE EVIDENCE"},

 16: {2: "WHAT ALREADY EXISTS"},

 17: {},   # the whole slide is the picture, heading included

 18: {5: "TEAM MAYNARDS",
      9: "Prajwal Gunnala", 10: "github.com/prajwal-gunnala/maynards",
      13: "Yuva Raj Ambati", 14: "every number in this deck is in that repository"},
}

# slides whose template layout is a title only: we add the body box ourselves
# slides whose title the template centres down the page: ours sits where every other title sits
TITLE_TOP = {5, 6, 7, 8, 11, 15}

BODY_BOX = {5: (0.52, 4.20, 18.95, 6.40), 6: (0.52, 4.20, 18.95, 6.40),
            8: (0.52, 4.20, 18.95, 6.40)}

# template shapes to remove: display type left over from a layout we are reusing for body copy
DROP = {6: [9], 15: [8]}

# slides where we keep the title and the running heads and nothing else, because the picture is the slide
ONLY = {7: [2, 3, 4, 5, 6], 16: [22, 23, 24, 25], 17: [15, 16, 17, 18]}   # 16 carries its own heading inside the picture
DROP_MORE = {18: [17, 18]}      # the template's third person; this team is two

# Slides whose text was white because a photograph sat behind it. The photograph is gone, so the
# words have to come back to ink or they vanish into the paper.
INK_TEXT = {11}

# the title box is not always shape 2
TITLE_ID = {11: 3, 15: 7}

# a long title in the template's display size runs off the slide; these get their own size, in points
TITLE_PT = {2: 88, 12: 150, 14: 120}   # 15: a decorative bar that sat behind the template's photography

# pictures: (slide, image file, left, top, width) in inches. The stock photos are removed first.
PICTURES = {
 3: [("fits.png", 0.44, 5.35, 10.20)],
 4: [("app-new.png", 11.60, 5.90, 7.90)],
 6: [("flow.png", 0.52, 7.15, 18.95)],
 7: [("split.png", 0.52, 7.55, 18.95)],
 15: [("accuracy.png", 3.25, 4.60, 13.50)],
 7: [("arch.png", 1.30, 3.35, 17.40)],
 16: [("compare.png", 0.90, 1.00, 18.20)],
 17: [("biz.png", 0.90, 1.00, 18.20)],
}


def ladder_sentence():
    """The measured ladder, one configuration per line. Empty if nothing has been measured yet."""
    rows = []
    d = HERE.parent.parent / "maynards" / "results"
    for f in sorted(d.glob("*.json")) if d.exists() else []:
        r = json.loads(f.read_text())
        if r.get("tasks") == 15:
            rows.append(r)
    if not rows:
        return "The results table is in the repository"
    rows.sort(key=lambda r: r["accuracy"])
    return "\n".join(f"{r['label']}: {r['passed']} of {r['tasks']} correct" for r in rows)


def set_text(shape, text):
    """
    Replace the words, keep the formatting the template gave the first run.

    A line break starts a new paragraph. Nothing here is ever a paragraph of prose: a deck is read at
    a glance from the back of a room, so every body is a short line that can be taken in whole.
    """
    tf = shape.text_frame
    first = tf.paragraphs[0]
    keep = first.runs[0] if first.runs else None
    for p in list(tf.paragraphs)[1:]:
        p._p.getparent().remove(p._p)
    for r in list(first.runs)[1:]:
        r._r.getparent().remove(r._r)
    lines = [l for l in text.split("\n") if l.strip()]
    if not lines:
        lines = [""]
    if keep is None:
        tf.text = lines[0]
    else:
        keep.text = lines[0]
    for b in lines[1:]:
        el = copy.deepcopy(first._p)
        first._p.getparent().append(el)
        from pptx.text.text import _Paragraph
        para = _Paragraph(el, tf)
        if para.runs:
            para.runs[0].text = b
            for r in list(para.runs)[1:]:
                r._r.getparent().remove(r._r)
    if len(lines) > 1:
        # a list is read down the left edge: justified text stretches short lines into nonsense,
        # and a line needs air under it to be taken in on its own
        from pptx.enum.text import PP_ALIGN
        from pptx.util import Pt
        for para in tf.paragraphs:
            para.alignment = PP_ALIGN.LEFT
            para.space_after = Pt(9)


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

    # Drop what we are not using, then put the rest in ORDER. The template's sequence was written for a
    # different talk; this one is ours.
    lst = prs.slides._sldIdLst
    ids = list(lst)
    keep_els = {n: ids[n - 1] for n in KEEP}
    for el in ids:
        lst.remove(el)
    for n in ORDER:
        lst.append(keep_els[n])

    for slide in prs.slides:
        morph(slide)

    prs.save(str(OUT))
    print(f"wrote {OUT} ({len(prs.slides.__iter__.__self__._sldIdLst)} slides)")


if __name__ == "__main__":
    main()
