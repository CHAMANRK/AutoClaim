package com.chaman.autoclaim

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

class OcrLine(val text: String, val rect: Rect)
class Hit(val label: String, val rect: Rect)

object Detector {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Screen ka text + position (on-device OCR, internet nahi chahiye). */
    fun ocr(bmp: Bitmap): List<OcrLine> {
        return try {
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS)
            val out = ArrayList<OcrLine>()
            for (b in res.textBlocks) {
                for (l in b.lines) {
                    val r = l.boundingBox
                    if (r != null) out.add(OcrLine(l.text, r))
                }
            }
            out
        } catch (t: Throwable) {
            Logger.log("OCR error: ${t.message}")
            emptyList()
        }
    }

    /** Text ke aaspaas kitna bright green hai (active Collect button ~ zyada green). */
    fun greenFraction(bmp: Bitmap, r: Rect): Float {
        val padX = (r.width() * 0.25f).toInt()
        val padY = (r.height() * 0.3f).toInt()
        val l = maxOf(0, r.left - padX)
        val t = maxOf(0, r.top - padY)
        val rr = minOf(bmp.width - 1, r.right + padX)
        val bb = minOf(bmp.height - 1, r.bottom + padY)
        var total = 0
        var green = 0
        var y = t
        while (y <= bb) {
            var x = l
            while (x <= rr) {
                val p = bmp.getPixel(x, y)
                val R = Color.red(p)
                val G = Color.green(p)
                val B = Color.blue(p)
                total++
                if (G >= 170 && G - R >= 45 && G - B >= 80) green++
                x += 3
            }
            y += 3
        }
        return if (total == 0) 0f else green.toFloat() / total
    }

    /** Laal gol X / close badge dhundhta hai (size aur shape se). */
    fun findRedBadges(bmp: Bitmap): List<Rect> {
        val s = 4
        val w = bmp.width / s
        val h = bmp.height / s
        if (w < 20 || h < 20) return emptyList()
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()

        val mask = BooleanArray(w * h) { i ->
            val p = px[i]
            val R = Color.red(p)
            val G = Color.green(p)
            val B = Color.blue(p)
            R > 170 && G < 90 && B < 90 && (R - G) > 100
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Rect>()

        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var cnt = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                cnt++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val aspect = bw.toFloat() / bh
            val fill = cnt.toFloat() / (bw * bh)
            if (bw >= w * 0.055f && bw <= w * 0.12f && aspect in 0.8f..1.25f && fill in 0.35f..0.95f) {
                out.add(Rect(minX * s, minY * s, (maxX + 1) * s, (maxY + 1) * s))
            }
        }
        return out
    }

    private fun nearMask(mask: BooleanArray, rw: Int, x: Int, y: Int): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || xx >= rw || yy < 0) continue
                val i = yy * rw + xx
                if (i < mask.size && mask[i]) return true
            }
        }
        return false
    }

    private fun looksLikeX(mask: BooleanArray, rw: Int, x0: Int, y0: Int, bw: Int, bh: Int): Boolean {
        fun at(fx: Float, fy: Float) = nearMask(mask, rw, x0 + (bw * fx).toInt(), y0 + (bh * fy).toInt())
        val diag = at(0.5f, 0.5f) && at(0.22f, 0.22f) && at(0.78f, 0.22f) && at(0.22f, 0.78f) && at(0.78f, 0.78f)
        val empty = !at(0.5f, 0.06f) && !at(0.5f, 0.94f) && !at(0.06f, 0.5f) && !at(0.94f, 0.5f)
        return diag && empty
    }

    /** Ad ke 4 corners me chhota "X" (close) glyph shape se dhundhta hai (bright ya dark X). */
    fun findCornerX(bmp: Bitmap): List<Rect> {
        val w = bmp.width
        val h = bmp.height
        val cw = (w * 0.22f).toInt()
        val ch = (h * 0.14f).toInt()
        if (cw < 20 || ch < 20) return emptyList()
        val regions = listOf(
            Rect(0, 0, cw, ch),
            Rect(w - cw, 0, w, ch),
            Rect(0, h - ch, cw, h),
            Rect(w - cw, h - ch, w, h)
        )
        val out = ArrayList<Rect>()
        for (reg in regions) {
            val rw = reg.width()
            val rh = reg.height()
            val px = IntArray(rw * rh)
            bmp.getPixels(px, 0, rw, reg.left, reg.top, rw, rh)
            for (mode in 0..2) {
                val mask = BooleanArray(rw * rh) { i ->
                    val p = px[i]
                    val pr = Color.red(p)
                    val pg = Color.green(p)
                    val pb = Color.blue(p)
                    val l = (pr * 299 + pg * 587 + pb * 114) / 1000
                    when (mode) {
                        0 -> l > 185
                        1 -> l < 75
                        else -> pr > 215 && pg > 215 && pb > 215
                    }
                }
                val seen = BooleanArray(rw * rh)
                val stack = IntArray(rw * rh)
                for (start in 0 until rw * rh) {
                    if (!mask[start] || seen[start]) continue
                    var sp = 0
                    stack[sp++] = start
                    seen[start] = true
                    var minX = rw
                    var maxX = 0
                    var minY = rh
                    var maxY = 0
                    var cnt = 0
                    while (sp > 0) {
                        val i = stack[--sp]
                        val x = i % rw
                        val y = i / rw
                        cnt++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                        if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                        if (x < rw - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                        if (y > 0 && mask[i - rw] && !seen[i - rw]) { seen[i - rw] = true; stack[sp++] = i - rw }
                        if (y < rh - 1 && mask[i + rw] && !seen[i + rw]) { seen[i + rw] = true; stack[sp++] = i + rw }
                    }
                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1
                    if (cnt < 25 || bw < w * 0.015f || bw > w * 0.07f) continue
                    val aspect = bw.toFloat() / bh
                    val fill = cnt.toFloat() / (bw * bh)
                    if (aspect < 0.75f || aspect > 1.35f || fill < 0.15f || fill > 0.6f) continue
                    if (looksLikeX(mask, rw, minX, minY, bw, bh)) {
                        out.add(Rect(reg.left + minX, reg.top + minY, reg.left + maxX + 1, reg.top + maxY + 1))
                    }
                }
            }
        }
        return out
    }

    /** Bottom-left ka orange back-arrow button (chest result / daily reward page) dhundhta hai. */
    fun findBackButton(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        val rw = (w * 0.24f).toInt()
        val rh = (h * 0.12f).toInt()
        if (rw < 20 || rh < 20) return null
        val y0 = h - rh
        val px = IntArray(rw * rh)
        bmp.getPixels(px, 0, rw, 0, y0, rw, rh)
        val mask = BooleanArray(rw * rh) { i ->
            val p = px[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            r > 170 && g in 70..180 && b < 120 && (r - g) > 45
        }
        val seen = BooleanArray(rw * rh)
        val stack = IntArray(rw * rh)
        var best: Rect? = null
        var bestArea = 0
        for (start in 0 until rw * rh) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = rw
            var maxX = 0
            var minY = rh
            var maxY = 0
            var cnt = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % rw
                val y = i / rw
                cnt++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < rw - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - rw] && !seen[i - rw]) { seen[i - rw] = true; stack[sp++] = i - rw }
                if (y < rh - 1 && mask[i + rw] && !seen[i + rw]) { seen[i + rw] = true; stack[sp++] = i + rw }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val aspect = bw.toFloat() / bh
            val fill = cnt.toFloat() / (bw * bh)
            if (bw >= w * 0.06f && bw <= w * 0.16f && aspect in 0.75f..1.4f && fill in 0.45f..0.98f && cnt > bestArea) {
                bestArea = cnt
                best = Rect(minX, y0 + minY, maxX + 1, y0 + maxY + 1)
            }
        }
        return best
    }

    /** Lucky Box: band (tall) yellow ribbon dikhe toh gift box abhi band (closed) hai. */
    fun ribbonFraction(bmp: Bitmap, cx: Int, cy: Int, halfH: Int): Float {
        val halfW = maxOf(4, (bmp.width * 0.012f).toInt())
        val x0 = maxOf(0, cx - halfW)
        val x1 = minOf(bmp.width - 1, cx + halfW)
        val y0 = maxOf(0, cy - halfH)
        val y1 = minOf(bmp.height - 1, cy + halfH)
        var total = 0
        var yellow = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                total++
                if (r > 190 && g > 140 && b < 120 && (r - b) > 90) yellow++
                x += 2
            }
            y += 3
        }
        return if (total == 0) 0f else yellow.toFloat() / total
    }

    /** Events list me card ke top-right ke chhote laal "!" alert badges (screen ke daayen kinare). */
    fun findAlertBadges(bmp: Bitmap): List<Rect> {
        val s = 4
        val w = bmp.width / s
        val h = bmp.height / s
        if (w < 20 || h < 20) return emptyList()
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val mask = BooleanArray(w * h) { i ->
            val p = px[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            r > 185 && g < 90 && b < 90 && (r - g) > 100
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Rect>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var cnt = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                cnt++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val aspect = bw.toFloat() / bh
            val fill = cnt.toFloat() / (bw * bh)
            val cxp = (minX + maxX) / 2
            val cyp = (minY + maxY) / 2
            if (bw >= w * 0.03f && bw <= w * 0.075f && aspect >= 0.8f && aspect <= 1.25f &&
                fill >= 0.35f && fill <= 0.95f && cxp > w * 0.8f && cyp > h * 0.10f && cyp < h * 0.92f
            ) {
                out.add(Rect(minX * s, minY * s, (maxX + 1) * s, (maxY + 1) * s))
            }
        }
        out.sortBy { it.top }
        return out
    }
}
