package com.kongye.docs.vault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** 浏览器不能作为某一个网站密码的适用 App。 */
object Browsers {
    private val KNOWN = setOf("com.android.chrome", "com.chrome.beta", "com.microsoft.emmx", "org.mozilla.firefox",
        "org.mozilla.fenix", "com.heytap.browser", "com.oplus.browser", "com.android.browser", "com.mi.globalbrowser", "com.sec.android.app.sbrowser")
    fun isBrowser(context: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg in KNOWN) return true
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        return runCatching { context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() }.getOrDefault(false)
    }
}
