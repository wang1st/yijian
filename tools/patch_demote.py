import io

p = 'tools/build_pinyin_dict.py'
s = io.open(p, encoding='utf-8').read()

old = '''        pys = word_pinyin(word)
        if not pys:
            continue
        if word in seen:
            continue
        seen.add(word)
        for py in pys:
            entries.append((word, py[0], py[1], fq))'''

new = '''        pys = word_pinyin(word)
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
            entries.append((word, py[0], py[1], max(1, fq // 10)))'''

assert old in s, 'call site not found'
s = s.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(s)
print('frequency demotion added')
