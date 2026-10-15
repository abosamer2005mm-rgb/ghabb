package com.ghadd.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private val REQ_VPN = 1
    private val REQ_ADMIN = 2
    private lateinit var regular: Typeface
    private lateinit var bold: Typeface

    override fun attachBaseContext(base: Context) {
        val tag = Prefs.lang(base)
        val loc = Locale(tag)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        regular = Typeface.createFromAsset(assets, "fonts/tajawal_regular.ttf")
        bold = Typeface.createFromAsset(assets, "fonts/tajawal_bold.ttf")
        applyFonts(findViewById(android.R.id.content))
        findViewById<TextView>(R.id.tvTitle).typeface = Typeface.createFromAsset(assets, "fonts/almudid.ttf")

        findViewById<Button>(R.id.btnToggle).setOnClickListener { onToggle() }
        findViewById<Button>(R.id.btnNo).setOnClickListener { findViewById<View>(R.id.cardPause).visibility = View.GONE }
        findViewById<Button>(R.id.btnYes).setOnClickListener {
            findViewById<View>(R.id.tvYesNote).visibility = View.VISIBLE
            Prefs.setConfirmed(this, true)
            Prefs.setSetupPending(this, true)
            continueSetup()
        }
        findViewById<Button>(R.id.btnReqDisable).setOnClickListener { onRequestDisable() }
        findViewById<Button>(R.id.btnRemoveAdmin).setOnClickListener {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.removeActiveAdmin(ComponentName(this, GhaddAdminReceiver::class.java))
            Prefs.setConfirmed(this, false)
            refresh()
        }
        findViewById<Button>(R.id.btnAr).setOnClickListener { setLang("ar") }
        findViewById<Button>(R.id.btnEn).setOnClickListener { setLang("en") }
        findViewById<Button>(R.id.btnFr).setOnClickListener { setLang("fr") }
    }

    private fun applyFonts(v: View) {
        if (v is TextView) {
            val isBold = v.typeface?.isBold == true
            v.typeface = if (isBold) bold else regular
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) applyFonts(v.getChildAt(i))
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.setupPending(this)) continueSetup()
        refresh()
    }

    private fun setLang(tag: String) {
        Prefs.setLang(this, tag)
        recreate()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, GhaddAccessibilityService::class.java)
        return s.contains(cn.flattenToString()) || s.contains(cn.flattenToShortString())
    }

    private fun continueSetup() {
        when {
            !a11yEnabled() -> {
                toast(R.string.step_a11y)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            !Prefs.isAdmin(this) -> {
                val admin = ComponentName(this, GhaddAdminReceiver::class.java)
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
                }
                startActivityForResult(i, REQ_ADMIN)
            }
            else -> Prefs.setSetupPending(this, false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) startVpn() else toast(R.string.vpn_denied)
            REQ_ADMIN -> refresh()
        }
    }

    private fun onToggle() {
        if (Prefs.vpnOn(this)) {
            if (Prefs.guardActive(this) && !Prefs.unlockOpen(this)) { toast(R.string.locked_msg); return }
            startService(Intent(this, GhaddVpnService::class.java).setAction(GhaddVpnService.ACTION_STOP))
            findViewById<View>(R.id.btnToggle).postDelayed({ refresh() }, 400)
        } else {
            val i = VpnService.prepare(this)
            if (i != null) startActivityForResult(i, REQ_VPN) else startVpn()
        }
    }

    private fun startVpn() {
        startService(Intent(this, GhaddVpnService::class.java))
        findViewById<View>(R.id.btnToggle).postDelayed({ refresh() }, 400)
    }

    private fun onRequestDisable() {
        val now = System.currentTimeMillis()
        val req = Prefs.requestedAt(this)
        if (req == 0L) {
            Prefs.setRequestedAt(this, now); toast(R.string.disable_requested)
        } else {
            val left = 24 * 3600_000L - (now - req)
            if (left <= 0) {
                Prefs.setUnlockUntil(this, now + 10 * 60_000L)
                Prefs.setRequestedAt(this, 0L)
                toast(R.string.unlock_open)
            } else {
                Toast.makeText(this, getString(R.string.disable_wait, (left / 3600_000L + 1).toInt()), Toast.LENGTH_LONG).show()
            }
        }
        refresh()
    }

    private fun refresh() {
        val on = Prefs.vpnOn(this)
        findViewById<TextView>(R.id.tvStatus).setText(if (on) R.string.status_on else R.string.status_off)
        findViewById<TextView>(R.id.tvBlocked).text = getString(R.string.blocked_today, Prefs.blockedToday(this))
        findViewById<TextView>(R.id.tvStreak).text = getString(R.string.streak, Prefs.streakDays(this))
        findViewById<Button>(R.id.btnToggle).setText(if (on) R.string.btn_stop else R.string.btn_start)

        val guard = Prefs.guardActive(this)
        findViewById<View>(R.id.tvLock).visibility = if (guard) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnReqDisable).visibility = if (guard) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnRemoveAdmin).visibility =
            if (Prefs.isAdmin(this) && Prefs.unlockOpen(this)) View.VISIBLE else View.GONE
        if (guard) findViewById<View>(R.id.cardPause).visibility = View.GONE
    }
}
