package com.chaman.autoclaim

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private class P(val name: String, val ok: () -> Boolean, val act: () -> Unit)

    private val BG = 0xFF14100E.toInt()
    private val CARD = 0xFF221813.toInt()
    private val STROKE = 0xFF3A2A20.toInt()
    private val GOLD = 0xFFFFC857.toInt()
    private val SOFT = 0xFFD9C7B8.toInt()
    private val OKC = 0xFF8FE388.toInt()
    private val BADC = 0xFFFF8A80.toInt()

    private lateinit var logView: TextView
    private lateinit var svcChip: TextView
    private val permRows = ArrayList<Pair<P, TextView>>()
    private val runner by lazy { Runner(applicationContext) }
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pkgUri() = Uri.parse("package:$packageName")

    private val perms: List<P> by lazy {
        listOf(
            P("Accessibility service", { Perm.accessibilityOn(this) }, {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }),
            P("Display over other apps", { Perm.overlayOn(this) }, {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri()))
            }),
            P("Notifications", { Perm.notificationsOn(this) }, {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }),
            P("Battery: unrestricted", { Perm.batteryOn(this) }, {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri()))
            }),
            P("Exact alarm (daily schedule)", { Perm.exactAlarmOn(this) }, {
                if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri()))
            })
        )
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) g.setStroke(dp(1), stroke)
        return g
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(0, dp(top), 0, 0)
        return p
    }

    private fun card(title: String, parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(CARD, 16, STROKE)
        c.setPadding(dp(14), dp(12), dp(14), dp(14))
        parent.addView(c, lp(12))
        val t = TextView(this)
        t.text = title
        t.textSize = 12f
        t.setTextColor(GOLD)
        t.typeface = Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.1f
        t.setPadding(0, 0, 0, dp(6))
        c.addView(t)
        return c
    }

    private fun btn(text: String, primary: Boolean = false, small: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = if (small) 13f else 15f
        b.setTextColor(if (primary) 0xFF2A1710.toInt() else SOFT)
        b.background = rounded(if (primary) GOLD else 0xFF3A2A20.toInt(), 12)
        b.minHeight = dp(if (small) 36 else 48)
        b.minimumHeight = dp(if (small) 36 else 48)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toggle(text: String, key: String, def: Boolean, onChange: (Boolean) -> Unit): Switch {
        val init = prefs.getBoolean(key, def)
        onChange(init)
        val s = Switch(this)
        s.text = text
        s.setTextColor(SOFT)
        s.isChecked = init
        s.setPadding(0, dp(6), 0, dp(6))
        s.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(key, c).apply()
            onChange(c)
        }
        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(16), dp(28), dp(16), dp(28))
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        scroll.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        // ---- header with logo
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_badge)
        header.addView(logo, LinearLayout.LayoutParams(dp(68), dp(68)))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(dp(14), 0, 0, 0)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        val t1 = TextView(this)
        t1.text = "Chaman AutoClaim"
        t1.textSize = 22f
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.setTextColor(GOLD)
        val t2 = TextView(this)
        t2.text = "Carrom Pool daily rewards  •  v$ver"
        t2.textSize = 12f
        t2.setTextColor(SOFT)
        val t3 = TextView(this)
        t3.text = "by Chaman Bhai Production"
        t3.textSize = 11f
        t3.setTextColor(0xFF9C8A7C.toInt())
        titles.addView(t1)
        titles.addView(t2)
        titles.addView(t3)
        header.addView(titles)
        page.addView(header)

        svcChip = TextView(this)
        svcChip.textSize = 13f
        svcChip.setPadding(dp(12), dp(6), dp(12), dp(6))
        page.addView(svcChip, lp(14))

        // ---- permissions
        val permCard = card("PERMISSIONS", page)
        for (p in perms) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val tv = TextView(this)
            tv.textSize = 14f
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Allow", small = true) { p.act() })
            permCard.addView(row, lp(4))
            permRows.add(Pair(p, tv))
        }
        permCard.addView(btn("App info (Restricted settings allow)", small = true) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri()))
        }, lp(10))
        val hint = TextView(this)
        hint.text = "Accessibility grey ho to: App info > ⋮ > \"Allow restricted settings\", phir Accessibility me service ON karo."
        hint.textSize = 11f
        hint.setTextColor(0xFF9C8A7C.toInt())
        hint.setPadding(0, dp(6), 0, 0)
        permCard.addView(hint)

        // ---- settings
        val setCard = card("SETTINGS", page)
        setCard.addView(toggle("Rewards claim + ad logic", "claim", true) { runner.tapCollect = it })
        setCard.addView(toggle("Daily Reward ka Collect bhi claim karo", "daily", false) { runner.claimDaily = it })
        setCard.addView(toggle("Extra screens: chest, OK / Claim / Yes / No", "extras", true) { runner.handleExtras = it })
        setCard.addView(toggle("Floating status line", "overlay", true) {
            StatusOverlay.enabled = it
            if (!it) StatusOverlay.hide()
        })

        // ---- actions
        val actCard = card("ACTIONS", page)
        actCard.addView(btn("▶  Run once", primary = true) { Thread { runner.run() }.start() }, lp(4))
        actCard.addView(btn("■  Stop") { runner.stop = true }, lp(8))
        actCard.addView(btn("Carrom Pool kholo") { runner.openGameOnly() }, lp(8))
        actCard.addView(btn("Scan screen (5 sec baad, game pe jao)") {
            Logger.log("5 sec me game screen pe jao...")
            Thread {
                Thread.sleep(5000)
                runner.scanOnly()
            }.start()
        }, lp(8))
        actCard.addView(btn("Last scan dekho") { showImage("last_scan.jpg") }, lp(8))
        actCard.addView(btn("Last stuck ad screenshot dekho") { showImage("stuck_last.jpg") }, lp(8))

        // ---- log
        val logCard = card("LOG", page)
        val logBtns = LinearLayout(this)
        logBtns.orientation = LinearLayout.HORIZONTAL
        val copyBtn = btn("📋  Copy log", primary = true, small = true) { copyLog() }
        val clearBtn = btn("Clear", small = true) {
            Logger.clear()
            logView.text = ""
        }
        val half1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        half1.setMargins(0, 0, dp(6), 0)
        val half2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        half2.setMargins(dp(6), 0, 0, 0)
        logBtns.addView(copyBtn, half1)
        logBtns.addView(clearBtn, half2)
        logCard.addView(logBtns, lp(4))
        logView = TextView(this)
        logView.textSize = 11f
        logView.typeface = Typeface.MONOSPACE
        logView.setTextColor(0xFFB8E6B8.toInt())
        logView.setTextIsSelectable(true)
        logView.text = Logger.all()
        logCard.addView(logView)

        Logger.listener = { line -> runOnUiThread { logView.append(line) } }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
    }

    override fun onDestroy() {
        Logger.listener = null
        super.onDestroy()
    }

    private fun refreshPerms() {
        for ((p, tv) in permRows) {
            val ok = p.ok()
            tv.text = (if (ok) "✅  " else "❌  ") + p.name
            tv.setTextColor(if (ok) OKC else BADC)
        }
        val on = Perm.accessibilityOn(this)
        svcChip.text = if (on) "●  Service ON — ready" else "●  Service OFF — Accessibility allow karo"
        svcChip.setTextColor(if (on) OKC else BADC)
        svcChip.background = rounded(if (on) 0xFF1E3322.toInt() else 0xFF3A1E1E.toInt(), 20)
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("autoclaim-log", Logger.all()))
        Toast.makeText(this, "Log copy ho gaya", Toast.LENGTH_SHORT).show()
    }

    private fun showImage(name: String) {
        val f = File(filesDir, name)
        if (!f.exists()) {
            Logger.log("Abhi $name nahi bani")
            return
        }
        val bmp = BitmapFactory.decodeFile(f.absolutePath)
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.adjustViewBounds = true
        iv.visibility = View.VISIBLE
        AlertDialog.Builder(this).setView(iv).setPositiveButton("OK", null).show()
    }
}
