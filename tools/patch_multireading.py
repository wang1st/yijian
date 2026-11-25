import io

p = 'tools/build_pinyin_dict.py'
s = io.open(p, encoding='utf-8').read()

old = '''def word_pinyin(word):
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
    return "'".join(full), ''.join(initials)'''

new = '''def word_pinyin(word):
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
    return [("'".join(f for f, _ in combo), ''.join(i for _, i in combo)) for combo in combos]'''

assert old in s, 'old word_pinyin not found'
s = s.replace(old, new)

# 调用处也要展开多读音
old2 = '''        py = word_pinyin(word)
        if not py:
            continue
        if word in seen:
            continue
        seen.add(word)
        entries.append((word, py[0], py[1], fq))'''

new2 = '''        pys = word_pinyin(word)
        if not pys:
            continue
        if word in seen:
            continue
        seen.add(word)
        for py in pys:
            entries.append((word, py[0], py[1], fq))'''

assert old2 in s, 'old call site not found'
s = s.replace(old2, new2)

io.open(p, 'w', encoding='utf-8').write(s)
print('multi-reading support added')
