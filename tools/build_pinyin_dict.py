#!/usr/bin/env python3
"""Build the compact pinyin dictionary shipped in the IME.

Source data:
  - pinyin.txt : per-character pinyin (mozillazg/pinyin-data, MIT)
  - dict.txt   : jieba word list with frequencies (MIT)

Output format (assets/pinyin_dict.txt), one entry per line:
    <full_pinyin>,<initials>,<word>,<freq>
  e.g.  gan'ga,gg,尴尬,120

Only words made of characters present in the pinyin table are kept, and the list
is trimmed to the most frequent entries to keep the APK small while keeping common
phrases instant.
"""
import re
import sys
from collections import defaultdict

PINYIN_FILE = '/tmp/pinyin.txt'
DICT_FILE = '/tmp/dict.txt'
OUT = '/var/minis/workspace/yijian/app/src/main/assets/pinyin_dict.txt'
MAX_WORDS = 120_000          # ~3-4 MB raw, trimmed below if needed
MIN_FREQ = 2

# ---- per-character pinyin -------------------------------------------------
char_pinyin = {}
num_re = re.compile(r'^U\+([0-9A-Fa-f]+):\s*([^#]+)')
with open(PINYIN_FILE, encoding='utf-8') as f:
    for line in f:
        if line.startswith('#'):
            continue
        m = num_re.match(line)
        if not m:
            continue
        cp = int(m.group(1), 16)
        readings = [r.strip() for r in m.group(2).split(',') if r.strip()]
        # prefer the first reading; strip tones
        if not readings:
            continue
        def strip_tone(s):
            table = str.maketrans('āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿ',
                                  'aaaaeeeeiiiioooouuuuvvvvvnnnm')
            s = s.translate(table)
            # remove trailing tone digit / colon form
            s = re.sub(r'\d$', '', s)
            s = s.replace('u:', 'v')
            return s.lower()
        readings = [strip_tone(r) for r in readings]
        readings = [r for r in readings if re.fullmatch(r'[a-z]+', r)]
        if readings:
            char_pinyin[chr(cp)] = readings

print(f'characters with pinyin: {len(char_pinyin)}', file=sys.stderr)


def word_pinyin(word):
    """Returns (full 'ni'hao' style, initials 'nh') or None if any char lacks pinyin."""
    full = []
    initials = []
    for ch in word:
        if ch.isascii() and ch.isalnum():
            full.append(ch.lower())
            initials.append(ch.lower())
            continue
        rs = char_pinyin.get(ch)
        if not rs:
            return None
        r = rs[0]
        full.append(r)
        initials.append(r[0])
    return "'".join(full), ''.join(initials)


# ---- build dictionary -----------------------------------------------------
entries = []
seen = set()
with open(DICT_FILE, encoding='utf-8') as f:
    for line in f:
        parts = line.split()
        if len(parts) < 2:
            continue
        word, freq = parts[0], parts[1]
        # only pure CJK words, 1-6 chars
        if not (1 <= len(word) <= 6):
            continue
        if not all('\u4e00' <= c <= '\u9fff' for c in word):
            continue
        try:
            fq = int(freq)
        except ValueError:
            continue
        if fq < MIN_FREQ:
            continue
        py = word_pinyin(word)
        if not py:
            continue
        if word in seen:
            continue
        seen.add(word)
        entries.append((word, py[0], py[1], fq))

# Also make sure every single character with pinyin is available, so the user can
# always type something even if the word list lacks it.
for ch, rs in char_pinyin.items():
    if not ('\u4e00' <= ch <= '\u9fff'):
        continue
    if ch in seen:
        continue
    r = rs[0]
    entries.append((ch, r, r[0], 1))
    seen.add(ch)

entries.sort(key=lambda e: -e[3])
entries = entries[:MAX_WORDS]

# Emit both the separated form (ni'hao) and the compact form (nihao) so the runtime
# index can match whatever the user types without pre-processing.
with open(OUT, 'w', encoding='utf-8') as f:
    for word, full, ini, fq in entries:
        compact = full.replace("'", '')
        f.write(f'{compact},{ini},{word},{fq}\n')
        if compact != full:
            f.write(f'{full},{ini},{word},{fq}\n')

print(f'wrote {len(entries)} entries to {OUT}', file=sys.stderr)
multi = sum(1 for e in entries if len(e[0]) > 1)
print(f'  multi-char words: {multi}', file=sys.stderr)
