#!/usr/bin/env python3
"""client.html 색 조합이 WCAG AA(본문 4.5:1, 테두리 등 3:1)를 넘는지 검사합니다."""
import re
import sys
from pathlib import Path

CSS = (Path(__file__).resolve().parent.parent / "src" / "client.html").read_text(encoding="utf-8")


def tokens(selector):
    block = re.search(re.escape(selector) + r"\{(.*?)\}", CSS, re.S).group(1)
    return dict(re.findall(r"--([\w-]+):(#[0-9a-fA-F]{6})", block))


def lum(hex_):
    rgb = [int(hex_[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    lin = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb]
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def ratio(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


dark = tokens(":root")
light = dict(dark, **tokens("[data-theme=light]"))
PALETTE = re.search(r"const PALETTE = \[(.*?)\]", CSS).group(1)
pastels = re.findall(r"#[0-9a-fA-F]{6}", PALETTE)

TEXT_PAIRS = [
    ("text", "bg"), ("text", "surface"), ("text", "surface2"),
    ("muted", "bg"), ("muted", "surface"), ("muted", "surface2"),
    ("faint", "bg"), ("faint", "surface"), ("faint", "surface2"),
    ("on-accent", "accent"), ("accent-text", "bg"), ("accent-text", "surface"),
    ("warn", "bg"), ("text", "warn-bg"), ("muted", "warn-bg"), ("ok", "surface"), ("ok", "bg"),
]
# 보내기 버튼은 화살표 아이콘(on-accent on accent)으로 구분되므로 위 TEXT_PAIRS에서 검사합니다.
# line(구분선)은 장식용이라 일부러 흐리게 둡니다. 입력칸 테두리(field)는 3:1 이상이어야 합니다.
UI_PAIRS = [("mention", "surface2"), ("mention", "bg"), ("field", "surface"), ("field", "bg"), ("accent-text", "bg")]

bad = 0
for name, t in (("dark", dark), ("light", light)):
    print("== %s theme" % name)
    for fg, bg in TEXT_PAIRS:
        r = ratio(t[fg], t[bg])
        ok = r >= 4.5
        bad += not ok
        print("  %-5s %-12s on %-9s %5.2f:1" % ("ok" if ok else "FAIL", fg, bg, r))
    for fg, bg in UI_PAIRS:
        r = ratio(t[fg], t[bg])
        ok = r >= 3.0
        bad += not ok
        print("  %-5s %-12s on %-9s %5.2f:1 (non-text, needs 3:1)" % ("ok" if ok else "FAIL", fg, bg, r))
print("== avatar initials (#15181d) on pastel palette")
for p in pastels:
    r = ratio("#15181d", p)
    ok = r >= 4.5
    bad += not ok
    print("  %-5s %s %5.2f:1" % ("ok" if ok else "FAIL", p, r))
print("\nRESULT:", "PASS" if not bad else "FAIL (%d)" % bad)
sys.exit(1 if bad else 0)
