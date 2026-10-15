package com.ghadd.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * وظيفتان: (1) فحص شريط العناوين في المتصفحات (بما فيها الخفي)،
 * (2) منع الوصول لشاشات إلغاء التثبيت/التعطيل الخاصة بالتطبيق ما لم يُفتح القفل الزمني.
 * معرّفات عناصر الواجهة تتغير بين إصدارات المتصفحات، فحدّثها عند الحاجة.
 */
class GhaddAccessibilityService : AccessibilityService() {

    private val urlBars = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
        "com.opera.browser" to "com.opera.browser:id/url_field",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text"
    )
    private val guardedPkgs = setOf(
        "com.android.settings", "com.google.android.packageinstaller",
        "com.android.packageinstaller", "com.samsung.android.packageinstaller",
        "com.miui.securitycenter", "com.coloros.safecenter"
    )
    private var lastAction = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return
        try {
            when {
                pkg in urlBars -> checkBrowser(pkg, root)
                pkg in guardedPkgs -> guardSettings(root)
            }
        } finally { root.recycle() }
    }

    private fun checkBrowser(pkg: String, root: AccessibilityNodeInfo) {
        val id = urlBars[pkg] ?: return
        val text = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString() ?: return
        if (Blocklist.matchesText(text) || Blocklist.isBlockedHost(text.substringBefore('/'))) block()
    }

    private fun guardSettings(root: AccessibilityNodeInfo) {
        if (!Prefs.guardActive(this) || Prefs.unlockOpen(this)) return
        val sb = StringBuilder()
        collect(root, sb, 0)
        val s = sb.toString()
        if (s.contains(getString(R.string.app_name)) || s.contains("Ghadd") || s.contains("غَضّ")) block()
    }

    private fun collect(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 25) return
        n.text?.let { sb.append(it).append(' ') }
        for (i in 0 until n.childCount) collect(n.getChild(i), sb, depth + 1)
    }

    private fun block() {
        val now = System.currentTimeMillis()
        if (now - lastAction < 1000) return
        lastAction = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        Prefs.countBlock(this)
        Toast.makeText(this, R.string.blocked_msg, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {}
}
