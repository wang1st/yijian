#!/usr/bin/env python3
"""Check that calls to this feature's own functions pass a compatible argument count.

Catches the class of error the split introduced: a signature changed but
one call site still passes the old arity. Only checks named functions
declared in the feature files, and skips calls whose arguments contain
nested brackets that make counting ambiguous.
"""
import re, os, sys

FILES = [
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewTheme.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewHomeScreens.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewPracticeScreen.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewWidgets.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewGenerateScreen.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/PracticeSession.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ExerciseTypes.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/main/MainHomeScreen.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/main/MainStatsScreen.kt",
]

def split_params(s):
    """Split a parameter list on top-level commas."""
    parts, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return [p for p in parts if p.strip()]

# collect signatures: name -> list of (required, total)
sigs = {}
for f in FILES:
    src = open(f).read()
    for m in re.finditer(r'fun\s+(?:[\w.<>]+\.)?([A-Za-z_]\w*)\s*\(', src):
        name = m.group(1)
        start = m.end() - 1
        depth, i = 0, start
        while i < len(src):
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        params = split_params(src[start + 1:i])
        required = sum(1 for p in params if "=" not in p)
        sigs.setdefault(name, []).append((required, len(params), f))

issues = []
for f in FILES:
    src = open(f).read()
    for m in re.finditer(r'(?<!fun )(?<![\w.])([A-Za-z_]\w*)\s*\(', src):
        name = m.group(1)
        # skip matches inside comments
        line_start = src.rfind("\n", 0, m.start()) + 1
        prefix = src[line_start:m.start()]
        if "//" in prefix or prefix.strip().startswith("*"):
            continue
        if name not in sigs:
            continue
        start = m.end() - 1
        depth, i = 0, start
        while i < len(src):
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        args_src = src[start + 1:i]
        if "{" in args_src and "}" not in args_src:
            continue   # trailing lambda, skip
        args = split_params(args_src)
        # Named arguments make arity counting unreliable at this level of analysis.
        if any(re.match(r'\s*[A-Za-z_]\w*\s*=(?!=)', a) for a in args):
            continue
        n = len(args)
        line = src[:m.start()].count("\n") + 1
        ok = False
        for required, total, _ in sigs[name]:
            # trailing lambda counts as the last parameter
            if required <= n <= total or n == total - 0:
                ok = True
                break
            if n + 1 == total:   # lambda passed outside the parens
                ok = True
                break
        if not ok:
            shapes = ", ".join(f"{r}..{t}" for r, t, _ in sigs[name])
            issues.append(f"{os.path.basename(f)}:{line} {name}() called with {n} arg(s), expected {shapes}")

print(f"checked {len(FILES)} files, {len(sigs)} functions")
if issues:
    print(f"\n{len(issues)} issue(s):")
    for i in sorted(set(issues)):
        print("  " + i)
    sys.exit(1)
print("ARITY CLEAN")
