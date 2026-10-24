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
    @Volatile var tapCollect = true
    @Volatile var claimDaily = false
    @Volatile private var running = false

    private var gamePkg = "com.miniclip.carrom"
    private val closerWords = setOf("not now", "no thanks", "no, thanks", "maybe later", "later", "skip", "close", "dismiss")
    private val adCloseWords = setOf("x", "×", "✕", "✖", "close", "skip", "skip ad", "close ad", "done", "continue")
    private val adNodeLabels = setOf("close", "close ad", "skip", "skip ad", "dismiss", "done", "x", "×", "✕", "continue")
    private val adClassHints = listOf(
        ".ads.", "adactivity", "applovin", "ironsource", "vungle", "adcolony", "mbridge", "chartboost",
        "inmobi", "pangle", "bytedance.sdk", "fyber", "admob", "unityads", "unity3d.ads",
        "interstitial", "rewarded", "fullscreenad"
    )

    private enum class AdResult { DONE, NOAD, TIMEOUT, STOPPED }

    companion object {
        private const val AD_TIMEOUT_MS = 120_000L
        private const val CYCLE_MS = 10_000L
        private const val MAX_CLAIMS = 6
    }

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

    private fun isAdClass(cn: String?): Boolean {
        if (cn == null) return false
        val s = cn.lowercase()
        return adClassHints.any { s.contains(it) }
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
        StatusOverlay.show()
        try {
            runInner()
        } catch (t: Throwable) {
            Logger.log("Run error: ${t.message}")
        } finally {
            running = false
            StatusOverlay.update("AutoClaim: run khatam")
            StatusOverlay.hideLater(4000)
        }
    }

    // ---------------------------------------------------------------- ad handling

    private fun saveStuck(svc: ClaimService) {
        val shot = svc.screenshot() ?: return
        try {
            FileOutputStream(File(ctx.filesDir, "stuck_last.jpg")).use { shot.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            val lines = Detector.ocr(shot)
            Logger.log("📸 Stuck screenshot save hui. Text: " + lines.take(12).joinToString(" | ") { it.text })
        } catch (t: Throwable) {
            Logger.log("Stuck save error: ${t.message}")
        }
        shot.recycle()
    }

    private fun inCorner(r: Rect, w: Int, h: Int): Boolean {
        val cx = r.centerX()
        val cy = r.centerY()
        return (cy < h * 0.2 || cy > h * 0.88) && (cx < w * 0.3 || cx > w * 0.7)
    }

    /** Ek close attempt: native node -> corner OCR (X/Skip/Close) -> (3rd cycle ke baad) system Back. */
    private fun closeCycle(svc: ClaimService, shot: Bitmap, lines: List<OcrLine>, cycles: Int): Boolean {
        val node = svc.clickCloseNode(adNodeLabels)
        if (node != null) {
            Logger.log("❎ Ad close (node): $node")
            return true
        }
        val w = shot.width
        val h = shot.height
        val cand = lines.firstOrNull { adCloseWords.contains(norm(it.text)) && inCorner(it.rect, w, h) }
        if (cand != null) {
            svc.tap(cand.rect.exactCenterX(), cand.rect.exactCenterY())
            Logger.log("❎ Ad close (OCR): '${cand.text}'")
            return true
        }
        if (cycles >= 3) {
            svc.back()
            Logger.log("⬅️ Ad: system Back (cycle $cycles)")
            return true
        }
        return false
    }

    /**
     * Collect dabane ke baad call hota hai. Ad chalne se lekar popup wapas aane tak watch karta hai.
     * Har 10 sec me ek close cycle; max 120 sec.
     */
    private fun handleAd(svc: ClaimService): AdResult {
        val start = System.currentTimeMillis()
        var adSeen = false
        var adClassSeen = false
        var classBackSince = 0L
        var nextCycle = start + CYCLE_MS
        var cycles = 0
        var storeBacks = 0
        svc.resetWindowInfo()
        Logger.log("📺 Ad ka wait shuru")
        if (!sleepStop(3000)) return AdResult.STOPPED

        while (true) {
            if (stop) return AdResult.STOPPED
            val now = System.currentTimeMillis()
            val elapsed = now - start
            if (elapsed > AD_TIMEOUT_MS) {
                Logger.log("⏱ Ad timeout (${AD_TIMEOUT_MS / 1000}s)")
                saveStuck(svc)
                svc.back()
                Thread.sleep(800)
                svc.back()
                return AdResult.TIMEOUT
            }

            val fp = svc.foregroundPackage()
            if (fp != null && (fp == "com.android.vending" || fp.contains("chrome") || fp.contains("browser"))) {
                storeBacks++
                Logger.log("↩️ Store/browser khula ($fp), Back")
                svc.back()
                if (storeBacks >= 3) launchGame()
                if (!sleepStop(2000)) return AdResult.STOPPED
                continue
            }

            if (isAdClass(svc.lastWindowClass)) {
                adClassSeen = true
                classBackSince = 0L
            } else if (adClassSeen && svc.lastWindowPkg == gamePkg) {
                if (classBackSince == 0L) classBackSince = now
                if (now - classBackSince >= 6000) {
                    Logger.log("✅ Ad Activity band, game wapas (popup text nahi dikha)")
                    return AdResult.DONE
                }
            }

            val shot = svc.screenshot()
            if (shot != null) {
                val lines = Detector.ocr(shot)
                val an = analyze(shot, lines)
                val popupBack = an.rewardsOpen || an.dailyPage
                if (!popupBack) adSeen = true
                if (adSeen && popupBack) {
                    shot.recycle()
                    Logger.log("✅ Ad khatam, popup wapas")
                    sleepStop(1500)
                    return AdResult.DONE
                }
                if (!adSeen && elapsed > 12000) {
                    shot.recycle()
                    Logger.log("Ad shuru nahi hua (popup wahi hai)")
                    return AdResult.NOAD
                }
                if (now >= nextCycle) {
                    cycles++
                    nextCycle = now + CYCLE_MS
                    val did = closeCycle(svc, shot, lines, cycles)
                    shot.recycle()
                    if (did) {
                        if (!sleepStop(5000)) return AdResult.STOPPED
                        continue
                    } else {
                        Logger.log("🔎 Cycle $cycles: close nahi mila")
                    }
                } else {
                    shot.recycle()
                }
            }
            StatusOverlay.update("📺 Ad chal raha hai (${elapsed / 1000}s, cycle $cycles)")
            if (!sleepStop(2000)) return AdResult.STOPPED
        }
    }

    // ---------------------------------------------------------------- main run

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
        if (!sleepStop(12000)) {
            Logger.log("Roka gaya")
            return
        }

        var idle = 0
        var scanned = false
        var dailyLogged = false
        var dailyTaps = 0
        var dailyClaimTries = 0
        var claimsDone = 0
        var claimFails = 0
        var lastTexts = ""

        for (i in 1..60) {
            if (stop) {
                Logger.log("Roka gaya")
                return
            }

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
            lastTexts = lines.take(12).joinToString(" | ") { it.text }
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
                if (claimDaily && tapCollect && an.active.isNotEmpty() && dailyClaimTries < 2) {
                    dailyClaimTries++
                    val r = an.active.minByOrNull { it.top }!!
                    Logger.log("👆 Daily Collect dabaya")
                    svc.tap(r.exactCenterX(), r.exactCenterY())
                    val res = handleAd(svc)
                    if (res == AdResult.STOPPED) {
                        Logger.log("Roka gaya")
                        return
                    }
                    if (res == AdResult.DONE) Logger.log("🎉 Daily reward claim ho gaya") else Logger.log("⚠️ Daily claim fail ($res)")
                    acted = true
                } else {
                    dailyTaps++
                    if (dailyTaps > 2) {
                        svc.back()
                        Logger.log("⬅️ Daily Reward page: system Back")
                    } else {
                        svc.tap(an.w * 0.09f, an.h * 0.955f)
                        Logger.log("⬅️ Daily Reward page: bottom-left back arrow dabaya")
                    }
                    acted = true
                }
            } else if (an.rewardsOpen) {
                if (!scanned) {
                    scanned = true
                    Logger.log("🎁 Free Coins popup mila — active Collect: ${an.active.size}, locked: ${an.lockedCount}")
                }
                if (tapCollect && claimsDone < MAX_CLAIMS && claimFails < 2 && an.active.isNotEmpty()) {
                    val r = an.active.minByOrNull { it.top }!!
                    Logger.log("👆 Collect dabaya (reward ${claimsDone + 1})")
                    svc.tap(r.exactCenterX(), r.exactCenterY())
                    val res = handleAd(svc)
                    if (res == AdResult.STOPPED) {
                        Logger.log("Roka gaya")
                        return
                    }
                    if (res == AdResult.DONE) {
                        claimsDone++
                        claimFails = 0
                        Logger.log("🎉 Reward $claimsDone claim ho gaya")
                    } else {
                        claimFails++
                        Logger.log("⚠️ Reward claim fail ($res)")
                    }
                    acted = true
                } else {
                    val x = an.closeX
                    if (x != null) {
                        svc.tap(x.exactCenterX(), x.exactCenterY())
                        Logger.log("❎ Rewards popup ka X dabaya")
                        acted = true
                    }
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
                    Logger.log("✅ Kuch aur nahi mila, run complete (claims: $claimsDone)")
                    Logger.log("Last screen text: $lastTexts")
                    return
                }
                if (!sleepStop(2500)) return
            }
        }
        Logger.log("Max steps ho gaye, run khatam (claims: $claimsDone)")
    }
}
