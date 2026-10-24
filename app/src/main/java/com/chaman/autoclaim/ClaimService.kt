package com.chaman.autoclaim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClaimService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClaimService? = null

        private val CLOSE_LABELS = listOf("not now", "no thanks", "maybe later", "dismiss", "skip", "close")
    }

    @Volatile var lastWindowClass: String? = null
    @Volatile var lastWindowPkg: String? = null

    override fun onServiceConnected() {
        instance = this
        Logger.log("✅ Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        StatusOverlay.hide()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pk = event.packageName?.toString() ?: return
        if (pk == packageName || pk == "com.android.systemui") return
        lastWindowPkg = pk
        lastWindowClass = event.className?.toString()
    }

    override fun onInterrupt() {}

    fun resetWindowInfo() {
        lastWindowClass = null
        lastWindowPkg = null
    }

    /** Screenshot lekar ARGB bitmap deta hai (background thread se call karo). */
    fun screenshot(timeoutMs: Long = 5000): Bitmap? {
        StatusOverlay.setVisible(false)
        try {
            Thread.sleep(150)
        } catch (e: InterruptedException) {
        }
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        result = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                        bmp?.recycle()
                        hb.close()
                    } catch (t: Throwable) {
                        Logger.log("Screenshot convert error: ${t.message}")
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Logger.log("Screenshot fail, code $errorCode")
                    latch.countDown()
                }
            })
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            StatusOverlay.setVisible(true)
        }
        return result
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(g, cb, null)) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * Native (Android) dialogs jaise Play Store / Google popups ke "Not now", "No thanks" etc. band karta hai.
     * Sirf game, Play Store aur Google packages pe chalta hai.
     */
    fun closeNativeDialog(gamePkg: String): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val allowed = pkg == gamePkg || pkg == "com.android.vending" || pkg.startsWith("com.google.android.")
        if (!allowed) return null
        val labels = if (pkg == gamePkg) CLOSE_LABELS else CLOSE_LABELS + "cancel"
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (n in nodes) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
                if (t != label) continue
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return label
            }
        }
        return null
    }

    /** Ad ke native view tree me Close/Skip/X type node dhundh ke click karta hai. */
    fun clickCloseNode(labels: Set<String>): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() == packageName) return null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 800) {
            val n = q.removeFirst()
            seen++
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
            if (t != null && t in labels && n.isVisibleToUser) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return t
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty && tap(r.exactCenterX(), r.exactCenterY())) return t
            }
            for (i in 0 until n.childCount) {
                val ch = n.getChild(i)
                if (ch != null) q.add(ch)
            }
        }
        return null
    }
}
