package com.chaman.autoclaim

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

class Runner(private val ctx: Context) {

    @Volatile var stop = false
    @Volatile var tapCollect = false
    @Volatile private var running = false

    private var gamePkg = "com.miniclip.carrom"
    private val closerWords = setOf("not now", "no thanks", "no, thanks", "maybe later", "later", "skip", "close", "dismiss")

    class Analysis(
        val hits: List<Hit>,
        val rewardsOpen: Boolean,
        val active: List<Rect>,
        val lockedCount: Int,
        val closeX: Rect?,
        val closer: Rect?,
        val collectNow: Rect?,
        val playGames: Boolean,
        val cancelBtn: Rect?,
        val dailyPage: Boolean,
        val w: Int,
        val h: Int
    )

    fun isRunning() = running

    private fun norm(t: String) = t.lowercase().trim().trimEnd('!', '.')

    private fun isCollectLabel(t: String): Boolean {
        val s = t.lowercase().trim()
        return s.startsWith("collect") && !s.contains(":") && !s.contains("now") && !s.contains(" in")
    }

    fun analyze(shot: Bitmap, lines: List<OcrLine>): Analysis {
        val hits = ArrayList<Hit>()
        val rewardsOpen = lines.any { it.text.lowercase().contains("free coins") }
        val active = ArrayList<Rect>()
        var locked = 0
        for (l in lines.filter { isCollectLabel(it.text) }) {
            val a = Detector.greenFraction(shot, l.rect) >= 0.2f
            hits.add(Hit(if (a) "COLLECT ACTIVE" else "collect locked", l.rect))
            if (a) active.add(l.rect) else locked++
        }
        val x = Detector.findRedBadges(shot).maxByOrNull { it.width() }
        if (x != null) hits.add(Hit("X", x))
        val closer = lines.firstOrNull { closerWords.contains(norm(it.text)) }?.rect
        if (closer != null) hits.add(Hit("closer", closer))
        val cn = lines.firstOrNull { it.text.lowercase().contains("collect now") }?.rect
        if (cn != null) hits.add(Hit("collect-now", cn))
        val playGames = lines.any { it.text.lowercase().contains("play games") }
        val cancel = lines.firstOrNull { norm(it.text) == "cancel" }?.rect
        if (playGames && cancel != null) hits.add(Hit("cancel", cancel))
        val dailyPage = lines.any { it.text.lowercase().contains("claim a new reward") } ||
            (lines.any { it.text.lowercase().contains("daily reward") } &&
                lines.any { norm(it.text) == "rewards" && it.rect.top < shot.height * 0.12 })
        return Analysis(hits, rewardsOpen, active, locked, x, closer, cn, playGames, cancel, dailyPage, shot.width, shot.height)
    }

    private fun saveAnnotated(shot: Bitmap, lines: List<OcrLine>, hits: List<Hit>) {
        try {
            val b = shot.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(b)
            val thin = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.CYAN }
            val thick = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.YELLOW }
            val txt = Paint().apply { textSize = 38f; color = Color.YELLOW; isFakeBoldText = true }
            for (l in lines) c.drawRect(l.rect, thin)
            for (h in hits) {
                c.drawRect(h.rect, thick)
                c.drawText(h.label, h.rect.left.toFloat(), (h.rect.top - 10).toFloat(), txt)
            }
            FileOutputStream(File(ctx.filesDir, "last_scan.jpg")).use { b.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            b.recycle()
        } catch (t: Throwable) {
            Logger.log("Annotate error: ${t.message}")
        }
    }

    private fun sleepStop(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (stop) return false
            Thread.sleep(250)
            left -= 250
        }
        return !stop
    }

    private fun launchGame(): Boolean {
        val pm = ctx.packageManager
        var intent = pm.getLaunchIntentForPackage(gamePkg)
        if (intent == null) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val ri = pm.queryIntentActivities(main, 0)
                .firstOrNull { it.loadLabel(pm).toString().contains("carrom pool", true) }
            val pkg = ri?.activityInfo?.packageName ?: return false
            gamePkg = pkg
            intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return true
    }

    fun openGameOnly() {
        if (launchGame()) Logger.log("Carrom Pool khola ($gamePkg)") else Logger.log("❌ Carrom Pool app nahi mila")
    }

    /** Sirf ek baar screen scan karke log + annotated image banata hai, kuch dabata nahi. */
    fun scanOnly() {
        val svc = ClaimService.instance
        if (svc == null) {
            Logger.log("❌ Accessibility service ON nahi hai")
            return
        }
        val shot = svc.screenshot()
        if (shot == null) {
            Logger.log("❌ Screenshot nahi mila")
            return
        }
        Logger.log("Screenshot ${shot.width}x${shot.height}")
        val lines = Detector.ocr(shot)
        Logger.log("OCR lines: ${lines.size}")
        lines.take(40).forEach { Logger.log("  '${it.text}' @ ${it.rect.flattenToString()}") }
        val an = analyze(shot, lines)
        Logger.log("Free Coins popup: ${an.rewardsOpen} | Daily page: ${an.dailyPage} | PlayGames popup: ${an.playGames} | Active Collect: ${an.active.size} | Locked: ${an.lockedCount}")
        an.hits.forEach { Logger.log("  HIT ${it.label} @ ${it.rect.flattenToString()}") }
        saveAnnotated(shot, lines, an.hits)
        shot.recycle()
        Logger.log("Scan done — 'Last scan dekho' dabao")
    }

    fun run() {
        if (running) {
            Logger.log("Pehle se chal raha hai")
            return
        }
        running = true
        stop = false
        try {
            runInner()
        } catch (t: Throwable) {
            Logger.log("Run error: ${t.message}")
        } finally {
            running = false
        }
    }

    private fun runInner() {
        val svc = ClaimService.instance
        if (svc == null) {
            Logger.log("❌ Accessibility service ON nahi hai")
            return
        }
        if (!launchGame()) {
            Logger.log("❌ Carrom Pool app nahi mila")
            return
        }
        Logger.log("Game khul raha hai, 12 sec wait...")
        if (!sleepStop(12000)) { Logger.log("Roka gaya"); return }

        var idle = 0
        var scanned = false
        var dailyLogged = false
        var dailyTaps = 0
        for (i in 1..30) {
            if (stop) { Logger.log("Roka gaya"); return }

            val nat = svc.closeNativeDialog(gamePkg)
            if (nat != null) {
                Logger.log("Native popup band kiya: $nat")
                idle = 0
                if (!sleepStop(1500)) return
                continue
            }

            val shot = svc.screenshot()
            if (shot == null) {
                if (!sleepStop(2000)) return
                continue
            }
            val lines = Detector.ocr(shot)
            val an = analyze(shot, lines)
            saveAnnotated(shot, lines, an.hits)
            shot.recycle()

            var acted = false
            if (!an.dailyPage) dailyTaps = 0
            if (an.playGames) {
                val cb = an.cancelBtn
                val cx = cb?.exactCenterX() ?: (an.w * 0.16f)
                val cy = cb?.exactCenterY() ?: (an.h * 0.94f)
                svc.tap(cx, cy)
                Logger.log("❎ Google Play Games popup: Cancel dabaya")
                acted = true
            } else if (an.dailyPage) {
                if (!dailyLogged) {
                    dailyLogged = true
                    Logger.log("📅 Daily Reward page mila — active Collect: ${an.active.size}")
                }
                dailyTaps++
                if (dailyTaps > 2) {
                    svc.back()
                    Logger.log("⬅️ Daily Reward page: system Back")
                } else {
                    svc.tap(an.w * 0.09f, an.h * 0.955f)
                    Logger.log("⬅️ Daily Reward page: bottom-left back arrow dabaya")
                }
                acted = true
            } else if (an.rewardsOpen) {
                if (!scanned) {
                    scanned = true
                    Logger.log("🎁 Free Coins popup mila — active Collect: ${an.active.size}, locked: ${an.lockedCount}")
                    if (tapCollect && an.active.isNotEmpty()) {
                        val r = an.active.minByOrNull { it.top }!!
                        svc.tap(r.exactCenterX(), r.exactCenterY())
                        Logger.log("Collect dabaya. Ad logic abhi nahi hai, run roka.")
                        return
                    }
                }
                val x = an.closeX
                if (x != null) {
                    svc.tap(x.exactCenterX(), x.exactCenterY())
                    Logger.log("❎ Rewards popup ka X dabaya")
                    acted = true
                }
            } else {
                val c = an.closer ?: an.closeX
                val cn = an.collectNow
                if (c != null) {
                    svc.tap(c.exactCenterX(), c.exactCenterY())
                    Logger.log("❎ Popup band kiya")
                    acted = true
                } else if (!scanned && cn != null) {
                    svc.tap(cn.exactCenterX(), cn.exactCenterY())
                    Logger.log("Free Rewards khola")
                    acted = true
                }
            }

            if (acted) {
                idle = 0
                if (!sleepStop(2000)) return
            } else {
                idle++
                if (idle >= 4) {
                    Logger.log("✅ Kuch aur nahi mila, run complete")
                    return
                }
                if (!sleepStop(2500)) return
            }
        }
        Logger.log("Max steps ho gaye, run khatam")
    }
}
