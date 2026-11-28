package dev.patrickgold.florisboard.app.settings.review

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.app.design.Space
import dev.patrickgold.florisboard.app.design.YijianTheme
import dev.patrickgold.florisboard.ime.translation.review.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

@Composable
fun PersonalPracticeScreen() {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val db = PersonalPractice.shared
    var data by remember { mutableStateOf<PracticeSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        try {
            data = withContext(Dispatchers.IO) { db.snapshot() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = "暂时无法读取练习进度。" }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    data = withContext(Dispatchers.IO) {
                        val content = requireNotNull(resolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { reader ->
                            val chunk = CharArray(8192)
                            buildString {
                                while (true) {
                                    val n = reader.read(chunk)
                                    if (n < 0) break
                                    require(length + n <= 4_000_000)
                                    append(chunk, 0, n)
                                }
                            }
                        }
                        db.importPack(JSONObject(content))
                        db.snapshot()
                    }
                    notice = "练习库已导入，已有答题进度已保留。"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = "导入失败。请选择译键个人练习库 JSON 文件。" }
                finally { busy = false }
            }
        }
    }

    FlorisScreenNg(title = "场景练习", previewFieldVisible = false, background = colors.paper) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(horizontal = Space.gutter, vertical = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            val current = data
            if (current != null) {
                if (current.sources > 0) {
                    Text("近 28 天独立首答通过 ${current.passed} / ${current.graded}", style = type.label, color = colors.inkMuted)
                    val task = current.task
                    if (task == null) Text("今天的场景都练过了。到期后，这里会出现下一次练习。", style = type.title, color = colors.ink)
                    else key(task.id) {
                        ScenarioExercise(task, busy, { busy = it }, { notice = ""; refresh++ })
                    }
                    Text("已载入 ${current.sources} 条历史记录", style = type.small, color = colors.inkMuted)
                } else {
                    Text("从你自己想说的话开始", style = type.title, color = colors.ink)
                    Text("导入个人练习库后，你会遇到和过去相似的新情境。先用自己的英语回应，再看反馈。", style = type.body, color = colors.inkMuted)
                }
                TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(if (current.sources == 0) "导入个人练习库" else "导入或更新练习库")
                }
            } else if (notice.isEmpty()) CircularProgressIndicator()
            if (notice.isNotBlank()) Text(notice, style = type.small, color = colors.inkMuted)
            if (data == null && notice.isNotBlank()) TextButton(onClick = { refresh++ }) { Text("重新读取") }
        }
    }
}

@Composable
private fun ScenarioExercise(task: Scenario, busy: Boolean, setBusy: (Boolean) -> Unit, advance: () -> Unit) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    val scope = rememberCoroutineScope()
    var answer by rememberSaveable { mutableStateOf("") }
    val attemptId = rememberSaveable { UUID.randomUUID().toString() }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf("") }

    fun finish(hint: Boolean) {
        if (busy || feedback.isNotEmpty()) return
        submitted = true
        setBusy(true)
        problem = ""
        scope.launch {
            try {
                val verdict = withContext(Dispatchers.IO) {
                    val result = if (hint) Verdict("hint", "已记为看过示例，明天换个场景再试。")
                        else judgeScenario(task, answer)
                    if (result != null) PersonalPractice.shared.record(attemptId, task, answer, result)
                    result
                }
                if (verdict == null) problem = "暂时无法判读。请检查翻译设置中的模型连接，再用这次首答重试。"
                else feedback = listOf(when (verdict.outcome) {
                    "pass" -> "首答通过"
                    "meaning" -> "再练意思"
                    "tone" -> "再练语气"
                    else -> "已看示例"
                }, verdict.message).joinToString(" · ")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = "未能完成这次练习。可以用同一份回答重试。" }
            finally { setBusy(false) }
        }
    }

    Text(task.title, style = type.label, color = colors.inkMuted)
    Text(task.prompt, style = type.title, color = colors.ink)
    Text(when (task.lastOutcome) {
        "meaning" -> "上次漏了关键意思。这次换个情境再说一次。"
        "tone" -> "上次的措辞和对方身份不太合适。这次留意对谁说。"
        "hint" -> "上次看过示例。这次试试自己组织语言。"
        "pass" -> "上次独立答对了。换个情境，再看看能否用出来。"
        else -> "从你过去 ${task.sourceCount} 条相关记录中选出。"
    }, style = type.small, color = colors.inkMuted)
    if (task.uses > 0) Text("日常输入中出现过相关措辞 · ${task.uses} 条", style = type.small, color = colors.inkMuted)
    OutlinedTextField(value = answer, onValueChange = { answer = it.take(2000) }, readOnly = submitted,
        modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("你会怎么用英语说？") })
    if (feedback.isEmpty()) {
        Button(onClick = { finish(false) }, enabled = !busy && answer.isNotBlank()) {
            Text(if (busy) "处理中…" else if (submitted) "重试这次首答" else "提交首答")
        }
        TextButton(onClick = { finish(true) }, enabled = !busy) { Text("想不起来，看示例") }
        if (problem.isNotBlank()) Text(problem, style = type.body, color = colors.ink)
    } else {
        Text(feedback, style = type.body, color = colors.ink)
        Text("一种自然的说法", style = type.label, color = colors.inkMuted)
        Text(task.reference, style = type.title, color = colors.positive)
        Button(onClick = advance, enabled = !busy) { Text("下一题") }
    }
}
