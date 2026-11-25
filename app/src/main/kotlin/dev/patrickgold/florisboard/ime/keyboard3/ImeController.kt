/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
import dev.patrickgold.florisboard.ime.text.key.KeyCode
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3

import android.icu.text.BreakIterator
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.staticCompositionLocalOf
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.io.StorageController
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard3.extension.loadFoundationKeyboard
import dev.patrickgold.florisboard.ime.keyboard3.hint.FlickKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.hint.LongPressKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.touch.FnKeyArrangement
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.ShiftKeyBehavior
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelCache
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.ime.nlp.BreakIterators
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.translation.translationWindow
import dev.patrickgold.florisboard.ime.translation.replaceTranslation
import dev.patrickgold.florisboard.ime.translation.TranslationRange
import dev.patrickgold.florisboard.ime.translation.TranslationManager
import dev.patrickgold.florisboard.ime.translation.TranslationSuggestionCandidate
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.kotlin.collectIn
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.layer.K3LayerId
import org.k3lp.runtime.K3Content
import org.k3lp.runtime.K3InputMethod
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Provides the [ImeController] instance this composition tree is associated with.
 */
val LocalImeController = staticCompositionLocalOf<ImeController> {
    error("No IME controller is associated with this composition tree.")
}

@OptIn(ExperimentalAtomicApi::class)
class ImeController(
    storageController: StorageController,
    val clipboardManager: ClipboardManager, // TODO ClipboardController
    initialState: ImeState = ImeState(),
    val touchModelCache: TouchModelCache = TouchModelCache(),
) : K3InputMethod<ImeState, ImeEditor, ImeController.UpdateImeStateScope>(
    initialState = initialState,
) {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val breakIterators = BreakIterators()
    private val expectedContentQueue = ExpectedContentQueue()
    private var emitInterceptor = AtomicReference<EmitInterceptor?>(null)
    private val translationManager = TranslationManager(this)
    val pinyin = dev.patrickgold.florisboard.ime.translation.pinyin.PinyinInput(
        FlorisApplication.getHack()
    )

    val lastEnglishLesson get() = translationManager.lastLesson
    val translationToCopy get() = translationManager.translationToCopy
    val translationCandidates get() = translationManager.candidates
    val translationPreedit get() = translationManager.preeditText

    /** Translation API lifecycle: IDLE / TRANSLATING / ERROR. */
    val translationStatus get() = translationManager.status

    /** Human readable reason for the last translation failure, null when there is none. */
    val translationError get() = translationManager.errorMessage

    /** Commits the corrected English from the teacher line, replacing the source like a candidate. */
    fun commitTeacherEnglish() {
        val candidate = translationManager.currentCandidate() ?: return
        val english = candidate.correctedEnglish
        if (english.isBlank()) return
        updateStateBlocking { emitTranslationCandidate(candidate.copy(text = english)) }
    }

    /** Recorder shared with the translation panel so it can show recording state. */
    val recorder get() = translationManager.recorder

    /** True while a voice clip is being transcribed. */
    val voiceBusy get() = translationManager.voiceBusy

    /** Last voice status message (recording / recognised text / voice error). */
    val voiceStatus get() = translationManager.voiceStatus

    /** Records and transcribes speech; results go through the normal translation flow. */
    fun voiceStart(): Boolean = translationManager.startVoice()

    fun voiceStopAndTranslate() = translationManager.stopVoiceAndTranslate()

    fun voiceCancel() = translationManager.cancelVoice()

    /** Reads [text] aloud via DeepSeek TTS. */
    fun speak(text: String) = translationManager.speak(text)

    /** Migrate the old default sticky slot and its duplicate in one preference write. */
    suspend fun ensureImeSwitchAction() {
        val arrangement = prefs.smartbar.actionArrangement.get()
        val previous = QuickAction.InsertK3Descriptor(ImeActions.SwitchToPrevInputMethod)
        val sticky = when (arrangement.stickyAction) {
            QuickAction.InsertK3Descriptor(ImeActions.ExternalVoiceInput) -> previous
            else -> arrangement.stickyAction
        }
        val switches = setOf<QuickAction>(
            previous,
            QuickAction.InsertK3Descriptor(ImeActions.SwitchToNextInputMethod),
            QuickAction.InsertK3Descriptor(ImeActions.ShowInputMethodPicker),
        )
        val dynamicActions = if (sticky != null && switches.contains(sticky)) {
            arrangement.dynamicActions.filterNot { it in switches }
        } else {
            arrangement.dynamicActions
        }
        val migrated = arrangement.copy(stickyAction = sticky, dynamicActions = dynamicActions)
        if (migrated != arrangement) prefs.smartbar.actionArrangement.set(migrated)
    }

    /** True while the keyboard is in Chinese (pinyin) mode. */
    val pinyinActive get() = pinyin.active

    /** Non-null while the last accepted translation can still be undone. */
    val translationUndoable get() = translationManager.undoable

    /** The English teacher: practice records and the emailed report. */
    val teacher get() = translationManager.teacher

    val activeTouchModelOptions = combine<Any, TouchModelOptions>(
        prefs.keyboard.numberRow.asFlow(),
        prefs.keyboard.fnKeyEnabled.asFlow(),
        prefs.keyboard.fnKeyArrangement.asFlow(),
        prefs.keyboard.longPressKeyHintEnabled.asFlow(),
        prefs.keyboard.longPressKeyHintPlacement.asFlow(),
        prefs.keyboard.multiTapHighlightEnabled.asFlow(),
        prefs.keyboard.flickKeyHintEnabled.asFlow(),
        prefs.keyboard.flickKeyHintPlacement.asFlow(),
        prefs.translation.enabled.asFlow(),
    ) { values ->
        TouchModelOptions(
            showNumberRow = values[0] as Boolean,
            fnKeyEnabled = values[1] as Boolean,
            fnKeyArrangement = values[2] as FnKeyArrangement,
            longPressKeyHintEnabled = values[3] as Boolean,
            longPressKeyHintPlacement = values[4] as LongPressKeyHintPlacement,
            multiTapHighlightEnabled = values[5] as Boolean,
            flickKeyHintEnabled = values[6] as Boolean,
            flickKeyHintPlacement = values[7] as FlickKeyHintPlacement,
            showTranslationKey = values[8] as Boolean,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TouchModelOptions.Default)

    // TODO check if we can implement this differently
    val activeSmartbarVisibleDynamicActionsCount = MutableStateFlow(0)

    init {
        combine(
            prefs.devtools.enabled.asFlow(),
            prefs.devtools.showDragAndDropHelpers.asFlow(),
        ) { devtoolsEnabled, showDragAndDropHelpers ->
            devtoolsEnabled && showDragAndDropHelpers
        }.collectIn(scope) { showDragAndDropHelpers ->
            updateState {
                state = state.copy(
                    flags = state.flags
                        .withDebugShowDragAndDropHelpers(showDragAndDropHelpers),
                )
            }
        }

        // TODO proper stateful compilation
        scope.launch {
            loadFoundationKeyboard(this@ImeController, storageController.activeStorage.value)
        }
    }

    fun onHardwareKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            // TODO KeyEvent.KEYCODE_SPACE (auto-commit candidate)
            KeyEvent.KEYCODE_DEL -> true
            KeyEvent.KEYCODE_FORWARD_DEL -> true
            // TODO KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT
            else -> false
        }
    }

    fun onHardwareKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                updateStateBlocking {
                    emitBackspace()
                }
                true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                updateStateBlocking {
                    emitForwardDelete()
                }
                true
            }
            // TODO KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT
            else -> false
        }
    }

    fun setEmitInterceptor(interceptor: EmitInterceptor?) {
        emitInterceptor.store(interceptor)
    }

    fun snapshotState(): ImeState = activeState.value

    // TODO evaluate if we can move to a clean coroutine-based approach in FlorisBoard
    //  for now this helper can be used to update the state from non-suspending contexts
    inline fun updateStateBlocking(crossinline function: UpdateImeStateScope.() -> Unit) {
        runBlocking {
            updateState(function)
        }
    }

    override fun updateStateScopeOf(state: ImeState): UpdateImeStateScope {
        return UpdateImeStateScope(state)
    }

    inner class UpdateImeStateScope(
        state: ImeState,
    ) : UpdateStateScope<ImeState, ImeEditor>(state) {
        fun handleStartInputView(
            ic: WeakReference<InputConnection>,
            info: FlorisEditorInfo,
        ) {
            translationManager.resetEditor()
            val newTouchLayerId: K3LayerId
            val keyVariation: KeyVariation
            when (info.inputAttributes.type) {
                InputAttributes.Type.NUMBER -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Numpad
                }
                InputAttributes.Type.PHONE -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Telpad
                }
                InputAttributes.Type.TEXT -> {
                    keyVariation = when (info.inputAttributes.variation) {
                        InputAttributes.Variation.EMAIL_ADDRESS,
                        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
                            -> {
                            KeyVariation.EMAIL_ADDRESS
                        }
                        InputAttributes.Variation.PASSWORD,
                        InputAttributes.Variation.VISIBLE_PASSWORD,
                        InputAttributes.Variation.WEB_PASSWORD,
                            -> {
                            KeyVariation.PASSWORD
                        }
                        InputAttributes.Variation.URI -> {
                            KeyVariation.URI
                        }
                        else -> {
                            KeyVariation.NORMAL
                        }
                    }
                    newTouchLayerId = ImeLayerIds.Base
                }
                else -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Base
                }
            }
            val initialSelection = info.initialSelection
            val initialSurrounding = K3SurroundingText(
                textBefore = info.getInitialTextBeforeCursor(20)?.toString() ?: "",
                textSelected = info.getInitialSelectedText()?.toString() ?: "",
                textAfter = info.getInitialTextAfterCursor(20)?.toString() ?: "",
            )

            val rememberCapsLockState = prefs.typing.shiftKeyBehavior.get() != ShiftKeyBehavior.SINGLE_LETTER &&
                prefs.typing.rememberCapsLockState.get() &&
                state.flags.inputShiftState == InputShiftState.CAPS_LOCK

            state = state.copy(
                editor = ImeEditor(ic, info),
                touchLayerId = when {
                    rememberCapsLockState && newTouchLayerId.isTextLayer() -> ImeLayerIds.Caps
                    else -> newTouchLayerId
                },
                flags = state.flags
                    .withKeyVariation(keyVariation)
                    .withImeUiMode(
                        if (state.flags.imeUiMode != ImeUiMode.CLIPBOARD || prefs.clipboard.historyHideOnNextTextField.get()) {
                            ImeUiMode.TEXT
                        } else {
                            state.flags.imeUiMode
                        }
                    )
                    .withActionsOverflowVisible(false)
                    .withActionsEditorVisible(false)
                    .withInputShiftState(
                        when {
                            rememberCapsLockState -> InputShiftState.CAPS_LOCK
                            else -> InputShiftState.UNSHIFTED
                        }
                    )
                    .withComposingEnabled(
                        when (newTouchLayerId) {
                            ImeLayerIds.Numpad, ImeLayerIds.Telpad -> false
                            else -> keyVariation != KeyVariation.PASSWORD &&
                                // TODO review if this is the correct approach for composing region support detection
                                //  important: for codemirror6 in browsers, it is important that composing is disabled,
                                //  else all sorts of weird behavior starts to occur
                                info.isRichInputEditor &&
                                info.inputAttributes.flagTextAutoCorrect
                        }
                    )
                    .withIncognitoMode(
                        when (prefs.suggestion.incognitoMode.get()) {
                            IncognitoMode.FORCE_OFF -> false
                            IncognitoMode.FORCE_ON -> true
                            IncognitoMode.DYNAMIC_ON_OFF -> {
                                info.imeOptions.flagNoPersonalizedLearning ||
                                    prefs.suggestion.forceIncognitoModeFromDynamic.get()
                            }
                        }
                    )
            )
            resetContent(initialSelection, initialSurrounding)
            expectedContentQueue.clear()
        }

        fun handleUpdateSelection(newSelection: K3TextRange) {
            if (newSelection.min < 0 || state.editor.ic.get() == null) return
            state.editor.reportedCaret = newSelection.start.takeIf { newSelection.isCollapsed() }
            val content = expectedContentQueue.popUntilOrNull { it.selection == newSelection }
            if (content != null) {
                flogDebug { "DEDUPLICATED!!1" }
                return
            }
            resetContent(newSelection, state.editor.getSurroundingText(50, 10))
            expectedContentQueue.push(state.content)
            translationManager.invalidateStaleRequest(state.editor)
        }

        override fun emit(value: K3StringOrDescriptor) {
            val interceptor = emitInterceptor.load()
            if (interceptor != null) {
                interceptor.onEmit(value)
                return
            }
            super.emit(value)
        }

        override fun emitText(value: K3String) {
            // Pinyin mode: Latin letters build a pinyin buffer instead of being committed, so
            // the user can type Chinese mid-sentence without switching keyboards.
            val text = value.toText()
            if (text.length == 1 && pinyin.onLetter(text[0])) {
                return
            }
            // S1: otherwise nothing is held back — text goes straight to the editor.
            translationManager.observeEmitText(value, this)
            emitTextDirect(value)
            translationManager.maybeTranslateOnPunct(value)
        }

        /**
         * Commits text straight into the target editor, bypassing the yijian pre-edit buffer.
         * Used for the translation results themselves and for raw-buffer flushes.
         */
        fun emitTextDirect(value: K3String) {
            if (state.model.transforms.byType.isEmpty()) {
                // The bundled layout emits literal text. k3's general transform path
                // rewrites the whole input context, which document editors can append anew.
                val text = java.text.Normalizer.normalize(value.toText(), java.text.Normalizer.Form.NFC)
                if (state.editor.ic.get()?.commitText(text, 1) != true) return
                val content = state.content
                val surrounding = content.surroundingText.copy(
                    textBefore = (content.surroundingText.textBefore + text).takeLast(50),
                    textSelected = "",
                )
                // Used for key labels and deduplicating callbacks only. The host positions
                // every literal edit at its live selection, even if a callback is late.
                state = state.copy(content = K3Content(
                    selection = K3TextRange(content.selection.min + text.length),
                    surroundingText = surrounding,
                    inputContext = surrounding.textBefore.asK3String(),
                ))
            } else {
                super.emitText(value)
            }
            expectedContentQueue.push(state.content)
            reevaluateInputShiftState()
        }

        /**
         * Commits the raw buffered English into the editor (panel dismissed without picking a
         * translation). No-op when the buffer is empty.
         */
        fun translationCommitBufferAsRaw() {
            translationManager.commitBufferAsRaw(this)
            translationClearAll()
        }

        /**
         * Discards the buffered English and any pending translation: nothing is committed to the
         * target editor.
         */
        fun translationClearAll() {
            translationManager.dismissRequest()
            translationManager.clearVoiceStatus()
        }

        override fun emitDescriptor(descriptor: K3Descriptor) {
            val windowController = FlorisImeService.windowControllerOrNull()
            when (descriptor) {
                // TODO once k3lp supports this remove
                ImeActions.Delete -> emitForwardDelete()
                // TODO evaluate use of modern cursor anchor API instead of sending raw key events
                ImeActions.ArrowDown -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                ImeActions.ArrowLeft -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                ImeActions.ArrowRight -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                ImeActions.ArrowUp -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                // TODO clipboard manager needs a rewrite to match the new state paradigm
                ImeActions.ClipboardCopy -> {
                    if (state.editor.info.isRichInputEditor) {
                        val text = state.content.surroundingText.textSelected
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                        }
                    } else {
                        state.editor.performClipboardCopy()
                    }
                }
                ImeActions.ClipboardCut -> {
                    if (state.editor.info.isRichInputEditor) {
                        val text = state.content.surroundingText.textSelected
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                            emitBackspace()
                        }
                    } else {
                        state.editor.performClipboardCut()
                    }
                }
                ImeActions.ClipboardPaste -> {
                    if (state.editor.info.isRichInputEditor) {
                        emitClipboardItem(clipboardManager.primaryClip)
                    } else {
                        state.editor.performClipboardPaste()
                    }
                }
                ImeActions.ClipboardClearHistory -> clipboardManager.clearHistory()
                ImeActions.ClipboardClearFullHistory -> clipboardManager.clearFullHistory()
                ImeActions.ClipboardClearPrimaryClip -> {
                    if (prefs.clipboard.clearPrimaryClipAffectsHistoryIfUnpinned.get()) {
                        clipboardManager.primaryClip?.let { clipboardManager.deleteClip(it, onlyIfUnpinned = true) }
                    }
                    clipboardManager.updatePrimaryClip(null)
                    // TODO we have no ref here:
                    //  appContext.showShortToastSync(R.string.clipboard__cleared_primary_clip)
                }
                ImeActions.SelectAll -> state.editor.performSelectAll()
                ImeActions.Translate -> translationManager.translateNow(state)
                // The touch model emits once on press (start recording) and once on release
                // (stop + transcribe), which gives push-to-talk for free.
                ImeActions.TogglePinyin -> {
                    pinyin.toggle()
                    if (!pinyin.active.value) {
                        // leaving pinyin mode: keep whatever was typed as text
                        val raw = pinyin.rawInput()
                        if (raw.isNotEmpty()) emitTextDirect(raw.asK3String())
                        pinyin.clear()
                    }
                    reevaluateInputShiftState()
                }
                ImeActions.VoiceInput -> {
                    if (translationManager.recorder.isRecording) {
                        translationManager.stopVoiceAndTranslate()
                    } else {
                        translationManager.startVoice()
                    }
                }
                ImeActions.Share -> {
                    val text = state.content.surroundingText.textSelected
                    if (text.isNotEmpty()) {
                        state.editor.performTextShare()
                    }
                }
                ImeActions.ShowImeWindow -> FlorisImeService.showUi()
                ImeActions.HideImeWindow -> FlorisImeService.hideUi()
                ImeActions.ShowTextPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT),
                    )
                }
                ImeActions.ShowMediaPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.MEDIA),
                    )
                }
                ImeActions.ShowClipboardPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.CLIPBOARD),
                    )
                }
                ImeActions.Undo -> state.editor.performUndo()
                ImeActions.Redo -> state.editor.performRedo()
                ImeActions.Settings -> FlorisImeService.launchSettings()
                ImeActions.ShowInputMethodPicker -> FlorisImeService.showImePicker()
                ImeActions.SwitchToPrevInputMethod -> {
                    if (!FlorisImeService.switchToPrevInputMethod()) FlorisImeService.showImePicker()
                }
                ImeActions.SwitchToNextInputMethod -> FlorisImeService.switchToNextInputMethod()
                ImeActions.ShowSubtypePicker -> {} // TODO
                ImeActions.SwitchToPrevSubtype -> {} // TODO
                ImeActions.SwitchToNextSubtype -> {} // TODO
                ImeActions.ToggleActionsEditor -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsEditorVisible(!state.flags.isActionsEditorVisible),
                    )
                }
                ImeActions.ToggleActionsOverflow -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsOverflowVisible(!state.flags.isActionsOverflowVisible),
                    )
                }
                ImeActions.ToggleCompactLayout -> windowController?.actions?.toggleCompactLayout()
                ImeActions.ToggleFloatingWindow -> windowController?.actions?.toggleFloatingWindow()
                ImeActions.ToggleResizeMode -> windowController?.editor?.toggleEnabled()
                ImeActions.CompactLayoutToLeft -> windowController?.actions?.compactLayoutToLeft()
                ImeActions.CompactLayoutToRight -> windowController?.actions?.compactLayoutToRight()
                ImeActions.ExternalVoiceInput -> FlorisImeService.switchToVoiceInputMethod()
                else -> super.emitDescriptor(descriptor)
            }
            reevaluateInputShiftState()
        }

        override fun emitBackspace() {
            if (pinyin.onBackspace()) return
            translationManager.observeBackspace(this)
            if (state.model.transforms.byType.isEmpty()) {
                // The editor owns selection, grapheme boundaries and cross-paragraph deletion.
                expectedContentQueue.clear()
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL)
            } else {
                super.emitBackspace()
                expectedContentQueue.push(state.content)
            }
        }

        override fun emitEnter() {
            val info = state.editor.info
            val action = info.imeOptions.action
            if (info.imeOptions.flagNoEnterAction || info.inputAttributes.flagTextMultiLine ||
                action == ImeOptions.Action.NONE || action == ImeOptions.Action.UNSPECIFIED) {
                emitEnterKey()
            } else {
                emitEnterAction(action)
            }
        }

        fun emitEnterKey() {
            val info = state.editor.info
            if (info.isRawInputEditor) {
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
            } else {
                emitText(NEWLINE_SEQ)
            }
        }

        fun emitEnterAction(action: ImeOptions.Action) {
            state.editor.performEditorAction(action)
        }

        fun emitForwardDelete() {
            if (state.model.transforms.byType.isEmpty()) {
                expectedContentQueue.clear()
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_FORWARD_DEL)
                return
            }
            // TODO request additional text if too low on context length
            if (state.content.selection.isNotCollapsed()) {
                emitBackspace()
            } else {
                val newSurroundingText = state.content.surroundingText.copy(
                    textAfter = state.content.surroundingText.textAfter.let { text ->
                        // TODO unicode
                        if (text.isEmpty()) text else text.substring(1)
                    },
                )
                state = state.copy(
                    content = state.content.copy(
                        surroundingText = newSurroundingText,
                    ),
                )
                state.editor.deleteSurroundingText(
                    charsBefore = 0,
                    charsAfter = 1, // TODO
                )
            }
        }

        fun emitCandidate(candidate: SuggestionCandidate) {
//            scope.launch {
//                candidate.sourceProvider?.notifySuggestionAccepted(subtypeManager.activeSubtype, candidate)
//            }
            when (candidate) {
                is ClipboardSuggestionCandidate -> emitClipboardItem(candidate.clipboardItem)
                is TranslationSuggestionCandidate -> emitTranslationCandidate(candidate)
                else -> emitCompletion(candidate)
            }
        }

        internal fun emitTranslationCandidate(candidate: TranslationSuggestionCandidate) {
            if (!translationManager.isCurrent(candidate)) return
            val range = candidate.range
            val translation = candidate.text.toString()
            if (range == null) {
                // Only voice drafts have no editor text to replace.
                emitTextDirect(translation.asK3String())
            } else {
                if (!replaceAndRefresh(range, translation)) {
                    translationManager.dismissRequest()
                    translationManager.translationToCopy.value = translation
                    translationManager.setError("未能确认替换结果，请检查输入框。也可以复制译文。")
                    return
                }
                // A window-local offset can change after replacement in a long document.
                // Record the observed translation location for undo and subsequent sentences.
                val expectedEnd = state.editor.reportedCaret?.takeIf { it == range.end }?.let {
                    it - range.text.length + translation.length
                }
                val after = state.editor.ic.get()?.translationWindow(expectedEnd)
                val inserted = if (after != null && after.before.endsWith(translation) && after.selected.isEmpty()) {
                    range.copy(start = after.start - translation.length, text = translation)
                } else {
                    range.copy(text = translation)
                }
                translationManager.rememberTranslation(inserted)
                translationManager.offerUndo(inserted, range.text)
            }
            translationManager.dismissRequest()
            if (candidate.correctedEnglish.isNotBlank()) translationManager.lastLesson.value = candidate
        }

        private fun replaceAndRefresh(range: TranslationRange, replacement: String): Boolean {
            val ic = state.editor.ic.get() ?: return false
            // Do not feed a surrounding-text offset back into k3 as an absolute selection.
            // The host's next selection update supplies the new position.
            expectedContentQueue.clear()
            return ic.replaceTranslation(range, replacement, state.editor.reportedCaret,
                singleLine = !state.editor.info.inputAttributes.flagTextMultiLine)
        }

        fun undoLastReplacement() {
            val undo = translationManager.undoable.value ?: return
            translationManager.lastLesson.value = null
            translationManager.clearUndo()
            if (replaceAndRefresh(undo.range, undo.original)) {
                translationManager.forgetTranslation(undo.range)
            } else {
                translationManager.setError("未能确认撤销结果，请检查输入框")
            }
        }

        fun emitClipboardItem(item: ClipboardItem?): Boolean {
            return item != null && when (item.type) {
                ItemType.TEXT -> {
                    val text = item.text?.asK3String()
                    if (text != null) {
                        emitText(text)
                        true
                    } else {
                        false
                    }
                }
                ItemType.IMAGE, ItemType.VIDEO -> {
                    state.editor.performClipboardPasteMedia(item)
                }
            }.also {
                if (prefs.clipboard.historyHideOnPaste.get()) {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT)
                    )
                }
            }
        }

        fun emitCompletion(candidate: SuggestionCandidate): Boolean {
            val text = candidate.text.toString()
            if (text.isEmpty() || state.editor.info.isRawInputEditor) return false
//            val content = activeContent
//            return if (content.composing.isValid) {
//                phantomSpace.setActive(showComposingRegion = false, candidate = candidate)
//                super.finalizeComposingText(text)
//            } else {
//                val isPhantomSpaceActive = phantomSpace.determine(text)
//                phantomSpace.setActive(showComposingRegion = false, candidate = candidate)
//                return if (isPhantomSpaceActive) {
//                    super.commitText("$SPACE$text")
//                } else {
//                    super.commitText(text)
//                }.also {
//                    // handled in finalizeComposingText if content.composing.isValid
//                    updateLastCommitPosition()
//                }
//            }
            return false
        }

        override fun switchTouchLayer(newTouchLayerId: K3LayerId) {
            if (newTouchLayerId.isTextLayer()) {
                super.switchTouchLayer(state.flags.inputShiftState.correspondingLayerId)
            } else {
                super.switchTouchLayer(newTouchLayerId)
            }
        }

        fun cycleInputShiftState(isDoubleTap: Boolean) {
            val shiftKeyBehavior = prefs.typing.shiftKeyBehavior.get()
            val oldInputShiftState = state.flags.inputShiftState
            val newInputShiftState = when (shiftKeyBehavior) {
                ShiftKeyBehavior.SINGLE_LETTER -> if (oldInputShiftState == InputShiftState.UNSHIFTED) {
                    InputShiftState.SHIFTED_MANUAL
                } else {
                    InputShiftState.UNSHIFTED
                }
                ShiftKeyBehavior.CAPSLOCK_BY_DOUBLE_TAP -> when {
                    isDoubleTap -> InputShiftState.CAPS_LOCK
                    oldInputShiftState == InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    else -> InputShiftState.UNSHIFTED
                }
                ShiftKeyBehavior.CAPSLOCK_BY_CYCLE -> when (oldInputShiftState) {
                    InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    InputShiftState.SHIFTED_MANUAL -> InputShiftState.CAPS_LOCK
                    InputShiftState.SHIFTED_AUTOMATIC -> InputShiftState.UNSHIFTED
                    InputShiftState.CAPS_LOCK -> InputShiftState.UNSHIFTED
                }
            }
            state = state.copy(
                touchLayerId = newInputShiftState.correspondingLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun revertCycleInputShiftState(
            newTouchLayerId: K3LayerId,
            newInputShiftState: InputShiftState,
        ) {
            state = state.copy(
                touchLayerId = newTouchLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun reevaluateInputShiftState() {
            val isPeekOngoing = false // TODO
            if (state.flags.inputShiftState == InputShiftState.CAPS_LOCK || isPeekOngoing) return
            val capsMode = state.content.cursorCapsMode(state.editor.info.inputAttributes)
            val shift = prefs.typing.autoCapitalization.get()
                && capsMode != InputAttributes.CapsMode.NONE
                // && subtypeManager.activeSubtype.primaryLocale.supportsCapitalization
            val inputShiftState = when {
                shift -> InputShiftState.SHIFTED_AUTOMATIC
                else -> InputShiftState.UNSHIFTED
            }
            val touchLayerId = when {
                state.touchLayerId.isTextLayer() -> inputShiftState.correspondingLayerId
                else -> state.touchLayerId
            }
            state = state.copy(
                touchLayerId = touchLayerId,
                flags = state.flags.withInputShiftState(inputShiftState),
            )
        }

        override fun resetContent(newSelection: K3TextRange, newSurrounding: K3SurroundingText) {
            super.resetContent(newSelection, newSurrounding)
            reevaluateInputShiftState()
        }

        override fun resetContent() {
            super.resetContent()
            reevaluateInputShiftState()
        }

        fun handleFinishInputView() {
            translationManager.resetEditor()
            resetContent()
            state = state.copy(editor = ImeEditor.Disconnected)
            expectedContentQueue.clear()
        }

        override fun evaluateCompositionOf(
            model: K3Model,
            selection: K3TextRange,
            surroundingText: K3SurroundingText
        ): K3TextRange? {
            if (model.transforms.byType.isEmpty() || selection.isNotCollapsed() || !state.flags.isComposingEnabled) {
                return null
            }
            // TODO rework how we get the primary locale
            val locale = FlorisLocale.fromTag(model.locales.getOrElse(0) { "" })
            return breakIterators.word(locale) {
                it.setText(surroundingText.textBefore)
                val end = it.last()
                val isWord = it.ruleStatus != BreakIterator.WORD_NONE
                if (isWord) {
                    val start = it.previous().let { pos ->
                        // Include Emoji indicator in local composing. This is required so that emoji suggestion indicator
                        // can be detected in the composing text.
                        (pos - 1).takeIf { updatedPos ->
                            surroundingText.textBefore.getOrNull(updatedPos) == EmojiSuggestionType.LEADING_COLON.prefix.first()
                        } ?: pos
                    }
                    val offset = (selection.min - surroundingText.textBefore.length).coerceAtLeast(0)
                    K3TextRange(start + offset, end + offset)
                } else {
                    null
                }
            }
        }
    }

    companion object {
        private val NEWLINE_SEQ = "\n".asK3String()
    }
}

enum class OperationUnit {
    CHARACTERS,
    WORDS;
}

enum class OperationScope {
    BEFORE_CURSOR,
    AFTER_CURSOR;
}

fun interface EmitInterceptor {
    fun onEmit(value: K3StringOrDescriptor)
}

private class ExpectedContentQueue {
    private val list = mutableListOf<K3Content>()

    fun popUntilOrNull(predicate: (K3Content) -> Boolean): K3Content? {
        while (list.isNotEmpty()) {
            val item = list[0]
            if (predicate(item)) return item
            list.removeAt(0)
        }
        return null
    }

    fun push(item: K3Content) {
        list.add(item)
    }

    fun clear() {
        list.clear()
    }
}

fun K3Content.cursorCapsMode(inputAttributes: InputAttributes): InputAttributes.CapsMode {
    return InputAttributes.CapsMode.fromFlags(
        TextUtils.getCapsMode(surroundingText.textBefore, surroundingText.textBefore.length, inputAttributes.raw)
    )
}
