import io

# --- ImeController: add public commitTeacherEnglish ---
p = 'app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard3/ImeController.kt'
s = io.open(p, encoding='utf-8').read()

anchor = '''    val translationError get() = translationManager.errorText.value'''
new_method = '''    val translationError get() = translationManager.errorText.value

    /** Commits the corrected English from the teacher line, replacing the source like a candidate. */
    fun commitTeacherEnglish() {
        val candidate = translationManager.currentCandidate() ?: return
        val english = candidate.correctedEnglish
        if (english.isBlank()) return
        updateStateBlocking { emitTranslationCandidate(candidate.copy(text = english)) }
    }'''

assert anchor in s, 'anchor not found'
s = s.replace(anchor, new_method)
io.open(p, 'w', encoding='utf-8').write(s)
print('ImeController done')

# --- TranslationManager: add currentCandidate ---
p2 = 'app/src/main/kotlin/dev/patrickgold/florisboard/ime/translation/TranslationManager.kt'
s2 = io.open(p2, encoding='utf-8').read()

anchor2 = '''    fun isCurrent(candidate: TranslationSuggestionCandidate) =
        candidate.requestId == requestId && candidate in candidates.value'''
new2 = '''    fun isCurrent(candidate: TranslationSuggestionCandidate) =
        candidate.requestId == requestId && candidate in candidates.value

    /** The first live candidate of the current request, if any. */
    fun currentCandidate(): TranslationSuggestionCandidate? =
        candidates.value.firstOrNull { it.requestId == requestId }'''

assert anchor2 in s2, 'anchor2 not found'
s2 = s2.replace(anchor2, new2)
io.open(p2, 'w', encoding='utf-8').write(s2)
print('TranslationManager done')
