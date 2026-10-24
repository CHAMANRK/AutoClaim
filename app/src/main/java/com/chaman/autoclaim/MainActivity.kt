package com.chaman.autoclaim

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private class P(val name: String, val ok: () -> Boolean, val act: () -> Unit)

    private lateinit var logView: TextView
    private val permRows = ArrayList<Pair<P, TextView>>()
    private val runner by lazy { Runner(applicationContext) }

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
            P("Exact alarm (daily schedule ke liye)", { Perm.exactAlarmOn(this) }, {
                if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri()))
            })
        )
    }

    private fun label(text: String, size: Float, color: Int, top: Int = 0): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(top), 0, dp(4))
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF14100E.toInt())
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        val gold = 0xFFFFC857.toInt()
        val soft = 0xFFD9C7B8.toInt()

        page.addView(label("Chaman AutoClaim", 24f, gold))
        page.addView(label("v0.1 base: permissions + app open + reward find + popup close", 13f, soft))

        page.addView(label("PERMISSIONS", 13f, gold, 20))
        for (p in perms) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tv = TextView(this).apply {
                textSize = 15f
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(button("Allow") { p.act() })
            page.addView(row)
            permRows.add(Pair(p, tv))
        }
        page.addView(button("App info kholo (Restricted settings allow karne ke liye)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri()))
        })
        page.addView(label(
            "Android 13+ pe sideload app ka Accessibility grey ho to: App info > ⋮ (3 dot) > \"Allow restricted settings\", phir Accessibility me service ON karo.",
            12f, soft
        ))

        page.addView(label("TOOLS", 13f, gold, 20))
        val sw = Switch(this).apply {
            text = "Collect bhi dabao (ad logic abhi nahi, OFF rakho)"
            setTextColor(soft)
            setOnCheckedChangeListener { _, c -> runner.tapCollect = c }
        }
        page.addView(sw)
        page.addView(button("1) Carrom Pool kholo") { runner.openGameOnly() })
        page.addView(button("2) Scan screen (5 sec baad, tab tak game pe jao)") {
            Logger.log("5 sec me game screen pe jao...")
            Thread { Thread.sleep(5000); runner.scanOnly() }.start()
        })
        page.addView(button("3) Run once (popup close + rewards find)") { Thread { runner.run() }.start() })
        page.addView(button("Stop") { runner.stop = true })
        page.addView(button("Last scan dekho") { showLastScan() })

        page.addView(label("LOG", 13f, gold, 20))
        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFB8E6B8.toInt())
            setTextIsSelectable(true)
            text = Logger.all()
        }
        page.addView(logView)
        page.addView(button("Log clear") { Logger.clear(); logView.text = "" })

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
            tv.text = (if (ok) "✅ " else "❌ ") + p.name
            tv.setTextColor(if (ok) 0xFF8FE388.toInt() else 0xFFFF8A80.toInt())
        }
    }

    private fun showLastScan() {
        val f = File(filesDir, "last_scan.jpg")
        if (!f.exists()) {
            Logger.log("Abhi koi scan nahi hua")
            return
        }
        val bmp = BitmapFactory.decodeFile(f.absolutePath)
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            visibility = View.VISIBLE
        }
        AlertDialog.Builder(this).setView(iv).setPositiveButton("OK", null).show()
    }
}
