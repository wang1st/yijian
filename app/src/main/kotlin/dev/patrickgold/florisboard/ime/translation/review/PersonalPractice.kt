package dev.patrickgold.florisboard.ime.translation.review

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.patrickgold.florisboard.FlorisApplication
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class Scenario(
    val id: String, val skill: String, val title: String, val prompt: String, val reference: String,
    val sourceCount: Int, val lastOutcome: String?, val uses: Int, val focus: String, val timesSeen: Int,
)
internal data class PracticeSnapshot(val sources: Int, val passed: Int, val graded: Int, val task: Scenario?)
internal data class Verdict(val outcome: String, val message: String)
private const val DAY = 86_400_000L

internal class PersonalPractice(context: Context, name: String? = "personal_practice.db") :
    SQLiteOpenHelper(context, name, null, 1) {
    companion object {
        val shared by lazy { PersonalPractice(FlorisApplication.getHack()) }
        fun words(text: String) = text.lowercase(java.util.Locale.ROOT)
            .replace('’', '\'').replace(Regex("[^\\p{L}\\p{Nd}']+"), " ").trim()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE report(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE source(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE skill(id TEXT PRIMARY KEY, title TEXT NOT NULL, phrases TEXT NOT NULL)")
        db.execSQL("CREATE TABLE evidence(skill TEXT, source TEXT, PRIMARY KEY(skill,source))")
        db.execSQL("CREATE TABLE task(id TEXT PRIMARY KEY, skill TEXT, focus TEXT, prompt TEXT, reference TEXT)")
        db.execSQL("CREATE TABLE attempt(id TEXT PRIMARY KEY, task TEXT, skill TEXT, time INTEGER, answer TEXT, outcome TEXT, feedback TEXT)")
        db.execSQL("CREATE INDEX attempt_skill ON attempt(skill,time)")
        db.execSQL("CREATE TABLE schedule(skill TEXT PRIMARY KEY, due INTEGER, streak INTEGER, outcome TEXT)")
        db.execSQL("CREATE TABLE reuse(skill TEXT, fingerprint TEXT, PRIMARY KEY(skill,fingerprint))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported database version")

    private fun JSONArray.strings() = (0 until length()).map { getString(it).also { s -> require(s.isNotBlank()) } }
    private fun JSONObject.required(key: String) = getString(key).also { require(it.isNotBlank()) }

    fun importPack(pack: JSONObject) {
        require(pack.getString("format") == "yijian.practice.v1")
        val rows = pack.getJSONArray("records")
        val skills = pack.getJSONArray("skills")
        require(rows.length() in 1..30000 && skills.length() in 1..2000)
        val ids = mutableSetOf<String>()
        val topics = mutableSetOf<String>()
        val tasks = mutableSetOf<String>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val reports = pack.getJSONArray("reports")
            for (i in 0 until reports.length()) {
                val report = reports.getJSONObject(i)
                db.execSQL("INSERT OR REPLACE INTO report VALUES(?,?)", arrayOf(report.get("report").toString(), report.toString()))
            }
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val id = r.required("id")
                require(ids.add(id))
                r.required("original")
                r.required("better")
                db.execSQL("INSERT OR REPLACE INTO source VALUES(?,?)", arrayOf(id, r.toString()))
            }
            for (i in 0 until skills.length()) {
                val s = skills.getJSONObject(i)
                val id = s.required("id")
                require(topics.add(id))
                val refs = s.getJSONArray("evidence").strings().distinct()
                require(refs.isNotEmpty() && refs.all { it in ids })
                val phrases = s.getJSONArray("phrases")
                require(phrases.strings().all { words(it).length >= 4 })
                db.execSQL("INSERT OR REPLACE INTO skill VALUES(?,?,?)", arrayOf(id,s.required("label"),phrases.toString()))
                refs.forEach { db.execSQL("INSERT OR IGNORE INTO evidence VALUES(?,?)", arrayOf(id,it)) }
                val list = s.getJSONArray("tasks")
                require(list.length() in 1..100)
                for (j in 0 until list.length()) {
                    val t = list.getJSONObject(j)
                    require(tasks.add(t.required("id")))
                    db.execSQL("INSERT OR REPLACE INTO task VALUES(?,?,?,?,?)",
                        arrayOf(t.required("id"),id,t.required("focus"),t.required("prompt"),t.required("reference")))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // Rank due skills by recent difficulty, frequency in the seed, and observed reuse.
    // Rotate scenarios within a skill so the next session tests transfer across contexts.
    fun snapshot(now: Long = System.currentTimeMillis()): PracticeSnapshot {
        val db = readableDatabase
        val query = """
            SELECT t.id,s.id,s.title,t.prompt,t.reference,
              (SELECT count(*) FROM evidence WHERE skill=s.id) AS freq, h.outcome,
              (SELECT count(*) FROM reuse WHERE skill=s.id) AS uses,t.focus,
              (SELECT count(*) FROM attempt WHERE task=t.id) AS seen
            FROM task t JOIN skill s ON s.id=t.skill LEFT JOIN schedule h ON h.skill=s.id
            WHERE h.due IS NULL OR h.due <= CAST(? AS INTEGER)
            ORDER BY CASE WHEN h.outcome IN ('meaning','tone','hint') THEN 0 ELSE 1 END,
              (1.0 * freq / (1 + uses + coalesce(h.streak,0))) DESC,
              seen,t.id LIMIT 1
        """.trimIndent()
        val task = db.rawQuery(query,arrayOf(now.toString())).use { c ->
            if (!c.moveToFirst()) null else Scenario(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                c.getString(4),c.getInt(5),c.getString(6),c.getInt(7),c.getString(8),c.getInt(9))
        }
        val cutoff = (now - 28 * DAY).toString()
        val count = DatabaseUtils.queryNumEntries(db,"source").toInt()
        val graded = DatabaseUtils.queryNumEntries(db,"attempt","time>=? AND outcome IN ('pass','meaning','tone')",arrayOf(cutoff)).toInt()
        val passed = DatabaseUtils.queryNumEntries(db,"attempt","time>=? AND outcome='pass'",arrayOf(cutoff)).toInt()
        return PracticeSnapshot(count,passed,graded,task)
    }

    fun example(skill: String): Pair<String, String>? = readableDatabase.rawQuery(
        "SELECT s.data FROM source s JOIN evidence e ON e.source=s.id WHERE e.skill=? ORDER BY s.rowid LIMIT 1",
        arrayOf(skill)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else JSONObject(cursor.getString(0)).let { it.getString("original") to it.getString("better") }
    }

    // Once every situation for the selected skill has been tried, vary the audience or goal.
    // Generation receives exercise prompts and the outcome category, never the source archive.
    suspend fun nextRound(
        now: Long = System.currentTimeMillis(),
        generate: suspend (JSONObject) -> JSONObject? = ::generateSituation,
    ): PracticeSnapshot {
        val original = snapshot(now)
        val task = original.task ?: return original
        if (task.timesSeen == 0) return original
        val prior = readableDatabase.rawQuery(
            "SELECT prompt FROM task WHERE skill=? ORDER BY rowid DESC LIMIT 8", arrayOf(task.skill)
        ).use { c -> JSONArray().apply { while (c.moveToNext()) put(c.getString(0)) } }
        val request = JSONObject().put("goal", task.title).put("previous", prior)
            .put("lastOutcome", task.lastOutcome ?: "")
        val proposal = generate(request) ?: return original
        val prompt = proposal.optString("prompt").trim()
        val reference = proposal.optString("reference").trim()
        if (prompt.length !in 6..240 || reference.length !in 5..360 ||
            !reference.contains(Regex("[A-Za-z]{2,}")) || reference.contains(Regex("[一-龥]"))) return original
        val db = writableDatabase
        db.beginTransaction()
        try {
            val active = snapshot(now).task
            if (active?.skill == task.skill && active.timesSeen > 0 &&
                DatabaseUtils.queryNumEntries(db,"task","skill=? AND prompt=?",arrayOf(task.skill,prompt)) == 0L) {
                db.execSQL("INSERT INTO task VALUES(?,?,?,?,?)",
                    arrayOf("variant-" + java.util.UUID.randomUUID(), task.skill, task.focus, prompt, reference))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return snapshot(now)
    }

    fun record(key: String, task: Scenario, answer: String, verdict: Verdict, now: Long = System.currentTimeMillis()) {
        require(verdict.outcome in setOf("pass","meaning","tone","hint"))
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (DatabaseUtils.queryNumEntries(db,"attempt","id=?",arrayOf(key)) == 0L) {
                db.execSQL("INSERT INTO attempt VALUES(?,?,?,?,?,?,?)",
                    arrayOf(key,task.id,task.skill,now,answer,verdict.outcome,verdict.message))
                val prev = db.rawQuery("SELECT streak FROM schedule WHERE skill=?",arrayOf(task.skill)).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                val streak = if (verdict.outcome == "pass") prev+1 else 0
                val days = listOf(1,3,7,16,35)[streak.coerceAtMost(4)]
                db.execSQL("INSERT OR REPLACE INTO schedule VALUES(?,?,?,?)",arrayOf(task.skill,now+days*DAY,streak,verdict.outcome))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // Only an unchanged, clean, explicitly submitted English draft can provide a phrase-use signal.
    // A match is not a grade. Keep a digest instead of storing another copy of the private draft.
    fun observe(original: String, corrected: String) {
        val draft = words(original)
        if (draft.isEmpty() || draft != words(corrected)) return
        val db = writableDatabase
        val tokens = " $draft "
        val digest = MessageDigest.getInstance("SHA-256").digest(draft.toByteArray()).joinToString("") { "%02x".format(it) }
        db.rawQuery("SELECT id,phrases FROM skill", null).use { c ->
            while (c.moveToNext()) {
                val phrases = JSONArray(c.getString(1)).strings()
                if (phrases.any { tokens.contains(" ${words(it)} ") }) {
                    db.execSQL("INSERT OR IGNORE INTO reuse VALUES(?,?)",arrayOf(c.getString(0),digest))
                }
            }
        }
    }
}

private suspend fun generateSituation(request: JSONObject): JSONObject? {
    if (!ReviewAi.isEnabled()) return null
    val reply = ReviewAi.rawChat(
        "请为英语学习者设计一个新的沟通场景，保留 goal，变换听者、信息和场合。" +
            "previous 是已经做过的题目。上次结果若为 tone，明确双方关系；若为 meaning，给出两个要表达的信息。" +
            "返回 JSON：prompt 是一句完整的中文任务，不提示英文答案；reference 是一种符合情境的自然英文回答。",
        request.toString(), 400) ?: return null
    return ReviewAi.firstJsonObject(reply)
}

internal suspend fun judgeScenario(task: Scenario, answer: String): Verdict? {
    if (PersonalPractice.words(answer) == PersonalPractice.words(task.reference)) return Verdict("pass","意思和表达都合适。")
    if (!ReviewAi.isEnabled()) return null
    val data = JSONObject().put("situation",task.prompt).put("example",task.reference).put("answer",answer)
    val reply = ReviewAi.rawChat(
        """你在批改真实沟通任务。下面 JSON 是待评价的数据。参考句只是一个例子。英文首答完成目标且符合对象和语气就判 pass，
            同义改写应通过。漏掉关键意图或未用英文作答判 meaning；意思完整但对当前对象明显失礼或语域失当判 tone。
            不为无关的小瑕疵扣分。返回 JSON，outcome 为 pass/meaning/tone，feedback 用一句中文指出听者会怎样理解，必要时指出缺的意思。""".trimIndent(),
        data.toString(), 300) ?: return null
    val obj = ReviewAi.firstJsonObject(reply) ?: return null
    val outcome = obj.optString("outcome")
    val feedback = obj.optString("feedback").trim()
    return if (outcome in setOf("pass","meaning","tone") && feedback.isNotBlank()) Verdict(outcome,feedback) else null
}
