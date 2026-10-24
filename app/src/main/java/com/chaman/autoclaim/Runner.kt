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
    @Volatile var handleExtras = true
    @Volatile var tapPlayCta = true
    @Volatile private var running = false

    private var gamePkg = "com.miniclip.carrom"
    private var adBacks = 0
    private var ctaTaps = 0

    private val closerWords = setOf("not now", "no thanks", "no, thanks", "maybe later", "later", "skip", "close", "dismiss")
    private val adCloseWords = setOf("x", "×", "✕", "✖", "close", "skip", "skip ad", "close ad", "done", "continue")
    private val adNodeLabels = setOf("close", "close ad", "skip", "skip ad", "dismiss", "done", "x", "×", "✕", "continue")
    private val adClassHints = listOf(
        ".ads.", "adactivity", "applovin", "ironsource", "vungle", "adcolony", "mbridge", "chartboost",
        "inmobi", "pangle", "bytedance.sdk", "fyber", "admob", "unityads", "unity3d.ads",
        "interstitial", "rewarded", "fullscreenad"
    )

    // reward / result screens
    private val resultMarkers = listOf(
        "congratulations", "you have received", "you received", "you got", "tap chest to collect", "reward claimed"
    )
    private val navWords = setOf("home", "friends", "equipment", "events", "shop")

    // generic dialog buttons
    private val costWords = listOf(
        "buy", "purchase", "spend", "price", "₹", "$", "unlock", "upgrade", "open one more",
        "are you sure", "not enough", "insufficient"
    )
    private val rewardWords = listOf("reward", "claim", "bonus", "gift", "free")
    private val positiveWords = listOf(
        "ok", "okay", "claim", "continue", "got it", "awesome", "great", "nice", "done", "next", "accept", "tap to continue"
    )
    private val yesWords = listOf("yes", "sure", "confirm")
    private val noWords = listOf("no", "no thanks", "not now", "later", "maybe later", "cancel", "close", "skip", "dismiss")

    private enum class AdResult { DONE, NOAD, TIMEOUT, STOPPED }

    companion object {
        private const val AD_TIMEOUT_MS = 120_000L
        private const val CYCLE_MS = 10_000L
        private const val MAX_CLAIMS = 6
    }

    class Analysis(
        val lines: List<OcrLine>,
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
        val chestTap: Boolean,
        val youGot: Boolean,
        val resultScreen: Boolean,
        val navCount: Int,
        val backBtn: Rect?,
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

    private fun isGameScreen(an: Analysis) = an.rewardsOpen || an.dailyPage || an.resultScreen || an.navCount >= 2

    fun analyze(shot: Bitmap, lines: List<OcrLine>): Analysis {
        val hits = ArrayList<Hit>()
        val low = lines.map { it.text.lowercase() }
        val rewardsOpen = low.any { it.contains("free coins") }
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
        val playGames = low.any { it.contains("play games") || it.contains("sync your progress") }
        val cancel = lines.firstOrNull { norm(it.text) == "cancel" }?.rect
        if (playGames && cancel != null) hits.add(Hit("cancel", cancel))
        val dailyPage = low.any { it.contains("claim a new reward") } ||
            (low.any { it.contains("daily reward") } &&
                lines.any { norm(it.text) == "rewards" && it.rect.top < shot.height * 0.12 })
        val chestTap = low.any { it.contains("tap chest") }
        val youGot = low.any { it.contains("you got") }
        val resultScreen = low.any { t -> resultMarkers.any { t.contains(it) } }
        val navCount = lines.count { navWords.contains(norm(it.text)) }
        val backBtn = if (youGot || dailyPage) Detector.findBackButton(shot) else null
        if (backBtn != null) hits.add(Hit("back", backBtn))
        return Analysis(
            lines, hits, rewardsOpen, active, locked, x, closer, cn, playGames, cancel, dailyPage,
            chestTap, youGot, resultScreen, navCount, backBtn, shot.width, shot.height
        )
    }

    private fun saveAnnotated(shot: Bitmap, lines: List<OcrLine>, hits: List<Hit>) =
        saveAnnotatedTo("last_scan.jpg", shot, lines, hits)

    private fun saveAnnotatedTo(name: String, shot: Bitmap, lines: List<OcrLine>, hits: List<Hit>) {
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
            FileOutputStream(File(ctx.filesDir, name)).use { b.compress(Bitmap.CompressFormat.JPEG, 70, it) }
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
        Logger.log(
            "FreeCoins: ${an.rewardsOpen} | Daily: ${an.dailyPage} | PlayGames: ${an.playGames} | " +
                "Chest: ${an.chestTap} | YouGot: ${an.youGot} | Result: ${an.resultScreen} | Nav: ${an.navCount} | " +
                "ActiveCollect: ${an.active.size} | Locked: ${an.lockedCount}"
        )
        val gb = pickDialogButton(an)
        if (gb != null) Logger.log("Dialog button milta: '${gb.first.text}' (${gb.second})")
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

    // ---------------------------------------------------------------- dialog buttons

    /**
     * OK / Claim / Yes / No type buttons chunta hai.
     * Safety: kharch (gems/buy/spend...) wale dialog me kabhi positive nahi dabata.
     */
    private fun pickDialogButton(an: Analysis): Pair<OcrLine, String>? {
        if (an.playGames) return null
        val full = an.lines.joinToString(" ") { it.text.lowercase() }
        val cost = costWords.any { full.contains(it) }

        fun find(words: List<String>): OcrLine? {
            for (wd in words) {
                val m = an.lines.filter { norm(it.text) == wd }
                if (m.isNotEmpty()) return m.maxByOrNull { it.rect.centerY() }
            }
            return null
        }

        val yes = find(yesWords)
        val no = find(noWords)
        if (cost) {
            return if (no != null) Pair(no, "kharch wala dialog -> No/Cancel") else null
        }
        if (yes != null && no != null) {
            val rewardish = rewardWords.any { full.contains(it) }
            return if (rewardish) Pair(yes, "Yes (reward dialog)") else Pair(no, "No (safe default)")
        }
        val pos = find(positiveWords)
        if (pos != null) return Pair(pos, "positive")
        return null
    }

    // ---------------------------------------------------------------- chest

    /** Chest screen: back button aane tak chest pe tap. */
    private fun chestLoop(svc: ClaimService, w: Int, h: Int): Boolean {
        Logger.log("📦 Chest screen: back button aane tak tap")
        for (i in 1..45) {
            if (stop) return false
            val jx = if (i % 2 == 0) 0.47f else 0.53f
            svc.tap(w * jx, h * 0.68f)
            StatusOverlay.update("📦 Chest tap $i")
            if (!sleepStop(900)) return false
            if (i % 3 == 0) {
                val shot = svc.screenshot() ?: continue
                val lines = Detector.ocr(shot)
                val got = lines.any { it.text.lowercase().contains("you got") }
                val stillChest = lines.any { it.text.lowercase().contains("tap chest") }
                val back = Detector.findBackButton(shot)
                shot.recycle()
                if (got || (back != null && !stillChest)) {
                    Logger.log("✅ Chest khul gaya (tap $i), back button aa gaya")
                    return true
                }
            }
        }
        Logger.log("⚠️ Chest: 45 tap ke baad bhi back button nahi aaya")
        return false
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

    private fun isCountdown(t: String): Boolean {
        val s = norm(t)
        return Regex("\\d{1,2}").matches(s) || Regex("\\d{1,2}\\s?s").matches(s) ||
            s.contains("reward in") || s.contains("skip in")
    }

    /**
     * Ek close attempt: native node -> corner OCR (X/Skip/Close) -> corner shape X ->
     * (3rd cycle ke baad, countdown na ho toh) system Back.
     */
    private fun closeCycle(svc: ClaimService, shot: Bitmap, lines: List<OcrLine>, cycles: Int, elapsed: Long): Boolean {
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
        val xs = Detector.findCornerX(shot)
        if (xs.isNotEmpty()) {
            val r = xs.minByOrNull { it.centerY() }!!
            svc.tap(r.exactCenterX(), r.exactCenterY())
            Logger.log("❎ Ad close (shape X) @ ${r.centerX()},${r.centerY()}")
            return true
        }
        val countdown = lines.any { inCorner(it.rect, w, h) && isCountdown(it.text) }
        if (countdown && elapsed < 60_000) {
            Logger.log("⏳ Countdown dikh raha hai, Back/CTA nahi dabaunga")
            return false
        }
        if (cycles >= 2) saveAnnotatedTo("stuck_last.jpg", shot, lines, emptyList())
        // Playable ads: X tab aata hai jab "Google Play >>" dabate hain
        if (tapPlayCta && cycles >= 2 && ctaTaps < 2) {
            val cta = lines.firstOrNull { norm(it.text).contains("google play") && it.rect.centerY() < h * 0.2 }
            if (cta != null) {
                ctaTaps++
                svc.tap(cta.rect.exactCenterX(), cta.rect.exactCenterY())
                Logger.log("🎯 Playable ad: 'Google Play >>' dabaya ($ctaTaps/2), X uske baad aata hai")
                return true
            }
        }
        if (cycles >= 3 && adBacks < 2) {
            adBacks++
            svc.back()
            Logger.log("⬅️ Ad: system Back $adBacks/2 (cycle $cycles)")
            return true
        }
        return false
    }

    /**
     * Collect/Claim dabane ke baad call hota hai. Ad chalne se lekar game screen wapas aane tak watch karta hai.
     * Har 10 sec me ek close cycle; max 120 sec. Result screen (Congratulations / chest) dikhe toh bhi DONE.
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
        adBacks = 0
        ctaTaps = 0
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
                nextCycle = minOf(nextCycle, System.currentTimeMillis() + 3500)
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
                    Logger.log("✅ Ad Activity band, game wapas (screen text nahi pehchana)")
                    return AdResult.DONE
                }
            }

            val shot = svc.screenshot()
            if (shot != null) {
                val lines = Detector.ocr(shot)
                val an = analyze(shot, lines)
                if (an.resultScreen) {
                    shot.recycle()
                    Logger.log("✅ Reward/result screen aayi, ad khatam")
                    return AdResult.DONE
                }
                val gameBack = isGameScreen(an)
                if (!gameBack) adSeen = true
                if (adSeen && gameBack) {
                    shot.recycle()
                    Logger.log("✅ Ad khatam, game screen wapas")
                    sleepStop(1500)
                    return AdResult.DONE
                }
                if (!adSeen && elapsed > 12000) {
                    shot.recycle()
                    Logger.log("Ad shuru nahi hua (screen wahi hai)")
                    return AdResult.NOAD
                }
                if (now >= nextCycle) {
                    cycles++
                    nextCycle = now + CYCLE_MS
                    val did = closeCycle(svc, shot, lines, cycles, elapsed)
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
        var pgTaps = 0
        var chestRuns = 0
        val genericCount = HashMap<String, Int>()

        for (i in 1..80) {
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
            if (!an.playGames) pgTaps = 0

            if (an.playGames && pgTaps < 5) {
                pgTaps++
                if (pgTaps % 2 == 1) {
                    // Logs me system Back kaam kiya, Cancel-tap nahi: isliye pehle Back
                    svc.back()
                    Logger.log("⬅️ Play Games popup: system Back (try $pgTaps)")
                } else {
                    var rect: Rect? = an.cancelBtn
                    val s2 = svc.screenshot()
                    if (s2 != null) {
                        val c2 = Detector.ocr(s2).firstOrNull { norm(it.text) == "cancel" }?.rect
                        if (c2 != null) rect = c2
                        s2.recycle()
                    }
                    val cx = rect?.exactCenterX() ?: (an.w * 0.16f)
                    val cy = rect?.exactCenterY() ?: (an.h * 0.94f)
                    svc.tap(cx, cy)
                    Logger.log("❎ Play Games popup: Cancel dabaya (try $pgTaps)")
                }
                acted = true
            } else if (an.playGames) {
                Logger.log("⚠️ Play Games popup 5 try me nahi gaya, chhod rahe hain (Continue nahi dabaunga)")
            } else if (handleExtras && an.chestTap && chestRuns < 4) {
                chestRuns++
                chestLoop(svc, an.w, an.h)
                acted = true
            } else if (handleExtras && an.youGot) {
                val bb = an.backBtn
                val bx = bb?.exactCenterX() ?: (an.w * 0.075f)
                val by = bb?.exactCenterY() ?: (an.h * 0.965f)
                svc.tap(bx, by)
                Logger.log("⬅️ 'You Got' screen: back dabaya (paid 'Open one more chest' ko nahi chhua)")
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
                        val bb = an.backBtn
                        svc.tap(bb?.exactCenterX() ?: (an.w * 0.09f), bb?.exactCenterY() ?: (an.h * 0.955f))
                        Logger.log("⬅️ Daily Reward page: back arrow dabaya")
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
                // generic dialog buttons: OK / Claim / Yes / No ...
                var gb = if (handleExtras) pickDialogButton(an) else null
                if (gb != null) {
                    val key = norm(gb.first.text)
                    val n = (genericCount[key] ?: 0) + 1
                    genericCount[key] = n
                    if (n > 3) {
                        Logger.log("⚠️ '${gb.first.text}' 3 baar dabaya, ab skip")
                        gb = null
                    }
                }
                if (gb != null) {
                    val r = gb.first.rect
                    svc.tap(r.exactCenterX(), r.exactCenterY())
                    Logger.log("🔘 '${gb.first.text}' dabaya (${gb.second})")
                    acted = true
                    val word = norm(gb.first.text)
                    val full = an.lines.joinToString(" ") { it.text.lowercase() }
                    if (word == "claim" || (word == "yes" && full.contains("watch"))) {
                        val res = handleAd(svc)
                        if (res == AdResult.STOPPED) {
                            Logger.log("Roka gaya")
                            return
                        }
                        Logger.log("Claim ad result: $res")
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
