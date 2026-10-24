package com.chaman.autoclaim

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Screen ke upar ek line ka floating status (touch pass-through, accessibility overlay). */
object StatusOverlay {
    @Volatile
    var enabled = true

    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    private fun wm(svc: ClaimService) = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun show() {
        if (!enabled) return
        main.post {
            val svc = ClaimService.instance ?: return@post
            if (view != null) {
                view?.visibility = View.VISIBLE
                return@post
            }
            val d = svc.resources.displayMetrics.density
            val tv = TextView(svc)
            tv.text = "AutoClaim"
            tv.textSize = 12f
            tv.setTextColor(Color.WHITE)
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.maxWidth = (svc.resources.displayMetrics.widthPixels * 0.92f).toInt()
            tv.setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            val bg = GradientDrawable()
            bg.setColor(0xCC000000.toInt())
            bg.cornerRadius = 14 * d
            tv.background = bg

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (30 * d).toInt()
            try {
                wm(svc).addView(tv, lp)
                view = tv
            } catch (t: Throwable) {
                android.util.Log.e("AutoClaim", "overlay add fail: ${t.message}")
            }
        }
    }

    fun update(msg: String) {
        main.post { view?.text = msg.take(90) }
    }

    /** Screenshot ke waqt overlay chhupane ke liye (warna OCR apna hi text padh lega). */
    fun setVisible(v: Boolean) {
        main.post { view?.visibility = if (v) View.VISIBLE else View.INVISIBLE }
    }

    fun hide() {
        main.post {
            val v = view
            if (v != null) {
                try {
                    ClaimService.instance?.let { wm(it).removeView(v) }
                } catch (t: Throwable) {
                    android.util.Log.e("AutoClaim", "overlay remove fail: ${t.message}")
                }
                view = null
            }
        }
    }

    fun hideLater(ms: Long) {
        main.postDelayed({ hide() }, ms)
    }
}
