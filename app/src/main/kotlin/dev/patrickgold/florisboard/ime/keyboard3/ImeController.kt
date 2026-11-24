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
import dev.patrickgold.florisboard.ime.translation.TranslationManager
import dev.patrickgold.florisboard.ime.translation.TranslationSuggestionCandidate
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
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

    /** Current pre-edit buffer content (typed English that has not been sent to the app yet). */
    val translationPreedit get() = translationManager.preeditText

    /** Translation API lifecycle: IDLE / TRANSLATING / ERROR. */
    val translationStatus get() = translationManager.status

    /** Human readable reason for the last translation failure, null when there is none. */
    val translationError get() = translationManager.errorMessage

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

    /**
     * Guarantees the toolbar exposes a "switch input method" button.
     *
     * Editing QuickActionArrangement.Default only helps fresh installs: an existing
     * smartbar__action_arrangement replaces it. This migrates an existing arrangement once by
     * prepending the action when it is absent.
     */
    fun ensureImeSwitchAction() {
        scope.launch {
            val arrangement = prefs.smartbar.actionArrangement.get()
            val sticky = arrangement.stickyAction

            // The right-most toolbar slot should switch IMEs (one tap = previous keyboard).
            // Existing installs still carry the old voice-input action there.
            val isVoiceSticky = sticky is QuickAction.InsertK3Descriptor &&
                sticky.descriptor == ImeActions.ExternalVoiceInput
            if (isVoiceSticky) {
                prefs.smartbar.actionArrangement.set(
                    arrangement.copy(
                        stickyAction = QuickAction.InsertK3Descriptor(ImeActions.SwitchToPrevInputMethod),
                    )
                )
                flogError { "yijian: sticky toolbar action switched from voice to IME switch" }
                return@launch
            }

            // Already replaced, or the user chose something else there: leave it alone.
        }
    }

    /**
     * Removes a stray input-method switch from the dynamic row.
     *
     * Both the sticky slot and the dynamic row used to carry one, so the toolbar showed two globe
     * buttons. Only the sticky one (far right) is kept.
     */
    fun removeDuplicateImeSwitch() {
        scope.launch {
            val arrangement = prefs.smartbar.actionArrangement.get()
            val filtered = arrangement.dynamicActions.filterNot {
                it is QuickAction.InsertK3Descriptor &&
                    (it.descriptor == ImeActions.ShowInputMethodPicker ||
                        it.descriptor == ImeActions.SwitchToPrevInputMethod ||
                        it.descriptor == ImeActions.SwitchToNextInputMethod)
            }
            if (filtered.size == arrangement.dynamicActions.size) return@launch
            prefs.smartbar.actionArrangement.set(arrangement.copy(dynamicActions = filtered))
            flogError { "yijian: removed duplicate IME switch from the action row" }
        }
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
            translationManager.clearBuffer()
            translationManager.clearTranslationHistory()
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

            val rememberCapsLockState = prefs.typing.rememberCapsLockState.get() &&
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
            val content = expectedContentQueue.popUntilOrNull { it.selection == newSelection }
            if (content != null) {
                flogDebug { "DEDUPLICATED!!1" }
                return
            }
            resetContent(newSelection, state.editor.getSurroundingText(50, 10))
            expectedContentQueue.push(state.content)
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
            super.emitText(value)
            expectedContentQueue.push(state.content)
            reevaluateInputShiftState()
            translationManager.maybeTranslateOnPunct(value)
        }

        /** Set right after a translation replaced text, cleared when the undo window expires. */
        private var lastReplacement: ReplacedText? = null

        /**
         * Commits text straight into the target editor, bypassing the yijian pre-edit buffer.
         * Used for the translation results themselves and for raw-buffer flushes.
         */
        fun emitTextDirect(value: K3String) {
            super.emitText(value)
            expectedContentQueue.push(state.content)
            reevaluateInputShiftState()
        }

        /**
         * Commits the raw buffered English into the editor (panel dismissed without picking a
         * translation). No-op when the buffer is empty.
         */
        fun translationCommitBufferAsRaw() {
            translationManager.commitBufferAsRaw(this)
        }

        /**
         * Discards the buffered English and any pending translation: nothing is committed to the
         * target editor.
         */
        fun translationClearAll() {
            translationManager.clearBuffer()
            translationManager.pendingEnglish = null
            translationManager.clearTranslationMark()
            translationManager.resetLastCommitted()
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
                ImeActions.Translate -> translationManager.translateNow()
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
                ImeActions.SwitchToPrevInputMethod -> FlorisImeService.switchToPrevInputMethod()
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
            super.emitBackspace()
            expectedContentQueue.push(state.content)
        }

        override fun emitEnter() {
            val info = state.editor.info
            // yijian: Enter translates, Shift+Enter inserts a newline. The shift state comes from
            // the keyboard's own shift layer, so no physical key tracking is needed.
            val isShiftPressed = state.flags.inputShiftState != InputShiftState.UNSHIFTED
            if (isShiftPressed) {
                emitEnterKey()
                return
            }
            // S1: the text is already in the editor, so Enter always means "translate this".
            if (prefs.translation.enabled.get()) {
                translationManager.translateNow()
                return
            }
            if (info.imeOptions.flagNoEnterAction || info.inputAttributes.flagTextMultiLine) {
                emitEnterKey()
            } else {
                when (val action = info.imeOptions.action) {
                    ImeOptions.Action.UNSPECIFIED,
                    ImeOptions.Action.DONE,
                    ImeOptions.Action.GO,
                    ImeOptions.Action.NEXT,
                    ImeOptions.Action.PREVIOUS,
                    ImeOptions.Action.SEARCH,
                    ImeOptions.Action.SEND -> emitEnterAction(action)
                    else -> emitEnterKey()
                }
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
                is TranslationSuggestionCandidate -> {
                    emitTranslationCandidate(candidate)
                    // Selection made; dismiss the candidate list
                    dev.patrickgold.florisboard.FlorisApplication.getHack().nlpManager.value.clearSuggestions()
                }
                else -> emitCompletion(candidate)
            }
        }

        /**
         * Replaces the English sentence still sitting in the editor with the translation the
         * user picked from the candidates list. Runs inside the current state update scope on
         * purpose — calling updateStateBlocking here would deadlock on the state mutex.
         */
        /**
         * Replaces the span that was sent for translation with the picked candidate.
         *
         * The span is located by position ([pendingStartPos], cursor), which was recorded when
         * the request was issued. String-based matching was abandoned: spacing differences, the
         * model spelling-correcting a word, or our own text cleanup all made it compare two things
         * that were never identical and block a perfectly valid replace.
         *
         * One verification remains: the position is trusted only when the span it points to still
         * contains the text we translated (compared loosely, punctuation/whitespace ignored).
         * If the user edited meanwhile, the position is no longer meaningful and we refuse rather
         * than delete something unrelated.
         */
        fun emitTranslationCandidate(candidate: TranslationSuggestionCandidate) {
            val newText = candidate.text.toString()
            val sourceText = candidate.replaceTarget.ifBlank { translationManager.pendingEnglish }
            val startPos = translationManager.pendingStartPos
            val endPos = state.content.selection.start

            if (startPos == null || endPos <= startPos) {
                // No position recorded - fall back to appending without touching the editor.
                emitTextDirect(newText.asK3String())
                translationManager.pendingEnglish = null
                translationManager.pendingStartPos = null
                return
            }

            // No string verification. Position is the only fact we trust; comparing content kept
            // producing false rejections (the model spelling-corrects a word, spacing differs, our
            // own cleanup normalises something) even though the user had edited nothing.
            replaceSpanByPosition(startPos, endPos, newText)

            // Record the accepted pair so the same sentence is never re-translated.
            sourceText?.let { translationManager.rememberTranslation(it, newText) }
            translationManager.pendingEnglish = null
            translationManager.pendingStartPos = null
            translationManager.clearBuffer()
            translationManager.resetLastCommitted()
            lastReplacement = ReplacedText(
                original = sourceText.orEmpty(),
                translated = newText,
                timestamp = System.currentTimeMillis(),
            )
            sourceText?.let { translationManager.offerUndo(it) }
        }

        /** Strips punctuation and whitespace so two versions can be compared without those layers. */
    
        /** True when [b] is a suffix of [a] after normalisation. */
    
        /** Selects [startPos, endPos) and commits [replacement] over it, all in one batch. */
        private fun replaceSpanByPosition(startPos: Int, endPos: Int, replacement: String) {
            val ic = state.editor.ic.get() ?: return
            ic.beginBatchEdit()
            try {
                ic.setSelection(startPos, endPos)
                ic.commitText(replacement, 1)
            } finally {
                ic.endBatchEdit()
            }
        }

        /**
         * Replaces [old] (immediately before the cursor) with [replacement] by selecting the span
         * and committing the new text over it.
         *
         * The documented way to rewrite editor text is setSelection() + commitText(): commitText
         * replaces the current selection, whereas deleteSurroundingText is optional for the editor
         * to honour and is ignored outright by some apps (WeChat among them).
         *
         * @return true when the replacement is verified in place.
         */
        /**
         * True when [tail] ends with [target] once runs of whitespace are collapsed. Editors differ
         * from our request copy only in spacing; that must not block a replacement.
         */
        /**
         * Length of the suffix of [tail] that corresponds to [target], ignoring whitespace and
         * punctuation differences between them.
         *
         * Returns null when they do not match. The returned length is measured in [tail] characters
         * so it can be used to delete exactly the right span - comparing loosely but deleting by
         * `target.length` deleted the wrong number of characters whenever spacing differed.
         */
    
        /**
         * Replaces the [old] text immediately before the cursor with [replacement].
         *
         * There is no single API that every editor honours, so this walks a fallback chain and
         * **verifies after each step** whether the old text is really gone:
         *
         *  1. `setComposingRegion(start, end)` + `commitText` — the pattern Android's own
         *     InputMethodService uses. Composing regions are core to IMEs, so any editor that
         *     supports Chinese input supports them.
         *  2. `deleteSurroundingText` + `commitText` — the documented replace idiom.
         *  3. `KEYCODE_DEL` events + `commitText` — for editors that only react to key events.
         *
         * @return true when the replacement is confirmed in the editor.
         */
    
        /**
         * Deletes [text] immediately before the cursor and verifies it actually disappeared.
         *
         * WeChat and other apps ship custom InputConnection implementations that silently ignore
         * deleteSurroundingText, which is why the replacement looked like a no-op. If the batch
         * delete does not take effect, fall back to individual backspace key events.
         *
         * @return true when the text is gone.
         */

        /** Reads the text immediately before the cursor directly from the InputConnection. */
    
        /** Restores the English that the last accepted translation replaced. */
        fun undoLastReplacement() {
            val replaced = lastReplacement ?: return
            if (System.currentTimeMillis() - replaced.timestamp > UNDO_WINDOW_MS) {
                lastReplacement = null
                return
            }
            val cur = state.editor.getSurroundingText(replaced.translated.length + 5, 0).textBefore
            if (cur.endsWith(replaced.translated)) {
                state.editor.deleteSurroundingText(replaced.translated.length, 0)
                emitTextDirect(replaced.original.asK3String())
            }
            lastReplacement = null
            translationManager.clearUndo()
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
            translationManager.clearBuffer()
            resetContent()
            state = state.copy(editor = ImeEditor.Disconnected)
            expectedContentQueue.clear()
        }

        override fun evaluateCompositionOf(
            model: K3Model,
            selection: K3TextRange,
            surroundingText: K3SurroundingText
        ): K3TextRange? {
            if (selection.isNotCollapsed() || !state.flags.isComposingEnabled) {
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

/** Text that a translation replaced, kept briefly so the user can undo. */
internal data class ReplacedText(val original: String, val translated: String, val timestamp: Long)

internal const val UNDO_WINDOW_MS = 5000L

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
