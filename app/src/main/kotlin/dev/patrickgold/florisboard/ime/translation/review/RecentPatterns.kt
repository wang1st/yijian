package dev.patrickgold.florisboard.ime.translation.review

import dev.patrickgold.florisboard.ime.translation.PracticeEntry
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class ExpressionPattern(
    val id: String, val title: String, val cue: String, val signals: Regex,
    vararg val situations: Pair<String, String>,
)

// Each situation has its own audience, facts and reference. Report text is used only as evidence.
internal val expressionPatterns = listOf(
    ExpressionPattern("time", "让人听出事情发生在什么时候", "说已经结束的事时，把动作放回过去；说惯例时，才用一般现在时。",
        Regex("过去时|过去式|过去发生|已经发生的"),
        "邻居问包裹到了没有。告诉她，你昨晚已经拿到了。" to "I picked it up last night.",
        "同事在等维修员。告诉他，维修员十分钟前就走了。" to "The technician left ten minutes ago."),
    ExpressionPattern("base-verb", "问过去的事，动词怎么变？", "用 did 问过去发生的事，时间已经由 did 表明，后面的动词用原形。",
        Regex("(?i)did(?:n't|n’t)?[^。；]{0,40}原形|原形[^。；]{0,20}did"),
        "向前台确认：昨天那班接驳车提前走了吗？" to "Did the shuttle leave early yesterday?",
        "问朋友：他昨晚找到丢失的门卡了吗？" to "Did he find his key card last night?"),
    ExpressionPattern("articles", "让对方知道你指的是哪一个", "第一次提到某个单数可数事物，常用 a 或 an；双方已经知道是哪一个时，常用 the。",
        Regex("冠词|特指|泛指"),
        "你和同事刚谈过一份合同。请他把那份合同带来，再带一支笔来，哪支都行。" to "Please bring the contract and a pen.",
        "告诉室友你买了一盏灯，再告诉他这盏灯放在书桌上。" to "I bought a lamp. The lamp is on the desk."),
    ExpressionPattern("number", "把一个和多个说清楚", "先确定你在数什么。说两个或更多可数事物时，名词通常也要体现数量。",
        Regex("名词复数|复数形式|用复数|复数名词|单复数"),
        "在车站窗口，告诉售票员你需要三张去机场的票。" to "I need three tickets to the airport.",
        "同事安排会场，告诉他这张桌子还差两把椅子。" to "We need two more chairs for this table."),
    ExpressionPattern("subject", "说日常习惯时，让动词跟上主语", "说一个人或一件事平时怎样时，留意一般现在时动词的第三人称单数形式。",
        Regex("第三人称单数|三单|主谓一致"),
        "朋友想周日寄件。告诉他，这家邮局每周日都关门。" to "The post office closes every Sunday.",
        "向新同事介绍分工：米娅每天早上检查设备。" to "Mia checks the equipment every morning."),
    ExpressionPattern("indirect-question", "把直接提问变成嵌在句子里的问题", "用 Do you know 开头时，后面的疑问部分通常按“主语＋动词”排列。",
        Regex("间接疑问|陈述(?:句)?语序|宾语从句[^。；]{0,16}语序"),
        "你在博物馆找寄存柜。用“你知道……”向工作人员打听位置。" to "Do you know where the lockers are?",
        "同事提到末班车。用“你知道……”问他车什么时候开。" to "Do you know when the last bus leaves?"),
    ExpressionPattern("request", "让请求给对方留出回答的余地", "请不熟悉的人帮忙时，说清具体动作，并给对方选择的余地。",
        Regex("更礼貌|不够礼貌|过于直接|语气生硬|委婉"),
        "展览厅里有人挡住了说明牌。请这位陌生人稍微挪一下。" to "Could you move over a little, please?",
        "第一次联系会务人员，请对方帮你确认入口的位置。" to "Could you confirm where the entrance is?"),
    ExpressionPattern("comparison", "比较两个选择时，说清谁比谁更合适", "先确定比较的双方，再选比较级；避免给一个比较级再加 more。",
        Regex("比较级"),
        "朋友正选返程路线。告诉他，走河边这条路比走市中心快。" to "The riverside route is faster than the route through town.",
        "邻居在挑背包。告诉她，小的那个比大的轻。" to "The small backpack is lighter than the large one."),
)

internal data class RecentPattern(val pattern: ExpressionPattern, val sentences: Int)
internal data class HomePractice(val focus: RecentPattern?, val practice: PracticeSnapshot)

internal class PatternEvidence(now: Long) {
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val end = date.format(Date(now))
    private val start = date.format(Date(now - 28L * 86_400_000))
    private val hits = mutableMapOf<ExpressionPattern, MutableMap<String, String>>()
    private val examples = expressionPatterns.associateWith { p ->
        p.situations.map { PersonalPractice.words(it.second) }.toSet()
    }
    private val sourceSentences = mutableSetOf<String>()

    fun addArchive(row: JSONObject) {
        val original = row.optString("original")
        val better = row.optString("better")
        rememberSource(original, better)
        val date = row.optString("at")
        val corrections = row.optJSONArray("corrections") ?: return
        for (i in 0 until corrections.length()) {
            val correction = corrections.optJSONObject(i) ?: continue
            add(original, date, correction.optString("kind"), correction.optString("detail"))
        }
    }

    fun addEntry(entry: PracticeEntry) {
        rememberSource(entry.original, entry.corrected)
        val at = date.format(Date(entry.timestamp))
        entry.corrections.forEach { add(entry.original, at, it.tag, it.why) }
    }

    private fun rememberSource(original: String, better: String) {
        sourceSentences.add(PersonalPractice.words(original))
        sourceSentences.add(PersonalPractice.words(better))
    }

    private fun add(original: String, at: String, kind: String, reason: String) {
        if (at !in start..end || kind == "拼写" || original.isBlank() || reason.isBlank()) return
        val key = PersonalPractice.words(original)
        // A did + base-form correction is more specific than a general past-tense problem.
        val patterns = expressionPatterns.filter { it.signals.containsMatchIn(reason) }
        for (pattern in patterns) {
            if (pattern.id == "time" && patterns.any { it.id == "base-verb" }) continue
            val sentences = hits.getOrPut(pattern) { mutableMapOf() }
            if (at > sentences[key].orEmpty()) sentences[key] = at
        }
    }

    fun focus(preferred: String = ""): RecentPattern? = hits.entries
        .filter { (pattern, _) -> examples.getValue(pattern).any { it !in sourceSentences } }
        .sortedWith(compareByDescending<Map.Entry<ExpressionPattern, MutableMap<String, String>>> { it.key.id == preferred }
            .thenByDescending { it.value.size }
            .thenByDescending { it.value.values.maxOrNull() }.thenBy { it.key.id })
        .firstOrNull()?.let { RecentPattern(it.key, it.value.size) }

    fun eligible(pattern: ExpressionPattern) = pattern.situations.filter { PersonalPractice.words(it.second) !in sourceSentences }
}
