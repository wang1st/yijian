/*
 * Copyright (C) 2026 The yijian Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.translation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the English learning report as an A4 PDF.
 *
 * Everything is drawn manually onto [PdfDocument] pages (no third-party lib): a title block,
 * a stats strip, an error-distribution bar chart, the top practice points, and a per-sentence
 * correction list. Long content flows onto additional pages automatically.
 */
object PdfReportWriter {

    // A4 at 72dpi (PostScript points) — the unit PdfDocument expects.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN

    private const val INK = 0xFF1B2420.toInt()
    private const val MUTED = 0xFF6B7570.toInt()
    private const val ACCENT = 0xFF2E7D5B.toInt()
    private const val RULE = 0xFFE2E6E4.toInt()
    private const val CHIP_BG = 0xFFF2F5F3.toInt()
    private const val DANGER = 0xFFB3261E.toInt()

    private val TAG_COLORS = mapOf(
        "拼写" to 0xFFB3261E.toInt(),
        "语法" to 0xFFB26B00.toInt(),
        "用词" to 0xFF2E5B8A.toInt(),
        "表达" to 0xFF5B4B8A.toInt(),
    )

    fun build(entries: List<PracticeEntry>): ByteArray {
        val doc = PdfDocument()
        val ctx = Ctx(doc)
        try {
            ctx.startPage()
            drawReport(ctx, entries)
            ctx.finishPage()
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    // ---------------------------------------------------------------- layout engine

    /** Page state + flowing cursor with automatic page breaks. */
    private class Ctx(val doc: PdfDocument) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = MARGIN
        var pageNumber = 0
        val pageNumbers = mutableListOf<Int>()

        fun startPage() {
            if (page != null) finishPage()
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber + 1).create()
            page = doc.startPage(info)
            canvas = page!!.canvas
            y = MARGIN
            pageNumber += 1
            pageNumbers.add(pageNumber)
        }

        fun finishPage() {
            page?.let { p ->
                drawFooter(canvas!!, pageNumber)
                doc.finishPage(p)
            }
            page = null
            canvas = null
        }

        /** Ensures [height] fits on the page, breaking if needed. */
        fun ensure(height: Float) {
            if (y + height > PAGE_H - MARGIN - 24) startPage()
        }

        private fun drawFooter(c: Canvas, n: Int) {
            val p = paint(MUTED, 8f)
            c.drawText("译键 · 英语学习报告", MARGIN, PAGE_H - 26f, p)
            val label = n.toString()
            c.drawText(label, PAGE_W - MARGIN - p.measureText(label), PAGE_H - 26f, p)
        }
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

    /** Wraps [text] to [maxWidth] and draws it, breaking pages as needed. Returns the height used. */
    private fun drawWrapped(
        ctx: Ctx,
        text: String,
        x: Float,
        maxWidth: Float,
        p: Paint,
        lineGap: Float = 1.35f,
        keepWith: Float = 0f,
    ): Float {
        if (text.isBlank()) return 0f
        val lineH = p.textSize * lineGap
        val lines = breakLines(text, p, maxWidth)
        var used = 0f
        for (line in lines) {
            if (ctx.y + lineH > PAGE_H - MARGIN - 24) ctx.startPage()
            ctx.canvas!!.drawText(line, x, ctx.y + p.textSize, p)
            ctx.y += lineH
            used += lineH
        }
        return used
    }

    private fun breakLines(text: String, p: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) { out.add(""); continue }
            var line = StringBuilder()
            // Split on spaces, but keep CJK breakable per character.
            val tokens = mutableListOf<String>()
            var buf = StringBuilder()
            for (ch in para) {
                if (ch == ' ') { tokens.add(buf.toString()); tokens.add(" "); buf = StringBuilder() }
                else if (ch.code > 0x2E7F) { if (buf.isNotEmpty()) { tokens.add(buf.toString()); buf = StringBuilder() }; tokens.add(ch.toString()) }
                else buf.append(ch)
            }
            if (buf.isNotEmpty()) tokens.add(buf.toString())

            for (t in tokens) {
                if (p.measureText(line.toString() + t) <= maxWidth) {
                    line.append(t)
                } else {
                    if (line.isNotEmpty()) out.add(line.toString().trimEnd())
                    line = StringBuilder(t.trimStart())
                }
            }
            if (line.isNotEmpty()) out.add(line.toString().trimEnd())
        }
        return out
    }

    private fun drawRule(ctx: Ctx, gap: Float = 10f) {
        ctx.ensure(gap + 1f)
        ctx.canvas!!.drawRect(MARGIN, ctx.y + gap / 2, PAGE_W - MARGIN, ctx.y + gap / 2 + 0.7f, paint(RULE, 1f))
        ctx.y += gap
    }

    /** Small filled pill with the correction category, matching the HTML preview. */
    private fun drawTagChip(ctx: Ctx, tag: String, color: Int, x: Float) {
        val p = paint(Color.WHITE, 7.5f)
        val w = p.measureText(tag) + 10f
        ctx.canvas!!.drawRoundRect(
            RectF(x, ctx.y + 1f, x + w, ctx.y + 13f), 2.5f, 2.5f, paint(color, 1f),
        )
        ctx.canvas!!.drawText(tag, x + 5f, ctx.y + 10f, p)
    }

    private fun sectionTitle(ctx: Ctx, title: String) {
        ctx.ensure(34f)
        // small accent bar + title
        val bar = paint(ACCENT, 1f)
        ctx.canvas!!.drawRect(MARGIN, ctx.y + 3f, MARGIN + 3f, ctx.y + 17f, bar)
        ctx.canvas!!.drawText(title, MARGIN + 10f, ctx.y + 14f, paint(INK, 13f, bold = true))
        ctx.y += 26f
    }

    // ---------------------------------------------------------------- report content

    private fun drawReport(ctx: Ctx, entries: List<PracticeEntry>) {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val totalWords = entries.sumOf { it.wordCount }
        val allCorrections = entries.flatMap { it.corrections }
        val byTag = allCorrections.groupBy { it.tag.ifBlank { "其他" } }
            .toList().sortedByDescending { it.second.size }
        val withIssues = entries.count { it.corrections.isNotEmpty() }
        val cleanRate = if (entries.isEmpty()) 0 else ((entries.size - withIssues) * 100.0 / entries.size).toInt()

        // ---- title block
        ctx.canvas!!.drawText("英语学习报告", MARGIN, ctx.y + 26f, paint(INK, 26f, bold = true))
        ctx.y += 36f
        ctx.canvas!!.drawText(
            "译键 · 生成于 ${df.format(Date())}",
            MARGIN, ctx.y + 4f, paint(MUTED, 9f),
        )
        ctx.y += 20f
        drawRule(ctx, 14f)

        // ---- stats strip
        ctx.ensure(64f)
        val stats = listOf(
            "练习句数" to entries.size.toString(),
            "英文词数" to totalWords.toString(),
            "有问题" to withIssues.toString(),
            "正确率" to "$cleanRate%",
        )
        val gap = 10f
        val cardW = (CONTENT_W - gap * 3) / 4
        stats.forEachIndexed { i, (label, value) ->
            val left = MARGIN + i * (cardW + gap)
            ctx.canvas!!.drawRoundRect(
                RectF(left, ctx.y, left + cardW, ctx.y + 52f), 6f, 6f, paint(CHIP_BG, 1f),
            )
            ctx.canvas!!.drawText(value, left + 10f, ctx.y + 26f, paint(ACCENT, 17f, bold = true))
            ctx.canvas!!.drawText(label, left + 10f, ctx.y + 42f, paint(MUTED, 8.5f))
        }
        ctx.y += 68f

        // ---- error distribution
        if (byTag.isNotEmpty()) {
            sectionTitle(ctx, "错误分布")
            val maxCount = byTag.maxOf { it.second.size }.coerceAtLeast(1)
            for ((tag, items) in byTag) {
                ctx.ensure(22f)
                val labelW = 46f
                ctx.canvas!!.drawText(tag, MARGIN, ctx.y + 11f, paint(INK, 10f))
                val barLeft = MARGIN + labelW
                val barMax = CONTENT_W - labelW - 40f
                val w = barMax * (items.size.toFloat() / maxCount)
                ctx.canvas!!.drawRect(RectF(barLeft, ctx.y + 2f, barLeft + barMax, ctx.y + 15f), paint(CHIP_BG, 1f))
                ctx.canvas!!.drawRoundRect(
                    RectF(barLeft, ctx.y + 2f, barLeft + w, ctx.y + 15f), 3f, 3f,
                    paint(TAG_COLORS[tag] ?: ACCENT, 1f),
                )
                ctx.canvas!!.drawText("${items.size} 次", barLeft + barMax + 8f, ctx.y + 12f, paint(MUTED, 9f))
                ctx.y += 21f
            }
            ctx.y += 8f
        }

        // ---- top things to practise
        if (allCorrections.isNotEmpty()) {
            sectionTitle(ctx, "最需要练的 5 个点")
            val groups = allCorrections
                .groupBy { it.why.ifBlank { it.right } }
                .entries.sortedByDescending { it.value.size }
                .take(5)
            groups.forEachIndexed { i, (why, items) ->
                val sample = items.first()
                ctx.ensure(46f)
                ctx.canvas!!.drawText("${i + 1}.", MARGIN, ctx.y + 12f, paint(ACCENT, 11f, bold = true))
                drawWrapped(ctx, "${sample.wrong}  →  ${sample.right}", MARGIN + 18f, CONTENT_W - 18f, paint(INK, 10.5f, bold = true))
                if (why.isNotBlank()) {
                    drawWrapped(ctx, why, MARGIN + 18f, CONTENT_W - 18f, paint(MUTED, 9f), lineGap = 1.35f)
                }
                ctx.y += 8f
            }
            ctx.y += 4f
        }

        // ---- per-sentence corrections
        sectionTitle(ctx, "逐句批改")
        entries.forEachIndexed { idx, e ->
            ctx.ensure(52f)
            ctx.canvas!!.drawText("${idx + 1}.  ${df.format(Date(e.timestamp))}", MARGIN, ctx.y + 10f, paint(MUTED, 8.5f))
            ctx.y += 16f

            ctx.canvas!!.drawText("你写的", MARGIN, ctx.y + 8f, paint(MUTED, 8.5f))
            drawWrapped(ctx, e.original, MARGIN + 42f, CONTENT_W - 42f, paint(DANGER, 10.5f))

            if (e.corrected.isNotBlank()) {
                ctx.ensure(18f)
                ctx.canvas!!.drawText("地道说法", MARGIN, ctx.y + 8f, paint(MUTED, 8.5f))
                drawWrapped(ctx, e.corrected, MARGIN + 42f, CONTENT_W - 42f, paint(ACCENT, 10.5f, bold = true))
            }

            e.corrections.forEach { c ->
                ctx.ensure(30f)
                val tagColor = TAG_COLORS[c.tag] ?: ACCENT
                drawTagChip(ctx, c.tag, tagColor, MARGIN + 42f)
                drawWrapped(
                    ctx,
                    "${c.wrong}  →  ${c.right}",
                    MARGIN + 94f, CONTENT_W - 94f, paint(INK, 9.5f), lineGap = 1.35f,
                )
                if (c.why.isNotBlank()) {
                    drawWrapped(ctx, c.why, MARGIN + 94f, CONTENT_W - 94f, paint(MUTED, 8.5f), lineGap = 1.35f)
                }
                ctx.y += 3f
            }
            if (e.note.isNotBlank()) {
                ctx.ensure(14f)
                ctx.y += 2f
                ctx.canvas!!.drawText("点评", MARGIN + 42f, ctx.y + 8f, paint(ACCENT, 8.5f, bold = true))
                drawWrapped(ctx, e.note, MARGIN + 80f, CONTENT_W - 80f, paint(MUTED, 9f), lineGap = 1.35f)
            }
            ctx.y += 8f
            drawRule(ctx, 8f)
        }
    }
}
