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
}
