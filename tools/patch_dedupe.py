import io

p = 'tools/build_pinyin_dict.py'
s = io.open(p, encoding='utf-8').read()

old = '''entries.sort(key=lambda e: -e[3])
entries = entries[:MAX_WORDS]'''

new = '''entries.sort(key=lambda e: -e[3])
# Drop exact (pinyin, word) duplicates produced by polyphone tone variants.
deduped = []
seen_pairs = set()
for e in entries:
    key = (e[0], e[2])
    if key in seen_pairs:
        continue
    seen_pairs.add(key)
    deduped.append(e)
entries = deduped[:MAX_WORDS]'''

assert old in s, 'sort site not found'
s = s.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(s)
print('pair dedupe added')
