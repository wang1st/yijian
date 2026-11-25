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
PHRASE_FILE = '/tmp/ppd.txt'          # mozillazg/phrase-pinyin-data large: 413k modern words
OUT = '/var/minis/workspace/yijian/app/src/main/assets/pinyin_dict.txt'
MAX_WORDS = 250_000          # jieba + phrase corpus, trimmed to keep the APK reasonable
MIN_FREQ = 2
NEW_WORD_FREQ = 1            # phrase corpus has no frequencies; keep them below jieba words

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
    """Returns a list of (full, initials) pairs - one per pronunciation combo."""
    combos = [[]]  # each element: list of (full_part, initial_part)
    for ch in word:
        if ch.isascii() and ch.isalnum():
            for combo in combos:
                combo.append((ch.lower(), ch.lower()))
            continue
        rs = char_pinyin.get(ch)
        if not rs:
            return []
        new_combos = []
        for combo in combos:
            for r in rs:
                new_combos.append(combo + [(r, r[0])])
        combos = new_combos
    return [("'".join(f for f, _ in combo), ''.join(i for _, i in combo)) for combo in combos]


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
        pys = word_pinyin(word)
        if not pys:
            continue
        if word in seen:
            continue
        seen.add(word)
        # Primary reading keeps full frequency; other readings (polyphones /
        # tone variants) are demoted so they rank after common candidates but
        # stay reachable (e.g. typing "hang" must still offer 行).
        primary = pys[0]
        entries.append((word, primary[0], primary[1], fq))
        for py in pys[1:]:
            if py == primary:
                continue
            entries.append((word, py[0], py[1], max(1, fq // 10)))

# ---- merge the phrase corpus (modern words like 益生菌 that jieba lacks) ---------
phrase_re = re.compile(r'^([^:#\s]+):\s*([^#]+)')
with open(PHRASE_FILE, encoding='utf-8') as f:
    for line in f:
        if line.startswith('#') or not line.strip():
            continue
        m = phrase_re.match(line)
        if not m:
            continue
        word, reading = m.group(1), m.group(2).strip()
        if not (1 <= len(word) <= 6):
            continue
        if not all('\u4e00' <= c <= '\u9fff' for c in word):
            continue
        if word in seen:
            continue
        # reading is tone-marked and space separated: yì shēng jūn
        syls = []
        for syl in reading.split():
            syl = syl.translate(str.maketrans(
                'āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿ',
                'aaaaeeeeiiiioooouuuuvvvvvnnnm'))
            syl = re.sub(r'\d$', '', syl).replace('u:', 'v').lower()
            if not re.fullmatch(r'[a-z]+', syl):
                syls = None
                break
            syls.append(syl)
        if not syls:
            continue
        # the word's own pinyin overrides per-character guessing (handles polyphones)
        full = "'".join(syls)
        ini = ''.join(s[0] for s in syls)
        seen.add(word)
        entries.append((word, full, ini, NEW_WORD_FREQ))

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
# Drop exact (pinyin, word) duplicates produced by polyphone tone variants.
deduped = []
seen_pairs = set()
for e in entries:
    key = (e[0], e[2])
    if key in seen_pairs:
        continue
    seen_pairs.add(key)
    deduped.append(e)
entries = deduped[:MAX_WORDS]

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
