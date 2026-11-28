package dev.patrickgold.florisboard.ime.translation.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalPracticeTest {
    private fun pack() = JSONObject("""{
        "format":"yijian.practice.v1","reports":[{"report":1}],"records":[{"id":"r1","original":"test","better":"test"}],
        "skills":[{"id":"s","label":"Ask for a time","evidence":["r1"],"phrases":["what time"],
        "tasks":[{"id":"a","focus":"when","prompt":"Ask when the bus leaves.","reference":"What time does the bus leave?"},
        {"id":"b","focus":"when","prompt":"Ask when lunch starts.","reference":"When does lunch start?"}]}]}""")
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = 86_400_000L

    @Test fun retryImportRotationAndIntervals() {
        PersonalPractice(context, null).use { db ->
            db.importPack(pack())
            val task = db.snapshot(0).task!!
            db.record("first",task,"",Verdict("hint","example"),0)
            db.importPack(pack())
            db.record("first",task,"duplicate",Verdict("pass","ok"),0)
            assertEquals(0, db.snapshot(day-1).graded)
            assertNull(db.snapshot(day-1).task)
            val next = db.snapshot(day).task!!
            assertNotEquals(task.id,next.id)
            db.record("second",next,"When does lunch start?",Verdict("pass","ok"),day)
            assertNull(db.snapshot(4*day-1).task)
            assertNotNull(db.snapshot(4*day).task)
            assertEquals(1,db.snapshot(4*day).passed)
            assertEquals(1,db.snapshot(4*day).graded)
            assertEquals(0,db.snapshot(30*day).graded)
        }
    }

    @Test fun generatesOnlyAfterEverySeedWasTriedAndKeepsOfflineFallback() = kotlinx.coroutines.runBlocking {
        PersonalPractice(context, null).use { db ->
            db.importPack(pack())
            val first = db.nextRound(0) { error("unused seed should not call model") }.task!!
            db.record("1",first,"",Verdict("hint","example"),0)
            val next = db.nextRound(day) { error("use second seed first") }.task!!
            db.record("2",next,"",Verdict("hint","example"),day)
            val fallback = db.nextRound(2*day) { null }.task!!
            assertEquals(1, fallback.timesSeen)
            val fresh = db.nextRound(2*day) { request ->
                assertEquals("hint",request.getString("lastOutcome"))
                JSONObject().put("prompt","问图书管理员，明天几点开门。").put("reference","What time does the library open tomorrow?")
            }.task!!
            assertEquals(0, fresh.timesSeen)
            assertNotEquals(first.id, fresh.id)
            assertNotEquals(next.id, fresh.id)
        }
    }

    @Test fun invalidPackRollsBackAndCleanReuseIsIndependentOfGrades() {
        PersonalPractice(context, null).use { db ->
            val invalid = pack()
            invalid.getJSONArray("skills").getJSONObject(0).getJSONArray("evidence").put("missing")
            assertTrue(runCatching { db.importPack(invalid) }.isFailure)
            assertEquals(0,db.snapshot().sources)
            db.importPack(pack())
            db.observe("What time go?", "What time should we go?")
            db.observe("What timepiece is that?", "What timepiece is that?")
            assertEquals(0,db.snapshot().task!!.uses)
            repeat(2) { db.observe("What time is lunch?", "What time is lunch?") }
            assertEquals(1,db.snapshot().task!!.uses)
            assertEquals(0,db.snapshot().passed)
            val now = System.currentTimeMillis()
            db.record("tone",db.snapshot(now).task!!,"Answer",Verdict("tone","register"),now)
            assertEquals(1,db.snapshot(now).graded)
            assertEquals("tone",db.snapshot(now+day).task!!.lastOutcome)
        }
    }
}
