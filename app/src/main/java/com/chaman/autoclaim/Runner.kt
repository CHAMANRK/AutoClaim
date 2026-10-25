package com.chaman.autoclaim

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.random.Random

class Runner(private val ctx: Context) {

    @Volatile var stop = false

    // rewards toggles (MainActivity se set hote hain)
    @Volatile var tFree = true
    @Volatile var tChest = true
    @Volatile var tGifts = true
    @Volatile var tCalendar = true
    @Volatile var tLucky = true
    @Volatile var tLuckyGems = true
    @Volatile var tEvents = true
    @Volatile var tCloseGame = true
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
    // ad screens ke text me aate hain (game ke lobby/popup me nahi)
    private val adIndicators = listOf("install", "download", "get it on", "google play", "learn more", "sponsored", "app store")

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

    // Lucky Box: 4 gift boxes ki jagah (screen ka fraction). 4th = bottom-right (index 3) kabhi nahi.
    private val boxCenters = listOf(0.30f to 0.415f, 0.70f to 0.415f, 0.30f to 0.576f, 0.70f to 0.576f)

    private enum class AdResult { DONE, NOAD, TIMEOUT, STOPPED }

    companion object {
        private const val AD_TIMEOUT_MS = 120_000L
        private const val CYCLE_MS = 10_000L
        private const val MAX_CLAIMS = 6
        private const val P_CLEAN = 0
        private const val P_FREE = 1
        private const val P_CHEST = 2
        private const val P_GIFTS = 3
        private const val P_EVENTS = 4
        private const val P_FINISH = 5
        private const val MAX_LUCKY_ADS = 10
        private const val LUCKY_MAX_MS = 15 * 60 * 1000L
        private const val MAX_GEM_PRICE = 30
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
        val adLike: Boolean,
        val backAny: Rect?,
        val cornerX: Rect?,
        val lobbyText: Boolean,
        val luckyIntro: Boolean,
        val golden: Boolean,
        val luckyBoard: Boolean,
        val bomb: Boolean,
        val areYouSure: Boolean,
        val leaveBtn: Rect?,
        val eventsList: Boolean,
        val friendsScreen: Boolean,
        val acceptAll: Rect?,
        val noGifts: Boolean,
        val requestGifts: Boolean,
        val giftBadge: Rect?,
        val readyOpen: Rect?,
        val alertBadges: List<Rect>,
        val w: Int,
        val h: Int
    )

    private class Snap(val shot: Bitmap, val lines: List<OcrLine>, val an: Analysis) {
        fun recycle() {
            try {
                shot.recycle()
            } catch (e: Throwable) {
            }
        }
    }

    private class Lucky {
        var done = false
        var start = 0L
        var paid = false
        var adContinues = 0
        var adFails = 0
        var giveUp = false
        var lastPattern = ""
        var waits = 0
        var leaveTaps = 0
        var introTaps = 0
        var gemsSpent = 0
    }

    private class Ev {
        val visited = HashSet<String>()
        var scrolls = 0
        var lastSig = ""
        var pageBacks = 0
        var generic = 0
        var navTaps = 0
        var calendarDone = false
    }

    private class Cand(val key: String, val x: Float, val y: Float)

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

    private fun isGameScreen(an: Analysis) =
        an.rewardsOpen || an.dailyPage || an.resultScreen || an.lobbyText || an.navCount >= 3 ||
            an.luckyBoard || an.luckyIntro || an.bomb || an.eventsList || an.acceptAll != null

    fun analyze(shot: Bitmap, lines: List<OcrLine>, deep: Boolean = false): Analysis {
        val w = shot.width
        val h = shot.height
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

        // Events list (header "Events" upar-left me + "Play Now!" ribbons)
        val evHeader = lines.any { norm(it.text) == "events" && it.rect.centerY() < h * 0.12 && it.rect.centerX() < w * 0.35 }
        val eventsList = evHeader && low.any { it.contains("play now") }

        // NOTE: Events list ke Login Calendar card me bhi "Claim a new reward every day!" likha hota hai
        val dailyPage = !eventsList && (
            low.any { it.contains("claim a new reward") } ||
                (low.any { it.contains("daily reward") } &&
                    lines.any { norm(it.text) == "rewards" && it.rect.top < h * 0.12 })
            )
        val chestTap = low.any { it.contains("tap chest") }
        val youGot = low.any { it.contains("you got") }
        val resultScreen = low.any { t -> resultMarkers.any { t.contains(it) } }

        // bug fix (v0.5.2): lines nahi, alag-alag nav words, sirf neeche ke 15% me
        val navSeen = HashSet<String>()
        for (l in lines) {
            val n = norm(l.text)
            if (navWords.contains(n) && l.rect.centerY() > h * 0.85) navSeen.add(n)
        }
        val navCount = navSeen.size
        val adLike = low.any { t -> adIndicators.any { t.contains(it) } }
        val lobbyText = low.any { it.contains("lucky shot") || it.contains("leaderboards") } ||
            (!rewardsOpen && low.any { it.contains("free rewards") })

        // Friends / Gifts
        val friendsScreen = lines.any { norm(it.text) == "friends" && it.rect.centerY() < h * 0.12 && it.rect.centerX() < w * 0.35 }
        val acceptAll = lines.firstOrNull { norm(it.text) == "accept all" }?.rect
        val noGifts = low.any { it.contains("no gifts to collect") }
        val requestGifts = low.any { it.contains("request gifts") } && low.any { it.contains("select all") }

        // Lucky Box
        val luckyTitle = low.any { it.contains("lucky box") || it.contains("pick a box") }
        val playNow = lines.any { norm(it.text) == "play now" }
        val golden = low.any { it.contains("golden lucky") || it.contains("bigger prizes") || it.contains("1st three levels") } ||
            (lines.any { it.text.contains("₹") } && low.any { it.contains("hidden in") })
        val bomb = low.any { it.contains("hit a bomb") || it.contains("lose your rewards") || it.contains("next box comes") }
        val areYouSure = low.any { it.contains("are you sure") } && lines.any { norm(it.text) == "leave" }
        val luckyIntro = playNow && !eventsList && !golden && !bomb &&
            (luckyTitle || low.any { it.contains("guaranteed prizes every") || it.contains("super jackpot levels") || it.contains("hidden in") })
        val luckyBoard = !playNow && !golden && !bomb && !areYouSure && !eventsList &&
            low.any { it.contains("next jackpot") || it.contains("next super jackpot") || it.contains("jackpot is a guaranteed") }
        val leaveBtn = lines.firstOrNull { norm(it.text) == "leave with rewards" }?.rect

        // Lobby: gift box ka number badge aur free chest "Ready / Open"
        val lobbyScreen = navCount >= 3
        val giftBadge: Rect? = if (lobbyScreen) {
            lines.firstOrNull {
                Regex("\\d{1,3}").matches(norm(it.text)) && it.rect.centerX() < w * 0.25 &&
                    it.rect.centerY() > h * 0.18 && it.rect.centerY() < h * 0.30
            }?.rect
        } else null
        var readyOpenTmp: Rect? = null
        if (lobbyScreen) {
            val ol = lines.firstOrNull { norm(it.text) == "open" && it.rect.centerY() > h * 0.80 && it.rect.centerY() < h * 0.93 }
            if (ol != null) {
                val ready = lines.any {
                    norm(it.text) == "ready" && it.rect.centerY() < ol.rect.centerY() && it.rect.centerY() > h * 0.70 &&
                        abs(it.rect.centerX() - ol.rect.centerX()) < w * 0.15
                }
                val paid = lines.any {
                    Regex("\\d{1,4}").matches(norm(it.text)) && abs(it.rect.centerY() - ol.rect.centerY()) < h * 0.03 &&
                        it.rect.centerX() > ol.rect.centerX() && it.rect.centerX() - ol.rect.centerX() < w * 0.2
                }
                if (ready && !paid) readyOpenTmp = ol.rect
            }
        }
        val readyOpen: Rect? = readyOpenTmp

        val needBack = youGot || dailyPage || luckyIntro || golden
        val backBtn = if (needBack) Detector.findBackButton(shot) else null
        if (backBtn != null) hits.add(Hit("back", backBtn))
        // deep = global back finder (cleanup / events phase, jab lobby nahi dikh raha)
        val backAny = if (deep && navCount < 3) Detector.findBackButton(shot) else backBtn
        val cornerX = if (deep && navCount < 3) Detector.findCornerX(shot).minByOrNull { it.centerY() } else null
        if (deep && backAny != null && backBtn == null) hits.add(Hit("back*", backAny))
        if (cornerX != null) hits.add(Hit("corner-X", cornerX))
        val alertBadges: List<Rect> = if (eventsList) Detector.findAlertBadges(shot) else emptyList()
        for (b in alertBadges) hits.add(Hit("alert!", b))
        if (readyOpen != null) hits.add(Hit("ready-open", readyOpen))
        if (giftBadge != null) hits.add(Hit("gift-badge", giftBadge))
        if (acceptAll != null) hits.add(Hit("accept-all", acceptAll))
        if (leaveBtn != null) hits.add(Hit("leave-rewards", leaveBtn))

        return Analysis(
            lines = lines, hits = hits, rewardsOpen = rewardsOpen, active = active, lockedCount = locked,
            closeX = x, closer = closer, collectNow = cn, playGames = playGames, cancelBtn = cancel,
            dailyPage = dailyPage, chestTap = chestTap, youGot = youGot, resultScreen = resultScreen,
            navCount = navCount, backBtn = backBtn, adLike = adLike, backAny = backAny, cornerX = cornerX,
            lobbyText = lobbyText, luckyIntro = luckyIntro, golden = golden, luckyBoard = luckyBoard,
            bomb = bomb, areYouSure = areYouSure, leaveBtn = leaveBtn, eventsList = eventsList,
            friendsScreen = friendsScreen, acceptAll = acceptAll, noGifts = noGifts, requestGifts = requestGifts,
            giftBadge = giftBadge, readyOpen = readyOpen, alertBadges = alertBadges, w = w, h = h
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
        val an = analyze(shot, lines, deep = true)
        Logger.log(
            "FreeCoins: ${an.rewardsOpen} | Daily: ${an.dailyPage} | PlayGames: ${an.playGames} | " +
                "Chest: ${an.chestTap} | YouGot: ${an.youGot} | Result: ${an.resultScreen} | Nav: ${an.navCount} | " +
                "AdLike: ${an.adLike} | LobbyText: ${an.lobbyText} | BackFinder: ${an.backAny != null} | CornerX: ${an.cornerX != null} | " +
                "ActiveCollect: ${an.active.size} | Locked: ${an.lockedCount}"
        )
        Logger.log(
            "Lucky: board=${an.luckyBoard} intro=${an.luckyIntro} bomb=${an.bomb} sure=${an.areYouSure} golden=${an.golden} | " +
                "Events: ${an.eventsList} alerts=${an.alertBadges.size} | Friends: ${an.friendsScreen} acceptAll=${an.acceptAll != null} " +
                "noGifts=${an.noGifts} | GiftBadge=${an.giftBadge != null} | ReadyOpen=${an.readyOpen != null}"
        )
        if (an.luckyBoard) {
            val cl = closedBoxes(shot)
            Logger.log("Lucky boxes closed (TL,TR,BL,BR): ${cl.joinToString(",") { if (it) "closed" else "open" }}")
        }
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
        if (an.playGames || an.bomb || an.luckyBoard || an.luckyIntro || an.areYouSure || an.golden || an.eventsList) return null
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
        var gameStreak = 0
        var lastAdLike = false
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
                if (now - classBackSince >= 10000 && !lastAdLike) {
                    Logger.log("✅ Ad Activity band, game wapas (screen text nahi pehchana)")
                    return AdResult.DONE
                }
            }

            val shot = svc.screenshot()
            if (shot != null) {
                val lines = Detector.ocr(shot)
                val an = analyze(shot, lines)
                lastAdLike = an.adLike
                // game screen tab maanenge jab ad ke text (install/download/google play...) na ho
                // aur lagatar 2 baar dikhe (ek frame ke dhoke se bachne ke liye)
                val gameBack = isGameScreen(an) && !an.adLike
                if (gameBack) gameStreak++ else gameStreak = 0
                if (!gameBack) adSeen = true
                if (gameBack && gameStreak >= 2 && (adSeen || an.resultScreen)) {
                    shot.recycle()
                    Logger.log(if (an.resultScreen) "✅ Reward/result screen aayi, ad khatam" else "✅ Ad khatam, game screen wapas")
                    if (!an.resultScreen) sleepStop(1000)
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

    /** Game khulne ka intezaar: max 12 sec. 5 sec baad har 1 sec check (lobby / koi page / popup). */
    private fun waitForGameOpen(svc: ClaimService): Boolean {
        val t0 = System.currentTimeMillis()
        Logger.log("Game khul raha hai (max 12 sec, 5 sec baad har sec check)")
        if (!sleepStop(5000)) return false
        while (System.currentTimeMillis() - t0 < 12000) {
            if (stop) return false
            val fp = svc.foregroundPackage()
            val shot = svc.screenshot()
            if (shot != null) {
                val lines = Detector.ocr(shot)
                val an = analyze(shot, lines)
                shot.recycle()
                val inGame = fp == null || fp == gamePkg || fp.startsWith("com.google.android.")
                val signal = an.navCount >= 3 || an.lobbyText || an.rewardsOpen || an.dailyPage || an.playGames ||
                    an.chestTap || an.youGot || an.resultScreen || an.closeX != null || an.closer != null || lines.size >= 8
                if (inGame && signal) {
                    val sec = (System.currentTimeMillis() - t0) / 1000
                    Logger.log("✅ Game/page khul gaya (${sec}s)")
                    return true
                }
            }
            if (!sleepStop(1000)) return false
        }
        Logger.log("⏱ 12 sec ho gaye, aage badhte hain")
        return true
    }

    // ---------------------------------------------------------------- v0.6 helpers

    private fun snap(svc: ClaimService, deep: Boolean = false): Snap? {
        val shot = svc.screenshot() ?: return null
        val lines = Detector.ocr(shot)
        val an = analyze(shot, lines, deep)
        return Snap(shot, lines, an)
    }

    private fun tapBack(svc: ClaimService, an: Analysis, fx: Float = 0.075f, fy: Float = 0.965f) {
        val b = an.backAny ?: an.backBtn
        svc.tap(b?.exactCenterX() ?: (an.w * fx), b?.exactCenterY() ?: (an.h * fy))
    }

    /** Lucky Box: 4 boxes me se kaun se abhi band (closed) hain. */
    private fun closedBoxes(shot: Bitmap): List<Boolean> {
        val w = shot.width
        val h = shot.height
        return boxCenters.map { c ->
            Detector.ribbonFraction(shot, (w * c.first).toInt(), (h * c.second).toInt(), (h * 0.065f).toInt()) >= 0.75f
        }
    }

    private fun cardKey(lines: List<OcrLine>, y0: Int, y1: Int, h: Int): String {
        val ref = (y0 + y1) / 2
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (l in lines) {
            val cy = l.rect.centerY()
            if (cy < y0 || cy > y1) continue
            val t = l.text.lowercase()
            val k: String? = when {
                t.contains("login") || t.contains("calendar") || t.contains("new reward every day") -> "login"
                t.contains("lucky box") || t.contains("pick a box") || t.contains("your luck") -> "lucky"
                t.contains("trials") -> "trials"
                t.contains("fever") -> "fever"
                t.contains("cinema") -> "cinema"
                t.contains("sticker") -> "sticker"
                else -> null
            }
            if (k != null && abs(cy - ref) < bestD) {
                best = k
                bestD = abs(cy - ref)
            }
        }
        return best ?: ("other@" + (ref * 10 / h))
    }

    /** Events list: red "!" / Ready wale cards kholta hai, warna scroll. true = kuch kiya, false = list khatam. */
    private fun eventsListStep(svc: ClaimService, sn: Snap, ev: Ev, lk: Lucky): Boolean {
        val an = sn.an
        val w = an.w
        val h = an.h
        val cands = ArrayList<Cand>()
        for (b in an.alertBadges) {
            val key = cardKey(sn.lines, b.centerY(), b.centerY() + (h * 0.2f).toInt(), h)
            cands.add(Cand(key, w * 0.45f, b.centerY() + h * 0.07f))
        }
        for (l in sn.lines) {
            if (norm(l.text) == "ready" && l.rect.centerY() > h * 0.12 && l.rect.centerY() < h * 0.9) {
                val cy = l.rect.centerY()
                val key = cardKey(sn.lines, cy - (h * 0.07f).toInt(), cy + (h * 0.09f).toInt(), h)
                cands.add(Cand(key, l.rect.exactCenterX(), l.rect.exactCenterY()))
            }
        }
        cands.sortBy { it.y }
        for (c in cands) {
            if (ev.visited.contains(c.key)) continue
            ev.visited.add(c.key)
            val allowed = when (c.key) {
                "login" -> tCalendar && !ev.calendarDone
                "lucky" -> tLucky && !lk.done
                else -> tEvents
            }
            if (!allowed) {
                Logger.log("⏭ Event skip (toggle OFF / ho chuka): ${c.key}")
                continue
            }
            svc.tap(c.x, c.y)
            Logger.log("🎪 Event khola: ${c.key}")
            return true
        }
        // koi naya alert/ready nahi: thoda scroll karke dekho
        val sig = sn.lines.joinToString("|") { norm(it.text) }.take(400)
        if (ev.scrolls >= 4 || (ev.scrolls > 0 && sig == ev.lastSig)) return false
        ev.lastSig = sig
        ev.scrolls++
        svc.swipe(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.35f, 450)
        Logger.log("⬇️ Events list scroll (${ev.scrolls}/4)")
        return true
    }

    /** Anjaan event page: free Collect/Claim/Open ho toh lo (paid skip), warna back. */
    private fun eventPageStep(svc: ClaimService, sn: Snap, ev: Ev): Boolean {
        val an = sn.an
        val full = sn.lines.joinToString(" ") { it.text.lowercase() }
        val paidHint = costWords.any { full.contains(it) }
        if (!paidHint && ev.generic < 4) {
            for (word in listOf("collect", "claim", "open")) {
                val l = sn.lines.firstOrNull { norm(it.text) == word && Detector.greenFraction(sn.shot, it.rect) >= 0.2f } ?: continue
                val priceNear = sn.lines.any {
                    Regex("\\d{1,4}").matches(norm(it.text)) && abs(it.rect.centerY() - l.rect.centerY()) < an.h * 0.03 &&
                        it.rect.centerX() > l.rect.centerX() && it.rect.centerX() - l.rect.centerX() < an.w * 0.2
                }
                if (priceNear) continue
                ev.generic++
                svc.tap(l.rect.exactCenterX(), l.rect.exactCenterY())
                Logger.log("🎪 Event page: '$word' dabaya")
                if (word != "open") {
                    val res = handleAd(svc)
                    Logger.log("Event collect ad result: $res")
                }
                return true
            }
        }
        ev.pageBacks++
        if (ev.pageBacks > 6) return false
        if (ev.pageBacks > 3) {
            svc.back()
            Logger.log("⬅️ Event page: system Back")
        } else {
            tapBack(svc, an)
            Logger.log("⬅️ Event page: back arrow")
        }
        return true
    }

    /**
     * "You Hit a Bomb" popup: pehle ad wala "Play on!", ad na chale toh gems wala (max 30, ek run me 1 baar).
     */
    private fun handleBomb(svc: ClaimService, sn: Snap, lk: Lucky) {
        val an = sn.an
        val playOn = sn.lines.filter { norm(it.text).startsWith("play on") }.sortedBy { it.rect.centerX() }

        fun priceOf(l: OcrLine): Int? {
            val own = Regex("\\d{1,4}").find(l.text)?.value?.toIntOrNull()
            if (own != null) return own
            val near = sn.lines.firstOrNull { x ->
                Regex("\\d{1,4}").matches(norm(x.text)) &&
                    abs(x.rect.centerY() - l.rect.centerY()) < an.h * 0.03 &&
                    x.rect.centerX() > l.rect.centerX() && x.rect.centerX() - l.rect.centerX() < an.w * 0.3
            }
            return near?.let { norm(it.text).toIntOrNull() }
        }

        val adBtn: OcrLine?
        val gemBtn: OcrLine?
        if (playOn.size >= 2) {
            adBtn = playOn.first()
            gemBtn = playOn.last()
        } else if (playOn.size == 1) {
            if (priceOf(playOn[0]) != null) {
                adBtn = null
                gemBtn = playOn[0]
            } else {
                adBtn = playOn[0]
                gemBtn = null
            }
        } else {
            adBtn = null
            gemBtn = null
        }

        val timeUp = System.currentTimeMillis() - lk.start > LUCKY_MAX_MS
        if (adBtn != null && lk.adFails < 2 && lk.adContinues < MAX_LUCKY_ADS && !timeUp) {
            Logger.log("💣 Bomb: ad se Play on")
            svc.tap(adBtn.rect.exactCenterX(), adBtn.rect.exactCenterY())
            val res = handleAd(svc)
            if (res == AdResult.DONE) {
                lk.adContinues++
                Logger.log("✅ Ad continue ho gaya (${lk.adContinues})")
            } else if (res != AdResult.STOPPED) {
                lk.adFails++
                Logger.log("⚠️ Bomb ad se continue nahi hua ($res, fail ${lk.adFails})")
            }
            return
        }
        if (gemBtn != null && tLuckyGems && !lk.paid) {
            val price = priceOf(gemBtn)
            if (price != null && price in 1..MAX_GEM_PRICE) {
                Logger.log("💎 Bomb: gems se Play on ($price gems, sirf 1 baar)")
                svc.tap(gemBtn.rect.exactCenterX(), gemBtn.rect.exactCenterY())
                lk.paid = true
                lk.gemsSpent = price
                return
            } else {
                Logger.log("⚠️ Gems price padh nahi paya ya limit se zyada ($price), nahi dunga")
            }
        }
        Logger.log("💣 Bomb: continue ka tareeka nahi bacha, Back dabate hain")
        lk.giveUp = true
        svc.back()
    }

    /** Run ke end me: game Force stop (Settings App Info se) aur AutoClaim app khol dena. */
    private fun finishRun(svc: ClaimService) {
        Logger.log("🏁 Rewards khatam. 5 sec ruk ke game band karte hain")
        if (!sleepStop(5000)) return
        if (tCloseGame) {
            try {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$gamePkg"))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                svc.startActivity(i)
                if (!sleepStop(2500)) return
                var stopped = false
                for (n in 1..3) {
                    val c1 = svc.clickByLabels(listOf("force stop", "force-stop", "forcestop"), true)
                    if (c1 != null) {
                        if (!sleepStop(1500)) return
                        val c2 = svc.clickByLabels(listOf("ok", "force stop", "yes"), false)
                        Logger.log("🛑 Force stop: '$c1' -> '${c2 ?: "-"}'")
                        stopped = true
                        sleepStop(1500)
                        break
                    }
                    if (!sleepStop(1500)) return
                }
                if (!stopped) Logger.log("⚠️ Force stop button nahi mila (App Info page), game background me rahega")
            } catch (t: Throwable) {
                Logger.log("Force stop error: ${t.message}")
            }
        }
        try {
            val i = Intent(svc, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            svc.startActivity(i)
            Logger.log("✅ AutoClaim app khola")
        } catch (t: Throwable) {
            Logger.log("AutoClaim kholne me error: ${t.message}")
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
        if (!waitForGameOpen(svc)) {
            Logger.log("Roka gaya")
            return
        }

        var idle = 0
        var scanned = false
        var dailyLogged = false
        var dailyTaps = 0
        var calendarTries = 0
        var claimsDone = 0
        var claimFails = 0
        var lastTexts = ""
        var pgTaps = 0
        var chestRuns = 0
        var lobbyWaits = 0
        var unexpectedAds = 0
        var lobbyConfirm = 0
        var unknownCount = 0
        var globalTaps = 0
        var sysBacks = 0
        var chestOpens = 0
        var giftTaps = 0
        var giftAccepts = 0
        var inboxTaps = 0
        val genericCount = HashMap<String, Int>()
        val ev = Ev()
        val lk = Lucky()
        var phase = P_CLEAN
        var phaseStart = System.currentTimeMillis()
        val phase0Start = phaseStart

        fun phaseName(p: Int): String = when (p) {
            P_CLEAN -> "cleanup"
            P_FREE -> "Free Rewards"
            P_CHEST -> "Lobby chest"
            P_GIFTS -> "Gifts"
            P_EVENTS -> "Events"
            else -> "finish"
        }

        fun enabled(p: Int): Boolean = when (p) {
            P_FREE -> tFree
            P_CHEST -> tChest
            P_GIFTS -> tGifts
            P_EVENTS -> tCalendar || tLucky || tEvents
            else -> true
        }

        fun goNext(reason: String) {
            var p = phase + 1
            while (p < P_FINISH && !enabled(p)) p++
            Logger.log("➡️ $reason -> ${phaseName(p)}")
            phase = p
            phaseStart = System.currentTimeMillis()
            idle = 0
            lobbyWaits = 0
            lobbyConfirm = 0
        }

        Logger.log("🧹 Cleanup phase: popups/pages band karke lobby 3 baar confirm karenge")

        for (i in 1..400) {
            if (stop) {
                Logger.log("Roka gaya")
                return
            }
            if (phase >= P_FINISH) break
            val limit = if (phase == P_EVENTS) 420_000L else 150_000L
            if (phase > P_CLEAN && System.currentTimeMillis() - phaseStart > limit) {
                goNext("${phaseName(phase)} time limit")
                continue
            }

            val nat = svc.closeNativeDialog(gamePkg)
            if (nat != null) {
                Logger.log("Native popup band kiya: $nat")
                idle = 0
                if (!sleepStop(1500)) return
                continue
            }

            val sn = snap(svc, phase == P_CLEAN || phase == P_EVENTS)
            if (sn == null) {
                if (!sleepStop(2000)) return
                continue
            }
            try {
                val an = sn.an
                val lines = sn.lines
                lastTexts = lines.take(12).joinToString(" | ") { it.text }
                saveAnnotated(sn.shot, lines, an.hits)
                val lobbyNow = !an.adLike && (an.navCount >= 3 || an.lobbyText || an.collectNow != null)
                if (!an.dailyPage) dailyTaps = 0
                if (!an.playGames) pgTaps = 0
                if (phase == P_CLEAN && an.rewardsOpen && tFree) {
                    phase = P_FREE
                    phaseStart = System.currentTimeMillis()
                    Logger.log("🎁 Free Coins popup pehle se khula hai, seedha claim tab")
                }
                val phaseAtStart = phase
                var acted = false

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
                } else if (an.adLike && !isGameScreen(an) && unexpectedAds < 3) {
                    unexpectedAds++
                    Logger.log("📺 Ad screen mili (bina Collect ke), close karte hain")
                    val res = handleAd(svc)
                    if (res == AdResult.STOPPED) {
                        Logger.log("Roka gaya")
                        return
                    }
                    Logger.log("Ad result: $res")
                    acted = true
                } else if (handleExtras && an.chestTap && chestRuns < 12) {
                    chestRuns++
                    chestLoop(svc, an.w, an.h)
                    acted = true
                } else if (handleExtras && an.youGot) {
                    tapBack(svc, an)
                    Logger.log("⬅️ 'You Got' screen: back dabaya (paid 'Open one more chest' ko nahi chhua)")
                    acted = true
                } else if (an.golden) {
                    tapBack(svc, an)
                    Logger.log("⬅️ Golden Lucky Box (paid) page: sirf back")
                    acted = true
                } else if (phase == P_EVENTS && an.bomb) {
                    if (lk.start == 0L) lk.start = System.currentTimeMillis()
                    handleBomb(svc, sn, lk)
                    if (stop) {
                        Logger.log("Roka gaya")
                        return
                    }
                    acted = true
                } else if (phase == P_EVENTS && an.areYouSure) {
                    val lv = lines.firstOrNull { norm(it.text) == "leave" }
                    svc.tap(lv?.rect?.exactCenterX() ?: (an.w * 0.333f), lv?.rect?.exactCenterY() ?: (an.h * 0.662f))
                    lk.done = true
                    Logger.log("🚪 Lucky Box: 'Are you sure?' -> Leave")
                    acted = true
                } else if (phase == P_EVENTS && an.luckyBoard) {
                    if (lk.start == 0L) {
                        lk.start = System.currentTimeMillis()
                        Logger.log("🎁 Lucky Box board mila")
                    }
                    val closed = closedBoxes(sn.shot)
                    val closedCount = closed.count { it }
                    val leave = an.leaveBtn
                    val leaveActive = leave != null && Detector.greenFraction(sn.shot, leave) >= 0.2f
                    val pattern = closed.joinToString("") { if (it) "1" else "0" } + (if (leaveActive) "L" else "x")
                    val timeUp = System.currentTimeMillis() - lk.start > LUCKY_MAX_MS
                    if (pattern != lk.lastPattern && lk.waits < 6) {
                        // animation settle hone do: same pattern 2 baar dikhe tab hi action
                        lk.lastPattern = pattern
                        lk.waits++
                        acted = true
                    } else {
                        lk.waits = 0
                        lk.lastPattern = ""
                        val wantLeave = lk.giveUp || timeUp || (closedCount == 4 && (lk.paid || lk.adContinues >= MAX_LUCKY_ADS))
                        val eligible = (0..2).filter { closed[it] }
                        if (wantLeave || eligible.isEmpty()) {
                            if (leave != null && leaveActive && lk.leaveTaps < 5) {
                                lk.leaveTaps++
                                svc.tap(leave.exactCenterX(), leave.exactCenterY())
                                Logger.log("🚪 Lucky Box: Leave With Rewards dabaya")
                            } else {
                                Logger.log("⏳ Lucky Box: Leave button abhi active nahi")
                            }
                            acted = true
                        } else {
                            val idx = eligible[Random.nextInt(eligible.size)]
                            val bx = an.w * boxCenters[idx].first
                            val by = an.h * boxCenters[idx].second
                            svc.tap(bx, by)
                            Logger.log("🎲 Lucky Box: box ${idx + 1} dabaya (band boxes: $closedCount)")
                            acted = true
                        }
                    }
                } else if (phase == P_EVENTS && an.luckyIntro) {
                    val pn = lines.firstOrNull { norm(it.text) == "play now" }
                    if (tLucky && !lk.done && pn != null && lk.introTaps < 3) {
                        lk.introTaps++
                        svc.tap(pn.rect.exactCenterX(), pn.rect.exactCenterY())
                        Logger.log("🎁 Lucky Box: Play Now")
                    } else {
                        tapBack(svc, an)
                        Logger.log("⬅️ Lucky Box intro: back")
                    }
                    acted = true
                } else if (an.dailyPage) {
                    if (!dailyLogged) {
                        dailyLogged = true
                        Logger.log("📅 Daily Reward page mila — active Collect: ${an.active.size}")
                    }
                    if (tCalendar && !ev.calendarDone && an.active.isNotEmpty() && calendarTries < 2) {
                        calendarTries++
                        val r = an.active.minByOrNull { it.top }!!
                        Logger.log("👆 Daily Collect dabaya")
                        svc.tap(r.exactCenterX(), r.exactCenterY())
                        val res = handleAd(svc)
                        if (res == AdResult.STOPPED) {
                            Logger.log("Roka gaya")
                            return
                        }
                        if (res == AdResult.DONE) {
                            ev.calendarDone = true
                            Logger.log("🎉 Daily reward claim ho gaya")
                        } else {
                            Logger.log("⚠️ Daily claim fail ($res)")
                        }
                        acted = true
                    } else {
                        dailyTaps++
                        if (dailyTaps > 2) {
                            svc.back()
                            Logger.log("⬅️ Daily Reward page: system Back")
                        } else {
                            tapBack(svc, an, 0.09f, 0.955f)
                            Logger.log("⬅️ Daily Reward page: back arrow dabaya")
                        }
                        acted = true
                    }
                } else if (an.rewardsOpen) {
                    if (!scanned) {
                        scanned = true
                        Logger.log("🎁 Free Coins popup mila — active Collect: ${an.active.size}, locked: ${an.lockedCount}")
                    }
                    if (phase == P_FREE && tFree && claimsDone < MAX_CLAIMS && claimFails < 2 && an.active.isNotEmpty()) {
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
                        if (n > 6) {
                            Logger.log("⚠️ '${gb.first.text}' bahut baar dabaya, ab skip")
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
                        } else if (phase == P_FREE && tFree && !scanned && cn != null) {
                            svc.tap(cn.exactCenterX(), cn.exactCenterY())
                            Logger.log("Free Rewards khola")
                            acted = true
                        } else if (phase == P_FREE) {
                            if (lobbyNow && (scanned || an.collectNow == null)) goNext("Free Rewards done")
                        } else if (phase == P_CLEAN && !lobbyNow) {
                            // GLOBAL BACK FINDER: koi anjaan page ho toh back/close dhundho
                            val b = an.backAny
                            val cxr = an.cornerX
                            if (b != null && globalTaps < 8) {
                                globalTaps++
                                svc.tap(b.exactCenterX(), b.exactCenterY())
                                Logger.log("⬅️ Back finder: back button dabaya ($globalTaps)")
                                acted = true
                            } else if (cxr != null && unknownCount >= 2 && globalTaps < 8) {
                                globalTaps++
                                svc.tap(cxr.exactCenterX(), cxr.exactCenterY())
                                Logger.log("❎ Back finder: corner X dabaya ($globalTaps)")
                                acted = true
                            } else {
                                unknownCount++
                                if (unknownCount == 1) Logger.log("❓ Anjaan page: $lastTexts")
                                if (unknownCount >= 3 && sysBacks < 3 && lines.size >= 5) {
                                    sysBacks++
                                    svc.back()
                                    Logger.log("⬅️ Back finder: system Back $sysBacks/3")
                                    acted = true
                                }
                            }
                        } else if (phase == P_CHEST) {
                            val ro = an.readyOpen
                            if (lobbyNow && ro != null && chestOpens < 4) {
                                chestOpens++
                                svc.tap(ro.exactCenterX(), ro.exactCenterY())
                                Logger.log("📦 Lobby chest: Open dabaya ($chestOpens)")
                                acted = true
                            } else if (lobbyNow) {
                                goNext("chest done")
                            }
                        } else if (phase == P_GIFTS) {
                            val inbox = lines.firstOrNull { norm(it.text) == "inbox" }
                            val aa = an.acceptAll
                            val gbd = an.giftBadge
                            if (an.requestGifts) {
                                svc.tap(an.w * 0.945f, an.h * 0.207f)
                                Logger.log("❎ Request Gifts popup: X (fixed position)")
                                acted = true
                            } else if (aa != null && giftAccepts < 3) {
                                giftAccepts++
                                svc.tap(aa.exactCenterX(), aa.exactCenterY())
                                Logger.log("🎁 Gifts: Accept All dabaya ($giftAccepts)")
                                acted = true
                            } else if (an.noGifts) {
                                goNext("gifts done")
                            } else if (an.friendsScreen) {
                                if (inbox != null && inboxTaps < 2) {
                                    inboxTaps++
                                    svc.tap(inbox.rect.exactCenterX(), inbox.rect.exactCenterY())
                                    Logger.log("📬 Friends: Inbox tab")
                                    acted = true
                                } else {
                                    goNext("gifts: inbox me kuch nahi mila")
                                }
                            } else if (lobbyNow) {
                                if (gbd != null && giftTaps < 3) {
                                    giftTaps++
                                    svc.tap(gbd.exactCenterX() - an.w * 0.03f, gbd.exactCenterY() + an.h * 0.02f)
                                    Logger.log("🎁 Gifts: gift box dabaya (number dikha) ($giftTaps)")
                                    acted = true
                                } else {
                                    goNext("gifts: number nahi dikha")
                                }
                            }
                        } else if (phase == P_EVENTS) {
                            if (an.eventsList) {
                                ev.pageBacks = 0
                                ev.navTaps = 0
                                val did = eventsListStep(svc, sn, ev, lk)
                                if (did) acted = true else goNext("events khatam")
                            } else if (lobbyNow) {
                                if (ev.navTaps < 6) {
                                    ev.navTaps++
                                    val nv = lines.firstOrNull { norm(it.text) == "events" && it.rect.centerY() > an.h * 0.88 }
                                    svc.tap(nv?.rect?.exactCenterX() ?: (an.w * 0.72f), nv?.rect?.exactCenterY() ?: (an.h * 0.975f))
                                    Logger.log("🗂 Events tab dabaya")
                                    acted = true
                                } else {
                                    goNext("events tab nahi khula")
                                }
                            } else {
                                val did = eventPageStep(svc, sn, ev)
                                if (did) acted = true else goNext("events page se nikal nahi paya")
                            }
                        }
                    }
                }

                if (phase != phaseAtStart) continue   // goNext chal gaya

                if (phase == P_CLEAN) {
                    if (acted) {
                        lobbyConfirm = 0
                        if (!sleepStop(1500)) return
                    } else if (lobbyNow) {
                        lobbyConfirm++
                        unknownCount = 0
                        Logger.log("🏠 Lobby capture $lobbyConfirm/3")
                        if (lobbyConfirm >= 3) {
                            goNext("lobby 3/3 confirm")
                            continue
                        }
                        if (!sleepStop(1000)) return
                    } else {
                        lobbyConfirm = 0
                        if (!sleepStop(1000)) return
                    }
                    if (System.currentTimeMillis() - phase0Start > 60_000) {
                        goNext("cleanup 60 sec ho gaye, lobby confirm nahi hua")
                    }
                    continue
                }

                if (acted) {
                    idle = 0
                    if (!sleepStop(2000)) return
                } else {
                    idle++
                    if (idle >= 4) {
                        if (!lobbyNow && lobbyWaits < 6) {
                            lobbyWaits++
                            idle = 3
                            Logger.log("⏳ Lobby abhi nahi dikha, thoda aur wait ($lobbyWaits/6)")
                        } else {
                            Logger.log("Last screen text: $lastTexts")
                            goNext("idle (kuch aur nahi mila)")
                            continue
                        }
                    }
                    if (!sleepStop(2500)) return
                }
            } finally {
                sn.recycle()
            }
        }

        Logger.log(
            "📊 Summary: FreeRewards=$claimsDone | LobbyChest=$chestOpens | Gifts=$giftAccepts | " +
                "Calendar=${ev.calendarDone} | LuckyBox(done=${lk.done}, ads=${lk.adContinues}, gems=${if (lk.paid) lk.gemsSpent else 0})"
        )
        finishRun(svc)
    }
}
