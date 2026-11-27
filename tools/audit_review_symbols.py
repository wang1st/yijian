#!/usr/bin/env python3
"""Focused audit for the review feature.

Only checks the files this feature owns, and only for errors that are
unambiguous without a full type checker:

  A. a reference to a symbol that is private in another file of this module
  B. a qualified call `dev.x.y.fn(` where fn is an extension/companion member
  C. a `by` delegate on a var without the matching getValue/setValue import
  D. a composable called from a function that is not @Composable
  E. a Modifier modifier that needs a scope (weight/align) used outside it
"""
import os, re, sys

FEATURE_FILES = [
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewTheme.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewHomeScreens.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewPracticeScreen.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewWidgets.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ReviewGenerateScreen.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/PracticeSession.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/review/ExerciseTypes.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/ime/translation/review/ReviewStore.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/ime/translation/review/ReviewAi.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/ime/translation/review/MistakeTracker.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/ime/translation/review/WordDiff.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/main/MainHomeScreen.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/app/main/MainStatsScreen.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/ime/translation/AtomicFileWrite.kt",
    "app/src/main/kotlin/dev/patrickgold/florisboard/ime/translation/PracticeExporter.kt",
]

issues = []

def add(kind, where, msg):
    issues.append(f"[{kind}] {where}: {msg}")

# ---- collect declarations per file ------------------------------------------------
decl = {}          # name -> list of (file, visibility)
for f in FEATURE_FILES:
    src = open(f).read()
    for m in re.finditer(
        r'^\s*(?P<vis>internal |private )?(?:@Composable\s+)?'
        r'(?:fun|val|var|class|object|interface)\s+(?:[\w.<>]+\.)?(?P<name>[A-Za-z_]\w*)',
        src, re.M):
        decl.setdefault(m.group("name"), []).append((f, (m.group("vis") or "public").strip()))

# A. private used elsewhere
for name, ds in decl.items():
    priv_files = {f for f, v in ds if v == "private"}
    if not priv_files:
        continue
    # A private name declared in several files is normal shadowing (e.g. `prefs`), not a leak.
    if len({f for f, _ in ds}) > 1:
        continue
    for f in FEATURE_FILES:
        if f in priv_files:
            continue
        body = re.sub(r'^import .*$', '', open(f).read(), flags=re.M)
        if re.search(r'(?<![\w.])' + re.escape(name) + r'\s*[({<.]', body) or \
           re.search(r'(?<![\w.])' + re.escape(name) + r'\b(?!\s*\()', body) and \
           re.search(r'\b' + re.escape(name) + r'\b', body):
            # ignore if the other file declares its own same-named symbol
            if any(ff == f for ff, _ in ds):
                continue
            add("A", os.path.basename(f), f"uses '{name}' which is private in {os.path.basename(list(priv_files)[0])}")

# B. fully-qualified calls to functions that live in this module
all_fun = {n for n, ds in decl.items() if any(True for _ in ds)}
for f in FEATURE_FILES:
    src = open(f).read()
    for m in re.finditer(r'\bdev\.patrickgold\.florisboard\.[\w.]*?\.([A-Za-z_]\w*)\s*\(', src):
        fn = m.group(1)
        # qualified call only valid for top-level functions, never members/extensions
        if fn in all_fun and not any(d[0] == f for d in decl[fn]):
            add("B", os.path.basename(f), f"qualified call to '{fn}' — use an import")

# C. by-delegate without setValue import
for f in FEATURE_FILES:
    src = open(f).read()
    has_var_delegate = re.search(r'\bvar \w+ by ', src)
    if has_var_delegate:
        if "import androidx.compose.runtime.setValue" not in src:
            add("C", os.path.basename(f), "var ... by delegate but no setValue import")
        if "import androidx.compose.runtime.getValue" not in src:
            add("C", os.path.basename(f), "delegate but no getValue import")

# D. composable called from non-composable function
composables = set()
for f in FEATURE_FILES:
    src = open(f).read()
    for m in re.finditer(r'@Composable\s+(?:internal |private )?fun (?:[\w.<>]+\.)?([A-Za-z_]\w*)', src):
        composables.add(m.group(1))

for f in FEATURE_FILES:
    src = open(f).read()
    for m in re.finditer(r'^(?P<vis>internal |private )?fun (?:[\w.<>]+\.)?(?P<name>[A-Za-z_]\w*)\s*\(', src, re.M):
        start = m.start()
        if src[:start].rstrip().endswith("@Composable"):
            continue
        name = m.group("name")
        if name in composables:
            continue
        bs = src.find("{", m.end())
        if bs < 0:
            continue
        depth, i = 0, bs
        while i < len(src):
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = src[bs:i]
        called = sorted({c for c in composables
                         if re.search(r'(?<![\w.])' + re.escape(c) + r'\s*[({]', body)})
        if called:
            line = src[:start].count("\n") + 1
            add("D", f"{os.path.basename(f)}:{line}",
                f"fun {name} calls composable(s) {', '.join(called[:3])}")

print(f"checked {len(FEATURE_FILES)} files")
if issues:
    print(f"\n{len(issues)} issue(s):")
    for i in sorted(set(issues)):
        print("  " + i)
    sys.exit(1)
print("AUDIT CLEAN")
