package com.ghadd.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

object Prefs {
    private const val DAY = 86_400_000L
    private fun sp(c: Context) = c.getSharedPreferences("ghadd", Context.MODE_PRIVATE)
    private fun today() = System.currentTimeMillis() / DAY

    fun vpnOn(c: Context) = sp(c).getBoolean("vpn", false)
    fun setVpnOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("vpn", v).apply()

    fun confirmed(c: Context) = sp(c).getBoolean("confirmed", false)
    fun setConfirmed(c: Context, v: Boolean) = sp(c).edit().putBoolean("confirmed", v).apply()

    fun setupPending(c: Context) = sp(c).getBoolean("pending", false)
    fun setSetupPending(c: Context, v: Boolean) = sp(c).edit().putBoolean("pending", v).apply()

    fun lang(c: Context) = sp(c).getString("langTag", "ar") ?: "ar"
    fun setLang(c: Context, tag: String) = sp(c).edit().putString("langTag", tag).apply()

    fun isAdmin(c: Context): Boolean {
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(c, GhaddAdminReceiver::class.java))
    }

    /** الحماية من الحذف/التعطيل فعّالة: أكّد المستخدم + صلاحية المسؤول مفعّلة */
    fun guardActive(c: Context) = confirmed(c) && isAdmin(c)

    // ---- القفل الزمني ----
    fun requestedAt(c: Context) = sp(c).getLong("reqAt", 0L)
    fun setRequestedAt(c: Context, t: Long) = sp(c).edit().putLong("reqAt", t).apply()
    fun unlockUntil(c: Context) = sp(c).getLong("unlockUntil", 0L)
    fun setUnlockUntil(c: Context, t: Long) = sp(c).edit().putLong("unlockUntil", t).apply()
    fun unlockOpen(c: Context) = System.currentTimeMillis() < unlockUntil(c)

    // ---- الإحصاءات (محلية فقط) ----
    fun countBlock(c: Context) {
        val s = sp(c); val t = today()
        val same = s.getLong("day", -1L) == t
        s.edit().putLong("day", t).putInt("count", if (same) s.getInt("count", 0) + 1 else 1)
            .putLong("lastBlockDay", t).apply()
    }
    fun blockedToday(c: Context): Int {
        val s = sp(c); return if (s.getLong("day", -1L) == today()) s.getInt("count", 0) else 0
    }
    fun streakDays(c: Context): Int {
        val s = sp(c)
        if (!s.contains("installDay")) s.edit().putLong("installDay", today()).apply()
        val from = maxOf(s.getLong("lastBlockDay", 0L), s.getLong("installDay", today()))
        return (today() - from).toInt()
    }
}
